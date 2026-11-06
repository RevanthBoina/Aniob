package com.aniob.core.domain

/**
 * One model reply that plans several actions for the *current* screen.
 *
 * Only the burst conditions ask for this shape: when every target is already visible, a tap, a
 * typed value and a submit press can be planned in one completion instead of three round trips.
 * The list is capped at [MAX_ACTIONS] so a single reply can never turn into a long blind run.
 *
 * A plan is split in two, and the split is the whole safety argument:
 *
 * - [actions] — the part that can be executed **now**, because every element it names is on the
 *   screen the plan was made for ([com.aniob.core.perception.BurstPlanScope] performs the split).
 * - [deferred] — the tail of the plan that names elements which only exist **after** the actions
 *   above have run (the time field of an alarm editor, the second page of a wizard). Those actions
 *   are not evidence and are not executed; they are kept so the next planning step can be told
 *   what the model already decided instead of paying for the same reasoning again, and they are
 *   only honoured after the screen they were written for has actually been observed.
 *
 * A model that is asked for "2-3 actions for everything on this screen" answers that way. The
 * canonical shape is still one action per screen, so the plan is *reduced* to what the observation
 * can ground rather than rejected: rejecting it was how a correct multi-step plan turned into a
 * three-step fallback and made the basic task fail.
 */
data class ActionBurst(
    val thought: String,
    /** The model believes the goal is complete once every action of the plan lands. */
    val isComplete: Boolean,
    /** Actions that can be dispatched against the screen this plan was made on. */
    val actions: List<AniobAction>,
    /** The rest of the plan, written for screens this one does not show yet. Never executed here. */
    val deferred: List<AniobAction> = emptyList()
) {
    init {
        require(actions.size + deferred.size <= MAX_ACTIONS) {
            "an action plan may hold at most $MAX_ACTIONS actions (got ${actions.size + deferred.size})"
        }
    }

    /** Every action the model planned, executed or not, in model order. */
    val planned: List<AniobAction> get() = actions + deferred

    /**
     * Actions that may be dispatched without a fresh model call. When the model reported
     * completion, the whole list is executed and a provisional finish carries the step to
     * verification; otherwise the last action stays with the normal step pipeline.
     */
    val head: List<AniobAction>
        get() = when {
            actions.isEmpty() -> emptyList()
            isComplete -> actions
            else -> actions.dropLast(1)
        }

    /** The action a normal (non-burst) step would have returned on its own. */
    val tail: AniobAction?
        get() = if (isComplete) null else actions.lastOrNull()

    companion object {
        /** Hard ceiling for one reply; larger arrays are rejected, never executed in part. */
        const val MAX_ACTIONS = 3

        /** Smallest plan that is worth the extra output tokens a burst reply costs. */
        const val MIN_ACTIONS = 2

        /**
         * Placeholder id for an `input` element that named no field.
         *
         * A model writing "type 7:30" without an id is not addressing element -1; it is relying on
         * the screen having one obvious field. The placeholder keeps that intent representable in
         * the action model (which cannot carry a target-less typed action) without ever being a
         * valid Set-of-Mark id, so the parser cannot accidentally point it at a real element.
         */
        const val UNNAMED_TARGET = -1
    }
}
