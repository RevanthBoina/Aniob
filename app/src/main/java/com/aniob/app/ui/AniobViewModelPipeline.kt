package com.aniob.app.ui

import com.aniob.core.providers.AniobModelFiles
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.research.ResearchRun
import com.aniob.app.research.ResearchSummaryRow
import com.aniob.app.research.StudyPass
import com.aniob.app.device.AniobCapabilityProbe
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.Outcome
import com.aniob.core.narration.StepNarration
import com.aniob.core.exec.ExecOutcome
import com.aniob.core.exec.ExecReport
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.AniobReflexResolver
import com.aniob.core.ladder.ResolvedIntentShortcut
import com.aniob.core.ladder.TaskProgress
import com.aniob.core.run.AniobFastLane
import com.aniob.core.run.RunLane
import com.aniob.core.verify.FastVerify
import com.aniob.core.logging.AniobExecutionTracker
import com.aniob.core.memory.AniobHippocampusTracker
import com.aniob.core.policy.AniobWaitForIdle
import com.aniob.core.providers.AniobGenerationResult
import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.providers.AniobStructuredOutput
import com.aniob.core.providers.AniobLocalActionResolver
import com.aniob.core.router.RouteTarget
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskPolicy
import com.aniob.core.safety.InterceptResult
import com.aniob.core.tools.AniobScrollHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.BURST_MAX_OUTPUT_TOKENS
import com.aniob.app.ui.AniobViewModel.Companion.BURST_REPLAN_SETTLE_MS
import com.aniob.app.ui.AniobViewModel.Companion.SESSION_REVEAL_WAIT_MS
import com.aniob.app.ui.AniobViewModel.Companion.SESSION_RELAUNCH_WAIT_MS
import com.aniob.app.ui.AniobViewModel.Companion.BURST_MAX_ZERO_PROGRESS_REPLANS
import com.aniob.app.ui.AniobViewModel.Companion.HYDRATION_BUDGET_MS
import com.aniob.app.ui.AniobViewModel.Companion.START_CAPTURE_FRESH_MS
import com.aniob.app.ui.AniobViewModel.Companion.APP_LAUNCH_VERIFY_MS
import com.aniob.app.ui.AniobViewModel.Companion.INTENT_SETTLE_MS
import com.aniob.app.ui.AniobViewModel.Companion.PRE_OBSERVE_FRESH_MS
import com.aniob.app.ui.AniobViewModel.RememberTransition
import com.aniob.app.ui.AniobViewModel.CompletedLearningSnapshot

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
    var taskContext = com.aniob.core.domain.TaskContext(
        instruction = resolvedGoalPrompt,
        successCriteria = resolvedCriteria,
        grillAnswers = task.grillAnswers,
        taskId = task.id
    )

    AniobAccessibilityService.isTaskActive = true
    val startTime = System.currentTimeMillis()
    var currentStep = 0
    var totalTokens = 0
    var primaryProvider = "INTENT"
    var decisionReason = "Direct intent shortcut"
    var screenReads = 0
    var actionsDispatched = 0
    var escalations = 0
    var finishRejections = 0
    verifiedActions.clear()

    // Prefrontal-cortex condensation: TaskProgress summary (not full history) is
    // threaded through the ladder so the LLM sees a pure-text progress summary.
    var taskProgress: TaskProgress? = null

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
    val fastLaunchTarget = laneShortcut?.targetPackage?.takeIf { lane == RunLane.FAST_LAUNCH && accessibility != null }
    var screenBefore: AniobScreenState? =
        if (lane.isFast) null else accessibility?.captureCurrentScreenState()
    // Set once a fast-lane launch is confirmed in the foreground; the run closes on it.
    var fastLaunchProof: AniobScreenState? = null
    // A reflex is planned once from a package-only observation instead of a tree walk. One
    // shot: anything other than an immediate DirectIntent returns to the full observation path.
    var fastReflexOpen = lane == RunLane.FAST_REFLEX && executionRouter.intentShortcutsEnabled

    fun recordTrajectory(
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

    var settleTransition: (suspend (AniobAction) -> AniobScreenState?)? = null
    val stepPipe = StepPipeline(
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

    val maxSteps = agentLimits.maxStepsPerTask
    // A bitmap taken by the tier-2 verify is reused by the next Reason step instead of being
    // captured twice. The structural identity it belongs to is stored with it, so a bitmap
    // can never leak onto a different screen; cleared once consumed.
    var pendingVerifyScreenshot: Pair<String, String>? = null
    // Verified transitions of this run, persisted only if the task ends successfully.
    val rememberTransitions = mutableListOf<RememberTransition>()
    // Screen on which the last replay changed nothing; the cache is skipped there next time.
    val stalledReplayIdentities = mutableSetOf<String>()
    var finalOutcome: Outcome? = null
    var terminalReason: String = ""
    var stepResult: StepResult? = null
    // An intent shortcut that carried the whole task is a finished run: the criteria re-check
    // below must not turn "Wi-Fi settings opened" into an unverified or failed run.
    var completedByIntent = false
    // The verdict the shortcut was judged by, so a success carries the evidence it was
    // actually checked against instead of being re-evaluated on a later screen.
    var intentVerdict: List<com.aniob.core.domain.CriterionEvaluation>? = null
    // A shortcut this run switched off (an action the device does not have) is restored when
    // the run ends, so one failed dispatch cannot degrade every later task in the batch.
    val shortcutsWereEnabled = executionRouter.intentShortcutsEnabled
    // Model routing for this task: one on-device attempt, then cloud, and cloud stays chosen.
    var cloudEscalationAllowed = false
    val executionLlm = com.aniob.core.ladder.ExecutionLlmGateway { cloudEscalationAllowed }
    // Pre-observe scrolls already made in this run, per screen identity.
    val preObservedScreens = HashMap<String, Int>()
    // Viewport merged after that scroll, handed to the next planning pass.
    var preObservedScreen: AniobScreenState? = null
    // Set once a burst's target vanished mid-burst. The screen in front is dynamic (search
    // results re-indexing, animated lists), so the rest of the run plans one action at a time
    // from a fresh observation instead of trusting a multi-action plan again.
    var burstsSuspended = false
    // The part of the last plan that named elements the current screen did not show yet.
    // Rendered into the next planning request as context, cleared as soon as it is used.
    var deferredPlanHint: String? = null
    // Consecutive plans that could not execute even their first member: the screen is not
    // settling, so the rest of the run plans one action at a time.
    var burstZeroProgressReplans = 0

    val setupDurationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
    app.researchTelemetry.setSetupLatency(setupDurationMs)

    try {
        if (fastLaunchTarget != null && accessibility != null && laneShortcut != null &&
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
        while (fastLaunchProof == null && currentStep < maxSteps && _uiState.value.isRunning) {
            // Honest default for each step: no model, until a branch below really calls one.
            setPhase(AgentPhase.PLANNING, ThinkSource.RULES)
            if (taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                finalOutcome = Outcome.STOPPED
                break
            }
            val wasPaused = pauseRequested
            awaitIfPaused()
            if (!_uiState.value.isRunning || taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                finalOutcome = Outcome.STOPPED
                break
            }

            _uiState.update { it.copy(currentStep = currentStep + 1) }

            // Step 0 already has a capture from the moment the task started; re-reading the
            // same window only costs latency. Applies only while it is still fresh and the
            // pause/takeover path is not involved.
            // A pre-observe scroll already captured the viewport it revealed. Re-reading the
            // window here would pay for the same hierarchy twice and lose the merged view.
            val mergedObservation = preObservedScreen?.takeIf {
                !wasPaused && System.currentTimeMillis() - it.timestamp < PRE_OBSERVE_FRESH_MS
            }
            preObservedScreen = null
            val reuseStartCapture = currentStep == 0 && !wasPaused && screenBefore != null &&
                System.currentTimeMillis() - screenBefore.timestamp < START_CAPTURE_FRESH_MS
            // Reuse the last verified planning snapshot only when the observation policy allows;
            // every dispatch still re-reads/revalidates its target through the shared pipeline.
            val reflexStub = fastReflexOpen && currentStep == 0 && !wasPaused && screenBefore == null &&
                mergedObservation == null
            fastReflexOpen = false
            val captured = if (mergedObservation != null) {
                mergedObservation
            } else if (reflexStub) {
                AniobScreenState(
                    packageName = accessibility?.currentForegroundPackage() ?: "com.aniob.app",
                    observationKind = "fast-lane"
                )
            } else if (reuseStartCapture) {
                // Step 0 already holds a capture from just before the prompt was typed.
                screenBefore!!
            } else if (wasPaused || observationPolicy.shouldObserve() || screenBefore == null) {
                screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                val fresh = accessibility?.captureCurrentScreenState() ?: AniobScreenState(
                    packageName = "com.aniob.app",
                    treeHash = "mock_hash_${currentStep}",
                    nodes = listOf(
                        AniobNode(id = 1, className = "TextView", text = "Settings", isClickable = true, bounds = AniobRect(50, 100, 300, 180)),
                        AniobNode(id = 2, className = "Button", text = "Search", isClickable = true, bounds = AniobRect(50, 200, 300, 280))
                    )
                )
                if (wasPaused && screenBefore != null && fresh.treeHash != screenBefore.treeHash) {
                    onTakeoverCompleted()
                }
                fresh
            } else {
                screenBefore
            }

            // Session validation: do not trust a cached app session blindly.
            val foregroundBefore = accessibility?.currentForegroundPackage()
            // A package-only fast-lane observation is not a session screen: nothing to validate.
            val reuseCheck = if (reflexStub) null else appSessionManager.ensureReusableOrInvalidate(
                packageName = captured.packageName,
                liveForegroundPackage = foregroundBefore,
                liveScreenState = captured
            )
            var currentScreen = captured
            val liveService = accessibility
            if (reuseCheck != null && !reuseCheck.valid && liveService != null) {
                if (foregroundBefore == "com.aniob.app") {
                    app.eventLogger.info("AniobViewModel", "Aniob is still foregrounded; minimizing to reveal target app")
                    val revealQuiet = windowQuiet(liveService, requireMovement = false)
                    AniobBackgroundController.currentActivity?.runOnUiThread {
                        AniobBackgroundController.currentActivity?.moveTaskToBack(true)
                    }
                    // Was a fixed 600 ms. Expected effect: another app owns the foreground and
                    // its window has stopped moving.
                    until(SESSION_REVEAL_WAIT_MS) {
                        val quiet = revealQuiet.settled()
                        val front = liveService.currentForegroundPackage()
                        quiet && !front.isNullOrBlank() && front != "com.aniob.app"
                    }
                    currentScreen = liveService.captureCurrentScreenState()
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                } else if (foregroundBefore != null && foregroundBefore != captured.packageName) {
                    app.eventLogger.info(
                        "AniobViewModel",
                        "Session invalid (${reuseCheck.reason}) - cold starting $foregroundBefore"
                    )
                    val relaunchQuiet = windowQuiet(liveService, requireMovement = true)
                    executeActionSync(liveService, AniobAction.OpenApp(packageName = foregroundBefore))
                    // Was a fixed 800 ms. Expected effect: the relaunch produced window events,
                    // they have stopped, and the app is the one in front.
                    until(SESSION_RELAUNCH_WAIT_MS) {
                        val quiet = relaunchQuiet.settled()
                        quiet && liveService.currentForegroundPackage() == foregroundBefore
                    }
                    currentScreen = liveService.captureCurrentScreenState()
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                } else {
                    app.eventLogger.info(
                        "AniobViewModel",
                        "Session tree stale (${reuseCheck.reason}) - refreshing capture"
                    )
                    currentScreen = liveService.captureCurrentScreenState()
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordTreeRead()
                }
            }
            if (!reflexStub) appSessionManager.onSessionScreenUpdated(currentScreen.packageName, currentScreen)

            // Evaluate Execution Ladder with real power state and on-device model status
            val powerState = AniobDeviceTelemetry.getRealState(app)
            val resolvedModelId = modelDownloader.getDefaultModelId()
            val resolvedFileExists = AniobModelFiles.isInstalled(modelDownloader.getModelsDir(), resolvedModelId)
            val effectiveModelId = resolvedModelId.takeIf { resolvedFileExists }
            val isModelFileMissing = !resolvedFileExists

            // A reflex is answered by the intent rung, which sits above every learned rung.
            if (currentStep == 0 && !reflexStub) {
                hydration?.await()
            }

            val userRouting = _uiState.value.autoRouterMode.lowercase()
            val planLadder = {
                executionRouter.planStep(
                    taskPrompt = task.clarifiedGoal,
                    screenState = currentScreen,
                    stepIndex = currentStep,
                    taskSignature = task.rawPrompt.lowercase(),
                    powerState = powerState,
                    installedModelId = effectiveModelId,
                    lastLocalFailCount = localFailCount,
                    isModelFileMissing = isModelFileMissing,
                    previousProgress = taskProgress,
                    userRoutingMode = userRouting,
                    lastObservedResult = if (currentStep > 0) "Step ${currentStep - 1} observed" else null,
                    taskStickyTarget = taskStickyTarget
                )
            }
            var ladderResult = planLadder()
            if (reflexStub && ladderResult !is AniobExecutionRouter.ExecutionPlanResult.DirectIntent) {
                // The router did not answer with the reflex (already dispatched for this task,
                // shortcuts off). Nothing below may plan on a package-only observation: take
                // the real capture the fast lane skipped and plan this step again.
                awaitAppBackgrounded(accessibility)
                screenBefore = accessibility?.captureCurrentScreenState()
                if (screenBefore != null) continue
                // No accessibility service at all: the standard path's own placeholder applies.
                screenBefore = AniobScreenState(packageName = "com.aniob.app", treeHash = "mock_hash_$currentStep")
                continue
            }
            // A replay that left the screen exactly as it found it must not be replayed again
            // on that same screen: the cache would keep dispatching the same action. Re-plan
            // once with the cache off so the model gets the step (the documented fallback).
            if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.RememberReplay &&
                FastVerify.screenIdentity(currentScreen) in stalledReplayIdentities
            ) {
                app.eventLogger.info("AniobViewModel", "Remember replay made no progress; falling back to the model")
                app.researchTelemetry.recordReplayDiverged()
                executionRouter.rememberReplayEnabled = false
                ladderResult = planLadder()
                executionRouter.rememberReplayEnabled =
                    researchMode.rememberReplay
            }
            if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch) {
                taskProgress = ladderResult.progress ?: taskProgress
            }

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
                                continue
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
                                    break
                                }
                                verdict.isNotEmpty() -> {
                                    app.eventLogger.warn(
                                        "AniobViewModel",
                                        "Intent ${shortcut.action} landed but the criteria are not met; continuing"
                                    )
                                    app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Criteria not met")
                                    currentStep++
                                    continue
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
                                    break
                                }
                                else -> {
                                    // Nothing machine-checkable and not a finished reflex (an editor
                                    // that was only opened): honest, and never a claimed success.
                                    completedByIntent = true
                                    finalOutcome = Outcome.UNVERIFIED
                                    terminalReason = shortcut.label?.let { "Dispatched $it" }
                                        ?: "System intent dispatched without a checkable criterion"
                                    break
                                }
                            }
                        }
                        app.eventLogger.warn(
                            "AniobViewModel",
                            "System intent ${shortcut.action} could not be dispatched; planning without shortcuts"
                        )
                        app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Dispatch failed")
                        executionRouter.intentShortcutsEnabled = false
                        continue
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
                            return
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
                continue
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
                break
            } else if (res.terminalState == StepResult.TerminalState.FAIL) {
                if (targetMissCountForStep != null) {
                    if (targetMissCountForStep >= 2) {
                        requestTargetClarification(
                            task,
                            res.action,
                            res.resolvedNode ?: com.aniob.core.execution.AniobActionExecutor
                                .resolveTarget(res.action, currentScreen)?.node
                        )
                        return
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
                    continue
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
                    return
                }
                finalOutcome = Outcome.FAILED
                terminalReason = (res.action as? AniobAction.Fail)?.reason
                    ?: res.verification?.reason
                    ?: "Step failed or rejected"
                break
            }

            screenBefore = res.nextCtx.lastScreenState ?: currentScreen
            currentStep++
        }
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
        return
    }

    // A confirmed SUCCESS was checked against the screen the pipeline verified. A fresh capture
    // here is the user's *next* screen (or Aniob's own window) and used to flip good runs to
    // "not done". Every other outcome still reports against the live screen.
    // A fast-lane close was judged on evidence that is not a tree (the foreground package, or
    // the dispatch itself); walking the tree now would only delay the reported completion.
    val fastLaneFinalScreen: AniobScreenState? = fastLaunchProof ?: if (
        lane == RunLane.FAST_REFLEX && completedByIntent && finalOutcome == Outcome.SUCCESS
    ) {
        screenBefore ?: AniobScreenState(
            packageName = accessibility?.currentForegroundPackage() ?: "com.aniob.app",
            observationKind = "fast-lane"
        )
    } else {
        null
    }
    val finalScreen = fastLaneFinalScreen ?: if (stepResult?.terminalState == StepResult.TerminalState.SUCCESS) {
        stepResult.nextCtx.lastScreenState ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
    } else {
        accessibility?.captureCurrentScreenState() ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
    }
    // A run closed by a shortcut keeps the verdict it was actually judged by. Otherwise the
    // common path re-checks the final screen; a completion with nothing checkable has nothing
    // to re-check at all.
    // Device evidence is merged into EVERY closing path (intent, pipeline, model Finish).
    // v1 only wired it into the intent branch, leaving the common ladder path unguarded.
    setPhase(AgentPhase.VERIFYING)
    val finalDeviceState = if (resolvedCriteria?.hasDeviceChecks == true) probeDeviceState(resolvedCriteria) else DeviceStateSnapshot.UNKNOWN
    val deviceEvaluations = evalDeviceAssertions(resolvedCriteria, finalDeviceState)
    val criteriaEvaluations = (
        when {
            intentVerdict != null -> intentVerdict!!
            completedByIntent -> emptyList()
            else -> resolvedCriteria?.evaluateCriteria(finalScreen, currentStep, finalDeviceState) ?: emptyList()
        } + deviceEvaluations
    ).distinctBy { it.description }
    val evidenceItems = criteriaEvaluations.map { ev ->
        EvidenceItem(
            label = ev.description,
            met = ev.state == com.aniob.core.domain.CriterionState.PASSED,
            detail = ev.detail,
            state = ev.state
        )
    }

    if (finalOutcome == null) {
        finalOutcome = when {
            !_uiState.value.isRunning -> Outcome.STOPPED
            currentStep >= maxSteps -> {
                terminalReason = "Maximum steps ($maxSteps) reached"
                Outcome.FAILED
            }
            stepResult?.terminalState == StepResult.TerminalState.SUCCESS ->
                if (resolvedCriteria?.hasObservableCheck == true) Outcome.SUCCESS else Outcome.UNVERIFIED
            stepResult?.terminalState == StepResult.TerminalState.FAIL -> Outcome.FAILED
            else -> Outcome.FAILED
        }
    } else if (!completedByIntent && finalOutcome == Outcome.SUCCESS &&
        resolvedCriteria?.hasObservableCheck != true
    ) {
        // Confirmed with no machine-checkable criterion: honest "unverified", never a failure.
        finalOutcome = Outcome.UNVERIFIED
    }

    // FINAL GATE (BUG-01/06): SUCCESS may not survive an unmet or unprobed criterion — even
    // when a pipeline step or a model Finish claimed it. This is the single place every run
    // passes through, so no future code path can quietly opt out of device evidence.
    if (finalOutcome == Outcome.SUCCESS) {
        val unsupported = criteriaEvaluations.filter {
            it.state != com.aniob.core.domain.CriterionState.PASSED && it.description.startsWith("Device state:")
        }
        if (unsupported.isNotEmpty()) {
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "Completion not supported by device evidence: " +
                unsupported.joinToString("; ") { "${it.description} — ${it.detail}" }
        }
    }
    if (finalOutcome == Outcome.SUCCESS &&
        resolvedCriteria?.requireInstallConfirmation == true &&
        resolvedCriteria.mustBeInstalled.isEmpty()
    ) {
        // "install <unknown app>" only reached a store SEARCH. There is no package id to query,
        // so the run cannot prove an install happened — honest UNVERIFIED, never SUCCESS.
        finalOutcome = Outcome.UNVERIFIED
        terminalReason = "Store search opened, but the install target is unresolved; " +
            "PackageManager cannot confirm completion."
    }

    val totalDuration = System.currentTimeMillis() - startTime
    val closedOutcome = finalOutcome ?: Outcome.FAILED
    val isSuccess = (closedOutcome == Outcome.SUCCESS)
    val outcomeCopy = com.aniob.app.outcome.AniobOutcomeCopy.forTask(
        outcome = closedOutcome ?: Outcome.FAILED,
        reason = terminalReason.ifBlank { "Unmet criteria" },
        taskLabel = task.rawPrompt,
        completedChecks = evidenceItems.filter { it.met }.map { it.label },
        outstandingChecks = evidenceItems.filter { !it.met }.map { it.label }
    )
    val resultSummary = outcomeCopy.message

    // Research metric closure: latency comes from elapsedRealtime inside the manager, LLM calls
    // and steps were counted as they happened, and success is the deterministic verdict above.
    // endTask appends the row, rewrites the summary table and logs RESEARCH_METRICS.
    val researchFailureReason = when {
        isSuccess -> ""
        closedOutcome == Outcome.STOPPED -> "stopped"
        closedOutcome == Outcome.INTERRUPTED -> "interrupted"
        closedOutcome == Outcome.UNVERIFIED -> terminalReason.ifBlank { "unverified" }
        else -> terminalReason.ifBlank { "unmet_criteria" }
    }
    val episodeSnapshot = hippocampusTracker.getSteps()
    val actionsSnapshot = verifiedActions.toList()
    val transitionsSnapshot = rememberTransitions.toList()
    val stateTraceSnapshot = episodeSnapshot.map { it.screenHash }
    val stepCountSnapshot = currentStep + 1
    val routingReasonSnapshot = _uiState.value.lastRoutingReason
    val skipTaskLearning = stepResult?.nextCtx?.hasFastSkippedSteps == true
    lastCompletedLearningSnapshot = CompletedLearningSnapshot(task.id,
        task.clarifiedGoal.ifBlank { task.rawPrompt }, resolvedCriteria, finalScreen,
        episodeSnapshot.map { it.action }, transitionsSnapshot, researchMode, episodeSnapshot)
    // Clear only this episode, synchronously. Background consolidation uses the captured list.
    hippocampusTracker.onFailure()
    var researchRun: com.aniob.app.research.ResearchRun? = null
    var researchLine: String? = null
    var researchSummaryRow: com.aniob.app.research.ResearchSummaryRow? = null

    suspend fun bookkeeping() = kotlinx.coroutines.supervisorScope {
        val research = async {
            runCatching {
                if (researchMode.immediateCompletion) app.researchTelemetry.endTask(
                    com.aniob.app.research.ResearchOutcome.valueOf(closedOutcome.name), researchFailureReason, runId = task.id)
                else null
            }.getOrNull()
        }
        launch { runCatching {
            if (isSuccess) {
                // The cache file load no longer blocks dispatch, so it is awaited here, after
                // the run: recording into (and saving) the not-yet-loaded empty cache would
                // overwrite the persisted one.
                if (researchMode.rememberReplay) rememberCacheLoadJob?.join()
                persistRememberTransitions(researchMode,
                    task.clarifiedGoal.ifBlank { task.rawPrompt }, transitionsSnapshot)
            }
        } }
        launch { runCatching {
            app.metricsCollector.recordSession(taskId = task.id, prompt = task.rawPrompt, isSuccess = isSuccess,
                steps = stepCountSnapshot, durationMs = totalDuration, tokensUsed = totalTokens,
                providerUsed = primaryProvider, decisionReason = decisionReason)
        } }
        launch { try { runCatching {
            if (isSuccess && !completedByIntent && !skipTaskLearning &&
                (researchMode.immediateCompletion || stepResult?.terminalState != StepResult.TerminalState.SUCCESS)) {
                learningPipeline.onTaskSuccess(TaskContext(instruction = task.rawPrompt,
                    successCriteria = resolvedCriteria, taskId = task.id, lastScreenState = finalScreen), actionsSnapshot)
            }
        } } finally { learningPipeline.onTaskEnd(task.id) } }
        launch { runCatching {
            if (isSuccess && researchMode.skillsEnabled) {
                val name = hippocampusTracker.consolidateEpisode(task.id,
                    task.clarifiedGoal.ifBlank { task.rawPrompt }, episodeSnapshot, elapsedMs = totalDuration)
                if (name != null) {
                    val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
                    semanticSkillMatcher.loadAndIndexSingleSkill(File(dir, "$name.yaml"))
                }
            }
        } }
        launch { runCatching {
            app.eventLogger.recordSessionCounters(screenReads = screenReads, actions = actionsDispatched,
                escalations = escalations, elapsedMs = totalDuration, stateTrace = stateTraceSnapshot,
                providerDecisionReason = decisionReason, lastRoutingReason = routingReasonSnapshot)
        } }
        researchRun = research.await()
        researchLine = researchRun?.let { app.researchTelemetry.hudLine(it) }
        researchSummaryRow = researchRun?.let { app.researchTelemetry.summaryFor(it.mode) }
        if (_uiState.value.lastSealedTaskId == task.id) _uiState.update {
            it.copy(researchLastRun = researchRun ?: it.researchLastRun,
                researchStatusLine = researchLine ?: it.researchStatusLine,
                researchSummary = researchSummaryRow ?: it.researchSummary)
        }
    }

    val execOutcome = when (closedOutcome) {
        Outcome.SUCCESS -> ExecOutcome.SUCCESS
        Outcome.STOPPED -> ExecOutcome.STOPPED
        Outcome.INTERRUPTED -> ExecOutcome.INTERRUPTED
        Outcome.UNVERIFIED -> ExecOutcome.UNVERIFIED
        Outcome.FAILED -> ExecOutcome.FAILED_VERIFICATION
    }
    fun recordExecSnapshot() {
        ExecReport(
            outcome = execOutcome,
            reason = resultSummary,
            screenReads = screenReads,
            actions = actionsDispatched,
            escalations = escalations,
            elapsedMs = totalDuration,
            stateTrace = stateTraceSnapshot,
            providerUsed = primaryProvider,
            decisionReason = decisionReason
        ).toMetricSnapshot(taskId = task.id, stepIndex = currentStep + 1).also { snapshot ->
            app.eventLogger.info(
                "AniobMetrics",
                "SNAPSHOT{task=${snapshot.taskId},provider=${snapshot.provider}," +
                    "screenReads=${snapshot.screenReads},actions=${snapshot.actions}," +
                    "escalations=${snapshot.escalations},routing=${snapshot.lastRoutingReason}}"
            )
        }

    }

    fun publishCompletion() {
        val summaryCard = buildSummary(
            prompt = task.rawPrompt,
            outcome = closedOutcome,
            steps = currentStep + 1,
            durationMs = totalDuration,
            provider = primaryProvider,
            evidence = evidenceItems,
            reason = terminalReason.ifBlank { null }
        )
        postSummaryCard(summaryCard)
        // Step 1 invariant: SUCCESS survives only with observer-made evidence; otherwise UNVERIFIED.
        recordSealedOutcome(
            task.id,
            AniobOutcomeBridge.seal(
                outcome = closedOutcome ?: Outcome.FAILED,
                reason = terminalReason.ifBlank { resultSummary },
                evaluations = criteriaEvaluations
            )
        )
        setPhase(AgentPhase.IDLE)
        clearRunningMarker()

        AniobBackgroundController.onTaskFinished(
            app,
            resultSummary,
            isSuccess = isSuccess,
            unverified = closedOutcome == Outcome.UNVERIFIED,
            openChat = closedOutcome == Outcome.FAILED || closedOutcome == Outcome.UNVERIFIED,
            wasStopped = closedOutcome == Outcome.STOPPED,
            resultTitle = outcomeCopy.title
        )

        val chatBadge = when (closedOutcome) {
            Outcome.SUCCESS -> "Success"
            Outcome.UNVERIFIED -> "Unverified"
            Outcome.STOPPED -> "Stopped"
            Outcome.INTERRUPTED -> "Interrupted"
            Outcome.FAILED -> "Failed"
        }
        val assistantMessage = ChatMessage(
            id = "msg_asst_${java.util.UUID.randomUUID()}",
            role = "assistant",
            content = outcomeCopy.message,
            badge = chatBadge,
            stepIndex = currentStep + 1
        )

        if (researchMode.immediateCompletion) app.researchTelemetry.prepareBackgroundClosure(task.id)
        updateToIdle {
            it.copy(
                statusMessage = resultSummary,
                lastProviderUsed = primaryProvider,
                chatMessages = it.chatMessages + assistantMessage,
                researchStatusLine = researchLine ?: it.researchStatusLine,
                researchLastRun = researchRun ?: it.researchLastRun,
                researchSummary = researchSummaryRow ?: it.researchSummary
            )
        }
        app.researchTelemetry.recordCompletionMessageShown(task.id)
    }
    com.aniob.core.execution.CompletionPublication.complete(
        immediate = researchMode.immediateCompletion,
        publish = ::publishCompletion,
        bookkeeping = {
            if (researchMode.immediateCompletion) {
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.supervisorScope {
                        launch { runCatching { recordExecSnapshot() } }
                        bookkeeping()
                    }
                }
            } else {
                bookkeeping()
                recordExecSnapshot()
            }
        }
    )
    if (!researchMode.immediateCompletion) {
        val row = app.researchTelemetry.endTask(com.aniob.app.research.ResearchOutcome.valueOf(closedOutcome.name),
            researchFailureReason, runId = task.id)
        if (row != null) {
            val line = app.researchTelemetry.hudLine(row)
            val summary = app.researchTelemetry.summaryFor(row.mode)
            _uiState.update { it.copy(researchLastRun = row, researchStatusLine = line, researchSummary = summary) }
        }
    }

}
