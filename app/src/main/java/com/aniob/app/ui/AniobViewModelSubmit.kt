package com.aniob.app.ui

import com.aniob.core.domain.*
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.aniob.core.external.AniobExternalAiTrigger
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.intent.AniobIntent
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.run.AniobFastLane
import com.aniob.core.safety.AskPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Entry point when user submits a natural language command.
 */
fun AniobViewModel.submitTask(prompt: String) {
    val trimmed = prompt.trim()
    if (trimmed.isBlank()) return
    if (handleConversationCommand(trimmed)) return
    if (tryApplyNaturalLanguageCorrection(trimmed)) return

    // Reject if another task is active to prevent double submission
    if (_uiState.value.isRunning) {
        app.eventLogger.warn("AniobViewModel", "Cannot start new task while a task is running")
        return
    }

    val taskId = "task_${System.currentTimeMillis()}"
    val submittedChatId = activeConversationId()
    conversationRegistrations[taskId] = viewModelScope.async(Dispatchers.IO) {
        runCatching { conversationJournal.begin(com.aniob.app.db.ConversationTaskEntity(
            taskId, submittedChatId, trimmed, System.currentTimeMillis())) }.isSuccess
    }
    val leaseGen = app.taskOwner.acquireLease(taskId)
    val generation = ++activeGeneration
    val task = AniobTask(id = taskId, rawPrompt = trimmed)

    // Start task-scoped safety and retry ledgers. OFF is a persisted user choice and stays OFF.
    app.approvalController.clearAll()
    targetMissLedger.beginTask(taskId)
    pendingTargetClarificationKey = null
    notificationGrillAnswers.clear()
    AniobForegroundService.clearGrillQuestion(app)
    terminationStage.reset()
    pauseRequested = false
    localFailCount = 0
    taskStickyTarget = null
    verifiedActions.clear()

    val userMessage = ChatMessage(
        id = "msg_user_${java.util.UUID.randomUUID()}",
        role = "user",
        content = trimmed
    )

    _uiState.update {
        it.copy(
            activeTask = task,
            isRunning = true,
            currentStep = 0,
            statusMessage = "Analyzing task...",
            chatMessages = it.chatMessages + userMessage,
            steps = emptyList(),
            stepEvents = emptyList(),
            lastNarration = "",
            confirmRequest = null,
            pendingInputPrefill = null
        )
    }

    activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
        app.taskOwner.attachJob(leaseGen, currentCoroutineContext()[kotlinx.coroutines.Job]!!)
        if (conversationRegistrations[taskId]?.await() != true) {
            updateToIdle { it.copy(statusMessage = "Could not save the request. Check device storage and retry.") }
            return@launch
        }

        // Briefly wait for first-run registration; a no-op once the registry is warm. Without
        // it, a launch typed during the first probe resolves against an empty registry.
        withTimeoutOrNull(com.aniob.core.knowledge.AniobRegistryBoot.READY_WAIT_MS) {
            com.aniob.app.knowledge.AniobAppBootstrap.registryReady.await()
        }

        // Resolve the effective goal and direct device/app capabilities before any chooser,
        // clarification, Doctor advice, or model route.
        val alternative = if (AniobLaunchHub.isSwitchToOther(trimmed))
            AniobLaunchHub.plan(trimmed, AniobAppRegistry.getInstance(), { isLaunchablePackage(it) }) else null
        val effectivePrompt = alternative?.let { "open ${it.packageName}" } ?: resolveContextualGoal(trimmed)
        val effectiveTask = task.copy(clarifiedGoal = effectivePrompt)
        // Safety is enforced by AskPolicy DECISIONS at action time, not narrated into
        // the transcript. The interruption level is shown once in Settings; the actual
        // confirmation sheets still fire before payments, secrets, deletion, sending,
        // posting, sharing, or calling.
        _uiState.update { it.copy(activeTask = effectiveTask) }
        val resolvedShortcut = AniobIntentResolver.resolve(effectivePrompt, { isLaunchablePackage(it) })
        // FAST LANE: a solved launch / instant reflex needs no understanding echo,
        // feasibility court, Grill-Me pass or Doctor preflight. AskPolicy DECISIONS
        // still apply at dispatch time. "Solved" means the shortcut answers the WHOLE
        // prompt: "open WhatsApp and send hi" resolves an app shortcut too, and keeps its gates.
        val lane = AniobFastLane.decide(
            prompt = effectivePrompt,
            shortcut = resolvedShortcut,
            accessibilityConnected = AniobAccessibilityService.isServiceConnected,
            hasGatedPlanSteps = planStepsFor(effectivePrompt).isNotEmpty()
        )
        lastRunLane = lane
        val workflowDecision = if (resolvedShortcut == null) {
            com.aniob.core.workflow.WorkflowRouter.route(
                effectivePrompt,
                isAppLaunchable = { isLaunchablePackage(it) }
            )
        } else {
            com.aniob.core.workflow.WorkflowDecision.DelegateToExisting(effectivePrompt)
        }
        when (workflowDecision) {
            is com.aniob.core.workflow.WorkflowDecision.DirectAction -> {
                executeOwnedWorkflowAction(
                    taskId = taskId,
                    leaseGen = leaseGen,
                    prompt = effectivePrompt,
                    action = workflowDecision.action
                )
                return@launch
            }
            is com.aniob.core.workflow.WorkflowDecision.NeedsChooser -> {
                app.workflowCoordinator.updateState(
                    com.aniob.core.workflow.WorkflowState.NeedsChoice(workflowDecision.candidates, workflowDecision.prompt)
                )
                updateToIdle {
                    it.copy(
                        statusMessage = if (workflowDecision.candidates.isNotEmpty() &&
                            workflowDecision.candidates.all { it.category == com.aniob.core.workflow.DestinationCategory.APP }
                        ) "Please choose an installed app" else "Please choose a destination"
                    )
                }
                finishConversationTask(taskId, "NEEDS_CHOICE", "Waiting for a destination selection")
                return@launch
            }
            is com.aniob.core.workflow.WorkflowDecision.NeedsClarification -> {
                val asstMsg = ChatMessage(
                    id = "msg_clarify_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = workflowDecision.question,
                    provider = "WORKFLOW"
                )
                updateToIdle {
                    it.copy(
                        chatMessages = it.chatMessages + asstMsg,
                        statusMessage = "Clarification needed"
                    )
                }
                finishConversationTask(taskId, "NEEDS_CLARIFICATION", workflowDecision.question)
                return@launch
            }
            is com.aniob.core.workflow.WorkflowDecision.DelegateToExisting -> {
                // Fallthrough to existing automation / Q&A
            }
        }

        // Step 0: Pre-route supported shortcuts before general Q&A classification.
        var intent = if (resolvedShortcut != null) {
            AniobIntent.DEVICE_AUTOMATION
        } else {
            AniobTaskIntentClassifier.classify(effectivePrompt)
        }

        // BUG-02 last line of defence: a hardware toggle that dodged both rungs must NEVER be
        // routed to the answer path — that path records generated text as an executed command.
        if (intent != AniobIntent.DEVICE_AUTOMATION &&
            AniobTaskIntentClassifier.looksLikeDeviceToggle(effectivePrompt)
        ) {
            app.eventLogger.warn(
                "AniobViewModel",
                "Hardware toggle escaped classification; re-routing to device automation: $trimmed"
            )
            intent = AniobIntent.DEVICE_AUTOMATION
        }

        // Fail CLOSED: a toggle with no device noun and no device in recent user context is
        // ambiguous. Answering it with prose would recreate exactly the report's false success,
        // so ask one question instead. Clarification is cheaper than confident fiction.
        if (intent != AniobIntent.DEVICE_AUTOMATION &&
            AniobTaskIntentClassifier.looksLikeBareToggle(trimmed) &&
            effectivePrompt == trimmed
        ) {
            val clarify = ChatMessage(
                id = "msg_clarify_${System.currentTimeMillis()}",
                role = "assistant",
                content = "Which device should I switch? I heard an on/off command but no device " +
                    "(flashlight, Wi-Fi, Bluetooth…).",
                provider = "CLARIFY",
                badge = "❓ Clarification"
            )
            updateToIdle {
                it.copy(
                    chatMessages = it.chatMessages + clarify,
                    statusMessage = "Clarification needed",
                    lastRoutingReason = "Ambiguous device toggle — asked instead of guessing"
                )
            }
            finishConversationTask(taskId, "NEEDS_CLARIFICATION", "Device command needs a target")
            return@launch
        }

        if (intent != AniobIntent.DEVICE_AUTOMATION) {
            _uiState.update {
                it.copy(
                    statusMessage = "Answering query...",
                    isStreaming = true,
                    streamingBubbleText = "",
                    lastProviderUsed = "EXTERNAL_AI"
                )
            }
            val chatId = submittedChatId
            val history = runCatching {
                conversationStore.historyForLlm(chatId, maxTurns = com.aniob.core.external.ChatTurn.MAX_TURNS)
            }.getOrElse { emptyList() }
            // Do not initialize native memory for an explicitly cloud-only request.
            if (_uiState.value.autoRouterMode != "cloud-only") refreshLocalEngine()
            val trigger = AniobExternalAiTrigger(
                // Keyed to the conversation, not the task, so the gateway sees one session per chat.
                cloudProvider = if (isCloudConfigured()) getCloudProvider(sessionId = chatId) else null,
                localProvider = localLlmProvider,
                sharedKnowledgeStore = com.aniob.core.memory.ConversationKnowledgeStore(sharedKnowledgeStore, chatId)
            )
            val powerState = AniobDeviceTelemetry.getRealState(app)
            val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            val autoMode = prefs.getString("autorouter_mode", "auto") ?: "auto"

            val result = trigger.query(
                prompt = trimmed,
                intent = intent,
                powerState = powerState,
                autoMode = autoMode,
                history = history,
                onDelta = { delta ->
                    if (generation == activeGeneration && chatId == conversationId) {
                        _uiState.update { it.copy(streamingBubbleText = it.streamingBubbleText + delta, isStreaming = true) }
                    }
                }
            )
            if (generation != activeGeneration || chatId != conversationId || !currentCoroutineContext().isActive) return@launch
            val answered = result is com.aniob.core.external.ExternalAiResult.Success
            val assistantMessage = ChatMessage(
                id = "msg_asst_${java.util.UUID.randomUUID()}",
                role = "assistant",
                content = if (result is com.aniob.core.external.ExternalAiResult.Error && result.provider != "LOCAL_SLM")
                    "I couldn't get an answer. Check your connection or cloud settings, then try again."
                else result.answer,
                badge = if (answered) "💬 AI Answer" else "Answer unavailable",
                provider = result.provider,
                retryPrompt = if (answered) null else trimmed
            )
            updateToIdle {
                it.copy(
                    isStreaming = false,
                    streamingBubbleText = "",
                    statusMessage = if (answered) "Ready" else "Could not answer — try again",
                    lastRoutingReason = if (answered) "Answered via ${result.provider}" else "Answer unavailable",
                    chatMessages = it.chatMessages + assistantMessage,
                    lastProviderUsed = result.provider
                )
            }
            if (answered && chatId == conversationId) {
                runCatching { conversationStore.appendExchange(chatId, trimmed, result.answer, result.provider, taskId) }
                    .onFailure { app.eventLogger.warn("AniobViewModel", "Could not save chat turn: ${it.message}") }
            }

            // Record Session Telemetry for analytics and audit
            val tokenEstimate = if (result.provider == "VAULT" || result.provider == "NO_PROVIDER") 0
                else (result.answer.length / 4).coerceAtLeast(1)
            app.metricsCollector.recordSession(
                taskId = taskId,
                prompt = trimmed,
                isSuccess = result is com.aniob.core.external.ExternalAiResult.Success,
                steps = 0,
                durationMs = result.latencyMs,
                tokensUsed = tokenEstimate,
                providerUsed = result.provider,
                // BUG-01: this metric records ANSWER DELIVERY, never a device change — say so.
                decisionReason = "Conversational answer (asserts no device change) for ${intent.name}"
            )
            recordSealedOutcome(
                taskId,
                if (answered) SealedOutcome.answered("Answered via ${result.provider}")
                else SealedOutcome.failed("Answer unavailable")
            )
            return@launch
        }

        // Step 0.5 - 1.5: understanding echo, feasibility, Grill-Me and the Doctor preflight.
        // The fast lane skips all four; every other task runs them exactly as before.
        val admitted = AniobFastLane.admit(lane) {
            runPreExecutionGates(task, effectiveTask, effectivePrompt, resolvedShortcut)
        }
        if (!admitted) {
            finishConversationTask(taskId, "WAITING", "Pre-execution gate did not admit execution; inspect recorded outcome")
            return@launch
        }

        // Task is self-contained -> Proceed directly. The finally closes the research run when
        // the pipeline is cancelled (Stop) or throws before its own endTask call.
        try {
            executeTaskPipeline(effectiveTask, generation, lane, resolvedShortcut)
        } finally {
            publishResearchRun(
                app.researchTelemetry.endTaskIfActive(
                    failureReason = if (_uiState.value.isRunning) "interrupted" else "stopped",
                    runId = effectiveTask.id
                )
            )
        }
    }
    activeTaskJob?.invokeOnCompletion { failure ->
        if (failure != null) finishConversationTask(taskId,
            if (failure is kotlinx.coroutines.CancellationException) "STOPPED" else "FAILED",
            if (failure is kotlinx.coroutines.CancellationException) "Task cancelled" else "Task execution raised ${failure.javaClass.simpleName}")
    }
}
