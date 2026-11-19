package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.research.StudyPass
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.*
import com.aniob.core.execution.StepPipeline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.ResolvedIntentShortcut
import com.aniob.core.run.AniobFastLane
import com.aniob.core.run.RunLane
import com.aniob.core.verify.FastVerify
import com.aniob.core.logging.AniobExecutionTracker
import com.aniob.core.memory.AniobHippocampusTracker
import com.aniob.core.policy.AniobWaitForIdle
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.safety.AskPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.HYDRATION_BUDGET_MS

internal suspend fun AniobViewModel.executeTaskPipeline(
    task: AniobTask,
    taskGeneration: Long = activeGeneration,
    lane: RunLane = RunLane.STANDARD,
    laneShortcut: ResolvedIntentShortcut? = null
) {
    if (taskGeneration != activeGeneration) return
    // Snapshot all five flags once. Settings edits during this task only affect the next task.
    val researchControlsEnabled = com.aniob.app.AniobBuild.isDeveloperBuild(app)
    val researchMode = app.researchTelemetry.featureSelection
    val researchPass = if (researchControlsEnabled) app.researchTelemetry.studyPass else StudyPass.MEASURED
    activeRunMode = researchMode
    executionRouter.beginTask()
    applyFeatureSelection(researchMode)
    // Skill and remember-cache hydration keep running in the background; the rungs consume
    // whatever is ready. A cache that is not hydrated yet simply misses on the very first
    // task, which is the cold-cache outcome anyway - without blocking dispatch on it.
    app.researchTelemetry.startTask(
        task.clarifiedGoal.ifBlank { task.rawPrompt },
        researchMode.mode,
        researchPass,
        runId = task.id,
        featureMask = researchMode.mask
    )
    app.researchTelemetry.recordQuestionsBeforeStart(questionsBeforeStart.remove(task.id) ?: 0)
    persistRunningMarker(task, 0, "Task started")

    watchdog.reset()
    observationPolicy.reset()
    executionTracker.clear()
    com.aniob.core.exec.AniobErrorTaxonomy.reset()

    // Honest completion (no fake SUCCESS): extract deterministic success criteria BEFORE step 0
    // so a provisional Finish can be verified against evidence. Without criteria a Finish could
    // be declared on an unchanged screen.
    // Lazy: a task that arrives with its criteria already resolved never builds the map.
    val appCatalog by lazy(LazyThreadSafetyMode.NONE) {
        runCatching {
            com.aniob.core.knowledge.AniobAppCatalog.getInstance().getAll()
                .filter { it.appName.isNotBlank() }
                .associate { it.appName.lowercase() to it.packageName }
        }.getOrDefault(emptyMap())
    }
    // The goal the runner acts on MUST carry the resolved device noun; otherwise the criteria
    // extractor cannot see a torch/install assertion for a context-resolved bare toggle.
    val resolvedGoalPrompt = resolveContextualGoal(
        task.clarifiedGoal.ifBlank { task.rawPrompt }
    )
    val resolvedCriteria = task.successCriteria ?: AniobTaskIntentClassifier.extractCriteria(
        prompt = resolvedGoalPrompt,
        grillAnswers = task.grillAnswers,
        appCatalog = appCatalog
    )
    val initialTaskContext = com.aniob.core.domain.TaskContext(
        instruction = resolvedGoalPrompt,
        successCriteria = resolvedCriteria,
        grillAnswers = task.grillAnswers,
        taskId = task.id,
        conversationHistory = listOf(
            com.aniob.core.external.ChatTurn(
                com.aniob.core.external.ChatTurn.USER,
                resolvedGoalPrompt
            )
        )
    )

    AniobAccessibilityService.isTaskActive = true
    val startTime = System.currentTimeMillis()
    var finishRejections = 0
    verifiedActions.clear()

    // Hippocampus: short-term episodic stream for this task ("hippocampus -> cortex" consolidation)
    hippocampusTracker.beginTask(task.id, task.clarifiedGoal)

    // Learned state (skills, FastPath trajectories) hydrates in parallel with the first
    // capture instead of in front of it. A cold cache must not delay step 0; the first task
    // after install simply plans from the model.
    val hydration = if (researchMode.rememberReplay) {
        viewModelScope.async(Dispatchers.Default) {
            learningPipeline.awaitHydration(HYDRATION_BUDGET_MS)
        }
    } else {
        null
    }
    // Warm the gateway again at task start: the user may have been idle long enough for the
    // connection pool to drop the socket. Once per task, on the task's own provider.
    viewModelScope.launch(Dispatchers.IO) { prewarmGateway(task.id) }

    // Background automation integrity: minimize Aniob chat UI and run foreground service
    AniobBackgroundController.onTaskStarted(app, task.clarifiedGoal.ifBlank { task.rawPrompt })

    val accessibility = AniobAccessibilityService.instance
    // The old fixed 500 ms sleep is now a bounded poll on the foreground package: usually the
    // window manager wins in ~100 ms, and a device that is already backgrounded returns at once.
    // The fast lane does not wait here: a launch backgrounds the chat by itself, and a reflex
    // has no window to reveal. Both waits happen later if the fast lane falls back.
    if (!lane.isFast) awaitAppBackgrounded(accessibility)

    // A full tree walk taken purely as a diff baseline. A launch is proven by the foreground
    // package and a reflex by its dispatch, so neither needs a before-image; every consumer
    // below already treats a null baseline as "no diff".
    // The state of this run. Everything the three step phases and the closing phase share lives
    // here; values are read and written exactly where the single function used to do it.
    val pipe = PipelineRun(
        task = task,
        taskGeneration = taskGeneration,
        lane = lane,
        laneShortcut = laneShortcut,
        researchMode = researchMode,
        resolvedCriteria = resolvedCriteria,
        startTime = startTime,
        hydration = hydration,
        accessibility = accessibility,
        maxSteps = agentLimits.maxStepsPerTask,
        taskContext = initialTaskContext,
        screenBefore = if (lane.isFast) null else accessibility?.captureCurrentScreenState(),
        // A reflex is planned once from a package-only observation instead of a tree walk. One
        // shot: anything other than an immediate DirectIntent returns to the full observation path.
        fastReflexOpen = lane == RunLane.FAST_REFLEX && executionRouter.intentShortcutsEnabled
    )
    with(pipe) {
        // Run-scoped state that a RESUMED run also needs: the fast-lane target is resolved once,
        // and the shortcut switch is restored when the run really ends (not when it parks).
        fastLaunchTarget = laneShortcut?.targetPackage?.takeIf { lane == RunLane.FAST_LAUNCH && accessibility != null }
        shortcutsWereEnabled = executionRouter.intentShortcutsEnabled

        stepPipe = StepPipeline(
            limits = agentLimits,
            fastVerificationSkipEnabled = researchMode.skipFastStepVerification,
            optimizedFinish = researchMode.optimizedFinish,
            deferTaskLearning = researchMode.immediateCompletion,
            beforeDispatch = { AniobWaitForIdle.waitForIdle(fast = researchMode.fastIdleWait) },
            learning = learningPipeline,
            reflectionAgent = reflectionAgent,
            recordStep = { stepRecord ->
                if (stepRecord.provider == "SKILL") executionRouter.onSkillStepRecorded(stepRecord.verified)
                if (stepRecord.dispatched) app.researchTelemetry.recordActionCompleted()
                if (stepRecord.verificationSkippedFast) app.researchTelemetry.recordFastSkippedStep()
                val node = stepRecord.resolvedNode
                val obs = if (node != null) {
                    "node ${node.id} (${node.text.take(15)})"
                } else {
                    "screen ${screenBefore?.treeHash?.take(6) ?: "none"} after ${stepRecord.action.toolName}"
                }
                recordTrajectory(
                    stepIndex = stepRecord.stepIndex,
                    observation = obs,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    screenHash = screenBefore?.treeHash ?: "",
                    verified = stepRecord.verified,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                publishStep(
                    stepIndex = stepRecord.stepIndex,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    verified = stepRecord.verified,
                    failureReason = stepRecord.failureReason,
                    node = stepRecord.resolvedNode
                )
                val uiStep = AniobStepRecord(
                    stepIndex = stepRecord.stepIndex,
                    screenHash = screenBefore?.treeHash ?: "",
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    tokensUsed = stepRecord.tokensUsed,
                    verifiedSuccess = stepRecord.verified,
                    verificationSkippedFast = stepRecord.verificationSkippedFast,
                    failureReason = stepRecord.failureReason,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                addStepRecord(uiStep)
            },
            // Dispatch already polled for the transition (FastVerify); a second fixed wait here
            // would only re-add the latency this change removes.
            settle = { action, capture -> settleTransition?.invoke(action) ?: capture() },
            // Hardware/package evidence for Finish verification. Unknown probes stay inconclusive.
            deviceState = { criteria -> probeDeviceState(criteria) }
        )

        // A bitmap taken by the tier-2 verify is reused by the next Reason step instead of being
        // captured twice. The structural identity it belongs to is stored with it, so a bitmap
        // can never leak onto a different screen; cleared once consumed.
        // Verified transitions of this run, persisted only if the task ends successfully.
        // Screen on which the last replay changed nothing; the cache is skipped there next time.
        // An intent shortcut that carried the whole task is a finished run: the criteria re-check
        // below must not turn "Wi-Fi settings opened" into an unverified or failed run.
        // The verdict the shortcut was judged by, so a success carries the evidence it was
        // actually checked against instead of being re-evaluated on a later screen.
        // A shortcut this run switched off (an action the device does not have) is restored when
        // the run ends, so one failed dispatch cannot degrade every later task in the batch.
        // Model routing for this task: one on-device attempt, then cloud, and cloud stays chosen.
        executionLlm = com.aniob.core.ladder.ExecutionLlmGateway { cloudEscalationAllowed }
        // Pre-observe scrolls already made in this run, per screen identity.
        // Viewport merged after that scroll, handed to the next planning pass.
        // Set once a burst's target vanished mid-burst. The screen in front is dynamic (search
        // results re-indexing, animated lists), so the rest of the run plans one action at a time
        // from a fresh observation instead of trusting a multi-action plan again.
        // The part of the last plan that named elements the current screen did not show yet.
        // Rendered into the next planning request as context, cleared as soon as it is used.
        // Consecutive plans that could not execute even their first member: the screen is not
        // settling, so the rest of the run plans one action at a time.

        // PART 2/7: a read request ("what are my two newest emails?") carries task-scoped
        // evidence. The destination decides which packages count; opening the app or a model
        // saying "done" never satisfies it — only content observed inside that app does.
        readEvidence = com.aniob.core.intent.AniobAppDataRead.detect(task.rawPrompt)?.let { request ->
            val readPlan = com.aniob.core.ladder.AniobAppDataReadPlanner.plan(
                request,
                AniobAppRegistry.getInstance(),
                { isLaunchablePackage(it) }
            )
            val targets = when (readPlan) {
                is com.aniob.core.ladder.AppDataReadPlan.Ready ->
                    setOfNotNull(readPlan.destination.packageName)
                is com.aniob.core.ladder.AppDataReadPlan.Ambiguous ->
                    readPlan.candidates.mapNotNull { it.packageName }.toSet()
                is com.aniob.core.ladder.AppDataReadPlan.Unresolved -> emptySet()
            }
            readTargetPackages = targets
            com.aniob.core.execution.AppReadEvidence(request, targets)
        }

        activePipeline = pipe
        openTaskInputChannel(task.id)
        // false = the run parked for the user (question / handover); it keeps its identity and is
        // resumed from here by `resumeParkedTask`, never restarted from step 0.
        if (!runPipelineSteps(pipe)) return

        pipelineComplete(pipe)
    }
}

/**
 * Records one step in the three trajectory sinks (hippocampus, execution tracker, event log).
 *
 * Extracted from [executeTaskPipeline]'s scope so a RESUMED run writes exactly the same record:
 * the resumed loop lives in [runPipelineSteps], which cannot see that function's locals.
 */
internal fun AniobViewModel.recordTrajectory(
    stepIndex: Int,
    observation: String,
    action: AniobAction,
    provider: String,
    latencyMs: Long,
    screenHash: String,
    verified: Boolean,
    textEvidence: String? = null,
    targetBounds: AniobRect? = null
) {
    hippocampusTracker.recordStep(
        AniobHippocampusTracker.HippocampusStep(
            stepIndex = stepIndex,
            observation = observation,
            thought = action.thought,
            action = action,
            latencyMs = latencyMs,
            screenHash = screenHash,
            provider = provider,
            verified = verified,
            textEvidence = textEvidence,
            targetBounds = targetBounds
        )
    )
    executionTracker.recordEvent(
        AniobExecutionTracker.TrajectoryEvent(
            stepIndex = stepIndex,
            observationSummary = observation,
            thought = action.thought,
            toolName = action.toolName,
            action = action,
            latencyMs = latencyMs,
            outcome = if (verified) "SUCCESS" else "FAILURE",
            isVerified = verified,
            textEvidence = textEvidence,
            targetBounds = targetBounds
        )
    )
    app.eventLogger.logStep(
        com.aniob.app.metrics.EventLogger.TrajectoryStep(
            stepIndex = stepIndex,
            observation = observation,
            thought = action.thought,
            action = action,
            latencyMs = latencyMs,
            screenHash = screenHash,
            provider = provider,
            textEvidence = textEvidence,
            targetBounds = targetBounds
        )
    )
}

/**
 * The observation/planning/action loop of one run, plus the cleanup that belongs to it.
 *
 * Split out of [executeTaskPipeline] so the SAME run can be resumed after a question, a
 * confirmation or a manual handover: everything the loop reads lives in [PipelineRun] (task context,
 * step index, last screen, verified actions, provider lock), so resuming continues the work instead
 * of replaying it. Called once by [executeTaskPipeline] and again by the resume path in
 * `AniobViewModelTaskInput.kt`.
 *
 * @return true when the run finished (its closing phase must run), false when it parked waiting for
 *         the user — a parked run keeps its identity and is resumed, never restarted.
 */
internal suspend fun AniobViewModel.runPipelineSteps(pipe: PipelineRun): Boolean {
    with(pipe) {
        // First entry only: a resume must not re-measure (or re-run) the run's setup.
        val tryFastLane = !stepsEntered
        stepsEntered = true
        if (tryFastLane) {
            val setupDurationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            app.researchTelemetry.setSetupLatency(setupDurationMs)
        }

        try {
            // The fast-lane launch is attempted exactly once per run. A resume starts from the
            // screen the task was paused on, not from the launch that already happened.
            if (tryFastLane && fastLaunchTarget != null && accessibility != null && laneShortcut != null &&
                taskGeneration == activeGeneration && _uiState.value.isRunning
            ) {
                val label = laneShortcut.label ?: fastLaunchTarget
                val openApp = AniobAction.OpenApp(
                    packageName = fastLaunchTarget,
                    activityName = AniobLaunchHub.activityClassName(laneShortcut.launchActivity),
                    thought = "Open $label"
                )
                val origin = AniobScreenState(
                    packageName = accessibility.currentForegroundPackage().orEmpty(),
                    observationKind = "fast-lane"
                )
                // AskPolicy still decides. Anything it wants to block or confirm is not a fast
                // launch: the standard path below owns the confirmation sheet.
                val safety = AniobSafetyInterceptor.evaluateAction(
                    action = openApp,
                    targetNode = null,
                    screenState = origin,
                    screenFingerprint = origin.treeHash
                )
                val closingVerdict = AniobFastLane.launchVerdict(resolvedCriteria, fastLaunchTarget)
                if (safety.isAllowed && !safety.requiresConfirmation && closingVerdict != null) {
                    setPhase(AgentPhase.PLANNING, ThinkSource.RULES)
                    primaryProvider = "APP_LAUNCH"
                    decisionReason = "App shortcut: '$label' resolved by the intent resolver; " +
                        "foreground package confirmed (0 LLM calls)"
                    app.researchTelemetry.recordShortcutAttempt(attempted = true)
                    _uiState.update {
                        it.copy(
                            currentStep = 1,
                            statusMessage = "Opening $label...",
                            lastRoutingReason = decisionReason,
                            lastProviderUsed = primaryProvider
                        )
                    }
                    persistRunningMarker(task, 1, openApp.describeAction())
                    val dispatchedAt = System.currentTimeMillis()
                    val dispatched = executeActionSync(accessibility, openApp, waitForIdle = false)
                    if (dispatched) {
                        actionsDispatched++
                        app.researchTelemetry.recordStep()
                        app.researchTelemetry.recordActionCompleted()
                    }
                    // Event-driven proof instead of idle-wait + settle + a second tree capture.
                    val confirmed = dispatched && confirmLaunched(fastLaunchTarget)
                    val launchLatencyMs = (System.currentTimeMillis() - dispatchedAt).coerceAtLeast(0L)
                    if (confirmed) {
                        app.researchTelemetry.markIntentShortcutUsed()
                        recordTrajectory(
                            stepIndex = 0,
                            observation = "foreground $fastLaunchTarget after ${openApp.toolName}",
                            action = openApp,
                            provider = primaryProvider,
                            latencyMs = launchLatencyMs,
                            screenHash = "",
                            verified = true,
                            textEvidence = fastLaunchTarget
                        )
                        publishStep(
                            stepIndex = 0,
                            action = openApp,
                            provider = primaryProvider,
                            latencyMs = launchLatencyMs,
                            verified = true
                        )
                        addStepRecord(
                            AniobStepRecord(
                                stepIndex = 0,
                                screenHash = "",
                                action = openApp,
                                provider = primaryProvider,
                                latencyMs = launchLatencyMs,
                                tokensUsed = 0,
                                verifiedSuccess = true,
                                textEvidence = fastLaunchTarget
                            )
                        )
                        completedByIntent = true
                        intentVerdict = closingVerdict
                        finalOutcome = Outcome.SUCCESS
                        terminalReason = "Opened $label"
                        fastLaunchProof = AniobScreenState(
                            packageName = fastLaunchTarget,
                            observationKind = "fast-lane"
                        )
                    } else {
                        app.researchTelemetry.recordShortcutAttempt(
                            attempted = true,
                            degraded = true,
                            reason = if (dispatched) "Foreground not confirmed" else "Dispatch failed"
                        )
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Fast-lane launch of $fastLaunchTarget not confirmed in ${launchLatencyMs}ms; using full observation"
                        )
                    }
                }
                if (fastLaunchProof == null) {
                    // Not confirmed (or not eligible): the FULL existing observation path, starting
                    // from the before-image the fast lane skipped.
                    awaitAppBackgrounded(accessibility)
                    screenBefore = accessibility.captureCurrentScreenState()
                }
            }
            runStepLoop(pipe)
        } finally {
            // A shortcut disabled mid-run because this device could not dispatch it is a property
            // of the run, not of the session.
            executionRouter.intentShortcutsEnabled = shortcutsWereEnabled
            releaseTaskProviders(task.id)
            AniobAccessibilityService.isTaskActive = false
            if (!researchMode.immediateCompletion || !currentCoroutineContext().isActive) learningPipeline.onTaskEnd(task.id)
        }

        if (taskGeneration != activeGeneration) {
            // A newer task took ownership; record this one honestly but do not touch the live UI.
            app.researchTelemetry.endTask(com.aniob.app.research.ResearchOutcome.INTERRUPTED, "superseded", runId = task.id)
            return false
        }

        return !parked
    }
}

/**
 * The step loop alone: observe, propose, act — until a proof, a limit, a stop or a park.
 *
 * Kept separate from [runPipelineSteps] so a closing phase that finds an unprocessed instruction
 * can keep working inside the SAME run (same context, same step counter, same verified actions)
 * without re-running the run's setup or its cleanup.
 */
internal suspend fun AniobViewModel.runStepLoop(pipe: PipelineRun) {
    with(pipe) {
        while (fastLaunchProof == null && currentStep < maxSteps && _uiState.value.isRunning) {
            // Instructions the user typed while the last step ran are folded in HERE, before the
            // screen is read and the model is asked: the plan then follows the newest wording.
            drainInstructionsInto(pipe)
            val observed = when (val flow = pipelineObserve(pipe)) {
                is PipelineFlow.Next -> flow.value
                PipelineFlow.Continue -> continue
                PipelineFlow.Break -> break
                PipelineFlow.Return -> return
            }
            val proposed = when (val flow = pipelinePropose(pipe, observed)) {
                is PipelineFlow.Next -> flow.value
                PipelineFlow.Continue -> continue
                PipelineFlow.Break -> break
                PipelineFlow.Return -> return
            }
            when (pipelineAct(pipe, observed, proposed)) {
                is PipelineFlow.Next -> Unit
                PipelineFlow.Continue -> continue
                PipelineFlow.Break -> break
                PipelineFlow.Return -> return
            }
        }
    }
}
