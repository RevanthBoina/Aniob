package com.aniob.app.ui

import com.aniob.core.domain.*
import androidx.lifecycle.viewModelScope
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.NextAction
import com.aniob.app.ui.model.Outcome
import com.aniob.app.ui.model.TaskSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aniob.app.ui.AniobViewModel.Companion.KEY_ACTIVE_CONVERSATION
import com.aniob.app.ui.AniobViewModel.Companion.CHAT_RENDER_WINDOW

internal fun AniobViewModel.activeConversationId(): String {
    if (conversationId.isBlank()) {
        startNewConversation()
        _uiState.update { it.copy(chatMessages = emptyList()) }
    }
    return conversationId
}

internal fun AniobViewModel.startNewConversation() {
    setActiveConversation(java.util.UUID.randomUUID().toString())
}

internal fun AniobViewModel.setActiveConversation(id: String) {
    if (conversationId != id) clearConversationTaskContext()
    conversationId = id
    conversationPrefs.edit().putString(KEY_ACTIVE_CONVERSATION, id).apply()
}

/** A correction, queued follow-up or contextual device command must not target another chat. */
internal fun AniobViewModel.clearConversationTaskContext() {
    lastCompletedLearningSnapshot = null
    lastResumablePipeline = null
    taskStickyTarget = null
    conversationPrefs.edit().remove("queued_prompt").apply()
    _uiState.update { it.copy(activeTask = null, lastSealedTaskId = null, lastSealedOutcome = null,
        lastSummary = null, confirmRequest = null, pendingInputPrefill = null, queuedPrompt = null,
        steps = emptyList(), stepEvents = emptyList(), lastNarration = "", momentChips = emptyList()) }
}

internal fun AniobViewModel.storedMessageToChat(message: com.aniob.app.db.ChatMessageEntity) = ChatMessage(
    id = message.id,
    role = message.role,
    content = message.content,
    timestamp = message.timestamp,
    badge = if (message.role == "assistant") "💬 AI Answer" else null,
    provider = message.provider
)

/** Successful answers stay in chat_messages; failed/automation requests come from the journal. */
internal suspend fun AniobViewModel.savedConversationMessages(id: String): List<ChatMessage> {
    val stored = conversationStore.messages(id).map { storedMessageToChat(it) }
    val journal = app.database.conversationJournalDao().tasks(id).filter { task -> task.outcome != "ANSWERED" || stored.none { it.id == "${task.taskId}:assistant" } }.flatMap { task ->
        listOf(ChatMessage(id = "journal_user_${task.taskId}", role = "user", content = task.prompt, timestamp = task.startedAt),
            ChatMessage(id = "journal_result_${task.taskId}", role = "assistant",
                content = task.response.ifBlank { "${task.outcome}: ${task.reason.ifBlank { "No terminal result recorded" }}" },
                timestamp = task.startedAt, provider = "RECORDED_HISTORY"))
    }
    return (stored + journal).sortedBy { it.timestamp }
}

/** Cold start: show the chat that was open when the app last closed. */
internal fun AniobViewModel.restoreActiveConversation() {
    val saved = conversationPrefs.getString(KEY_ACTIVE_CONVERSATION, null)
    if (!saved.isNullOrBlank()) conversationId = saved
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            conversationJournal.recover(conversationStartedAt)
            if (saved.isNullOrBlank()) return@launch
            val stored = savedConversationMessages(saved)
            _uiState.update { state ->
                if (conversationId == saved && state.chatMessages.all { it.id == "msg_init" })
                    state.copy(chatMessages = state.chatMessages + stored) else state
            }
        }.onFailure {
            app.eventLogger.warn("ConversationJournal", "Could not restore conversation history")
            _uiState.update { state -> if (conversationId == saved && !state.isRunning)
                state.copy(statusMessage = "History unavailable — check device storage") else state }
        }
    }
}

/** Opens a saved chat: its messages replace the screen and follow-ups continue that thread. */
fun AniobViewModel.openConversation(id: String) {
    if (_uiState.value.isRunning) return
    val origin = conversationId
    val generation = ++activeGeneration
    // Reserve ownership before IO. A submit cannot slip between a stale-state check and switch.
    _uiState.update { it.copy(isRunning = true, activeTask = null, statusMessage = "Opening saved chat…") }
    activeTaskJob = viewModelScope.launch {
        try {
            val stored = withContext(Dispatchers.IO) {
                requireNotNull(conversationStore.find(id)) { "Conversation no longer exists" }
                val messages = savedConversationMessages(id)
                if (origin.isNotBlank() && origin != id) conversationStore.close(origin, "User switched to another chat")
                messages
            }
            if (generation != activeGeneration || origin != conversationId) return@launch
            setActiveConversation(id)
            _uiState.update { it.copy(chatMessages = stored, streamingBubbleText = "", isStreaming = false,
                statusMessage = "Ready") }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
        } catch (_: android.database.SQLException) {
            if (generation == activeGeneration) _uiState.update { it.copy(statusMessage = "History unavailable — check device storage") }
        } catch (_: IllegalArgumentException) {
            if (generation == activeGeneration) _uiState.update { it.copy(statusMessage = "That conversation is no longer available") }
        } finally {
            if (generation == activeGeneration) _uiState.update { it.copy(isRunning = false) }
        }
    }
}

/** Deletes a saved chat. Deleting the open one also clears the screen. */
fun AniobViewModel.deleteConversation(id: String) {
    val wasActive = id == conversationId
    if (_uiState.value.isRunning) return
    viewModelScope.launch(Dispatchers.IO) {
        val deleted = runCatching {
            conversationStore.delete(id)
            com.aniob.core.memory.ConversationKnowledgeStore(sharedKnowledgeStore, id).clear()
        }.onFailure { app.eventLogger.warn("AniobViewModel", "Could not delete chat") }.isSuccess
        if (deleted && wasActive && id == conversationId) clearChat()
    }
}

fun AniobViewModel.clearChat(title: String = "New chat") {
    // The cleared chat stays in the previous-chats list; new messages start a new thread.
    if (_uiState.value.isRunning) return // Stop the current task before changing its conversation.
    val previous = conversationId
    startNewConversation()
    val next = conversationId
    viewModelScope.launch(Dispatchers.IO) {
        if (previous.isNotBlank()) conversationStore.close(previous, "User started a new chat")
        conversationStore.create(next, title)
    }
    _uiState.update {
        it.copy(
            chatMessages = emptyList(),
            steps = emptyList(),
            stepEvents = emptyList(),
            lastNarration = "",
            lastSummary = null,
            momentChips = emptyList(),
            streamingBubbleText = "",
            isStreaming = false,
            queuedPrompt = null
        )
    }
}

fun AniobViewModel.dismissInterruptedSummary() {
    _uiState.update { it.copy(interruptedSummary = null) }
}

fun AniobViewModel.dismissRecoveryBanner() {
    _uiState.update { it.copy(accessibilityRecoveryNeeded = false) }
}

fun AniobViewModel.clearLastSummary() {
    _uiState.update { it.copy(lastSummary = null) }
}

fun AniobViewModel.setTrackerFilter(filter: String) {
    _uiState.update { it.copy(trackerFilter = filter) }
}

fun AniobViewModel.setShowTrackerSheet(show: Boolean) {
    // Tracker is a pure view over already-recorded Room/EventLogger data: opening it must not
    // trigger a screen capture or a model call (asserted by TrackerZeroCaptureTest).
    if (show) trackerOpenCount++
    _uiState.update { it.copy(showTrackerSheet = show) }
}

/** Test/diagnostic surface for the zero-capture guarantee. */
fun AniobViewModel.trackerOpenCount(): Int = trackerOpenCount

/** Renders only the newest [CHAT_RENDER_WINDOW] messages; the data list keeps everything (U18). */
fun AniobViewModel.renderWindow(): List<ChatMessage> {
    val messages = _uiState.value.chatMessages
    return if (messages.size <= CHAT_RENDER_WINDOW) messages
    else messages.takeLast(CHAT_RENDER_WINDOW)
}

/**
 * Builds an honest completion card. Evidence comes straight from the verifier's unmet list,
 * never invented; a STOPPED outcome is never reported as SUCCESS or FAILED.
 */
internal fun AniobViewModel.buildSummary(
    prompt: String,
    outcome: Outcome,
    steps: Int,
    durationMs: Long,
    provider: String,
    evidence: List<EvidenceItem>,
    reason: String? = null
): TaskSummary {
    val next = when (outcome) {
        Outcome.SUCCESS -> listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS)
        Outcome.FAILED -> listOf(NextAction.RETRY, NextAction.EXPLORE_APP, NextAction.VIEW_STEPS)
        Outcome.UNVERIFIED -> listOf(NextAction.RETRY, NextAction.VIEW_STEPS)
        Outcome.STOPPED -> listOf(NextAction.VIEW_STEPS)
        Outcome.INTERRUPTED -> listOf(NextAction.VIEW_STEPS)
    }
    return TaskSummary(
        prompt = prompt,
        outcome = outcome,
        steps = steps,
        durationMs = durationMs,
        provider = provider,
        evidence = evidence,
        nextActions = next,
        reason = reason
    )
}

/**
 * Persists the running marker in Room plus a structured [com.aniob.core.run.TaskRecoverySnapshot]
 * so a process death can restore the task as interrupted on next open.
 *
 * Scope boundary: in-memory same-process continuation uses `TaskInputChannel` / `activePipeline` /
 * `lastResumablePipeline`, which do NOT survive process death. This marker/snapshot stores the
 * original task, correlated action IDs, execution outcomes, pending instructions, and last known
 * task status so cold restart restores the task as interrupted and reconciles any uncertain
 * dispatch before continuing — without blindly replaying interrupted actions.
 */
internal fun AniobViewModel.persistRunningMarker(
    task: AniobTask,
    stepIndex: Int,
    lastKnownEffect: String = "",
    verifiedSoFar: List<String> = emptyList(),
    taskContext: TaskContext? = null,
    pendingInstructions: List<String> = emptyList()
) {
    val recordedEffect = buildString {
        if (verifiedSoFar.isNotEmpty()) {
            append("verified: ")
            append(verifiedSoFar.joinToString(" | ").take(300))
            append("; ")
        }
        if (lastKnownEffect.startsWith(com.aniob.core.run.AniobInterruptedTask.WAITING_PREFIX)) {
            append(lastKnownEffect.take(160))
        } else if (lastKnownEffect.isNotBlank()) {
            append("may have happened: ").append(lastKnownEffect.take(160))
        }
    }.trim()
    val mergedPending = ((taskContext?.additionalInstructions.orEmpty()) + pendingInstructions)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val snapshot = com.aniob.core.run.TaskRecoverySnapshot(
        taskId = task.id,
        originalInstruction = taskContext?.instruction?.ifBlank { task.rawPrompt } ?: task.rawPrompt,
        lastKnownTaskStatus = task.status.name,
        currentStep = stepIndex,
        pendingInstructions = mergedPending,
        executionResults = taskContext?.executionResults.orEmpty()
    )
    runCatching {
        app.getSharedPreferences(TASK_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TASK_RECOVERY_SNAPSHOT, snapshot.toJson())
            .apply()
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            app.database.activeTaskDao().setActiveTask(
                com.aniob.app.db.ActiveTaskEntity(
                    taskId = task.id,
                    rawPrompt = task.rawPrompt,
                    currentStep = stepIndex,
                    lastKnownEffect = recordedEffect,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("AniobViewModel", "Failed to update running marker in database", e)
        }
    }
}

private const val TASK_RECOVERY_PREFS = "aniob_task_recovery"
private const val KEY_TASK_RECOVERY_SNAPSHOT = "snapshot_json"

internal fun AniobViewModel.loadPersistedRecoverySnapshot(): com.aniob.core.run.TaskRecoverySnapshot? =
    runCatching {
        val raw = app.getSharedPreferences(TASK_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_TASK_RECOVERY_SNAPSHOT, null)
        com.aniob.core.run.TaskRecoverySnapshot.fromJson(raw)
    }.getOrNull()

internal fun AniobViewModel.clearRunningMarker() {
    runCatching {
        app.getSharedPreferences(TASK_RECOVERY_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TASK_RECOVERY_SNAPSHOT)
            .apply()
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            app.database.activeTaskDao().clearActiveTasks()
        } catch (e: Exception) {
            android.util.Log.w("AniobViewModel", "Failed to clear active tasks from database", e)
        }
    }
}

internal fun AniobViewModel.updateToIdle(transform: (AniobUiState) -> AniobUiState = { it }) {
    clearRunningMarker()
    AniobAccessibilityService.isTaskActive = false
    if (!terminationStage.isTerminated) {
        runCatching {
            terminationStage.terminate(
                goal = _uiState.value.activeTask?.clarifiedGoal ?: _uiState.value.activeTask?.rawPrompt ?: "",
                residue = com.aniob.core.termination.TerminationStage.DeviceResidue(),
                didDo = emptyList(),
                didNotDo = emptyList(),
                userStopped = false
            )
        }
    }
    _uiState.update { current ->
        transform(current).copy(isRunning = false)
    }
}

/**
 * PART 6: the explicit, user-started continuation of an interrupted task.
 *
 * Nothing here re-runs the dead run. It clears the marker, then submits a NEW task whose prompt
 * carries the recorded uncertainty so the agent's first move is to look at the live screen and
 * re-check — never to repeat a possibly completed action. Deliberately not automatic: a persisted
 * marker is evidence, not a checkpoint.
 */
fun AniobViewModel.continueInterruptedTask() {
    if (_uiState.value.isRunning) return
    val summary = _uiState.value.interruptedSummary ?: return
    val snapshot = pendingInterruptedSnapshot
    pendingInterruptedSnapshot = null
    val liveScreen = AniobAccessibilityService.instance?.captureCurrentScreenState()
    val continuationPrompt = if (snapshot != null) {
        val restored = snapshot.restoreAsInterrupted(liveScreen)
        val verifiedDesc = restored.verifiedExecutionRecords()
            .joinToString("; ") { "[${it.actionId}] ${it.actionSummary}" }
        val uncertainDesc = restored.uncertainExecutionRecords()
            .joinToString("; ") { "[${it.actionId}] ${it.actionSummary} (${it.detail})" }
        val pendingDesc = restored.additionalInstructions.joinToString("; ")
        buildString {
            append(restored.instruction)
            if (pendingDesc.isNotBlank()) {
                append("\nPending user instructions: ").append(pendingDesc)
            }
            if (verifiedDesc.isNotBlank()) {
                append("\nAlready verified before restart (do not repeat): ").append(verifiedDesc)
            }
            if (uncertainDesc.isNotBlank()) {
                append("\nUncertain dispatch after restart (check live screen first; do not blindly replay): ")
                append(uncertainDesc)
            }
        }
    } else {
        val report = com.aniob.core.run.AniobInterruptedTask.parse(
            taskId = "recovered",
            prompt = summary.prompt,
            step = summary.steps,
            lastKnownEffect = summary.evidence.joinToString("; ") { item ->
                if (item.met) "verified: ${item.label}" else "may have happened: ${item.label}"
            }
        )
        report.continuationPrompt()
    }
    clearRunningMarker()
    _uiState.update {
        it.copy(
            interruptedSummary = null,
            statusMessage = "Continuing the interrupted task — checking the screen first"
        )
    }
    app.eventLogger.info("AniobViewModel", "User started a safe continuation of an interrupted task")
    submitTask(continuationPrompt)
}

/** Dismisses the interrupted card without continuing: the marker is cleared too, so it cannot return. */
fun AniobViewModel.dismissInterruptedTask() {
    pendingInterruptedSnapshot = null
    clearRunningMarker()
    _uiState.update { it.copy(interruptedSummary = null) }
}

/** Reads the persisted marker on cold start and surfaces it as an Interrupted card. */
internal fun AniobViewModel.restoreInterruptedSummary() {
    viewModelScope.launch(Dispatchers.IO) {
        // Check workflow repository first
        val recoveredList = try {
            app.workflowRepository.recoverAllOnStartup()
        } catch (_: Exception) { emptyList() }

        val interrupted = recoveredList.firstOrNull { it.state == "EffectUnknown" }
        if (interrupted != null) {
            val summary = buildSummary(
                prompt = "Workflow ${interrupted.taskId}",
                outcome = Outcome.INTERRUPTED,
                steps = 1,
                durationMs = 0L,
                provider = "WORKFLOW",
                evidence = emptyList(),
                reason = "Interrupted during dispatch. Outcome in external app is unverified."
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (_uiState.value.isRunning) {
                    _uiState.update { it.copy(interruptedSummary = summary) }
                } else {
                    updateToIdle { it.copy(interruptedSummary = summary) }
                }
            }
        }

        val waiting = recoveredList.firstOrNull { it.state == "WaitingForUser" }
        if (waiting != null) {
            val dest = com.aniob.core.workflow.Destination(
                handler = waiting.destinationKey,
                url = waiting.destinationUrl,
                category = com.aniob.core.workflow.DestinationCategory.WEBSITE
            )
            val identity = com.aniob.core.workflow.OperationIdentity(
                taskId = waiting.taskId,
                actionId = waiting.actionId,
                generation = waiting.generation,
                destinationKey = waiting.destinationKey,
                approvedPayloadHash = waiting.payloadHash
            )
            app.workflowCoordinator.updateState(
                com.aniob.core.workflow.WorkflowState.WaitingForUser(
                    identity = identity,
                    destination = dest,
                    brief = waiting.evidence
                )
            )
        }

        if (interrupted != null) return@launch

        val persistedSnapshot = loadPersistedRecoverySnapshot()
        val marker = try {
            app.database.activeTaskDao().getActiveTask()
        } catch (_: Exception) { null }
        if (marker == null && persistedSnapshot == null) return@launch

        val recoveredTaskId = marker?.taskId ?: persistedSnapshot?.taskId.orEmpty()
        // If a task is currently executing with this taskId, it is live, not an interrupted prior task
        if (_uiState.value.isRunning && _uiState.value.activeTask?.id == recoveredTaskId) {
            return@launch
        }

        pendingInterruptedSnapshot = persistedSnapshot
        val prompt = persistedSnapshot?.originalInstruction?.ifBlank { marker?.rawPrompt.orEmpty() }
            ?: marker?.rawPrompt.orEmpty()
        val step = persistedSnapshot?.currentStep ?: marker?.currentStep ?: 0
        // PART 6: the marker/snapshot is restored honestly as interrupted, never auto-replayed.
        val report = com.aniob.core.run.AniobInterruptedTask.parse(
            taskId = recoveredTaskId,
            prompt = prompt,
            step = step,
            lastKnownEffect = marker?.lastKnownEffect.orEmpty()
        )
        val snapshotVerified = persistedSnapshot?.executionResults
            ?.filter { it.status == ExecutionOutcomeStatus.VERIFIED }
            ?.map { "[${it.actionId}] ${it.actionSummary}" }
            .orEmpty()
        val snapshotUncertain = persistedSnapshot?.executionResults
            ?.filter {
                it.status == ExecutionOutcomeStatus.UNCERTAIN ||
                    it.status == ExecutionOutcomeStatus.UNVERIFIED
            }
            ?.map { "[${it.actionId}] ${it.actionSummary}" }
            .orEmpty()
        val verifiedLabels = if (snapshotVerified.isNotEmpty()) snapshotVerified else report.verified
        val uncertainLabels = if (snapshotUncertain.isNotEmpty()) snapshotUncertain else report.needsChecking
        val summary = buildSummary(
            prompt = prompt,
            outcome = Outcome.INTERRUPTED,
            steps = step,
            durationMs = 0L,
            provider = if (verifiedLabels.isEmpty()) "NONE" else "RECOVERED",
            evidence = verifiedLabels.map { label ->
                com.aniob.app.ui.model.EvidenceItem(label = label, met = true, detail = "Verified before the interruption")
            } + uncertainLabels.map { label ->
                com.aniob.app.ui.model.EvidenceItem(
                    label = label,
                    met = false,
                    detail = "Effect unknown — reconcile on the live screen before continuing"
                )
            },
            reason = report.reason()
        )
        clearRunningMarker()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (_uiState.value.isRunning) {
                _uiState.update { it.copy(interruptedSummary = summary) }
            } else {
                updateToIdle { it.copy(interruptedSummary = summary) }
            }
        }
    }
}
