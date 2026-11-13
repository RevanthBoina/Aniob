package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.Outcome
import com.aniob.core.narration.StepNarration
import com.aniob.core.domain.*
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.narration.AgentPhase
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.verify.FastVerify
import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskPolicy
import com.aniob.core.safety.InterceptResult
import com.aniob.core.tools.AniobScrollHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.BURST_REPLAN_SETTLE_MS
import com.aniob.app.ui.AniobViewModel.Companion.BURST_MAX_ZERO_PROGRESS_REPLANS
import com.aniob.app.ui.AniobViewModel.Companion.APP_LAUNCH_VERIFY_MS
import com.aniob.app.ui.AniobViewModel.RememberTransition

/**
 * Act: last third of one step of [executeTaskPipeline]. Dispatches the proposal (or the bundled
 * plan), verifies it and records the result. The statements are the loop body unchanged; `break`,
 * `continue` and `return` are reported as [PipelineFlow] values.
 */
internal suspend fun AniobViewModel.pipelineAct(
    pipe: PipelineRun,
    observed: ObservedStep,
    proposed: ProposedStep
): PipelineFlow<Unit> {
    return with(pipe) {
        var currentScreen = observed.currentScreen
        val ladderResult = observed.ladderResult
        var proposal = proposed.proposal
        var targetNode = proposed.targetNode
        var plannedBurst = proposed.plannedBurst
        persistRunningMarker(task, currentStep + 1, proposal.action.describeAction())

        val captureLambda: suspend () -> AniobScreenState = {
            screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
            accessibility?.captureCurrentScreenState() ?: currentScreen
        }

        // The screen the action was planned on: the two-tier verifier compares against it.
        var dispatchOrigin: AniobScreenState = currentScreen
        var burstDispatchTreeHash: String? = null

        /**
         * Waits for the dispatched action to show up, then returns the screen to verify
         * against. Tier 1 polls window events every 50 ms (max 800 ms) and stops on the
         * first structural change or expected text; only a timeout buys a bitmap, which is
         * kept for the next Reason step because an unchanged tree is exactly what a visual
         * popup or a no-op tap looks like.
         */
        suspend fun awaitTransition(act: AniobAction): AniobScreenState? {
            if (accessibility == null) return null
            // Opening an app that is already in front has nothing to wait for; using the
            // launch budget here would idle for seconds before the next step.
            if (act is AniobAction.OpenApp &&
                dispatchOrigin.packageName.equals(act.packageName, ignoreCase = true)
            ) {
                return dispatchOrigin
            }
            // A cold app launch can take seconds on a busy phone; every other action keeps
            // the 800 ms budget. The wait is still event-driven, so a fast launch returns
            // as soon as the package actually changes.
            val budget = if (act is AniobAction.OpenApp) {
                APP_LAUNCH_VERIFY_MS
            } else {
                com.aniob.core.verify.FastVerify.MAX_WAIT_MS
            }
            val fast = com.aniob.core.verify.FastVerify.await(
                preAction = dispatchOrigin,
                expectedTexts = com.aniob.core.verify.FastVerify.expectedTexts(act, resolvedCriteria),
                timeoutMs = budget,
                windowEvents = { accessibility.windowEventSequence() },
                capturePoll = { accessibility.captureCurrentScreenState().also {
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                } },
                screenshot = { captureVerifyScreenshot() },
                finalCaptureAtTimeout = activeRunMode.fastDeltaVerify
            )
            fast.screenshotBase64?.let { bitmap ->
                val id = com.aniob.core.verify.FastVerify
                    .screenIdentity(fast.screen ?: dispatchOrigin)
                if (id.isNotBlank()) pendingVerifyScreenshot = id to bitmap
            }
            return fast.screen ?: accessibility.captureCurrentScreenState()
        }

        settleTransition = { action -> awaitTransition(action) }
        val dispatchLambda: suspend (AniobAction) -> StepPipeline.DispatchOutcome = { act ->
            when {
                proposal is StepPipeline.Proposal.DirectIntent && ladderResult is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                    val ok = dispatchSystemIntent(ladderResult.shortcut)
                    val screenAfter = awaitTransition(act)
                    actionsDispatched++
                    StepPipeline.DispatchOutcome(dispatched = ok, reason = act.describeAction(), screenAfter = screenAfter)
                }
                act is AniobAction.DeviceControl -> {
                    val result = runDeviceCommand(act.command, act.args)
                    // A command that could not choose for the user (no file matched) asks.
                    pendingDeviceClarify = result.clarify
                    postDeviceResult(result)
                    actionsDispatched++
                    // The result text is what the model reads next; a silent command leaves the
                    // screen untouched, so the planning screen is reused instead of a capture.
                    val after = when {
                        !result.ok -> null
                        act.command.completesOnDispatch -> dispatchOrigin
                        else -> awaitTransition(act)
                    }
                    StepPipeline.DispatchOutcome(dispatched = result.ok, reason = result.message, screenAfter = after)
                }
                act is AniobAction.Finish -> {
                    StepPipeline.DispatchOutcome(dispatched = true, reason = act.summary)
                }
                act is AniobAction.Fail -> {
                    StepPipeline.DispatchOutcome(dispatched = false, reason = act.reason)
                }
                else -> {
                    // A target that is not on the current screen is not a failure yet: if
                    // the screen scrolls, one forward scroll usually reveals it. Doing that
                    // here, before the model is asked again, is the difference between one
                    // round trip and three.
                    val plannedMiss = act.semanticTarget() != null &&
                        com.aniob.core.execution.AniobActionExecutor
                            .planDispatch(act, dispatchOrigin) is DispatchCommand.NoOp
                    var ok = if (accessibility != null) executeActionSync(accessibility, act, burstDispatchTreeHash ?: dispatchOrigin.screenHash.ifBlank { dispatchOrigin.treeHash }, waitForIdle = false) else true
                    actionsDispatched++
                    var screenAfter = if (researchMode.skipFastStepVerification) null else awaitTransition(act)
                    // A target named by its text can be searched for directly: scroll the
                    // container that should hold it until it shows, then run the SAME step
                    // again. No model call is spent on "it is further down".
                    val namedTarget = act.semanticTarget()
                    val namedText = AniobScrollHelper.textOf(namedTarget)
                    var namedSearchTried = false
                    if (!ok && burstDispatchTreeHash == null && plannedMiss && accessibility != null &&
                        namedText != null
                    ) {
                        namedSearchTried = true
                        val found = scrollUntilTargetVisible(accessibility, dispatchOrigin, namedTarget, namedText)
                        if (found?.found == true) {
                            ok = executeActionSync(accessibility, act)
                            screenAfter = awaitTransition(act) ?: found.screen
                            app.eventLogger.info(
                                "AniobViewModel",
                                "Scroll-until-visible revealed '${namedText.take(40)}' after ${found.swipes} steps"
                            )
                        } else if (found?.screen != null) {
                            // Not there in either direction: let the next plan see where the search ended.
                            preObservedScreen = found.screen
                        }
                    }
                    if (!ok && !namedSearchTried && burstDispatchTreeHash == null && plannedMiss && accessibility != null &&
                        com.aniob.core.perception.PreObserveScroll
                            .shouldScroll(dispatchOrigin, preObservedScreens)
                    ) {
                        preObservedScreens.merge(FastVerify.screenIdentity(dispatchOrigin), 1, Int::plus)
                        val scrollQuiet = windowQuiet(accessibility, requireMovement = true)
                        if (accessibility.scrollForwardOnce()) {
                            // Was a fixed SETTLE_MS. Expected effect: the scroll produced
                            // window events and they have stopped.
                            until(com.aniob.core.perception.PreObserveScroll.SETTLE_MS) { scrollQuiet.settled() }
                            val revealed = accessibility.captureCurrentScreenState()
                            // Retry through a stable identity (view id -> text -> content
                            // description): replaying a SoM index against the tree the
                            // scroll just created can address a different element.
                            val retry = com.aniob.core.perception.PreObserveScroll
                                .retargetForRetry(act, dispatchOrigin)
                            if (retry == null) {
                                app.eventLogger.info(
                                    "AniobViewModel",
                                    "Pre-observe scroll: target has no stable identity, re-planning"
                                )
                            } else {
                                ok = executeActionSync(accessibility, retry)
                                screenAfter = awaitTransition(retry) ?: revealed
                            }
                            if (ok) {
                                app.eventLogger.info(
                                    "AniobViewModel",
                                    "Pre-observe scroll revealed the target of ${act.describeAction()}"
                                )
                            } else {
                                // Still missing: give the next planning pass both viewports
                                // so it does not spend a call rediscovering the first one.
                                preObservedScreen = com.aniob.core.perception.PreObserveScroll
                                    .merge(dispatchOrigin, revealed)
                                app.eventLogger.info(
                                    "AniobViewModel",
                                    "Pre-observe scroll: merged ${revealed.nodes.size} new nodes for the next plan"
                                )
                            }
                        }
                    }
                    StepPipeline.DispatchOutcome(dispatched = ok, screenAfter = screenAfter)
                }
            }
        }
        val confirmLambda: suspend (AniobAction, AniobNode?, InterceptResult) -> Boolean = { approvedAction, approvedNode, verdict ->
            val category = AskPolicy.category(approvedAction, approvedNode)
            val sensitive = category == AskCategory.SECRET || approvedNode?.isPassword == true
            val humanWhat = when {
                sensitive -> "Use a protected value"
                approvedAction is AniobAction.InputText -> "Type into the text field"
                else -> StepNarration.narrate(approvedAction, approvedNode).take(80)
            }
            val humanWhy = verdict.reason.ifBlank { "Action requires user confirmation" }
            val computedRisk = riskFromTier(verdict.riskTier, _uiState.value.safetyLevel)
            val details = when {
                sensitive -> "The protected value and field contents are hidden."
                approvedAction is AniobAction.InputText -> "Typed content is hidden and bound to this approval."
                else -> approvedAction.describeAction()
            }
            val confirmReq = buildConfirmRequest(
                what = humanWhat,
                why = humanWhy,
                risk = computedRisk,
                details = details,
                taskId = task.id,
                payload = com.aniob.app.workflow.ApprovalFingerprint.payload(approvedAction, approvedNode),
                target = com.aniob.app.workflow.ApprovalFingerprint.displayTarget(
                    approvedAction, approvedNode, sensitive
                ),
                identityTarget = com.aniob.app.workflow.ApprovalFingerprint.target(approvedAction, approvedNode)
            )
            val decision = requestConfirmation(confirmReq)
            decision == ConfirmDecision.APPROVE_ONCE || decision == ConfirmDecision.APPROVE_FOR_TASK
        }

        // ---------------------------------------------------------------------------
        // Act: run the bundled plan one member at a time (ActionBurstRunner).
        // The screen is re-read and the next member re-grounded after every action, and a
        // member that no longer fits the screen closes the plan instead of failing the task.
        // ---------------------------------------------------------------------------
        var burstAbort: String? = null
        // A plan whose target vanished is not a failed task: it was written for a screen
        // that has since moved (Files/Search re-index and hide nodes after the first tap).
        // The rest of the plan is dropped and the next step is planned from a fresh screen.
        var burstReplan: String? = null
        // True once a plan member ran: this step's verification is then the plan's, and
        // running the pipeline again would repeat an action that already happened.
        var planConsumedStep = false
        var lastPlanResult: StepResult? = null
        if (plannedBurst != null) {
            val burst = plannedBurst!!
            // Everything the model planned for screens this one does not show yet. It is
            // context for the next planning pass, never an instruction to act blind.
            com.aniob.core.perception.BurstPlanScope.renderDeferredHint(burst.deferred)
                ?.let { deferredPlanHint = it }
            val plan = com.aniob.core.domain.ActionBurst(
                thought = burst.thought,
                isComplete = burst.isComplete,
                actions = burst.head + listOfNotNull(burst.tail),
                deferred = burst.deferred
            )
            if (plan.actions.isNotEmpty()) {
                // One runner for a model plan and for a remembered replay: every member goes
                // through the same safety, dispatch and verification pipeline, and the screen
                // is re-read (and the member re-grounded) after every action.
                val burstRunner = com.aniob.core.execution.ActionBurstRunner(
                    capture = {
                        accessibility?.captureCurrentScreenState()?.also {
                            screenReads++; screenCaptureCount++
                            app.researchTelemetry.recordTreeRead()
                        }
                    },
                    executeMember = { member, memberCtx, planningScreen, target ->
                        val dispatchable = target?.let {
                            com.aniob.core.perception.BurstTargetGuard.retarget(member, it)
                        } ?: member
                        dispatchOrigin = planningScreen
                        // A member is dispatched against the tree it was re-grounded on, so a
                        // screen that re-indexes between the check and the tap cannot make
                        // the tap land on a different widget.
                        burstDispatchTreeHash =
                            com.aniob.core.diff.AniobScreenDiff.computeTreeHash(planningScreen.nodes)
                        val step = try {
                            stepPipe.executeStep(
                                ctx = memberCtx,
                                planningScreen = planningScreen,
                                proposal = StepPipeline.Proposal.Model(dispatchable, primaryProvider),
                                capture = { planningScreen },
                                dispatch = dispatchLambda,
                                // Every member uses the same approval gate as a single
                                // action. A sensitive early member must pause for approval,
                                // not be silently treated as a denial merely because it is
                                // inside a burst.
                                confirm = confirmLambda,
                                targetNode = target
                            )
                        } finally {
                            burstDispatchTreeHash = null
                        }
                        if (step.verification?.isSuccessful == true ||
                            step.terminalState == StepResult.TerminalState.SUCCESS
                        ) {
                            recordTargetSuccess(task.id, step.action, step.resolvedNode ?: target)
                        }
                        app.researchTelemetry.recordBurstMemberRun()
                        app.researchTelemetry.recordStep()
                        // Only verified members are remembered; a failed member teaches
                        // nothing and must never seed the replay cache.
                        if (step.verification?.isSuccessful == true &&
                            step.terminalState != StepResult.TerminalState.FAIL
                        ) {
                            rememberTransitions.add(
                                RememberTransition(screen = planningScreen, action = step.action, node = target, nextScreen = step.nextCtx.lastScreenState)
                            )
                        }
                        step
                    }
                )
                val run = burstRunner.run(
                    plan = plan,
                    origin = currentScreen,
                    ctx = taskContext,
                    shouldContinue = {
                        taskGeneration == activeGeneration && _uiState.value.isRunning
                    }
                )
                taskContext = run.ctx
                currentScreen = run.screen
                lastPlanResult = run.lastResult
                val missedAction = run.missedAction
                val missedNode = run.missedNode
                val hasGroundingMiss = missedAction != null
                if (missedAction != null) {
                    val misses = recordTargetMiss(task.id, missedAction, missedNode)
                    if (misses != null && misses >= 2) {
                        requestTargetClarification(task, missedAction, missedNode)
                        return PipelineFlow.Return
                    }
                }
                // Only a plan that ended for its own reasons speaks for this step. A plan
                // closed early still owes the loop one action on the screen it revealed, and
                // that action gets its own step record.
                planConsumedStep = run.ran.isNotEmpty() &&
                    (run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.NONE ||
                        run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.BLOCKED ||
                        run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.ABORTED)
                when (run.stop) {
                    com.aniob.core.execution.ActionBurstRunner.Stop.NONE -> Unit

                    com.aniob.core.execution.ActionBurstRunner.Stop.TARGET_GONE,
                    com.aniob.core.execution.ActionBurstRunner.Stop.NAVIGATED,
                    com.aniob.core.execution.ActionBurstRunner.Stop.NO_CAPTURE -> {
                        // What really happened is kept; the rest of the plan is closed and
                        // the loop continues from the screen the plan revealed. This is the
                        // normal end of a plan that spans screens, not an error.
                        app.eventLogger.info("AniobViewModel", "Plan closed early: ${run.detail}")
                        burstReplan = run.detail
                    }

                    com.aniob.core.execution.ActionBurstRunner.Stop.BLOCKED -> {
                        if (hasGroundingMiss) {
                            burstReplan = run.detail
                        } else {
                            burstAbort = run.detail
                        }
                    }

                    com.aniob.core.execution.ActionBurstRunner.Stop.ABORTED -> {
                        burstAbort = run.detail
                    }
                }
                if (run.completed && run.lastResult?.terminalState == StepResult.TerminalState.CONTINUE) {
                    // Every member ran and none of them was a Finish: the plan says the goal
                    // is met, so carry a provisional finish into verification rather than
                    // asking the model for another action on a screen it already finished.
                    proposal = StepPipeline.Proposal.Model(
                        AniobAction.Finish(burst.thought.ifBlank { "Planned actions completed" }),
                        primaryProvider
                    )
                    targetNode = null
                    planConsumedStep = false
                }
            }
            plannedBurst = null
        }
        if (burstAbort == null && burstReplan != null) {
            app.eventLogger.info(
                "AniobViewModel",
                "Plan target invalidated ($burstReplan); dropping the rest and planning from a fresh screen"
            )
            // A plan that spans screens ends this way by design, so batching stays on. Only
            // repeated plans that cannot even start mean the screen is thrashing, and then
            // the rest of the run plans one action at a time. Anything that did run before
            // the plan went stale resets the counter.
            burstZeroProgressReplans = if (planConsumedStep) 0 else burstZeroProgressReplans + 1
            if (burstZeroProgressReplans >= BURST_MAX_ZERO_PROGRESS_REPLANS) {
                burstsSuspended = true
                app.researchTelemetry.recordBurstSuspended()
            }
            // Was a fixed sleep. Expected effect: the dynamic screen stopped re-indexing, so
            // the inputs of the next plan are stable.
            val replanQuiet = accessibility?.let { windowQuiet(it, requireMovement = false) }
            until(BURST_REPLAN_SETTLE_MS) { replanQuiet?.settled() ?: false }
            accessibility?.captureCurrentScreenState()?.let { fresh ->
                screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                screenBefore = fresh
            }
            _uiState.update { it.copy(statusMessage = "The screen changed. Planning the next step from what is on screen...") }
            currentStep++
            return PipelineFlow.Continue
        }
        if (burstAbort != null) {
            app.eventLogger.warn("AniobViewModel", "Plan stopped: $burstAbort")
            val userReason = if (burstAbort.contains("stopped")) {
                "I stopped before finishing. Actions that already ran were not undone."
            } else {
                "I stopped before finishing because an action could not be safely completed or verified. Check the screen before trying again; earlier changes were not undone."
            }
            _uiState.update { it.copy(statusMessage = userReason) }
            proposal = StepPipeline.Proposal.Model(
                AniobAction.Fail(userReason),
                primaryProvider
            )
            targetNode = null
            planConsumedStep = false
        }

        // A plan that ran to its end already produced this step's verified result. Running
        // the remaining action now would repeat work that really happened, so the plan's own
        // last result is the step result.
        dispatchOrigin = currentScreen
        val res = if (planConsumedStep) {
            lastPlanResult ?: stepPipe.executeStep(
                ctx = taskContext,
                planningScreen = currentScreen,
                proposal = proposal,
                capture = captureLambda,
                dispatch = dispatchLambda,
                confirm = confirmLambda,
                targetNode = targetNode
            )
        } else {
            stepPipe.executeStep(
                ctx = taskContext,
                planningScreen = currentScreen,
                proposal = proposal,
                capture = captureLambda,
                dispatch = dispatchLambda,
                confirm = confirmLambda,
                targetNode = targetNode
            )
        }
        stepResult = res
        val targetMissCountForStep = if (planConsumedStep || res.verificationSkippedFast) null else
            recordStepTargetOutcome(task.id, res, currentScreen)
        app.researchTelemetry.recordStep()
        if (!res.verificationSkippedFast && ladderResult is AniobExecutionRouter.ExecutionPlanResult.RememberReplay) {
            val postScreen = res.nextCtx.lastScreenState ?: currentScreen
            val isStalled = FastVerify.screenIdentity(postScreen) == FastVerify.screenIdentity(currentScreen)
            val replayEntry = rememberCache.entryFor(task.clarifiedGoal.ifBlank { task.rawPrompt }, currentScreen)
            val expectedReached = replayEntry?.expectedNextHash.orEmpty().let {
                it.isBlank() || it == FastVerify.screenIdentity(postScreen)
            }
            if (res.verification?.isSuccessful == true && !isStalled && expectedReached) {
                replayEntry?.let { rememberCache.recordSuccess(it.key) }
                app.researchTelemetry.recordReplayValidated()
            } else {
                replayEntry?.let { rememberCache.recordFailure(it.key) }
                stalledReplayIdentities.add(FastVerify.screenIdentity(currentScreen))
                app.researchTelemetry.recordReplayDiverged()
            }
            viewModelScope.launch(Dispatchers.IO) { app.rememberCacheFile.save(rememberCache) }
        }
        if (proposal is StepPipeline.Proposal.Model && proposal.action is AniobAction.OpenApp && primaryProvider == "APP_LAUNCH") {
            if (res.verification?.isSuccessful == true) {
                app.researchTelemetry.markIntentShortcutUsed()
            }
        }
        // A plan member recorded its own transition against the screen it ran on; adding
        // the same action again here would key it to the screen it produced.
        if (!planConsumedStep &&
            (res.verification?.isSuccessful == true || res.terminalState == StepResult.TerminalState.SUCCESS)
        ) {
            rememberTransitions.add(
                RememberTransition(
                    screen = currentScreen,
                    action = res.action,
                    node = targetNode,
                    nextScreen = res.nextCtx.lastScreenState
                )
            )
        }
        taskContext = res.nextCtx
        if (!res.verificationSkippedFast) observationPolicy.recordActionOutcome(proposal.action, currentScreen.packageName, res.verification?.isSuccessful == true)

        if (!res.verificationSkippedFast && primaryProvider == "LOCAL_SLM") {
            if (res.verification?.isSuccessful == true) localFailCount = 0 else localFailCount++
        }

        if (res.terminalState == StepResult.TerminalState.SUCCESS) {
            finalOutcome = Outcome.SUCCESS
            terminalReason = res.verification?.reason ?: "Verified successfully"
            return PipelineFlow.Break
        } else if (res.terminalState == StepResult.TerminalState.FAIL) {
            if (targetMissCountForStep != null) {
                if (targetMissCountForStep >= 2) {
                    requestTargetClarification(
                        task,
                        res.action,
                        res.resolvedNode ?: com.aniob.core.execution.AniobActionExecutor
                            .resolveTarget(res.action, currentScreen)?.node
                    )
                    return PipelineFlow.Return
                }
                deferredPlanHint = null
                val fresh = accessibility?.captureCurrentScreenState()
                if (fresh != null) {
                    screenReads++; screenCaptureCount++
                    app.researchTelemetry.recordTreeRead()
                    screenBefore = fresh
                } else {
                    screenBefore = res.nextCtx.lastScreenState ?: currentScreen
                }
                _uiState.update {
                    it.copy(statusMessage = "I couldn't find that control. I'll re-check the current screen before asking again.")
                }
                currentStep++
                return PipelineFlow.Continue
            }
            // GRILL-ME for the model: "FAIL: ASK_USER: question | opt | opt" becomes the Grill sheet, once per task.
            val deviceAsk = pendingDeviceClarify
            pendingDeviceClarify = null
            val parsedAsk = deviceAsk
                ?: com.aniob.core.grillme.AniobAskUser.parse((res.action as? AniobAction.Fail)?.reason)
            if (parsedAsk != null && task.grillAnswers.isEmpty()) {
                val ask = grillMeEngine.sanitizeQuestion(parsedAsk)
                setPhase(AgentPhase.ASKING)
                updateToIdle {
                    it.copy(
                        activeTask = task,
                        grillMeResult = AniobGrillMeResult(needsClarification = true, questions = listOf(ask)),
                        showGrillMeSheet = true,
                        statusMessage = ask.question
                    )
                }
                AniobForegroundService.notifyGrillQuestion(
                    app, task.id, ask, task.clarifiedGoal.ifBlank { task.rawPrompt }
                )
                return PipelineFlow.Return
            }
            finalOutcome = Outcome.FAILED
            terminalReason = (res.action as? AniobAction.Fail)?.reason
                ?: res.verification?.reason
                ?: "Step failed or rejected"
            return PipelineFlow.Break
        }

        screenBefore = res.nextCtx.lastScreenState ?: currentScreen
        currentStep++

        PipelineFlow.Next(Unit)
    }
}
