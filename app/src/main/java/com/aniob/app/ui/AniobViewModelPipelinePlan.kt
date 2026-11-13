package com.aniob.app.ui

import android.content.Intent
import com.aniob.app.device.AniobCapabilityProbe
import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.*
import com.aniob.core.execution.StepPipeline
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.AniobReflexResolver
import com.aniob.core.verify.FastVerify
import com.aniob.core.providers.AniobGenerationResult
import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.providers.AniobStructuredOutput
import com.aniob.core.providers.AniobLocalActionResolver
import com.aniob.core.router.RouteTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.BURST_MAX_OUTPUT_TOKENS
import com.aniob.app.ui.AniobViewModel.Companion.INTENT_SETTLE_MS

/** What the planning phase of one step produced for dispatch. */
internal class ProposedStep(
    val proposal: StepPipeline.Proposal,
    val targetNode: AniobNode?,
    val plannedBurst: ActionBurst?
)

/**
 * Plan: second third of one step of [executeTaskPipeline]. Turns the ladder result into the
 * proposal to dispatch (intent, app launch, replay, skill or a model call). The statements are
 * the loop body unchanged; `break` and `continue` are reported as [PipelineFlow] values.
 */
internal suspend fun AniobViewModel.pipelinePropose(
    pipe: PipelineRun,
    observed: ObservedStep
): PipelineFlow<ProposedStep> {
    return with(pipe) {
        val currentScreen = observed.currentScreen
        val powerState = observed.powerState
        val userRouting = observed.userRouting
        val ladderResult = observed.ladderResult
        var proposal: StepPipeline.Proposal
        var targetNode: AniobNode? = null
        // Filled by a model burst or a remember-cache replay; executed below.
        var plannedBurst: ActionBurst? = null
        when (ladderResult) {
            is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                primaryProvider = "INTENT"
                decisionReason = ladderResult.reason
                app.researchTelemetry.recordShortcutAttempt(attempted = true)
                val shortcut = ladderResult.shortcut
                _uiState.update { it.copy(statusMessage = "Opening with a system intent...") }
                // The intent is fired before any model work. A partial shortcut (an alarm
                // editor, a prefilled contact, a settings panel) leaves the agent on the
                // screen it opened, so the next step plans from there instead of from the
                // launcher. A shortcut that claims the whole task still has to be seen on
                // the screen before it may close the run.
                if (currentStep == 0) {
                    val beforePackage = accessibility?.currentForegroundPackage() ?: screenBefore?.packageName
                    val deviceCommand = AniobReflexResolver.commandOf(shortcut)
                    val deviceArgs = AniobReflexResolver.argsOf(shortcut)
                    val dispatched = if (deviceCommand != null) {
                        runDeviceShortcut(deviceCommand, deviceArgs)
                    } else {
                        dispatchSystemIntent(shortcut)
                    }
                    if (dispatched) {
                        actionsDispatched++
                        app.researchTelemetry.recordStep()
                        app.researchTelemetry.recordActionCompleted()
                        // A silent device command (volume, torch, pause...) changes nothing on
                        // screen: waiting for a window change would burn the whole settle budget.
                        val sequenceWaitMs = deviceArgs["then_delay_ms"]?.toLongOrNull() ?: 0L
                        val landed = if (deviceCommand != null && deviceCommand.completesOnDispatch) {
                            // A decomposed sequence ("on … off after 5s") finishes AFTER the
                            // dispatch. Judging it immediately would read the wrong state
                            // and mislabel a correct run, so wait out the scheduled leg.
                            // Was a fixed sleep of the whole budget. The scheduled
                            // leg cannot fire before its delay; after that the run is
                            // released as soon as the hardware shows the final state. With
                            // nothing probeable the full budget still applies.
                            if (sequenceWaitMs > 0L) {
                                val legScheduledAt = System.currentTimeMillis()
                                until(sequenceWaitMs + 400L, stepMs = 50L) {
                                    System.currentTimeMillis() - legScheduledAt >= sequenceWaitMs &&
                                        evalDeviceAssertions(resolvedCriteria).let { verdicts ->
                                            verdicts.isNotEmpty() && verdicts.all {
                                                it.state == com.aniob.core.domain.CriterionState.PASSED
                                            }
                                        }
                                }
                            }
                            null
                        } else {
                            awaitIntentScreen(beforePackage, INTENT_SETTLE_MS, shortcut.targetPackage)
                        }
                        screenBefore = landed ?: screenBefore
                        if (shortcut.requiresObservation) {
                            if (landed != null) {
                                app.researchTelemetry.markIntentShortcutUsed()
                            } else {
                                app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Screen did not land")
                            }
                            currentStep++
                            return PipelineFlow.Continue
                        }
                        // The intent claims to be the whole task. Prove it on the screen: a
                        // success nobody looked at is not a success this app reports.
                        // `currentStep + 1` because this dispatch already happened; the
                        // criterion set includes a min-steps check that would fail otherwise.
                        val screenVerdict = if (resolvedCriteria?.hasObservableCheck == true) {
                            landed?.let { resolvedCriteria.evaluateCriteria(it, currentStep + 1) }.orEmpty()
                        } else {
                            emptyList()
                        }
                        // World-state assertions (torch) are probed against the hardware —
                        // no screen could ever prove them (BUG-06).
                        val verdict = screenVerdict + evalDeviceAssertions(resolvedCriteria)
                        when {
                            verdict.isNotEmpty() && verdict.all {
                                it.state == com.aniob.core.domain.CriterionState.PASSED
                            } -> {
                                app.researchTelemetry.markIntentShortcutUsed()
                                completedByIntent = true
                                intentVerdict = verdict
                                finalOutcome = Outcome.SUCCESS
                                terminalReason = shortcut.label?.let { "Opened $it" }
                                    ?: "System intent dispatched"
                                return PipelineFlow.Break
                            }
                            verdict.isNotEmpty() -> {
                                app.eventLogger.warn(
                                    "AniobViewModel",
                                    "Intent ${shortcut.action} landed but the criteria are not met; continuing"
                                )
                                app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Criteria not met")
                                currentStep++
                                return PipelineFlow.Continue
                            }
                            shortcut.completesOnDispatch && (deviceCommand?.completesOnDispatch == true || landed != null) && resolvedCriteria?.hasObservableCheck != true -> {
                                // A system reflex (alarm/timer with SKIP_UI, torch, battery, dial,
                                // URL, a settings panel that is the whole request): Android resolved
                                // the intent or the system service accepted the call, and no
                                // interactive screen is left to read. The dispatch IS the
                                // verification, so the run is a verified success; recording it as
                                // UNVERIFIED made research telemetry count a clean run as failed.
                                app.researchTelemetry.markIntentShortcutUsed()
                                completedByIntent = true
                                finalOutcome = Outcome.SUCCESS
                                terminalReason = shortcut.label?.let { "Dispatched $it" }
                                    ?: "System intent dispatched"
                                return PipelineFlow.Break
                            }
                            else -> {
                                // Nothing machine-checkable and not a finished reflex (an editor
                                // that was only opened): honest, and never a claimed success.
                                completedByIntent = true
                                finalOutcome = Outcome.UNVERIFIED
                                terminalReason = shortcut.label?.let { "Dispatched $it" }
                                    ?: "System intent dispatched without a checkable criterion"
                                return PipelineFlow.Break
                            }
                        }
                    }
                    app.eventLogger.warn(
                        "AniobViewModel",
                        "System intent ${shortcut.action} could not be dispatched; planning without shortcuts"
                    )
                    app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Dispatch failed")
                    executionRouter.intentShortcutsEnabled = false
                    return PipelineFlow.Continue
                }
                proposal = StepPipeline.Proposal.DirectIntent(
                    action = AniobAction.Finish(summary = "Intent dispatched: ${shortcut.targetPackage}"),
                    label = "INTENT"
                )
            }
            is AniobExecutionRouter.ExecutionPlanResult.AppLaunch -> {
                primaryProvider = "APP_LAUNCH"
                decisionReason = ladderResult.reason
                app.researchTelemetry.recordShortcutAttempt(attempted = true)
                _uiState.update {
                    it.copy(
                        statusMessage = "Opening ${ladderResult.appName}...",
                        lastRoutingReason = ladderResult.reason,
                        lastProviderUsed = primaryProvider
                    )
                }
                proposal = StepPipeline.Proposal.Model(
                    AniobAction.OpenApp(
                        packageName = ladderResult.packageName,
                        // Explicit component when the registry knows it: no chooser sheet,
                        // and the verifier can check the package it actually expects.
                        activityName = AniobLaunchHub.activityClassName(ladderResult.launchActivity),
                        thought = "Open ${ladderResult.appName}"
                    ),
                    primaryProvider
                )
            }
            is AniobExecutionRouter.ExecutionPlanResult.RememberReplay -> {
                primaryProvider = "REMEMBER"
                decisionReason = ladderResult.reason
                app.researchTelemetry.recordRememberReplay(ladderResult.actions.size)
                _uiState.update {
                    it.copy(
                        statusMessage = "Replaying ${ladderResult.actions.size} remembered action(s)...",
                        lastRoutingReason = ladderResult.reason,
                        lastProviderUsed = primaryProvider
                    )
                }
                // The replay is dispatched like a burst: every action is re-grounded on the
                // live window, and the last one keeps the normal step pipeline so Verify runs.
                plannedBurst = ActionBurst(
                    thought = "remembered ${ladderResult.actions.size} action(s)",
                    isComplete = false,
                    actions = ladderResult.actions
                )
                proposal = StepPipeline.Proposal.Model(ladderResult.actions.last(), primaryProvider)
            }
            is AniobExecutionRouter.ExecutionPlanResult.FastPathStep -> {
                primaryProvider = "FASTPATH"
                decisionReason = ladderResult.reason
                app.researchTelemetry.recordFastpathStep()
                _uiState.update { it.copy(statusMessage = "FastPath replay step ${currentStep + 1}...") }
                proposal = StepPipeline.Proposal.FastPath(ladderResult.action)
                targetNode = ladderResult.action.semanticTarget()?.let {
                    com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                }
            }
            is AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution -> {
                primaryProvider = "SKILL"
                decisionReason = ladderResult.reason
                app.researchTelemetry.recordSkillUsed(ladderResult.skill.name)
                _uiState.update { it.copy(statusMessage = "Executing Skill '${ladderResult.skill.name}' step ${currentStep + 1}...") }
                proposal = StepPipeline.Proposal.Skill(
                    action = ladderResult.action,
                    skillName = ladderResult.skill.name,
                    riskTier = ladderResult.skill.riskTier,
                    requiresConfirmation = ladderResult.skill.confirmationRequired
                )
                targetNode = ladderResult.action.semanticTarget()?.let {
                    com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                }
            }
            is AniobExecutionRouter.ExecutionPlanResult.CannotProceed -> {
                primaryProvider = "NONE"
                decisionReason = ladderResult.reason
                _uiState.update {
                    it.copy(
                        statusMessage = "Cannot proceed: ${ladderResult.reason}",
                        lastRoutingReason = ladderResult.reason,
                        lastProviderUsed = primaryProvider
                    )
                }
                proposal = StepPipeline.Proposal.Model(
                    AniobAction.Fail(ladderResult.reason),
                    primaryProvider
                )
            }
            is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch -> {
                // A plan that was held back last turn describes what the model expected to find
                // after the actions that did run; it is handed to this planning pass only, and
                // spent below so it can never describe a screen the model has moved past.
                val carriedPlan = deferredPlanHint
                val decision = ladderResult.decision
                primaryProvider = when (decision.target) {
                    RouteTarget.OMNIROUTE_CLOUD -> "OMNIROUTE_CLOUD"
                    RouteTarget.LOCAL_SLM -> "LOCAL_SLM"
                    else -> "NONE"
                }
                // Lock the task onto the provider that is about to do the work, so the next
                // step does not bounce back to the other one. The lock only shapes routing
                // between the two model providers and never overrides strict policy or device
                // constraints (enforced inside decideRoute).
                if (decision.target == RouteTarget.LOCAL_SLM || decision.target == RouteTarget.OMNIROUTE_CLOUD) {
                    taskStickyTarget = decision.target
                }
                decisionReason = decision.reason
                _uiState.update {
                    it.copy(
                        statusMessage = "Routed to $primaryProvider: ${decision.reason}",
                        lastRoutingReason = decision.reason,
                        lastProviderUsed = primaryProvider
                    )
                }
                if (decision.target == RouteTarget.OMNIROUTE_CLOUD) escalations++

                if (decision.target == RouteTarget.LOCAL_SLM) refreshLocalEngine()
                val localCaps = if (decision.target == RouteTarget.LOCAL_SLM) localLlmClient.capabilities() else null
                val localCanDrive = decision.target == RouteTarget.LOCAL_SLM &&
                    localLlmClient.isModelLoaded() && (localCaps?.canDriveActions == true)
                if (decision.target == RouteTarget.LOCAL_SLM && !localCanDrive) {
                    app.eventLogger.warn("AniobViewModel", "Local engine quarantined: ${localCaps?.reason ?: "model not loaded"}")
                    decisionReason = "local_quarantined: ${localCaps?.reason ?: "model not loaded"}"
                    _uiState.update {
                        it.copy(lastProviderUsed = primaryProvider, lastRoutingReason = decisionReason)
                    }
                }

                // The cloud path can parse a burst; the local resolver is single-action, so
                // the local prompt keeps the one-action shape.
                val burstEnabled = researchMode.actionBursts && !localCanDrive && !burstsSuspended
                val executorRequest = taskContext.buildExecutorRequest(
                    screen = currentScreen,
                    activeSubgoal = taskProgress?.summary,
                    lastAction = taskContext.trajectory.lastOrNull()?.describeAction(),
                    lastResult = taskContext.condensedHistory.lastOrNull(),
                    burstMode = burstEnabled,
                    // The hint describes a plan that was split across screens, which only the
                    // burst contract understands; a single-action reply would just read it as
                    // history. A suspended burst drops it instead of carrying it forward.
                    carriedPlanHint = carriedPlan.takeIf { burstEnabled }
                )
                val executorPrompt = executorRequest.toPromptString()
                // A planning call spends the carried plan: it is context for exactly one
                // request, and a second copy would describe a screen the model has left.
                deferredPlanHint = null
                val configuredPrompt = configLoader.prompt("system_prompt.txt") ?: "System: Android Agent. Reply with a single JSON tool call."
                // The model is told, every run, that it is wired into this phone, which direct
                // commands exist, and which of them are switched off right now. A small
                // on-device model gets the terse catalogue; a cloud model the full one.
                val capabilityState = AniobCapabilityProbe.snapshot(app)
                fun withCapabilities(base: String, compact: Boolean): String =
                    base + "\n\n" + com.aniob.core.capability.AniobCapabilityManifest.render(capabilityState, compact)
                val baseSystemPrompt = withCapabilities(configuredPrompt, compact = localCanDrive)
                val systemPrompt = if (burstEnabled) {
                    // The bundled prompt insists on one action per reply; the plan contract
                    // is appended so it overrides that line instead of fighting it.
                    baseSystemPrompt +
                        "\n\nMULTI-ACTION PLANNING: ignore the single-action rule above. Reply with ONE " +
                        "compact JSON object and nothing else:\n" +
                        "{\"thought\": \"brief 10-word plan\", \"is_complete\": false, " +
                        "\"actions\": [{\"type\": \"tap|input|long_press|scroll|back|open_app|wait|device_control\", " +
                        "\"id\": 2, \"text\": \"only for input\", \"submit\": true}]}\n" +
                        "Plan the next ${ActionBurst.MIN_ACTIONS}-${ActionBurst.MAX_ACTIONS} grounded sequential actions of the task, in order. The FIRST one must name an " +
                        "element that is on the screen you were given; the ones after it may belong to the " +
                        "screen it opens. Actions whose elements are not visible yet are held back and " +
                        "replayed later, so plan the whole next stretch of the task instead of stopping at " +
                        "the first tap. For a field that is the only text box on screen you may omit \"id\". " +
                        "No pixel coordinates. Keep the reply compact.\n" +
                        "Exception: a chore that needs no screen (media, volume, flashlight, calls, codes, files, " +
                        "alarms...) is NOT a plan; reply with the single DEVICE_CONTROL JSON object instead."
                } else if (baseSystemPrompt.contains("ACTION_JSON_SCHEMA")) {
                    baseSystemPrompt
                } else {
                    "$baseSystemPrompt\n\nSchema:\n${com.aniob.core.domain.AniobActionSchema.ACTION_JSON_SCHEMA}"
                }

                // Plan-5.1: Route prompt generation through PromptAssembler for prefix caching
                val promptAssembler = com.aniob.core.speed.PromptAssembler(systemPrompt)
                val assembledPrompt = promptAssembler.assemble(
                    com.aniob.core.speed.PromptAssembler.Tail(
                        observation = executorPrompt
                    )
                )
                val activePrompt = assembledPrompt.text

                // The plain one-action prompts, for any model that cannot read a burst contract.
                fun singleActionPrompts(): Pair<String, String> {
                    if (!burstEnabled && primaryProvider != "LOCAL_SLM") return systemPrompt to activePrompt
                    // A step handed to the on-device model after a cloud failure gets the terse catalogue.
                    val singleBase = if (primaryProvider == "LOCAL_SLM") {
                        withCapabilities(configuredPrompt, compact = true)
                    } else {
                        baseSystemPrompt
                    }
                    val singleSystem = if (singleBase.contains("ACTION_JSON_SCHEMA")) {
                        singleBase
                    } else {
                        "$singleBase\n\nSchema:\n${com.aniob.core.domain.AniobActionSchema.ACTION_JSON_SCHEMA}"
                    }
                    val singleObservation = taskContext.buildExecutorRequest(
                        screen = currentScreen,
                        activeSubgoal = taskProgress?.summary,
                        lastAction = taskContext.trajectory.lastOrNull()?.describeAction(),
                        lastResult = taskContext.condensedHistory.lastOrNull(),
                        burstMode = false
                    ).toPromptString()
                    val singleTail = com.aniob.core.speed.PromptAssembler.Tail(observation = singleObservation)
                    return singleSystem to com.aniob.core.speed.PromptAssembler(singleSystem).assemble(singleTail).text
                }

                // Resilient provider ladder. A cloud step that comes back unusable (HTTP 4xx/5xx,
                // an HTML page where JSON belongs, gateway down, no assistant message) must not
                // end the task: the same step is retried on the on-device model. Only when that
                // cannot produce a real action does the step fail, with a reason the user can act
                // on. A cloud-only routing choice is honoured and never downgraded.
                suspend fun degradeAfterCloudFailure(error: Throwable, allowLocal: Boolean): AniobAction {
                    val kind = com.aniob.core.external.AniobGatewayClassifier.kindOf(error)
                    val detail = if (kind == com.aniob.core.external.GatewayFailureKind.RATE_LIMIT) {
                        com.aniob.core.narration.FailureReasonCopy.gatewayRateLimited(
                            retryAfterMs = (error as? com.aniob.core.external.AniobGatewayException)?.retryAfterMs,
                            willRetryHere = false,
                            localFallbackReady = allowLocal && userRouting != "cloud-only"
                        )
                    } else {
                        error.message ?: "network error"
                    }
                    val cloudFailure = "Cloud action generation failed: $detail"
                    app.eventLogger.warn("AniobViewModel", "Cloud step failed (${kind.name}): $detail")
                    if (!allowLocal || userRouting == "cloud-only") return AniobAction.Fail(cloudFailure)
                    refreshLocalEngine()
                    val caps = localLlmClient.capabilities()
                    if (!localLlmClient.isModelLoaded() || !caps.canDriveActions) {
                        return AniobAction.Fail(
                            "$cloudFailure. No on-device model is ready (${caps.reason}): install one in " +
                                "Models, or check the gateway address in Settings."
                        )
                    }
                    primaryProvider = "LOCAL_SLM"
                    taskStickyTarget = RouteTarget.LOCAL_SLM
                    decisionReason = "Downgraded to the on-device model after a cloud failure (${kind.name})"
                    _uiState.update {
                        it.copy(
                            statusMessage = "Cloud unavailable. Continuing on the on-device model...",
                            lastProviderUsed = primaryProvider,
                            lastRoutingReason = decisionReason
                        )
                    }
                    val (localSystem, localObservation) = singleActionPrompts()
                    app.researchTelemetry.recordLocalInference()
                    val gen = localLlmClient.generateStepResult(localSystem, localObservation)
                    val localPromptEst = (localSystem.length + localObservation.length) / 4
                    app.researchTelemetry.recordPromptTokens(localPromptEst)
                    totalTokens += localPromptEst.coerceAtLeast(80)
                    val structured = AniobLocalActionResolver.resolveStructured(gen, currentScreen)
                    if (structured.usedFallback) {
                        app.eventLogger.warn(
                            "AniobViewModel",
                            "On-device fallback produced no schema-valid action: ${structured.reason}"
                        )
                        return AniobAction.Fail(
                            "$cloudFailure. The on-device model could not plan this step either (${structured.reason})."
                        )
                    }
                    return structured.action
                }

                suspend fun runOmniRouteStep(
                    allowLocalFallback: Boolean = true
                ): AniobAction {
                    val omniroute = getActionStepProvider(sessionId = task.id)
                    val promptAsksForVisualInspection = (localFailCount > 0) || task.rawPrompt.contains(
                        Regex("(?i)look|see|inspect|read|image|photo|visual")
                    )
                    val isSensitiveScreen = currentScreen.nodes.any {
                        it.isPassword || it.viewId.contains(Regex("(?i)password|pwd|otp|pin|cvv|card_number"))
                    }
                    // Observe cost: the bitmap is the expensive half of perception, so the
                    // compact conditions only pay for it when the tree is sparse or the
                    // previous verify failed. Baseline pays on every step by design.
                    val labeledInteractive = com.aniob.core.perception.ObserveCapturePolicy
                        .labeledInteractiveCount(currentScreen.nodes)
                    val previousStepFailed = stepResult?.verification?.isSuccessful == false
                    // A tier-2 verify only spends a bitmap when nothing in the tree moved,
                    // which is exactly the visual-popup / no-op-tap case: hand those pixels
                    // to this Reason step instead of dropping them.
                    val screenIdentityNow = com.aniob.core.verify.FastVerify.screenIdentity(currentScreen)
                    val reusedScreenshot = pendingVerifyScreenshot
                        ?.takeIf { it.first == screenIdentityNow }
                        ?.second
                    pendingVerifyScreenshot = null
                    val wantsPixels = reusedScreenshot != null ||
                        com.aniob.core.perception.ObserveCapturePolicy
                            .shouldCaptureScreenshot(
                                fastObservation = researchMode.fastObservation,
                                labeledInteractiveNodes = labeledInteractive,
                                previousStepFailed = previousStepFailed,
                                visionRequested = promptAsksForVisualInspection
                            )
                    val screenshotBase64 = if (wantsPixels && !isSensitiveScreen && accessibility != null) {
                        reusedScreenshot ?: captureVerifyScreenshot()
                    } else null
                    // A burst reply is a handful of tokens; a tight budget keeps the
                    // decode short and stops a chatty model from padding the plan.
                    // Streaming + early parse: the action leaves the device the moment the
                    // reply's JSON object closes, instead of waiting for the model to stop
                    // talking, for the socket to close and for a full body read.
                    setPhase(AgentPhase.PLANNING, ThinkSource.MODEL_CLOUD, provider = "OmniRoute")
                    val raw = omniroute.getNextActionRawStreaming(
                        systemPrompt = systemPrompt,
                        userPrompt = activePrompt,
                        screenshotBase64 = screenshotBase64,
                        maxTokens = if (burstEnabled) BURST_MAX_OUTPUT_TOKENS else 512,
                        onFirstToken = { advancePhase(AgentPhase.STREAMING) }
                    )
                    val promptEstTokens = (systemPrompt.length + activePrompt.length) / 4
                    app.researchTelemetry.recordPromptTokens(promptEstTokens)
                    totalTokens += promptEstTokens.coerceAtLeast(150)
                    return raw.fold(
                        onSuccess = { text ->
                            totalTokens += (text.length / 4).coerceAtLeast(1)
                            // Burst reply: several actions for the screen we just observed.
                            val burst = if (burstEnabled) {
                                AniobStructuredOutput.parseBurstOrRepair(
                                    raw = text,
                                    screenState = currentScreen,
                                    goal = task.clarifiedGoal
                                )
                            } else {
                                null
                            }
                            if (burst != null) {
                                plannedBurst = burst
                                app.researchTelemetry.recordBurstPlanned()
                                if (burst.isComplete) {
                                    AniobAction.Finish(burst.thought.ifBlank { "Burst complete" })
                                } else {
                                    burst.actions.last()
                                }
                            } else {
                                val executorDecode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR)
                                var structured = AniobStructuredOutput.parseOrRepair(
                                    raw = text,
                                    screenState = currentScreen,
                                    decode = executorDecode
                                )
                                if (structured.usedFallback) {
                                    val repairMsg = "${AniobStructuredOutput.repairPrompt(text)}\n\nOriginal Request:\n$executorPrompt"
                                    val repaired = runCatching {
                                        omniroute.getNextActionRaw(
                                            systemPrompt,
                                            repairMsg,
                                            null
                                        ).getOrNull()
                                    }.getOrNull()
                                    if (repaired != null) {
                                        val repairPromptTokens = (systemPrompt.length + repairMsg.length) / 4
                                        app.researchTelemetry.recordPromptTokens(repairPromptTokens)
                                        totalTokens += ((repairMsg.length + repaired.length) / 4).coerceAtLeast(1)
                                        structured = AniobStructuredOutput.parseOrRepair(
                                            raw = repaired,
                                            screenState = currentScreen,
                                            decode = executorDecode
                                        )
                                    }
                                }
                                if (structured.usedFallback) {
                                    app.eventLogger.warn("AniobViewModel", "Cloud output unusable: ${structured.reason}")
                                }
                                structured.action
                            }
                        },
                        onFailure = { error -> degradeAfterCloudFailure(error, allowLocalFallback) }
                    )
                }

                val canEscalateToCloud = userRouting != "local-only" && isCloudConfigured() && powerState.isNetworkAvailable

                val plannedAction = if (localCanDrive) {
                    cloudEscalationAllowed = canEscalateToCloud
                    val cloudWasSticky = executionLlm.stickyCloud
                    executionLlm.plan(
                        request = com.aniob.core.ladder.ExecutionLlmGateway.Request(systemPrompt, activePrompt),
                        local = { request ->
                            setPhase(AgentPhase.PLANNING, ThinkSource.MODEL_LOCAL)
                            app.researchTelemetry.recordLocalInference()
                            val gen = localLlmClient.generateStepResult(
                                request.systemPrompt,
                                request.userPrompt
                            )
                            val localPromptEst = (request.systemPrompt.length + request.userPrompt.length) / 4
                            app.researchTelemetry.recordPromptTokens(localPromptEst)
                            totalTokens += localPromptEst.coerceAtLeast(80)
                            if (gen is AniobGenerationResult.Ready) {
                                android.util.Log.i("AniobViewModel", "Local SLM generated output (len=${gen.fullText.length})")
                            }
                            val structured = AniobLocalActionResolver.resolveStructured(gen, currentScreen)
                            if (structured.usedFallback) {
                                app.eventLogger.warn("AniobViewModel", "Local SLM produced no schema-valid action: ${structured.reason}")
                            }
                            com.aniob.core.ladder.ExecutionLlmGateway.LocalAttempt(
                                structured.action, structured.usedFallback, structured.reason
                            )
                        },
                        cloud = { _ ->
                            // Same system and user prompt as the on-device attempt; the
                            // screen is not captured again.
                            primaryProvider = "OMNIROUTE_CLOUD"
                            taskStickyTarget = RouteTarget.OMNIROUTE_CLOUD
                            escalations++
                            decisionReason = if (!cloudWasSticky) {
                                "Escalated to OmniRoute Cloud after local output fallback"
                            } else {
                                "Staying on OmniRoute Cloud after an earlier local fallback"
                            }
                            _uiState.update {
                                it.copy(
                                    statusMessage = "Escalating to Cloud...",
                                    lastProviderUsed = primaryProvider,
                                    lastRoutingReason = decisionReason
                                )
                            }
                            runOmniRouteStep(allowLocalFallback = false)
                        }
                    ).action
                } else if (decision.target == RouteTarget.LOCAL_SLM && canEscalateToCloud) {
                    primaryProvider = "OMNIROUTE_CLOUD"
                    taskStickyTarget = RouteTarget.OMNIROUTE_CLOUD
                    escalations++
                    decisionReason = "Escalated to OmniRoute Cloud (${localCaps?.reason ?: "local model not loaded"})"
                    _uiState.update {
                        it.copy(
                            statusMessage = "Escalating to Cloud...",
                            lastProviderUsed = primaryProvider,
                            lastRoutingReason = decisionReason
                        )
                    }
                    runOmniRouteStep(allowLocalFallback = false)
                } else if (decision.target == RouteTarget.LOCAL_SLM) {
                    AniobAction.Fail("Local model is not capable or ready: ${localCaps?.reason ?: "model not loaded"}")
                } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD && isOmniRouteReady()) {
                    runOmniRouteStep()
                } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD) {
                    AniobAction.Fail("Cloud AI is not set up — add your OmniRoute key and gateway address in Settings")
                } else {
                    AniobAction.Fail("No capable provider available: ${decision.reason}")
                }

                // Watchdog check
                val watchdogKey = currentScreen.screenHash.ifBlank { currentScreen.treeHash }
                watchdog.record(watchdogKey, plannedAction)
                val loopDetected = watchdog.isLoopDetected()
                val loopAction = if (loopDetected) {
                    watchdog.reset()
                    AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK)
                } else {
                    plannedAction
                }

                val targetSom = loopAction.semanticTarget() as? SemanticTarget.SomIndex
                val effectiveAction = if (targetSom != null && currentScreen.nodes.none { it.id == targetSom.index }) {
                    AniobAction.Fail("Node id ${targetSom.index} not present on current screen")
                } else {
                    loopAction
                }

                proposal = StepPipeline.Proposal.Model(effectiveAction, primaryProvider)
                targetNode = effectiveAction.semanticTarget()?.let {
                    com.aniob.core.execution.AniobActionExecutor.resolveTarget(effectiveAction, currentScreen)?.node
                }
            }
        }

        PipelineFlow.Next(ProposedStep(proposal, targetNode, plannedBurst))
    }
}
