package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.Outcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun AniobViewModel.handleIncomingShare(shared: com.aniob.app.workflow.ParsedIncomingContent) {
    // Documents arrive as content URIs (pdf, docx, pptx, images…). Register each so later
    // steps can find and read it: the bytes are copied into private storage because
    // share-sheet grants are not persistable across process death.
    if (shared.uris.isNotEmpty()) {
        shared.uris.forEach { uri ->
            runCatching {
                com.aniob.app.device.AniobDocumentAccess.register(app, uri, shared.mimeType, source = "share")
            }.onFailure {
                app.eventLogger.warn("AniobViewModel", "Could not record shared file $uri: ${it.message}")
            }
        }
        _uiState.update {
            it.copy(
                statusMessage = "Received ${shared.uris.size} file(s). Ask me to open, read or summarize them."
            )
        }
    }
    val text = shared.text
    if (!text.isNullOrBlank()) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > com.aniob.core.workflow.WorkflowLimits.TEXT_BRIEF_MAX_BYTES) {
            _uiState.update {
                it.copy(
                    statusMessage = "Shared text exceeds 64 KiB limit (${bytes.size} bytes). Cannot import.",
                    pendingInputPrefill = null
                )
            }
        } else {
            _uiState.update { it.copy(pendingInputPrefill = text) }
        }
    }
}

fun AniobViewModel.consumePendingInputPrefill(): String? {
    val prefill = _uiState.value.pendingInputPrefill
    if (prefill != null) {
        _uiState.update { it.copy(pendingInputPrefill = null) }
    }
    return prefill
}

fun AniobViewModel.setPromptPrefill(text: String) {
    _uiState.update { it.copy(pendingInputPrefill = text) }
}

internal fun AniobViewModel.executeOwnedWorkflowAction(
    taskId: String,
    leaseGen: Long,
    prompt: String,
    action: com.aniob.core.workflow.WorkflowAction
) {
    val startTime = System.currentTimeMillis()
    val destName = when (action) {
        is com.aniob.core.workflow.WorkflowAction.OpenWebsite -> action.destination.handler
        is com.aniob.core.workflow.WorkflowAction.OpenApp -> action.destination.handler
        else -> "destination"
    }

    activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
        val currentJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
        app.taskOwner.attachJob(leaseGen, currentJob)

        _uiState.update {
            it.copy(
                isRunning = true,
                statusMessage = "Opening $destName...",
                lastProviderUsed = "WORKFLOW"
            )
        }

        val result = app.workflowCoordinator.executeAction(action, taskId, leaseGen)
        when (result) {
            is com.aniob.core.workflow.WorkflowResult.WaitingForUser -> {
                AniobForegroundService.notifyWaitingForUser(app, destName)
                updateToIdle {
                    it.copy(
                        statusMessage = "Task waiting on $destName",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
            is com.aniob.core.workflow.WorkflowResult.LaunchAccepted -> {
                app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                val summary = buildSummary(
                    prompt = prompt,
                    outcome = Outcome.SUCCESS,
                    steps = 1,
                    durationMs = System.currentTimeMillis() - startTime,
                    provider = "WORKFLOW",
                    evidence = listOf(EvidenceItem(label = "Opened $destName", met = true))
                )
                updateToIdle {
                    it.copy(
                        lastSummary = summary,
                        statusMessage = "Action launched: $destName",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
            is com.aniob.core.workflow.WorkflowResult.Failed -> {
                app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                val summary = buildSummary(
                    prompt = prompt,
                    outcome = Outcome.FAILED,
                    steps = 1,
                    durationMs = System.currentTimeMillis() - startTime,
                    provider = "WORKFLOW",
                    evidence = emptyList(),
                    reason = result.errorReason
                )
                updateToIdle {
                    it.copy(
                        lastSummary = summary,
                        statusMessage = "Action failed: ${result.errorReason}",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
            is com.aniob.core.workflow.WorkflowResult.Cancelled -> {
                val summary = buildSummary(
                    prompt = prompt,
                    outcome = Outcome.STOPPED,
                    steps = 1,
                    durationMs = System.currentTimeMillis() - startTime,
                    provider = "WORKFLOW",
                    evidence = emptyList(),
                    reason = result.reason
                )
                updateToIdle {
                    it.copy(
                        lastSummary = summary,
                        statusMessage = "Cancelled by user",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
            else -> {
                updateToIdle()
            }
        }
    }
}

fun AniobViewModel.onDestinationChosen(destination: com.aniob.core.workflow.Destination, brief: String?) {
    val taskId = "task_chooser_${System.currentTimeMillis()}"
    val leaseGen = app.taskOwner.acquireLease(taskId)
    val action = if (destination.category == com.aniob.core.workflow.DestinationCategory.APP) {
        com.aniob.core.workflow.WorkflowAction.OpenApp(
            actionId = "action_${System.currentTimeMillis()}",
            destination = destination
        )
    } else {
        com.aniob.core.workflow.WorkflowAction.OpenWebsite(
            actionId = "action_${System.currentTimeMillis()}",
            destination = destination,
            briefText = brief
        )
    }
    executeOwnedWorkflowAction(
        taskId = taskId,
        leaseGen = leaseGen,
        prompt = "Open ${destination.label ?: destination.handler}",
        action = action
    )
}

fun AniobViewModel.onContinueInWebsite(taskId: String) {
    val ws = app.workflowCoordinator.workflowState.value
    val waiting = (ws as? com.aniob.core.workflow.WorkflowState.WaitingForUser)?.takeIf { it.identity.taskId == taskId }
    if (waiting != null) {
        val leaseGen = app.taskOwner.acquireLease(taskId)
        val action = com.aniob.core.workflow.WorkflowAction.OpenWebsite(
            actionId = "action_continue_${System.currentTimeMillis()}",
            destination = waiting.destination,
            briefText = waiting.brief
        )
        executeOwnedWorkflowAction(
            taskId = taskId,
            leaseGen = leaseGen,
            prompt = "Continue in ${waiting.destination.handler}",
            action = action
        )
    }
}

fun AniobViewModel.onCustomUrlChosen(url: String, brief: String?) {
    val validated = com.aniob.core.workflow.WorkflowRouter.validateAndSanitizeUrl(url)
    if (validated == null) {
        _uiState.update { it.copy(statusMessage = "Invalid URL entered.") }
        return
    }
    val host = com.aniob.core.workflow.WorkflowRouter.extractUrl(validated)?.let {
        validated.substringAfter("://").substringBefore('/')
    } ?: validated
    val dest = com.aniob.core.workflow.Destination(
        handler = host,
        url = validated,
        category = com.aniob.core.workflow.DestinationCategory.WEBSITE,
        provenance = com.aniob.core.workflow.DestinationProvenance.USER_INPUT
    )
    onDestinationChosen(dest, brief)
}

fun AniobViewModel.onWorkflowMarkComplete(taskId: String, userNotes: String = "") {
    viewModelScope.launch(Dispatchers.IO) {
        val result = app.workflowCoordinator.completeWaitingTask(taskId, userNotes)
        val summary = buildSummary(
            prompt = "Workflow $taskId",
            outcome = Outcome.SUCCESS,
            steps = 1,
            durationMs = 0L,
            provider = "WORKFLOW",
            evidence = if (userNotes.isNotBlank()) listOf(EvidenceItem(label = userNotes, met = true)) else emptyList()
        )
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            updateToIdle {
                it.copy(
                    lastSummary = summary,
                    statusMessage = "Workflow marked complete",
                    lastProviderUsed = "WORKFLOW"
                )
            }
        }
    }
}

fun AniobViewModel.onWorkflowCancel(taskId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val result = app.workflowCoordinator.cancelWorkflow(taskId)
        val summary = buildSummary(
            prompt = "Workflow $taskId",
            outcome = Outcome.STOPPED,
            steps = 1,
            durationMs = 0L,
            provider = "WORKFLOW",
            evidence = emptyList(),
            reason = "Cancelled by user"
        )
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            updateToIdle {
                it.copy(
                    lastSummary = summary,
                    statusMessage = "Workflow cancelled",
                    lastProviderUsed = "WORKFLOW"
                )
            }
        }
    }
}
