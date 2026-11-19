package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.research.ResearchOutcome
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.core.domain.AniobTask
import com.aniob.core.domain.TaskContext
import com.aniob.core.domain.TaskStatus
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import com.aniob.core.run.TaskContinuation
import com.aniob.core.run.TaskInput
import com.aniob.core.run.TaskInputChannel
import com.aniob.core.run.TaskInputKind
import com.aniob.core.run.TaskWaitState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * PART 4 / PART 5 — one task, many turns of user input.
 *
 * Before this file, `submitTask()` dropped every prompt that arrived while `isRunning` was true and
 * a clarification answer (`onGrillAnswersSubmitted`) tore the run down and executed the whole
 * pipeline again from step 0. Both are wrong for a task that is still doing the work: the user's
 * words must reach the RUNNING task, and the task must keep its id, its goal, its verified actions
 * and its observation.
 *
 * The mechanism here is deliberately small:
 *  - `TaskInputChannel` (core-agent) orders the user's inputs and remembers which sequence number
 *    has already been folded into the task context, so nothing is applied twice;
 *  - a model request runs as a cancellable child (`awaitModelRequest`) so a newer instruction
 *    cancels THAT request only — a dispatched action is never interrupted, because an instruction
 *    is otherwise only consumed at a loop boundary;
 *  - a run that asks the user is PARKED (its `PipelineRun` is kept) and resumed from the same step.
 *
 * What is intentionally NOT here: any bypass of permissions, approvals or verification. Parking
 * hands control back to the user; it does not skip a gate.
 */

/** Result of a model call that a newer user instruction may supersede. */
internal sealed class ModelRequestResult<out T> {
    /** The request finished and its answer is still the newest input. */
    data class Ready<T>(val value: T) : ModelRequestResult<T>()

    /**
     * The user said something newer while the request was in flight. The reply is discarded: a
     * late model response must never override a newer instruction.
     */
    data object Superseded : ModelRequestResult<Nothing>()
}

// ---------------------------------------------------------------------------------------------- //
// Channel lifecycle
// ---------------------------------------------------------------------------------------------- //

/** Starts the input channel of a run and publishes it so `submitTask` can route new instructions. */
internal fun AniobViewModel.openTaskInputChannel(taskId: String): TaskInputChannel {
    val channel = TaskInputChannel(taskId)
    taskInputs = channel
    inputSignal = CompletableDeferred()
    taskWaitState = TaskWaitState.NONE
    return channel
}

/**
 * The run is over (finished, stopped or superseded): forget its channel so nothing is delivered to
 * a task that can no longer consume it. A late instruction is then rejected by `submitTask` instead
 * of being silently appended to a dead context.
 */
internal fun AniobViewModel.closeTaskInputChannel(taskId: String? = null) {
    val channel = taskInputs
    if (taskId != null && channel != null && channel.taskId != taskId) return
    channel?.clear()
    taskInputs = null
    inputSignal = null
    taskWaitState = TaskWaitState.NONE
    if (taskId == null || activePipeline?.task?.id == taskId) activePipeline = null
}

/** Wakes the current model request (if any) because newer input arrived. */
internal fun AniobViewModel.signalNewInput() {
    inputSignal?.complete(Unit)
    inputSignal = CompletableDeferred()
}

// ---------------------------------------------------------------------------------------------- //
// Running a model request that newer input can interrupt
// ---------------------------------------------------------------------------------------------- //

/**
 * Runs one model request as a cancellable child.
 *
 * The rest of the step (dispatching the planned action, verifying it) is NOT cancellable by this
 * path: only the model call is. That keeps a half-dispatched action from being abandoned and keeps
 * already verified work intact.
 *
 * @param block the request to run (ladder/LLM call).
 * @return [ModelRequestResult.Ready] with the answer, or [ModelRequestResult.Superseded] when input
 *         arrived (or was already waiting) after the request started.
 */
internal suspend fun <T> AniobViewModel.awaitModelRequest(block: suspend () -> T): ModelRequestResult<T> {
    val channel = taskInputs ?: return ModelRequestResult.Ready(block())
    val requestSeq = channel.latestSeq()
    val signal = inputSignal ?: CompletableDeferred<Unit>().also { inputSignal = it }
    return coroutineScope {
        val work = async { runCatching { block() } }
        val newer = async { signal.await() }
        val outcome = select<ModelRequestResult<T>> {
            work.onAwait { done -> ModelRequestResult.Ready(done.getOrThrow()) }
            newer.onAwait { ModelRequestResult.Superseded }
        }
        work.cancel()
        newer.cancel()
        // Input that arrived before the request even started still wins: ordering, not luck.
        if (outcome is ModelRequestResult.Ready && channel.isStaleResponse(requestSeq)) {
            ModelRequestResult.Superseded
        } else {
            outcome
        }
    }
}

// ---------------------------------------------------------------------------------------------- //
// Delivering an instruction to the running task
// ---------------------------------------------------------------------------------------------- //

/**
 * Routes a prompt typed while a task is running.
 *
 * @return true when the text was accepted by the running task (appended verbatim, ordering kept,
 *         model request interrupted); false when no channel belongs to the active task, in which
 *         case the caller keeps its normal rejection path.
 */
internal fun AniobViewModel.deliverTaskInput(prompt: String): Boolean {
    val task = _uiState.value.activeTask ?: return false
    val channel = taskInputs ?: return false
    if (channel.taskId != task.id) return false

    // An explicit cancel is not an instruction: it ends the task and must not wait behind the
    // step the user is trying to stop.
    if (TaskContinuation.isCancellation(prompt)) {
        channel.post(TaskInputKind.CANCELLATION, prompt)
        stopCurrentTask()
        return true
    }

    val input = channel.post(TaskInputKind.INSTRUCTION, prompt) ?: return false
    if (_uiState.value.isPaused) {
        pauseRequested = false
        com.aniob.app.background.AniobBackgroundController.onTaskPaused(false)
    }
    val message = ChatMessage(
        id = "msg_user_${java.util.UUID.randomUUID()}",
        role = "user",
        content = prompt
    )
    _uiState.update {
        it.copy(
            isPaused = false,
            chatMessages = it.chatMessages + message,
            pendingInstructionCount = if (channel.hasPendingInstruction()) 1 else 0,
            statusMessage = "Adding to the running task: \"${input.text}\""
        )
    }
    app.eventLogger.info("AniobViewModel", "Instruction #${input.seq} appended to ${task.id}")
    // Cancels the outstanding model request only. A dispatched action keeps running and is
    // verified by the step that dispatched it.
    signalNewInput()
    return true
}

/**
 * Folds pending instructions into the run's task context (verbatim, additive, once).
 *
 * Called at the two loop boundaries where the run is about to plan again: observation (before the
 * model sees the screen) and the act boundary (before a fresh action is planned). Applying it there
 * — instead of mid-flight — is what preserves ordering: a newer instruction is processed before any
 * later plan, and an instruction that arrived after a plan was made is applied to the next one.
 *
 * @return how many instructions were added (0 when nothing was pending).
 */
internal fun AniobViewModel.drainInstructionsInto(pipe: PipelineRun): Int {
    val channel = taskInputs ?: return 0
    if (channel.taskId != pipe.task.id) return 0
    val taken = channel.takeInstructions()
    if (taken.isEmpty()) return 0

    // Goal, criteria, verified actions and history are kept; only the instruction list grows.
    pipe.taskContext = TaskContinuation.applyToContext(pipe.taskContext, taken)
    val notice = TaskContinuation.continuationNotice(taken)
    app.eventLogger.info("AniobViewModel", notice)
    _uiState.update {
        it.copy(
            statusMessage = notice,
            pendingInstructionCount = if (channel.hasPendingInstruction()) 1 else 0
        )
    }
    return taken.size
}

/** Verified actions of the live task, as short text, for the recovery marker. */
internal fun AniobViewModel.verifiedSoFarActions(): List<String> = verifiedActions.map { it.describeAction() }

/** True when the user's words are still waiting to be folded into the task. */
internal fun AniobViewModel.hasPendingInstruction(): Boolean =
    taskInputs?.hasPendingInstruction() == true

/**
 * A turn may only close the task when every instruction the user typed has been processed. The
 * closing phases call this first, so a final response can never swallow a newer instruction.
 */
internal fun AniobViewModel.mayCloseTask(): Boolean = TaskContinuation.mayClose(taskInputs)

// ---------------------------------------------------------------------------------------------- //
// Parking and resuming (PART 5)
// ---------------------------------------------------------------------------------------------- //

/**
 * The step loop stopped because the user must act: a question, a confirmation or a manual handover.
 *
 * The run is NOT closed. Its task context, step index, verified actions, last observation and
 * provider lock all stay alive; only the coroutine returns. [TaskWaitState] says which kind of wait
 * this is so the UI can distinguish "answer me" from "do it on your phone and tell me when done".
 */
internal fun AniobViewModel.parkTask(pipe: PipelineRun, wait: TaskWaitState, reason: String) {
    pipe.parked = true
    taskWaitState = wait
    _uiState.update {
        it.copy(
            isRunning = false,
            isPaused = false,
            waitingForUser = wait != TaskWaitState.NONE,
            statusMessage = reason,
            pendingInstructionCount = if (hasPendingInstruction()) 1 else 0
        )
    }
    setPhase(AgentPhase.ASKING, ThinkSource.RULES, reason)
    // A parked task is not running, so the marker must say so: if the process dies now, the
    // recovery card has to report "it was waiting for you", not "it was working".
    persistRunningMarker(pipe.task, pipe.currentStep, "waiting for you: $reason", verifiedSoFarActions())
    app.eventLogger.info("AniobViewModel", "Task ${pipe.task.id} parked ($wait): $reason")
}

/**
 * Resumes the parked run of [task] from the same step, after the user answered or acted.
 *
 * @return true when a parked run was resumed; false when there is nothing to resume (the caller
 *         then falls back to its normal path, e.g. a brand-new prompt after the run really ended).
 */
internal fun AniobViewModel.resumeParkedTask(task: AniobTask, answers: Map<String, String>): Boolean {
    val pipe = prepareResume(task, answers) ?: return false
    activeTaskJob = viewModelScope.launch(Dispatchers.Default) { resumeParkedRun(pipe) }
    return true
}

/**
 * Everything a resume does except running it: fold the answers in, keep the run alive, restore the
 * live UI. Split out so the "same task, same progress, no replay" guarantee can be tested without a
 * device, and so the coroutine hand-off stays one line.
 *
 * @return the parked run to resume, or null when there is nothing to resume.
 */
internal fun AniobViewModel.prepareResume(task: AniobTask, answers: Map<String, String>): PipelineRun? {
    val pipe = activePipeline?.takeIf { it.task.id == task.id && it.parked } ?: return null
    if (pipe.taskGeneration != activeGeneration) {
        // Another task owns the device now; this run is stale and must not touch the live UI.
        closeTaskInputChannel(task.id)
        return null
    }

    // The answer joins the SAME context: goal, verified actions, condensed history and the last
    // screen stay exactly as they were. Nothing is replayed.
    pipe.taskContext = TaskContinuation.applyAnswers(pipe.taskContext, answers)
    pipe.task = pipe.task.copy(
        grillAnswers = pipe.task.grillAnswers + answers,
        status = com.aniob.core.domain.TaskStatus.RUNNING
    )
    pipe.parked = false
    pipe.resumedFromStep = pipe.currentStep
    taskWaitState = TaskWaitState.NONE
    _uiState.update { state ->
        state.copy(
            isRunning = true,
            isPaused = false,
            waitingForUser = false,
            activeTask = task.copy(status = com.aniob.core.domain.TaskStatus.RUNNING),
            grillMeResult = null,
            showGrillMeSheet = false,
            statusMessage = "Continuing ${task.rawPrompt.take(60)} from what is on screen…",
            currentStep = pipe.currentStep,
            pendingInstructionCount = if (hasPendingInstruction()) 1 else 0
        )
    }
    setPhase(AgentPhase.PLANNING, ThinkSource.RULES, "Resumed at step ${pipe.currentStep}")
    app.eventLogger.info(
        "AniobViewModel",
        "Resumed task ${task.id} at step ${pipe.currentStep}, answers=${answers.keys}"
    )
    return pipe
}

/** Body of a resumed run: same loop, same run object. */
private suspend fun AniobViewModel.resumeParkedRun(pipe: PipelineRun) {
    AniobAccessibilityService.isTaskActive = true
    var parkedAgain = false
    try {
        val finished = runPipelineSteps(pipe)
        parkedAgain = pipe.parked
        if (!finished || parkedAgain) return
        if (pipe.taskGeneration != activeGeneration) {
            app.researchTelemetry.endTask(ResearchOutcome.INTERRUPTED, "superseded", runId = pipe.task.id)
            return
        }
        pipelineComplete(pipe)
    } finally {
        if (!parkedAgain) {
            publishResearchRun(
                app.researchTelemetry.endTaskIfActive(
                    failureReason = if (_uiState.value.isRunning) "interrupted" else "stopped",
                    runId = pipe.task.id
                )
            )
        }
    }
}

/**
 * Task text of a parked run, for the clarification sheet: the sheet must show the ORIGINAL goal and
 * not a rewritten prompt, so the user can tell which task they are answering.
 */
internal fun AniobViewModel.parkedRunFor(taskId: String): PipelineRun? =
    activePipeline?.takeIf { it.task.id == taskId && it.parked }

/** The context a parked run keeps, exposed for the completion text and for tests. */
internal fun AniobViewModel.parkedContext(taskId: String): TaskContext? = parkedRunFor(taskId)?.taskContext

// ---------------------------------------------------------------------------------------------- //
// Resuming an incomplete / delegated / stopped / paused task on "Proceed" / "Continue" / follow-up
// ---------------------------------------------------------------------------------------------- //

internal fun AniobViewModel.shouldContinueResumableTask(prompt: String): Boolean {
    val resumable = lastResumablePipeline ?: return false
    if (resumable.task.status == TaskStatus.SUCCESS) return false
    return TaskContinuation.isFollowUpInstruction(prompt)
}

internal fun AniobViewModel.prepareResumableContinuation(prompt: String): PipelineRun? {
    val pipe = lastResumablePipeline ?: return null
    val trimmed = prompt.trim()
    if (trimmed.isBlank()) return null

    lastResumablePipeline = null
    val generation = ++activeGeneration
    openTaskInputChannel(pipe.task.id)
    activePipeline = pipe
    pauseRequested = false
    terminationStage.reset()

    pipe.taskGeneration = generation
    pipe.finalOutcome = null
    pipe.terminalReason = ""
    pipe.completedByIntent = false
    pipe.intentVerdict = null
    pipe.stepResult = null
    pipe.fastLaunchProof = null
    pipe.screenBefore = null
    pipe.parked = false
    pipe.resumedFromStep = pipe.currentStep
    if (pipe.currentStep >= pipe.maxSteps) {
        pipe.maxSteps = pipe.currentStep + agentLimits.maxStepsPerTask
    }

    val followUpInput = TaskInput(
        seq = 0L,
        kind = TaskInputKind.INSTRUCTION,
        text = trimmed,
        receivedAt = System.currentTimeMillis()
    )
    pipe.taskContext = TaskContinuation.applyToContext(
        pipe.taskContext.copy(consecutiveFailures = 0),
        listOf(followUpInput)
    )
    pipe.task = pipe.task.copy(
        status = TaskStatus.RUNNING,
        finishedAt = null
    )

    val userMessage = ChatMessage(
        id = "msg_user_${java.util.UUID.randomUUID()}",
        role = "user",
        content = trimmed
    )
    _uiState.update { state ->
        state.copy(
            isRunning = true,
            isPaused = false,
            waitingForUser = false,
            activeTask = pipe.task,
            grillMeResult = null,
            showGrillMeSheet = false,
            chatMessages = state.chatMessages + userMessage,
            statusMessage = "Continuing ${pipe.task.rawPrompt.take(60)}...",
            currentStep = pipe.currentStep,
            pendingInstructionCount = 0
        )
    }
    setPhase(AgentPhase.PLANNING, ThinkSource.RULES, "Continuing task from step ${pipe.currentStep}")
    app.eventLogger.info(
        "AniobViewModel",
        "Resuming incomplete task ${pipe.task.id} from step ${pipe.currentStep} with input: $trimmed"
    )
    return pipe
}

internal fun AniobViewModel.resumeResumableTask(prompt: String): Boolean {
    val pipe = prepareResumableContinuation(prompt) ?: return false
    val leaseGen = app.taskOwner.acquireLease(pipe.task.id)
    activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
        app.taskOwner.attachJob(leaseGen, currentCoroutineContext()[kotlinx.coroutines.Job]!!)
        resumeParkedRun(pipe)
    }
    return true
}
