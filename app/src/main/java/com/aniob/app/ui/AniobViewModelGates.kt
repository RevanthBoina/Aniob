package com.aniob.app.ui

import com.aniob.core.domain.*
import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.Outcome
import com.aniob.core.execution.StepPipeline
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.feasibility.AniobFeasibility
import com.aniob.core.feasibility.Feasibility
import com.aniob.core.narration.AgentPhase
import com.aniob.core.outcome.RunOutcome
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.understander.AniobPromptUnderstanding
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.ResolvedIntentShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The pre-execution gates of a standard-lane task, in their original order: understanding echo,
 * plan feasibility, Grill-Me clarification, Doctor preflight.
 *
 * @return false when a gate ended or parked the task (not started, or waiting for an answer).
 */
internal suspend fun AniobViewModel.runPreExecutionGates(
    task: AniobTask,
    effectiveTask: AniobTask,
    effectivePrompt: String,
    resolvedShortcut: ResolvedIntentShortcut?
): Boolean {
    // Step 0.5 (audit): say what was understood, then check the plan is feasible BEFORE anything is dispatched.
    setPhase(AgentPhase.UNDERSTANDING)
    val understood = AniobPromptUnderstanding.understand(effectivePrompt)
    if (understood.needsEcho) {
        val echo = ChatMessage(
            id = "msg_echo_${System.currentTimeMillis()}",
            role = "assistant",
            content = understood.echo,
            provider = "UNDERSTANDING",
            badge = "\uD83D\uDC42 Understood"
        )
        _uiState.update { it.copy(chatMessages = it.chatMessages + echo) }
    }
    when (val feasibility = AniobFeasibility.assess(planStepsFor(effectivePrompt), capabilityEnvelope())) {
        is Feasibility.Infeasible -> {
            val hint = feasibility.enable?.let { " To fix: $it." }.orEmpty()
            recordSealedOutcome(task.id, SealedOutcome.failed(feasibility.reason + hint))
            val note = ChatMessage(
                id = "msg_infeasible_${System.currentTimeMillis()}",
                role = "assistant",
                content = feasibility.reason + hint,
                provider = "FEASIBILITY",
                badge = "\u26D4 Not started"
            )
            setPhase(AgentPhase.IDLE)
            updateToIdle { it.copy(chatMessages = it.chatMessages + note, statusMessage = "Not started") }
            return false
        }
        is Feasibility.DelegateToUser -> {
            questionsBeforeStart.merge(task.id, 1, Int::plus)
            setPhase(AgentPhase.ASKING)
            _uiState.update {
                it.copy(
                    grillMeResult = AniobGrillMeResult(needsClarification = true, questions = listOf(feasibility.question)),
                    showGrillMeSheet = true,
                    statusMessage = feasibility.whatUserMustDo
                )
            }
            AniobForegroundService.notifyGrillQuestion(app, task.id, feasibility.question, effectivePrompt)
            return false
        }
        Feasibility.Feasible -> Unit
    }

    // Step 1: Grill-Me Check with remembered vault preferences
    val grillResult = grillMeEngine.evaluateTask(effectivePrompt, sharedKnowledgeStore.getAll())
    if (grillResult.needsClarification && grillResult.questions.isNotEmpty()) {
        questionsBeforeStart.merge(task.id, grillResult.questions.size, Int::plus)
        setPhase(AgentPhase.ASKING)
        _uiState.update {
            it.copy(
                grillMeResult = grillResult,
                showGrillMeSheet = true,
                statusMessage = "Awaiting clarification for '$effectivePrompt'"
            )
        }
        grillResult.questions.firstOrNull()?.let {
            AniobForegroundService.notifyGrillQuestion(app, task.id, it, effectivePrompt)
        }
        return false
    }

    // Step 1.5: Pre-flight Doctor Check
    if (!runDoctorPreflight(effectiveTask, effectivePrompt, resolvedShortcut)) {
        return false
    }
    return true
}

internal fun AniobViewModel.isLaunchablePackage(packageName: String): Boolean =
    runCatching { app.packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

internal fun AniobViewModel.checkDoctorPreflight(prompt: String): com.aniob.core.tools.DoctorResult =
    checkDoctorPreflight(prompt, AniobIntentResolver.resolve(prompt, { isLaunchablePackage(it) }))

/**
 * @param resolvedShortcut the shortcut for [prompt], when the caller already resolved it.
 *
 * An **app** shortcut is not an "offline shortcut": it still needs the accessibility service to
 * observe the launch, so it must not be exempt from that preflight check. Only a reflex whose
 * dispatch *is* the outcome (torch, battery, dial, open URL) may skip it.
 */
internal fun AniobViewModel.checkDoctorPreflight(
    prompt: String,
    resolvedShortcut: ResolvedIntentShortcut?
): com.aniob.core.tools.DoctorResult {
    return com.aniob.core.tools.AniobDoctor.preflightCheck(
        taskPrompt = prompt,
        targetPackage = resolvedShortcut?.targetPackage,
        isAccessibilityConnected = AniobAccessibilityService.isServiceConnected,
        modelsDir = modelDownloader.getModelsDir(),
        installedModelId = modelDownloader.getDefaultModelId(),
        isNetworkAvailable = AniobDeviceTelemetry.getRealState(app).isNetworkAvailable,
        autoRouterMode = _uiState.value.autoRouterMode,
        totalRamGb = AniobDeviceTelemetry.getRealState(app).totalRamGb,
        isAppInstalled = { pkg -> app.packageManager.getLaunchIntentForPackage(pkg) != null },
        isOfflineShortcut = resolvedShortcut?.completesOnDispatch == true
    )
}

internal fun AniobViewModel.runDoctorPreflight(task: AniobTask, prompt: String, resolvedShortcut: ResolvedIntentShortcut?): Boolean {
    // Doctor is advisory: it describes unavailable routes but never blocks supported direct
    // intents, another model provider, or the ordinary StepPipeline.
    val doctorResult = checkDoctorPreflight(prompt, resolvedShortcut)
    val failures = doctorResult.checks.filterNot { it.passed }
    if (failures.isEmpty()) return true

    val capabilityNotes = failures.mapNotNull { check ->
        when (check.name) {
            "accessibility_connected" ->
                "Screen automation is unavailable because Aniob Accessibility is off. Supported direct intents may still work."
            "target_app_installed" ->
                "The requested app is not installed or cannot be launched. I won't substitute a different app."
            "model_file_exists" ->
                "The selected on-device model is unavailable. Aniob can still try supported direct capabilities or a configured cloud route."
            "network_available" ->
                "You're offline, so cloud-only routes may not work. Supported on-device capabilities can still run."
            "ram_headroom" ->
                "The selected on-device model may exceed this device's available memory."
            else -> check.reason.takeIf { it.isNotBlank() }
        }
    }.distinct()
    if (capabilityNotes.isNotEmpty()) {
        val note = ChatMessage(
            id = "msg_doctor_advisory_${System.currentTimeMillis()}",
            role = "assistant",
            content = "Capability note: ${capabilityNotes.joinToString(" ")}",
            badge = "Capability note",
            provider = "DOCTOR",
            retryPrompt = prompt
        )
        _uiState.update { it.copy(chatMessages = it.chatMessages + note) }
    }
    app.eventLogger.info(
        "AniobViewModel",
        "Doctor advisory for task ${task.id}: ${failures.joinToString { it.name }}"
    )
    return true
}

internal fun AniobViewModel.resolveGrillAnswerFromNotification(
    taskId: String,
    questionId: String,
    answer: String,
    requireOption: Boolean
) {
    val state = _uiState.value
    val currentTask = state.activeTask
    if (currentTask?.id != taskId || !state.showGrillMeSheet) {
        app.eventLogger.warn("AniobViewModel", "Ignored stale Grill notification answer for $taskId")
        AniobForegroundService.clearGrillQuestion(app)
        return
    }
    val question = state.grillMeResult?.questions?.firstOrNull { it.id == questionId }
    if (question == null) {
        app.eventLogger.warn("AniobViewModel", "Ignored Grill answer for stale question $questionId")
        AniobForegroundService.clearGrillQuestion(app)
        return
    }
    val allowed = if (requireOption) answer in question.options
    else question.allowFreeText || answer in question.options
    if (!allowed) {
        app.eventLogger.warn("AniobViewModel", "Rejected unoffered Grill quick choice for $questionId")
        AniobForegroundService.notifyGrillQuestion(
            app, taskId, question, currentTask.clarifiedGoal.ifBlank { currentTask.rawPrompt }
        )
        return
    }

    val answers = notificationGrillAnswers.getOrPut(taskId) { linkedMapOf() }
    answers[questionId] = answer.trim()
    val nextQuestion = state.grillMeResult?.questions
        ?.firstOrNull { answers[it.id].isNullOrBlank() }
    if (nextQuestion != null) {
        AniobForegroundService.notifyGrillQuestion(
            app, taskId, nextQuestion, currentTask.clarifiedGoal.ifBlank { currentTask.rawPrompt }
        )
        return
    }

    val completedAnswers = answers.toMap()
    notificationGrillAnswers.remove(taskId)
    AniobForegroundService.clearGrillQuestion(app)
    onGrillAnswersSubmitted(completedAnswers)
}

fun AniobViewModel.onGrillAnswersSubmitted(answers: Map<String, String>, rememberKeys: Set<String> = emptySet()) {
    val currentTask = _uiState.value.activeTask ?: return
    notificationGrillAnswers.remove(currentTask.id)
    AniobForegroundService.clearGrillQuestion(app)
    if (answers.containsKey("target_miss")) {
        pendingTargetClarificationKey?.takeIf { it.first == currentTask.id }?.let { (_, key) ->
            targetMissLedger.recordSuccess(currentTask.id, key)
        }
        pendingTargetClarificationKey = null
    }
    // Plan-time feasibility hand-off ("I can't tap the system dialog for you"): the choice is not a task slot.
    answers.entries.firstOrNull { it.key.startsWith("delegate.") }?.let { (_, choice) ->
        when (choice) {
            AniobFeasibility.OPT_CANCEL -> {
                recordSealedOutcome(currentTask.id, SealedOutcome.stopped("Cancelled at the hand-off"))
                cancelGrillMe()
                return
            }
            AniobFeasibility.OPT_WAIT -> {
                finishDelegated(currentTask, "I can set this up, but the last tap is yours. Tell me with \uD83D\uDC4D when it worked.")
                return
            }
            else -> Unit // OPT_SKIP: continue with the rest of the plan, without the gated step
        }
    }
    // Persist the answers the user ticked so the question is skipped next time (UX-2).
    rememberKeys.forEach { key ->
        answers[key]?.let { rememberGrillAnswer(key, it) }
    }
    val clarifiedPrompt = buildString {
        append(currentTask.clarifiedGoal.ifBlank { currentTask.rawPrompt })
        answers.forEach { (q, a) -> append(" [$q: $a]") }
    }
    val updatedTask = currentTask.copy(
        clarifiedGoal = clarifiedPrompt,
        grillAnswers = answers,
        status = TaskStatus.RUNNING
    )

    _uiState.update {
        it.copy(
            showGrillMeSheet = false,
            isRunning = true,
            activeTask = updatedTask,
            statusMessage = "Proceeding with clarified goal..."
        )
    }

    val gen = activeGeneration
    activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
        val resolvedShortcut = AniobIntentResolver.resolve(clarifiedPrompt, { isLaunchablePackage(it) })
        if (!runDoctorPreflight(updatedTask, clarifiedPrompt, resolvedShortcut)) {
            return@launch
        }
        try {
            executeTaskPipeline(updatedTask, gen)
        } finally {
            publishResearchRun(
                app.researchTelemetry.endTaskIfActive(
                    failureReason = if (_uiState.value.isRunning) "interrupted" else "stopped",
                    runId = updatedTask.id
                )
            )
        }
    }
}

/** Explicit cancel from the Grill sheet or its notification, not a silent dismiss. */
fun AniobViewModel.cancelGrillMe() {
    val currentTask = _uiState.value.activeTask
    notificationGrillAnswers.remove(currentTask?.id)
    pendingTargetClarificationKey = null
    AniobForegroundService.clearGrillQuestion(app)
    if (currentTask != null) {
        app.taskOwner.stop(currentTask.id)
        activeTaskJob?.cancel()
        activeTaskJob = null
        app.approvalController.clearForTask(currentTask.id)
        targetMissLedger.clear()
        val prior = _uiState.value.lastSealedOutcome
        val alreadyStopped = _uiState.value.lastSealedTaskId == currentTask.id &&
            prior?.outcome == com.aniob.core.outcome.RunOutcome.STOPPED
        val reason = "Stopped while waiting for clarification. Earlier changes were not undone."
        if (!alreadyStopped) recordSealedOutcome(currentTask.id, SealedOutcome.stopped(reason))
        AniobBackgroundController.onTaskFinished(
            app,
            reason,
            isSuccess = false,
            openChat = false,
            wasStopped = true,
            resultTitle = com.aniob.app.outcome.AniobOutcomeCopy
                .forTask(Outcome.STOPPED, reason).title
        )
    }
    updateToIdle {
        it.copy(
            showGrillMeSheet = false,
            isStreaming = false,
            streamingBubbleText = "",
            statusMessage = "Task stopped. Earlier changes were not undone."
        )
    }
}

fun AniobViewModel.dismissGrillMe() = cancelGrillMe()
