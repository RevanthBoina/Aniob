package com.aniob.core.run

import com.aniob.core.domain.TaskContext

/**
 * What the user just said to a **running** task.
 *
 * The distinction matters more than the payload: an [INSTRUCTION] ("and only the unread ones")
 * continues the same task with its verified progress intact, while [CANCELLATION] ends it. Treating
 * them as one thing is how "stop" used to be queued behind the step it was meant to interrupt.
 */
enum class TaskInputKind {
    /** More work for the same task: append it, do not restart and do not replay. */
    INSTRUCTION,

    /** An answer to a question the task asked (clarification / account choice / target miss). */
    CLARIFICATION_ANSWER,

    /** A decision on a pending confirmation. */
    CONFIRMATION,

    /** The user says they performed the manual step the task handed over. */
    HANDOVER_ACK,

    /** Explicit cancellation ("stop", "never mind"). */
    CANCELLATION
}

/**
 * One ordered item of user input, identified for the whole life of the task.
 *
 * [seq] is monotonic per task and is the ordering authority: a model reply that was requested
 * before [seq] must never overwrite what the user said at [seq] (see
 * [TaskInputChannel.isStaleResponse]).
 */
data class TaskInput(
    val seq: Long,
    val kind: TaskInputKind,
    /** The user's own words, verbatim. Never rewritten, never summarised. */
    val text: String,
    val receivedAt: Long
)

/**
 * Why a task is not currently executing. Distinct states are required because the correct next
 * action differs: a question can be resumed from a stored answer, a confirmation needs a decision,
 * a manual takeover needs the user, and an interrupted process needs an honest report first.
 */
enum class TaskWaitState {
    /** Executing (or about to). */
    NONE,

    /** Waiting for an answer to a question the task itself asked. */
    USER_INPUT,

    /** Waiting for a yes/no on a dispatched-but-gated action. */
    CONFIRMATION,

    /** An authentication / verification step that only the user can perform. */
    MANUAL_TAKEOVER,

    /** The process died (or the dispatch outcome is unknown). Never auto-resumed. */
    INTERRUPTED
}

/**
 * The task-scoped input channel: one owner, one order, one task id.
 *
 * The channel is what makes same-task continuation possible instead of "reject the prompt while a
 * task is running". It is deliberately tiny and pure:
 *
 *  - [post] is the only way in, and it stamps a monotonic [TaskInput.seq];
 *  - [instructions] returns the pending *instructions* in order, so the executor can append them
 *    and drop them atomically ([TaskContinuation.applyToContext] does both);
 *  - [hasPendingInstruction] is consulted by the closing phase, so a final model answer cannot end
 *    the task while the user is still waiting to be heard;
 *  - [latestSeq] / [isStaleResponse] protect ordering: a reply requested before a newer user input
 *    is stale by definition, even if it arrives after it.
 *
 * Not thread-safe by design decision? No — it IS synchronised, because the user's submission runs
 * on the main thread while the pipeline runs on a worker, and both touch it.
 */
class TaskInputChannel(
    val taskId: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val inputs = ArrayDeque<TaskInput>()
    private var nextSeq: Long = 1L

    @Synchronized
    fun post(kind: TaskInputKind, text: String): TaskInput? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val input = TaskInput(seq = nextSeq++, kind = kind, text = body, receivedAt = now())
        inputs.addLast(input)
        return input
    }

    @Synchronized
    fun latestSeq(): Long = nextSeq - 1L

    @Synchronized
    fun pending(): List<TaskInput> = inputs.toList()

    @Synchronized
    fun hasPendingInstruction(): Boolean = inputs.any { it.kind == TaskInputKind.INSTRUCTION }

    @Synchronized
    fun hasPending(): Boolean = inputs.isNotEmpty()

    /** Pending instructions, oldest first, **without** removing them. */
    @Synchronized
    fun pendingInstructions(): List<TaskInput> =
        inputs.filter { it.kind == TaskInputKind.INSTRUCTION }

    /**
     * Takes every pending instruction in order and removes it from the queue, so an instruction is
     * appended to the task context exactly once — the guarantee that keeps a continuation from
     * replaying work.
     */
    @Synchronized
    fun takeInstructions(): List<TaskInput> {
        val taken = inputs.filter { it.kind == TaskInputKind.INSTRUCTION }
        if (taken.isEmpty()) return emptyList()
        inputs.removeAll { it.kind == TaskInputKind.INSTRUCTION }
        return taken
    }

    /** Takes the newest pending non-instruction input (an answer, a confirmation, a handover ack). */
    @Synchronized
    fun takeLatestOf(kind: TaskInputKind): TaskInput? {
        val found = inputs.lastOrNull { it.kind == kind } ?: return null
        inputs.removeAll { it.seq == found.seq }
        return found
    }

    @Synchronized
    fun cancellationRequested(): Boolean = inputs.any { it.kind == TaskInputKind.CANCELLATION }

    @Synchronized
    fun takeCancellation(): TaskInput? = takeLatestOf(TaskInputKind.CANCELLATION)

    /** Clears the queue for a task that has closed; nothing may leak into the next run. */
    @Synchronized
    fun clear() = inputs.clear()

    /**
     * True when a model reply requested at [requestSeq] has been overtaken by newer user input.
     *
     * The interrupter cancels the outstanding request when it can; this is the belt-and-braces
     * guard for the case where it completes anyway (a provider that ignores cancellation, a parse
     * that lands between two checks). A stale reply is discarded and the step is re-planned with
     * the new instruction — it must never be dispatched over the user's newer words.
     */
    @Synchronized
    fun isStaleResponse(requestSeq: Long): Boolean = latestSeq() > requestSeq
}

/**
 * The pure rules of same-task continuation.
 *
 * Everything here is deliberately free of coroutines and Android so the invariants are unit-tested
 * without a device: the app layer only decides *when* to call these, never *what* they mean.
 */
object TaskContinuation {

    /** "stop", "cancel", "never mind", "abort", "forget it" — explicit cancellation, not an edit. */
    private val cancellationPhrases = setOf(
        "stop", "stop it", "stop that", "cancel", "cancel it", "cancel that", "abort",
        "never mind", "nevermind", "forget it", "forget that", "quit", "wait stop"
    )

    private val continuationPhrases = setOf(
        "proceed", "continue", "resume", "go on", "go ahead", "keep going", "carry on",
        "continue task", "proceed with task", "resume task", "please proceed", "please continue",
        "ok proceed", "okay proceed", "ok continue", "okay continue", "yes proceed", "yes continue",
        "yes, proceed", "yes, continue", "done, proceed", "done, continue", "i did it",
        "done", "try again"
    )

    fun isCancellation(text: String): Boolean {
        val normalized = text.trim().lowercase().trimEnd('.', '!', '?', ' ')
        if (normalized in cancellationPhrases) return true
        return normalized == "stop the task" || normalized == "cancel the task" ||
            normalized == "stop current task"
    }

    /**
     * Recognizes explicit user continuation commands ("Proceed", "Continue", "Resume", etc.)
     * so they resume the active/recent task context instead of starting a disconnected chat query.
     */
    fun isContinuationCommand(text: String): Boolean {
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ' ')
        if (normalized in continuationPhrases) return true
        return normalized.startsWith("proceed ") || normalized.startsWith("continue ") ||
            normalized.startsWith("resume ") || normalized == "keep going" ||
            normalized.endsWith(" proceed") || normalized.endsWith(" continue")
    }

    /**
     * Recognizes likely follow-up instructions meant to refine or advance an incomplete task
     * (e.g. "also ...", "instead ...", "now tap ...", "try the second one").
     */
    fun isFollowUpInstruction(text: String): Boolean {
        if (isContinuationCommand(text)) return true
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        val prefixes = listOf(
            "and ", "also ", "then ", "now ", "instead ", "actually ", "try ",
            "select ", "tap ", "click ", "choose ", "pick ", "use ", "enter ", "type ",
            "scroll ", "after that", "next "
        )
        return prefixes.any { normalized.startsWith(it) }
    }

    /**
     * Appends [instructions] to the task context **in their original wording**, keeping:
     *
     *  - the original goal ([TaskContext.instruction] is never replaced — new instructions refine
     *    it, they do not restart it);
     *  - the verified trajectory and condensed history (progress is preserved);
     *  - the success criteria (a continuation must not quietly drop what was being proven).
     *
     * A rejected-finish counter is reset, because the user has just given the model new information;
     * holding an earlier rejection against it would fail the task on the next Finish.
     */
    fun applyToContext(ctx: TaskContext, instructions: List<TaskInput>): TaskContext {
        if (instructions.isEmpty()) return ctx
        val added = instructions.map { it.text }
        val existing = ctx.additionalInstructions
        val nonPureContinuations = added.filterNot { isContinuationCommand(it) && it.trim().split(' ').size <= 2 }
        val merged = (existing + nonPureContinuations).distinct()
        val historyEntries = added.map { "the user added an instruction: \"$it\"" }
        var updated = ctx.copy(
            additionalInstructions = merged,
            condensedHistory = ctx.condensedHistory + historyEntries,
            rejectedFinishCount = 0
        )
        added.forEach { text ->
            updated = updated.withConversationTurn(com.aniob.core.external.ChatTurn.USER, text)
        }
        return updated
    }

    /** An answer to a task question joins the goal the same way a Grill-Me answer does. */
    fun applyAnswer(ctx: TaskContext, answer: TaskInput, question: String?): TaskContext {
        val key = question?.takeIf { it.isNotBlank() } ?: "clarification"
        return ctx.copy(
            grillAnswers = ctx.grillAnswers + (key to answer.text),
            condensedHistory = ctx.condensedHistory + "the user answered \"$key\": \"${answer.text}\"",
            rejectedFinishCount = 0
        ).withConversationTurn(
            com.aniob.core.external.ChatTurn.USER,
            "Clarification ($key): ${answer.text}"
        )
    }

    /**
     * Folds a whole answered question sheet into the context.
     *
     * PART 5: the answer belongs to the SAME task, so the goal is untouched, each question keeps its
     * own id as the key, and the answered exchange is remembered in the condensed history. A blank
     * answer is not an answer and is dropped rather than stored as one.
     */
    fun applyAnswers(ctx: TaskContext, answers: Map<String, String>): TaskContext {
        val cleaned = answers.filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) return ctx
        var updated = ctx.copy(
            grillAnswers = ctx.grillAnswers + cleaned,
            condensedHistory = ctx.condensedHistory +
                cleaned.map { (question, answer) -> "the user answered \"$question\": \"$answer\"" },
            rejectedFinishCount = 0
        )
        cleaned.forEach { (question, answer) ->
            updated = updated.withConversationTurn(
                com.aniob.core.external.ChatTurn.USER,
                "Clarification ($question): $answer"
            )
        }
        return updated
    }

    /**
     * A task may only close when nothing the user said is still unprocessed.
     *
     * Requirement, verbatim: "a final response cannot close the task while an unprocessed user
     * instruction is pending". Answers and acknowledgements do not block a close — they are
     * consumed by the flow that needed them — but an instruction does.
     */
    fun mayClose(channel: TaskInputChannel?): Boolean = channel == null || !channel.hasPendingInstruction()

    /** One short, honest line for the transcript when a continuation is accepted. */
    fun continuationNotice(instructions: List<TaskInput>): String = when (instructions.size) {
        0 -> ""
        1 -> "Continuing the same task with: \"${instructions.first().text}\""
        else -> "Continuing the same task with ${instructions.size} new instructions: " +
            instructions.joinToString("; ") { "\"${it.text}\"" }
    }
}
