package com.aniob.core.speed

import com.aniob.core.domain.AniobAction

/**
 * Plan-5.4 — ACTION BURSTS for transient UI.
 *
 * 2..N turn-ending actions submitted together and executed back to back with no gate, no retry
 * and no screenshot between them. First failure aborts the rest.
 *
 * This is the only way to hit auto-fading control bars, snackbar buttons, toasts with actions and
 * short-lived menus: by the next turn those targets are GONE, so a one-action-per-observation loop
 * structurally cannot reach them.
 *
 * It is also the single most abusable feature in the whole plan — "batch the next five taps for
 * speed" throws away every safety property the loop has. The constraints below are therefore
 * enforced in code, not requested in a prompt.
 */
object ActionBurst {

    /**
     * @param isTransientTarget        the target vanishes on its own before the next observation
     * @param sequencingIsTheRequirement sub-second ordering is itself what the step needs
     * @param usesRecordedCoordinates  coordinates come from an earlier observation, not live data
     * @param summonedByIndex          index, within this burst, of the action that restores the
     *                                 state those recorded coordinates belonged to
     * @param verifiedBeforeDispatch   the runner re-observes and re-grounds this member against the
     *                                 live screen before it fires, so nothing is dispatched blind
     * @param homogeneousRepeat        the same action repeated on a stable target ("tap it 3 times")
     */
    data class Member(
        val action: AniobAction,
        val justification: String,
        val isTransientTarget: Boolean = false,
        val sequencingIsTheRequirement: Boolean = false,
        val usesRecordedCoordinates: Boolean = false,
        val summonedByIndex: Int? = null,
        val verifiedBeforeDispatch: Boolean = false,
        val homogeneousRepeat: Boolean = false
    )

    data class Validation(val isAllowed: Boolean, val violations: List<String>) {
        /** Told to the model verbatim, so a rejected burst becomes a learnable event. */
        fun explain(): String =
            if (isAllowed) "Burst accepted." else buildString {
                appendLine("Burst rejected. Submit these as ordinary single steps instead:")
                violations.forEach { appendLine("- $it") }
            }.trim()
    }

    data class Outcome(
        val attempted: Int,
        val dispatched: Int,
        val abortedAtIndex: Int?,
        val abortReason: String?
    ) {
        val fullyDispatched: Boolean get() = abortedAtIndex == null
    }

    const val MIN_SIZE = 2
    const val MAX_SIZE = 5

    /** Ceiling for one repeated action ("tap the bell 8 times"); a mixed burst stays at [MAX_SIZE]. */
    const val MAX_REPEAT = 8

    /**
     * True when every member is the same tool on the same target and none of them relies on
     * coordinates recorded earlier: the only shape where repetition itself is the task.
     */
    fun isHomogeneousRepeat(members: List<Member>): Boolean =
        members.size >= MIN_SIZE &&
            members.all { it.homogeneousRepeat && !it.usesRecordedCoordinates } &&
            members.map { it.action.toolName }.distinct().size == 1 &&
            members.map { it.action.semanticTarget()?.toString() }.distinct().size == 1

    fun validate(members: List<Member>): Validation {
        val v = mutableListOf<String>()

        val repeat = isHomogeneousRepeat(members)
        val ceiling = if (repeat) MAX_REPEAT else MAX_SIZE
        if (members.size < MIN_SIZE) v += "a burst needs at least $MIN_SIZE actions (got ${members.size})"
        if (members.size > ceiling) v += "a burst may contain at most $ceiling actions (got ${members.size})"

        // "the agent must justify every member"
        members.forEachIndexed { i, m ->
            if (m.justification.isBlank()) v += "member $i has no justification"
        }

        // "bursts only for transient targets or when sub-second sequencing is itself the
        //  requirement, never to batch ordinary steps for speed"
        // A burst is legitimate when a target is transient, when sub-second ordering IS the step,
        // when every member is re-grounded against a fresh observation before it fires, or when the
        // same action is repeated on a stable target. Batching blind, unverified steps is still not.
        val qualifies = members.any { it.isTransientTarget } ||
            members.all { it.sequencingIsTheRequirement } ||
            members.all { it.verifiedBeforeDispatch } ||
            repeat
        if (!qualifies) {
            v += "no member targets transient UI, sequencing is not stated as the requirement, and " +
                "members are not re-grounded before dispatch; batching ordinary steps for speed is not a valid burst"
        }

        // "coordinates must come from live data EXCEPT the transient target ... provided the
        //  summoning action in the same burst restores the state they belonged to"
        members.forEachIndexed { i, m ->
            if (!m.usesRecordedCoordinates) return@forEachIndexed
            if (!m.isTransientTarget) {
                v += "member $i reuses recorded coordinates but is not a transient target"
                return@forEachIndexed
            }
            val s = m.summonedByIndex
            when {
                s == null -> v += "member $i reuses recorded coordinates without naming a summoning action"
                s !in members.indices -> v += "member $i names summoning action $s, which is not in this burst"
                s >= i -> v += "member $i is summoned by action $s, which runs after it; the summoning " +
                    "action must run FIRST to restore the state those coordinates belonged to"
            }
        }

        // A burst is a sequence of turn-ending actions; a Finish anywhere but last is incoherent.
        members.dropLast(1).forEachIndexed { i, m ->
            if (m.action is AniobAction.Finish) v += "member $i is finish, which must be the last action"
        }

        return Validation(v.isEmpty(), v)
    }

    /**
     * Executes the burst back to back. No gate, no retry, no screenshot between members.
     * The first member that does not dispatch aborts the remainder.
     */
    suspend fun execute(
        members: List<Member>,
        dispatch: suspend (AniobAction) -> Boolean,
        onDispatched: (Int, AniobAction) -> Unit = { _, _ -> }
    ): Outcome {
        var dispatched = 0
        members.forEachIndexed { i, m ->
            val ok = try {
                dispatch(m.action)
            } catch (e: Exception) {
                return Outcome(members.size, dispatched, i, "dispatch threw: ${e.message}")
            }
            if (!ok) return Outcome(members.size, dispatched, i, "member $i did not dispatch")
            dispatched++
            onDispatched(i, m.action)
        }
        return Outcome(members.size, dispatched, null, null)
    }
}
