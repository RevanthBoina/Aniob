package com.aniob.app.ui

import com.aniob.core.domain.*
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.research.FeatureSelection
import com.aniob.app.research.ExecutionFeature
import com.aniob.app.research.AblationMode
import com.aniob.app.research.StudyPass
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.model.AniobExecutionEvents
import com.aniob.app.ui.model.MomentChip
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.app.ui.model.StepEvent
import com.aniob.core.narration.StepNarration
import com.aniob.core.narration.AgentPhase
import com.aniob.core.safety.AniobSafetyInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.PAUSE_POLL_MS
import com.aniob.app.ui.AniobViewModel.Companion.OBSERVATION_BURST_LIMIT
import com.aniob.app.ui.AniobViewModel.Companion.OBSERVATION_BURST_LIMIT_SINGLE_STEP

// =========================================================================================
// UX-2 Smart Composer
// =========================================================================================

/** Queues a follow-up while a task runs (depth 1; single-owner queue). */
fun AniobViewModel.queueFollowUp(prompt: String) {
    val trimmed = prompt.trim()
    if (trimmed.isBlank()) return
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        .edit().putString("queued_prompt", trimmed).apply()
    _uiState.update { it.copy(queuedPrompt = trimmed) }
}

fun AniobViewModel.cancelQueuedFollowUp() {
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        .edit().remove("queued_prompt").apply()
    _uiState.update { it.copy(queuedPrompt = null) }
}

fun AniobViewModel.startQueuedTask() {
    val nextPrompt = _uiState.value.queuedPrompt ?: return
    cancelQueuedFollowUp()
    submitTask(nextPrompt)
}

/** Remember a grill answer in the durable vault (previously in-memory only). */
fun AniobViewModel.rememberGrillAnswer(key: String, value: String) {
    persistentKnowledgeStore.put(vaultKey(key), value)
    _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys + key) }
}

fun AniobViewModel.forgetGrillAnswer(key: String) {
    persistentKnowledgeStore.remove(vaultKey(key))
    _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys - key) }
}

fun AniobViewModel.grillRemembered(key: String): String? = sharedKnowledgeStore.get(vaultKey(key))

internal fun AniobViewModel.vaultKey(questionId: String) = "grill.$questionId"

/** UX-5: the "What Aniob remembers" list, surfaced from the Trust Center / Skills screen. */
fun AniobViewModel.vaultEntries(): Map<String, String> =
    sharedKnowledgeStore.getAll().filterKeys { it.startsWith("grill.") }

fun AniobViewModel.forgetVaultEntry(key: String) {
    persistentKnowledgeStore.remove(key)
}

/** Plan-7.3: Support editing remembered preferences in the Trust Center. */
fun AniobViewModel.editVaultEntry(key: String, newAnswer: String) {
    sharedKnowledgeStore.put(key, newAnswer)
    persistentKnowledgeStore.put(key, newAnswer)
}

internal fun AniobViewModel.postMomentChip(label: String, kind: MomentChip.Kind) {
    val chip = MomentChip(id = "chip_${System.currentTimeMillis()}", label = label, kind = kind)
    _uiState.update { it.copy(momentChips = it.momentChips + chip) }
}

fun AniobViewModel.clearMomentChips() {
    _uiState.update { it.copy(momentChips = emptyList()) }
}

// =========================================================================================
// UX-3 Glass Cockpit — narration, pause, takeover, stop budget
// =========================================================================================

/** Publishes a step event to the in-app ticker and tracker; progress is mirrored to the notification. */
internal fun AniobViewModel.publishStep(
    stepIndex: Int,
    action: AniobAction,
    provider: String,
    latencyMs: Long,
    verified: Boolean,
    failureReason: String? = null,
    node: AniobNode? = null
): StepEvent {
    val narration = StepNarration.narrate(action, node)
    advancePhase(AgentPhase.ACTING, action.thought.orEmpty())
    val event = StepEvent(
        stepIndex = stepIndex,
        narration = narration,
        status = when {
            failureReason != null -> StepEvent.Status.FAILED
            verified -> StepEvent.Status.OK
            else -> StepEvent.Status.SKIPPED
        },
        provider = provider,
        latencyMs = latencyMs,
        failureReason = failureReason,
        technical = "${action.toolName} · ${provider.lowercase()}"
    )
    AniobExecutionEvents.publish(event)
    AniobBackgroundController.onStepNarrated(stepIndex, narration)
    _uiState.update {
        it.copy(stepEvents = it.stepEvents + event, lastNarration = narration)
    }
    return event
}

/** UX-3: cooperative pause. Resume re-captures a fresh screen; never resumes on a stale tree. */
fun AniobViewModel.togglePause() {
    pauseRequested = !pauseRequested
    AniobBackgroundController.onTaskPaused(pauseRequested)
    _uiState.update {
        it.copy(
            isPaused = pauseRequested,
            statusMessage = if (pauseRequested) "Paused — do this step yourself, then Resume"
            else "Resuming with a fresh screen…"
        )
    }
}

/** Awaited at the loop head and inside settle waits so pause is honoured promptly. */
internal suspend fun AniobViewModel.awaitIfPaused() {
    while (pauseRequested && _uiState.value.isRunning) {
        delay(PAUSE_POLL_MS)
    }
}

/**
 * Takeover Bridge (UX-3 §4): manual step performed by user during pause.
 * Resume cleanly without claiming an unconsented recording session has started.
 */
fun AniobViewModel.onTakeoverCompleted() {
    _uiState.update {
        it.copy(
            statusMessage = "Manual step acknowledged. Ready to resume."
        )
    }
}

// =========================================================================================
// UX-4 Trust & Safety
// =========================================================================================

fun AniobViewModel.setSafetyLevel(level: SafetyLevel) {
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        .edit().putInt("safety_level", level.ordinal).apply()
    applyAskLevel(level)
    _uiState.update { it.copy(safetyLevel = level) }
}

internal fun AniobViewModel.applyAskLevel(level: SafetyLevel) {
    AniobSafetyInterceptor.askLevel = when (level) {
        SafetyLevel.OFF -> com.aniob.core.safety.AskLevel.OFF
        SafetyLevel.STANDARD -> com.aniob.core.safety.AskLevel.BALANCED
        SafetyLevel.STRICT -> com.aniob.core.safety.AskLevel.CAREFUL
    }
}

fun AniobViewModel.isFastPathEnabled(): Boolean = _uiState.value.fastPathEnabled

/** REAL switch: OFF gates the replay lookup — proven by a zero-replay-hits test. */
fun AniobViewModel.setFastPathEnabled(enabled: Boolean) {
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("fastpath_enabled", enabled).apply()
    setFeatureSelection(_uiState.value.featureSelection.with(ExecutionFeature.REMEMBER, enabled))
    // Re-derive the effective replay flag: a mode without remember-replay keeps it off even
    // when the user switch is on.
    if (!_uiState.value.isRunning) applyFeatureSelection(_uiState.value.featureSelection)
}

// =========================================================================================
// Research: ablation selection, metrics HUD and manual verdict override
// =========================================================================================

/**
 * Switches the experimental condition for the next task.
 *
 * Persisted in the telemetry manager's prefs (not `aniob_prefs`) so the CSV and the mode that
 * produced it cannot drift apart. The change is applied to the real switches immediately.
 */
fun AniobViewModel.setFeatureSelection(selection: FeatureSelection) {
    app.researchTelemetry.featureSelection = selection
    _uiState.update { it.copy(featureSelection = selection, ablationMode = selection.mode,
        fastPathEnabled = selection.rememberReplay, researchStatusLine = null) }
    if (!_uiState.value.isRunning) applyFeatureSelection(selection)
}

fun AniobViewModel.setAblationMode(mode: AblationMode) {
    if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
    app.researchTelemetry.mode = mode
    setFeatureSelection(FeatureSelection.fromLegacy(mode))
    if (!_uiState.value.isRunning) applyAblationMode(mode)
    _uiState.update { it.copy(ablationMode = mode, researchStatusLine = null) }
    // The summary lives on disk; refresh it off the main thread.
    viewModelScope.launch(Dispatchers.IO) {
        val summary = app.researchTelemetry.summaryFor(mode)
        _uiState.update { it.copy(researchSummary = summary) }
    }
}

/**
 * Switches the study pass (warm-up vs measured) for the next task.
 */
fun AniobViewModel.setStudyPass(pass: StudyPass) {
    if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
    app.researchTelemetry.studyPass = pass
    _uiState.update { it.copy(studyPass = pass) }
}

/**
 * Seeds `remember_cache.json` with the first hop of every benchmark task, from the settings
 * screen, so a warm `FULL_HUMAN_SPEED` batch can be measured without a cold run first.
 *
 * Only the app each task starts in is seeded (see `RememberPresets`); every later step is still
 * learned from a verified run, and a replayed hop is observed like any other step.
 */
fun AniobViewModel.seedRememberPresets() {
    if (!com.aniob.app.AniobBuild.isDeveloperBuild(app) || _uiState.value.isRunning) return
    viewModelScope.launch(Dispatchers.IO) {
        val cache = app.rememberCacheFile.load()
        val isInstalled: (String) -> Boolean = { pkg ->
            runCatching { app.packageManager.getLaunchIntentForPackage(pkg) != null }
                .getOrDefault(false)
        }
        val written = com.aniob.core.memory.RememberPresets.seed(cache, isInstalled)
        app.rememberCacheFile.save(cache)
        rememberCache = cache
        app.eventLogger.info(
            "AniobViewModel",
            "Remember presets seeded: $written/${com.aniob.core.memory.RememberPresets.studyBattery.size} tasks -> ${app.rememberCacheFile.path()}"
        )
        _uiState.update {
            it.copy(
                researchStatusLine = "Remember cache seeded: $written of " +
                    "${com.aniob.core.memory.RememberPresets.studyBattery.size} benchmark first hops"
            )
        }
    }
}

/**
 * Applies a mode to the mechanisms that exist today. Called on init, on every mode change and
 * at the start of each task, so a stale switch can never survive into a measured run.
 */
internal fun AniobViewModel.applyAblationMode(mode: AblationMode) = applyFeatureSelection(FeatureSelection.fromLegacy(mode))

internal fun AniobViewModel.applyFeatureSelection(effective: FeatureSelection) {
    executionRouter.intentShortcutsEnabled = effective.intentShortcuts
    executionRouter.skillsEnabled = effective.skillsEnabled
    executionRouter.fastPathEnabled = effective.rememberReplay
    executionRouter.rememberReplayEnabled = effective.rememberReplay
    observationPolicy.setBurstLimit(if (effective.actionBursts) OBSERVATION_BURST_LIMIT else OBSERVATION_BURST_LIMIT_SINGLE_STEP)
    AniobAccessibilityService.setFastObservation(effective.fastObservation)
    AniobAccessibilityService.setResearchFastDeltaVerify(effective.fastDeltaVerify)
}

/**
 * Manual verdict for the last finished run, used when the deterministic verifier cannot judge
 * an outcome. Rewrites the last CSV row (flagged as manual) and refreshes the HUD.
 */
fun AniobViewModel.overrideResearchOutcome(success: Boolean) {
    if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
    viewModelScope.launch(Dispatchers.IO) {
        val updated = app.researchTelemetry.overrideLastOutcome(success)
        if (updated != null) {
            val line = app.researchTelemetry.hudLine(updated)
            _uiState.update { current ->
                current.copy(
                    researchLastRun = updated,
                    researchStatusLine = line,
                    researchSummary = app.researchTelemetry.summaryFor(updated.mode)
                )
            }
        }
    }
}
