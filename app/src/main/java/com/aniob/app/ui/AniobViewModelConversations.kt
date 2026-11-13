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
    conversationId = id
    conversationPrefs.edit().putString(KEY_ACTIVE_CONVERSATION, id).apply()
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
    viewModelScope.launch(Dispatchers.IO) {
        val stored = runCatching { savedConversationMessages(id) }.getOrElse { emptyList() }
        if (conversationStore.find(id) == null || _uiState.value.isRunning || origin != conversationId) return@launch
        val previous = conversationId
        if (previous.isNotBlank() && previous != id) conversationStore.close(previous, "User switched to another chat")
        if (_uiState.value.isRunning || origin != conversationId) return@launch
        setActiveConversation(id)
        _uiState.update {
            it.copy(
                chatMessages = stored,
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
}

/** Deletes a saved chat. Deleting the open one also clears the screen. */
fun AniobViewModel.deleteConversation(id: String) {
    val wasActive = id == conversationId
    if (wasActive && _uiState.value.isRunning) return
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

/** Persists active task marker in Room so process death can be honestly reviewed on next open. */
internal fun AniobViewModel.persistRunningMarker(task: AniobTask, stepIndex: Int, lastKnownEffect: String = "") {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            app.database.activeTaskDao().setActiveTask(
                com.aniob.app.db.ActiveTaskEntity(
                    taskId = task.id,
                    rawPrompt = task.rawPrompt,
                    currentStep = stepIndex,
                    lastKnownEffect = lastKnownEffect,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.w("AniobViewModel", "Failed to update running marker in database", e)
        }
    }
}

internal fun AniobViewModel.clearRunningMarker() {
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

        val marker = try {
            app.database.activeTaskDao().getActiveTask()
        } catch (_: Exception) { null } ?: return@launch

        // If a task is currently executing with this taskId, it is live, not an interrupted prior task
        if (_uiState.value.isRunning && _uiState.value.activeTask?.id == marker.taskId) {
            return@launch
        }

        val prompt = marker.rawPrompt
        val step = marker.currentStep
        val lastEffect = marker.lastKnownEffect
        val summary = buildSummary(
            prompt = prompt,
            outcome = Outcome.INTERRUPTED,
            steps = step,
            durationMs = 0L,
            provider = "NONE",
            evidence = emptyList(),
            reason = if (lastEffect.isNotBlank()) {
                "Interrupted at step $step. Last known effect: $lastEffect. Review external effects before retrying."
            } else {
                "Interrupted at step $step. Review external effects before retrying."
            }
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
