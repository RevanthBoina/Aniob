package com.aniob.app.ui

import com.aniob.core.providers.AniobModelFiles
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.verify.FastVerify
import com.aniob.core.tools.DevicePowerState
import kotlinx.coroutines.flow.update
import com.aniob.app.ui.AniobViewModel.Companion.SESSION_REVEAL_WAIT_MS
import com.aniob.app.ui.AniobViewModel.Companion.SESSION_RELAUNCH_WAIT_MS
import com.aniob.app.ui.AniobViewModel.Companion.START_CAPTURE_FRESH_MS
import com.aniob.app.ui.AniobViewModel.Companion.PRE_OBSERVE_FRESH_MS

/** Values one loop iteration hands from observation to planning and dispatch. */
internal class ObservedStep(
    val currentScreen: AniobScreenState,
    val powerState: DevicePowerState,
    val userRouting: String,
    val ladderResult: AniobExecutionRouter.ExecutionPlanResult
)

/**
 * Observe: first third of one step of [executeTaskPipeline]. Reads the screen, validates the app
 * session and asks the execution ladder what to do. The statements are the loop body unchanged;
 * `break` and `continue` are reported to the loop as [PipelineFlow] values.
 */
internal suspend fun AniobViewModel.pipelineObserve(pipe: PipelineRun): PipelineFlow<ObservedStep> {
    return with(pipe) {
        // Read-only view for this phase; the writes below go to the run state.
        val screenBefore = pipe.screenBefore
        // Honest default for each step: no model, until a branch below really calls one.
        setPhase(AgentPhase.PLANNING, ThinkSource.RULES)
        if (taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
            finalOutcome = Outcome.STOPPED
            return PipelineFlow.Break
        }
        val wasPaused = pauseRequested
        awaitIfPaused()
        if (!_uiState.value.isRunning || taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
            finalOutcome = Outcome.STOPPED
            return PipelineFlow.Break
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
            pipe.screenBefore = accessibility?.captureCurrentScreenState()
            if (pipe.screenBefore != null) return PipelineFlow.Continue
            // No accessibility service at all: the standard path's own placeholder applies.
            pipe.screenBefore = AniobScreenState(packageName = "com.aniob.app", treeHash = "mock_hash_$currentStep")
            return PipelineFlow.Continue
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

        PipelineFlow.Next(ObservedStep(currentScreen, powerState, userRouting, ladderResult))
    }
}
