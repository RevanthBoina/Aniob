package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.TaskContext
import com.aniob.core.verifier.DeterministicVerifier

/**
 * Outcome of one full loop iteration.
 *
 * [terminalState] is the only thing the caller switches on, which is what lets all ladder rungs
 * share a single pipeline instead of each owning its own success/verify/record logic.
 */
data class StepResult(
    val action: AniobAction,
    val dispatch: DispatchCommand,
    val verification: DeterministicVerifier.VerificationResult? = null,
    val reflection: ReflectionOutcome? = null,
    val nextCtx: TaskContext,
    val terminalState: TerminalState,
    /** Node that was resolved on the live tree, when the action targeted an element. */
    val resolvedNode: AniobNode? = null,
    val tokensUsed: Int = 0,
    val latencyMs: Long = 0L,
    /** True only for a hard safety stop; every other FAIL is a soft miss that is re-planned. */
    val blockedBySafety: Boolean = false
) {
    enum class TerminalState { CONTINUE, SUCCESS, FAIL }

    val isTerminal: Boolean get() = terminalState != TerminalState.CONTINUE
}

/**
 * Result of reflecting on a step failure or on a provisional `Finish`.
 *
 * For a step failure, [feedback] is the recovery guidance appended to the condensed history.
 * For a finish verdict, [confirmed] distinguishes "evidence accepted" from "keep working".
 */
data class ReflectionOutcome(
    val confirmed: Boolean,
    val reason: String,
    val feedback: String = "",
    val recoveryStrategy: String? = null,
    val learnedTip: String? = null,
    val usedLlmJudge: Boolean = false
) {
    companion object {
        fun confirmed(reason: String) = ReflectionOutcome(confirmed = true, reason = reason)

        fun rejected(reason: String, feedback: String) =
            ReflectionOutcome(confirmed = false, reason = reason, feedback = feedback)
    }
}