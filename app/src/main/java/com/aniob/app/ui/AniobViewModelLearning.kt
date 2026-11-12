package com.aniob.app.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.research.FeatureSelection
import com.aniob.app.research.ResearchRun
import com.aniob.app.device.AniobCapabilityProbe
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.app.ui.model.Outcome
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.app.ui.model.TaskSummary
import com.aniob.core.grillme.AniobPromptGrillMeClient
import com.aniob.core.feasibility.ActionGate
import com.aniob.core.feasibility.CapabilityEnvelope
import com.aniob.core.feasibility.PlanStep
import com.aniob.core.feasibility.Postcondition
import com.aniob.core.feasibility.Privilege
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.PhaseSnapshot
import com.aniob.core.narration.ThinkSource
import com.aniob.core.outcome.RunOutcome
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.outcome.UserDecision
import com.aniob.core.run.AniobFastLane
import com.aniob.core.run.ConditionWait
import com.aniob.core.memory.RememberAction
import com.aniob.core.memory.RememberCache
import com.aniob.core.verify.FastVerify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.Companion.BACKGROUND_WAIT_MAX_MS
import com.aniob.app.ui.AniobViewModel.Companion.CONFIRMATION_HISTORY_LIMIT
import com.aniob.app.ui.AniobViewModel.RememberTransition

/**
 * Stores this run's verified transitions, keyed by goal keywords, package and the screen the
 * action was planned on. [mode] is the condition the run started under, so a settings change
 * mid-task cannot seed a cache the run never read.
 */
internal fun AniobViewModel.persistRememberTransitions(
    mode: FeatureSelection,
    goal: String,
    transitions: List<RememberTransition>
) {
    if (transitions.isEmpty()) return
    if (!mode.rememberReplay) return

    // Drop undone transitions (e.g. BACK key) to keep minimal path (F-1)
    val pruned = mutableListOf<RememberTransition>()
    for (t in transitions) {
        if (t.action is AniobAction.PressKey && t.action.key == com.aniob.core.domain.KeyType.BACK && pruned.isNotEmpty()) {
            pruned.removeAt(pruned.lastIndex)
        } else {
            pruned.add(t)
        }
    }

    var recorded = 0
    for (transition in pruned) {
        if (transition.screen.nodes.any { it.isPassword || it.className.contains("Password", ignoreCase = true) }) {
            continue
        }
        val descriptor = RememberAction.fromAction(
            action = transition.action,
            node = transition.node,
            screen = transition.screen
        ) ?: continue
        val nextHash = transition.nextScreen?.let { FastVerify.screenIdentity(it) }.orEmpty()
        val ok = rememberCache.record(
            goal = goal,
            packageName = transition.screen.packageName,
            preActionScreenHash = RememberCache.navigationScreenKey(goal, transition.screen),
            actions = listOf(descriptor),
            expectedNextHash = nextHash
        )
        if (ok) recorded++
    }
    if (recorded == 0) return
    val snapshot = rememberCache
    viewModelScope.launch(Dispatchers.IO) {
        app.rememberCacheFile.save(snapshot)
    }
}

/**
 * Waits until the chat window is really in the background, by watching the foreground package
 * instead of sleeping a fixed 500 ms. Returns as soon as another app is in front; the cap keeps
 * a stubborn window manager from stalling the run.
 */
internal suspend fun AniobViewModel.awaitAppBackgrounded(service: AniobAccessibilityService?) {
    if (service == null) {
        delay(120)
        return
    }
    val deadline = System.currentTimeMillis() + BACKGROUND_WAIT_MAX_MS
    while (System.currentTimeMillis() < deadline) {
        val foreground = runCatching { service.currentForegroundPackage() }.getOrNull()
        if (!foreground.isNullOrBlank() && foreground != app.packageName) return
        delay(40)
    }
}

/** Wait for a condition with a deadline instead of waiting on a clock. */
internal suspend fun AniobViewModel.until(
    deadlineMs: Long,
    stepMs: Long = ConditionWait.DEFAULT_STEP_MS,
    condition: () -> Boolean
): Boolean = ConditionWait.until(deadlineMs = deadlineMs, stepMs = stepMs, condition = condition)

/** "The window stopped moving", read from the service's window-event counter. Create before the action. */
internal fun AniobViewModel.windowQuiet(service: AniobAccessibilityService, requireMovement: Boolean) =
    ConditionWait.WindowQuiet(requireMovement = requireMovement) { service.windowEventSequence() }

/** Event-driven launch proof: stops the instant the target owns the foreground. */
internal suspend fun AniobViewModel.confirmLaunched(
    target: String,
    deadlineMs: Long = AniobFastLane.LAUNCH_CONFIRM_DEADLINE_MS
): Boolean = AniobFastLane.confirmLaunched(target = target, deadlineMs = deadlineMs) {
    AniobAccessibilityService.instance?.currentForegroundPackage()
}

/**
 * Warms DNS/TLS/HTTP-2 for the gateway. Never throws and never counts as a model call: the
 * request is a plain `GET /models`, so the first real action reuses the open connection.
 */
internal suspend fun AniobViewModel.prewarmGateway(sessionId: String? = null) {
    if (!isCloudConfigured()) return
    // Only the gateway has a prewarm; opening a connection to the account service is not free.
    if (cloudSource() != com.aniob.core.auth.AniobCloudSource.OMNIROUTE) return
    // With a session id this warms the very instance the task's steps will use.
    runCatching { getOmniRouteProvider(sessionId).prewarm() }
}

/**
 * One bitmap for the tier-2 verify or for a visual Reason step. Returns null when the service
 * is gone or the capture fails; callers treat that as "no pixels", never as an error.
 */
internal suspend fun AniobViewModel.captureVerifyScreenshot(): String? {
    val service = AniobAccessibilityService.instance ?: return null
    return try {
        val snap = service.captureScreenshotAsync()
        snap.bitmap?.let { bmp ->
            try {
                app.researchTelemetry.recordBitmapCapture()
                val stream = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                bmp.recycle()
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Publishes a run that the abnormal-exit safety net just closed (Stop, cancellation, crash of a
 * step) so the developer-only research status remains available. Normal completions publish in
 * [updateToIdle]; this is only for runs closed outside that path.
 */
internal fun AniobViewModel.publishResearchRun(run: ResearchRun?) {
    if (run == null) return
    val line = app.researchTelemetry.hudLine(run)
    val summary = app.researchTelemetry.summaryFor(run.mode)
    _uiState.update { current ->
        current.copy(
            researchLastRun = run,
            researchStatusLine = line,
            researchSummary = summary
        )
    }
}

/** Deletes stored research CSVs so the next batch starts clean. */
fun AniobViewModel.clearResearchData() {
    if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
    viewModelScope.launch(Dispatchers.IO) {
        app.researchTelemetry.clearAll()
        _uiState.update {
            it.copy(
                researchLastRun = null,
                researchStatusLine = null,
                researchSummary = null
            )
        }
    }
}

fun AniobViewModel.confirmationHistory(): List<ConfirmationRecord> = _uiState.value.confirmationHistory

internal fun AniobViewModel.recordConfirmation(request: ConfirmRequest, decision: ConfirmDecision) {
    val record = ConfirmationRecord(
        what = request.what,
        risk = request.risk,
        decision = decision,
        at = System.currentTimeMillis()
    )
    _uiState.update {
        it.copy(confirmationHistory = (listOf(record) + it.confirmationHistory).take(CONFIRMATION_HISTORY_LIMIT))
    }
}

/**
 * The ONE gate on a risky dispatch (UX-4 §1). Replaces the old fire-and-forget
 * dialog: this suspends the caller until a real decision exists, so a risky
 * action can never execute while the approval is still on screen.
 */
internal suspend fun AniobViewModel.requestConfirmation(request: ConfirmRequest): ConfirmDecision {
    val currentTask = _uiState.value.activeTask
    val identity = com.aniob.core.workflow.OperationIdentity(
        taskId = request.taskId.ifBlank { currentTask?.id ?: "" },
        actionId = request.id,
        generation = activeGeneration,
        destinationKey = request.identityTarget.ifBlank { request.target },
        approvedPayloadHash = request.payload.takeIf { it.isNotBlank() }?.let {
            com.aniob.app.workflow.WorkflowPayloadStore.sha256(it.toByteArray(Charsets.UTF_8))
        }
    )
    _uiState.update { it.copy(confirmRequest = request) }
    AniobForegroundService.notifyConfirmation(app, request)
    val decision = app.approvalController.requestApproval(request, identity)
    recordConfirmation(request, decision)
    AniobForegroundService.clearConfirmation(app)
    _uiState.update { it.copy(confirmRequest = null) }
    return decision
}

/** Called by the ConfirmSheet / notification action with the user's real choice. */
fun AniobViewModel.resolveConfirmation(decision: ConfirmDecision, taskId: String? = null, reqId: String? = null) {
    app.approvalController.resolveConfirmation(decision, taskId, reqId)
}

/**
 * Maps an interceptor verdict onto the frozen confirm contract.
 * The interceptor decides *whether* to ask; the user decides *whether to proceed*.
 */
internal fun AniobViewModel.buildConfirmRequest(
    what: String,
    why: String,
    risk: ConfirmRequest.Risk,
    details: String,
    taskId: String = _uiState.value.activeTask?.id ?: "",
    payload: String = "",
    target: String = "",
    identityTarget: String = target
) = ConfirmRequest(
    id = "confirm_${java.util.UUID.randomUUID()}",
    title = "Aniob needs your approval",
    what = what,
    why = why,
    risk = risk,
    details = details,
    taskId = taskId,
    payload = payload,
    target = target,
    identityTarget = identityTarget
)

internal fun AniobViewModel.riskFromTier(tier: String, safetyLevel: SafetyLevel): ConfirmRequest.Risk = when {
    tier.equals("HIGH", true) -> ConfirmRequest.Risk.HIGH
    tier.equals("MEDIUM", true) -> ConfirmRequest.Risk.MEDIUM
    else -> ConfirmRequest.Risk.LOW
}

// =========================================================================================
// UX-5 Learning, visible
// =========================================================================================

/** Teach prefill handoff; the full Teach producer lands with the functional track. */
fun AniobViewModel.startTeachFlow(prompt: String?) {
    val seed = prompt?.trim().orEmpty()
    _uiState.update {
        it.copy(
            chatMessages = it.chatMessages + ChatMessage(
                id = "msg_teach_${System.currentTimeMillis()}",
                role = "assistant",
                content = if (seed.isBlank()) {
                    "Tell me the task you want to teach, and I'll record the steps as a skill."
                } else {
                    "Teach mode: I'll record the steps for '$seed' and save it as a skill."
                },
                badge = "Teach",
                provider = "TEACH",
                retryPrompt = seed.ifBlank { null }
            )
        )
    }
}

/** REAL switch: a disabled skill is skipped by the matcher (proven by test). */
fun AniobViewModel.setSkillEnabled(name: String, enabled: Boolean) {
    val disabled = disabledSkills.toMutableSet()
    if (enabled) disabled.remove(name) else disabled.add(name)
    disabledSkills = disabled
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        .edit().putStringSet("disabled_skills", disabled).apply()
    executionRouter.setDisabledSkills(disabled)
    _uiState.update { it.copy(disabledSkills = disabled) }
}

fun AniobViewModel.deleteSkill(name: String) {
    // Learned skills are YAML files under the skill library; built-ins live in assets and are
    // not deletable, so a missing file is a no-op rather than an error.
    runCatching {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
        File(dir, "$name.yaml").takeIf { it.exists() }?.delete()
        semanticSkillMatcher.removeSkill(name)
    }
}

// =========================================================================================
// UX-6 survival helpers
// =========================================================================================

/** UX-6: last completed task as a card on next open (Quiet Return). */
// ------------------------------------------------------------------------------------------
// Audit patch: phase visibility, closed-set outcome, plan-time feasibility, grill-me wiring
// ------------------------------------------------------------------------------------------

/** Starts a new phase and says whether a model is really involved (RULES = no model). */
internal fun AniobViewModel.setPhase(
    phase: AgentPhase,
    source: ThinkSource = ThinkSource.RULES,
    thought: String = "",
    retryAtMs: Long? = null,
    provider: String = ""
) {
    _uiState.update {
        it.copy(agentPhase = PhaseSnapshot(phase, source, System.currentTimeMillis(), thought.take(160), retryAtMs, provider))
    }
}

/** Moves to the next phase of the SAME step, keeping the source and provider already shown. */
internal fun AniobViewModel.advancePhase(phase: AgentPhase, thought: String = "") {
    _uiState.update {
        it.copy(
            agentPhase = it.agentPhase.copy(
                phase = phase,
                sinceMs = System.currentTimeMillis(),
                thoughtPreview = thought.take(160).ifBlank { it.agentPhase.thoughtPreview },
                retryAtMs = null
            )
        )
    }
}

/** The only way a run's result is stored: a SealedOutcome, which cannot be VERIFIED without evidence. */
internal fun AniobViewModel.recordSealedOutcome(taskId: String, sealed: SealedOutcome) {
    _uiState.update { it.copy(lastSealedOutcome = sealed, lastSealedTaskId = taskId) }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            outcomeStore.record(taskId, sealed)
        } catch (e: android.database.sqlite.SQLiteException) {
            // The SQL guard refused it (or the disk did). Never crash the UI over a result row.
            app.eventLogger.warn("AniobViewModel", "Outcome not stored: ${e.message}")
        }
    }
}

internal fun AniobViewModel.tryApplyNaturalLanguageCorrection(text: String): Boolean {
    if (_uiState.value.isRunning || !com.aniob.core.outcome.AniobOutcomeCorrection.matches(text)) return false
    val outcome = _uiState.value.lastSealedOutcome ?: return false
    if (outcome.userDecision != null || outcome.outcome !in setOf(RunOutcome.FAILED, RunOutcome.UNVERIFIED)) return false
    _uiState.update { it.copy(chatMessages = it.chatMessages + ChatMessage(
        id = "msg_user_${java.util.UUID.randomUUID()}", role = "user", content = text)) }
    onOutcomeDecision(UserDecision.YES)
    return true
}

/** The user's thumbs on the last result. YES attests an unconfirmed run; NO demotes a claimed success. */
fun AniobViewModel.onOutcomeDecision(decision: UserDecision) {
    val taskId = _uiState.value.lastSealedTaskId ?: return
    val current = _uiState.value.lastSealedOutcome ?: return
    if (current.userDecision != null) return
    recordSealedOutcome(taskId, current.withUserDecision(decision, System.currentTimeMillis()))
    if (decision != UserDecision.YES || current.outcome !in setOf(RunOutcome.FAILED, RunOutcome.UNVERIFIED)) return
    val snapshot = lastCompletedLearningSnapshot?.takeIf { it.taskId == taskId } ?: return
    viewModelScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.supervisorScope {
            launch { runCatching {
                val row = app.researchTelemetry.markUserCorrected(taskId)
                if (row != null) {
                    val summary = app.researchTelemetry.summaryFor(row.mode)
                    _uiState.update { state -> if (state.lastSealedTaskId == taskId)
                        state.copy(researchLastRun = row, researchSummary = summary) else state }
                }
            } }
            launch { runCatching {
                learningPipeline.onUserAttestedTask(TaskContext(instruction = snapshot.prompt,
                    successCriteria = snapshot.criteria ?: SuccessCriteria(), taskId = taskId,
                    lastScreenState = snapshot.screen), snapshot.actions)
            } }
            launch { runCatching { persistRememberTransitions(snapshot.mode, snapshot.prompt, snapshot.transitions) } }
            launch { runCatching {
                if (snapshot.mode.skillsEnabled) {
                    val name = hippocampusTracker.consolidateEpisode(snapshot.taskId, snapshot.prompt,
                        snapshot.episode, userAttested = true)
                    if (name != null) semanticSkillMatcher.loadAndIndexSingleSkill(File(
                        File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills"), "$name.yaml"))
                }
            } }
        }
    }
}

internal fun AniobViewModel.buildGrillLlmClient(): AniobPromptGrillMeClient? {
    val systemPrompt = try {
        app.assets.open("aniob_config/prompts/grill_me_prompt.txt").bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        return null
    }
    return AniobPromptGrillMeClient(systemPrompt = systemPrompt) { system, user ->
        // Never send the request to the cloud in local-only mode or without a configured gateway.
        if (!isCloudConfigured() || _uiState.value.autoRouterMode.lowercase() == "local-only") null
        else try {
            getOmniRouteProvider(sessionId = "grill").generate(user, system)
        } catch (e: java.io.IOException) {
            null
        }
    }
}

/** What this phone will let us do and OBSERVE right now. Shizuku/device-owner are not integrated, so never claimed. */
internal fun AniobViewModel.capabilityEnvelope(): CapabilityEnvelope {
    val state = AniobCapabilityProbe.snapshot(app)
    return CapabilityEnvelope(
        buildSet {
            add(Privilege.PACKAGE_QUERY) // launcher-visible packages via <queries>; see AndroidManifest
            if (state.accessibility) add(Privilege.ACCESSIBILITY)
            if (state.notificationAccess) add(Privilege.NOTIFICATION_LISTENER)
        }
    )
}

/** Conservative plan shape from the words of the request: only steps that are certainly system-gated. */
internal fun AniobViewModel.planStepsFor(prompt: String): List<PlanStep> {
    val words = com.aniob.core.text.AniobTextFold.tokens(prompt).toSet()
    return buildList {
        if ("apk" in words || "sideload" in words) {
            add(PlanStep("install the APK", ActionGate.SYSTEM_PACKAGE_DIALOG, setOf(Postcondition.PACKAGE_INSTALLED)))
        } else if ("uninstall" in words) {
            add(PlanStep("uninstall the app", ActionGate.SYSTEM_PACKAGE_DIALOG, setOf(Postcondition.PACKAGE_REMOVED)))
        }
    }
}

internal fun AniobViewModel.finishDelegated(task: AniobTask, whatUserMustDo: String) {
    recordSealedOutcome(task.id, SealedOutcome.delegated(whatUserMustDo))
    setPhase(AgentPhase.DELEGATED)
    val note = ChatMessage(
        id = "msg_deleg_${System.currentTimeMillis()}",
        role = "assistant",
        content = whatUserMustDo,
        provider = "DELEGATED",
        badge = "\uD83E\uDD1D Your turn"
    )
    updateToIdle { it.copy(showGrillMeSheet = false, statusMessage = whatUserMustDo, chatMessages = it.chatMessages + note) }
}

internal fun AniobViewModel.postSummaryCard(summary: TaskSummary) {
    _uiState.update { it.copy(lastSummary = summary) }
}
