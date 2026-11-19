package com.aniob.core.run

import com.aniob.core.domain.ActionExecutionRecord
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.ExecutionOutcomeStatus
import com.aniob.core.domain.TaskContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Persisted recovery snapshot for process-death boundaries.
 *
 * Distinct from live same-process continuation (`TaskInputChannel` / `activePipeline`): this
 * snapshot records the original task, correlated action IDs, execution outcomes, pending
 * instructions, and last known task status so a cold restart can restore the task as interrupted
 * and reconcile any uncertain dispatch before continuing — without blindly replaying actions.
 */
data class TaskRecoverySnapshot(
    val taskId: String,
    val originalInstruction: String,
    val lastKnownTaskStatus: String,
    val currentStep: Int,
    val pendingInstructions: List<String> = emptyList(),
    val executionResults: List<ActionExecutionRecord> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Restores this snapshot as an interrupted [TaskContext] and reconciles any uncertain
     * dispatches against [liveScreen] when available. Never auto-replays prior actions.
     */
    fun restoreAsInterrupted(liveScreen: AniobScreenState? = null): TaskContext {
        var ctx = TaskContext(
            instruction = originalInstruction,
            stepIndex = currentStep,
            taskId = taskId,
            additionalInstructions = pendingInstructions.distinct()
        )
        for (record in executionResults) {
            ctx = ctx.recordCorrelatedOutcome(record)
        }
        // If the task was still RUNNING when killed and has an unverified/uncertain dispatch
        // (or no record of a finished step), keep/reconcile it safely rather than re-running.
        if (liveScreen != null && ctx.hasUncertainExecution()) {
            ctx = ctx.reconcileUncertainExecutions(liveScreen)
        }
        return ctx
    }

    fun toJson(): String = ADAPTER.toJson(this)

    companion object {
        private val MOSHI: Moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        private val ADAPTER = MOSHI.adapter(TaskRecoverySnapshot::class.java)

        fun fromJson(raw: String?): TaskRecoverySnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching { ADAPTER.fromJson(raw) }.getOrNull()
        }
    }
}

/**
 * PART 6 — what a persisted "running" marker may and may not be used for.
 *
 * A marker proves one thing only: a task WAS running when the process died. It is not a checkpoint.
 * The last action Aniob recorded may have reached another app, may have been half-dispatched, or may
 * never have started at all. So the record is turned into words the user can act on — what was
 * verified, what may already have happened, and what needs checking — and into an explicit
 * continuation prompt the user has to ask for. Nothing in this file executes anything.
 *
 * This is deliberately separate from the live continuation path (`TaskInputChannel` / a parked
 * run): that one keeps the real run alive inside one process; this one only explains a dead one.
 */
data class InterruptedTaskReport(
    val taskId: String,
    val prompt: String,
    /** Step the marker recorded when the process died. */
    val step: Int,
    /** Actions Aniob had actually verified before it stopped. */
    val verified: List<String>,
    /** Actions that were about to be (or were being) dispatched: their effect is unknown. */
    val uncertain: List<String>,
    /** True when the task was parked waiting for the user rather than mid-action. */
    val wasWaitingForUser: Boolean
) {
    /** Everything the user has to look at before the task may continue. */
    val needsChecking: List<String> get() = uncertain

    /** Honest one-paragraph explanation for the recovery card. Never claims completion. */
    fun reason(): String = buildString {
        append("Interrupted at step $step; Aniob was no longer running, so nothing was retried.")
        if (wasWaitingForUser) {
            append(" It was waiting for your answer, so no action of this task was in flight.")
        }
        if (verified.isNotEmpty()) {
            append(" Verified before the interruption: ")
            append(verified.joinToString(", "))
            append(".")
        }
        if (uncertain.isNotEmpty()) {
            append(" Effect unknown, please check before continuing: ")
            append(uncertain.joinToString(", "))
            append(".")
        } else if (!wasWaitingForUser) {
            append(" No external action was recorded in flight, but the screen may have moved on.")
        }
    }

    /**
     * Prompt for a continuation the USER explicitly starts.
     *
     * It is a new task on purpose: it begins by observing the live screen and re-checking the
     * uncertain effect, so a possibly completed action is never repeated blindly.
     */
    fun continuationPrompt(): String = buildString {
        append("Continue this interrupted task: ")
        append(prompt.replace(Regex("\\s+"), " ").trim())
        append(". First look at the current screen and work out what was already done")
        if (uncertain.isNotEmpty()) {
            append(" — the step \"")
            append(uncertain.first().replace(Regex("\\s+"), " ").trim())
            append("\" may already have happened, so do not repeat it")
        }
        if (verified.isNotEmpty()) {
            append("; these were already verified: ")
            append(verified.joinToString(", "))
        }
        append(". If the state is still unclear after looking, ask me once instead of guessing.")
    }
}

/** Parses the `lastKnownEffect` text [com.aniob.app.ui.persistRunningMarker]-style writers produce. */
object AniobInterruptedTask {
    const val VERIFIED_PREFIX = "verified: "
    const val UNCERTAIN_PREFIX = "may have happened: "
    const val WAITING_PREFIX = "waiting for you: "

    /**
     * @param lastKnownEffect the marker text, e.g. `"verified: Tap Reply; may have happened: Type \"hi\""`.
     */
    fun parse(taskId: String, prompt: String, step: Int, lastKnownEffect: String): InterruptedTaskReport {
        var verified: List<String> = emptyList()
        var uncertain: List<String> = emptyList()
        var waiting = false
        lastKnownEffect.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            when {
                part.startsWith(VERIFIED_PREFIX) ->
                    verified = splitList(part.removePrefix(VERIFIED_PREFIX))
                part.startsWith(UNCERTAIN_PREFIX) -> {
                    val text = part.removePrefix(UNCERTAIN_PREFIX).trim()
                    if (text.startsWith(WAITING_PREFIX)) {
                        waiting = true
                    } else if (text.isNotEmpty()) {
                        uncertain = listOf(text)
                    }
                }
                part.startsWith(WAITING_PREFIX) -> waiting = true
                // An unlabelled record is still an action of unknown effect; say so instead of
                // pretending it was verified.
                else -> uncertain = uncertain + part
            }
        }
        return InterruptedTaskReport(
            taskId = taskId,
            prompt = prompt,
            step = step,
            verified = verified,
            uncertain = uncertain,
            wasWaitingForUser = waiting
        )
    }

    private fun splitList(value: String): List<String> =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
}
