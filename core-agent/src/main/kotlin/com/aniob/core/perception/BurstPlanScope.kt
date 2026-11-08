package com.aniob.core.perception

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget

/**
 * Splits a model's multi-action plan into the part this screen can execute and the part it cannot.
 *
 * A model asked for "2-8 sequential actions for the task" does not stop at the screen
 * boundary. "Open Clock and set an alarm for 7:30" is answered with
 * `tap(Add alarm) → input(7:30) → tap(OK)`, and only the first of those targets exists on the
 * screen the plan was made on. Treating that reply as unusable is what made a model that had
 * understood the task perfectly produce three extra round trips (or, worse, a blind tap on a
 * recycled element id).
 *
 * The rule here is the one the loop already relies on: an action is dispatched only against
 * evidence that exists **now**.
 *
 * - Every planned action is grounded in model order against the planning screen.
 * - Element actions are resolved through the same [com.aniob.core.grounding.AniobGroundingResolver]
 *   the dispatcher uses, so a plan can never mean something the dispatcher would refuse.
 * - Screen-free actions (swipe, back, wait, device commands) are always executable, and typing is
 *   executable **without** a target when the screen has exactly one editable field — the case that
 *   every dialog in the evaluation battery hits.
 * - The first action that cannot be grounded closes the executable prefix; everything from there
 *   on is deferred to the next observation, not executed and not discarded.
 *
 * A plan whose very first element action cannot be grounded is refused entirely
 * ([Outcome.groundedOnScreen] is false), so a model that invented element ids still falls back to
 * the single-action contract and its repair path.
 *
 * The outcome targets are stable identities (view id → text → description → bounds), never the
 * sequential ids of a past observation: ids are re-assigned on every capture.
 */
object BurstPlanScope {

    data class Outcome(
        val burst: ActionBurst,
        /** Planned actions grounded against the planning screen, in model order. */
        val executable: List<AniobAction>,
        /** Planned actions written for a screen that has not been observed yet. */
        val deferred: List<AniobAction>,
        /** True when the first element action of the plan resolves on this screen. */
        val groundedOnScreen: Boolean,
        /** Why the prefix stopped, for the event log. Empty when the whole plan was executable. */
        val deferredBecause: String = ""
    ) {
        /**
         * The plan reduced to what this screen can execute.
         *
         * Completion is only carried over when nothing was held back: the model's `is_complete`
         * describes the actions it wrote, and closing the run after the first of them would
         * report a finished task the screen does not show.
         */
        val actionable: ActionBurst
            get() = burst.copy(
                actions = executable,
                deferred = deferred,
                isComplete = burst.isComplete && deferred.isEmpty()
            )
    }

    /**
     * True when the node [action] resolved to can actually receive the action.
     *
     * Grounding answers "which element does this id mean"; it does not answer "can this element do
     * this". The difference matters in a bundled plan: a model that plans for the screen its first
     * action opens writes ids that belong to that screen, and those ids still exist — as different
     * widgets — on the current one. Typing into an id that happens to name a button is how a plan
     * for the next screen turns into a stray tap on this one. Only a field can be typed into.
     */
    private fun canReceive(action: AniobAction, node: AniobNode): Boolean = when (action) {
        is AniobAction.InputText -> node.isEditable
        else -> true
    }

    /** The one editable field on [screen], when there is exactly one. */
    fun soleEditable(screen: AniobScreenState): AniobNode? =
        screen.nodes.filter { it.isEditable && it.isVisibleToUser && it.isEnabled }.singleOrNull()

    /**
     * True when [action] can be dispatched against [screen] without inventing a target.
     *
     * @param ground the resolution callback; defaults to the live grounding resolver.
     */
    fun isExecutable(
        action: AniobAction,
        screen: AniobScreenState,
        typedTextIsOnScreen: Boolean = true
    ): Boolean {
        val target = action.semanticTarget()
        return when {
            // Screen-free: a gesture, a key, a wait or a device command changes the screen instead
            // of naming an element on it.
            target == null -> true
            resolves(action, screen) != null -> true
            // Typing: an editable field is the one target a model is allowed to mean without
            // naming it, because the field is unambiguous on this screen.
            action is AniobAction.InputText && action.text.isNotBlank() && typedTextIsOnScreen &&
                soleEditable(screen) != null -> true
            else -> false
        }
    }

    /** The node [action] names on [screen], or null. */
    fun resolves(action: AniobAction, screen: AniobScreenState): AniobNode? {
        val target = action.semanticTarget() ?: return null
        val node = com.aniob.core.grounding.AniobGroundingResolver.resolve(target, screen)?.node
            ?: return null
        return node.takeIf { canReceive(action, it) }
    }

    /**
     * Reduces [burst] to the actions [screen] can execute.
     *
     * @param goal the user's request; typing a value that appears nowhere in it is not trusted on
     *   an unnamed field, which keeps an injected screen from steering what gets typed.
     */
    fun scope(burst: ActionBurst, screen: AniobScreenState, goal: String = ""): Outcome {
        val executable = ArrayList<AniobAction>(burst.actions.size)
        var closedBy: String? = null
        var groundedOnScreen = true

        for ((index, action) in burst.actions.withIndex()) {
            if (closedBy != null) break
            val target = action.semanticTarget()
            val canRun = if (target == null) {
                true
            } else {
                val node = resolves(action, screen)
                when {
                    node != null -> true
                    action is AniobAction.InputText && action.text.isNotBlank() &&
                        trustedTypedText(action, screen, goal) -> true
                    else -> {
                        // The first planned action must land on this screen; anything after it may
                        // legitimately belong to the screen the first action opens.
                        if (index == 0) groundedOnScreen = false
                        closedBy = "action ${index + 1} (${action.toolName}) names an element that is " +
                            "not on the observed screen; dropped the rest and re-plan from a fresh capture"
                        false
                    }
                }
            }
            if (canRun) executable.add(action) else break
        }

        if (!groundedOnScreen) {
            return Outcome(
                burst = burst,
                executable = emptyList(),
                deferred = burst.actions,
                groundedOnScreen = false,
                deferredBecause = closedBy.orEmpty()
            )
        }

        val deferred = burst.actions.drop(executable.size) + burst.deferred
        return Outcome(
            burst = burst,
            executable = executable,
            deferred = deferred,
            groundedOnScreen = true,
            deferredBecause = closedBy.orEmpty()
        )
    }

    /**
     * Typed text is only trusted on an unnamed field when the request itself supplies it, and never
     * into a password field. An injected page cannot make the agent type a value the user did not
     * ask for.
     */
    private fun trustedTypedText(action: AniobAction.InputText, screen: AniobScreenState, goal: String): Boolean {
        val field = soleEditable(screen) ?: return false
        if (field.isPassword) return false
        if (goal.isBlank()) return false
        val needle = action.text.trim()
        if (needle.isEmpty()) return false
        return com.aniob.core.text.AniobTextFold.containsPhrase(goal, needle) || goal.contains(needle, ignoreCase = true)
    }

    /**
     * The deferred tail as a hint for the next planning step.
     *
     * It is deliberately *not* an action list the loop may execute: the screens it was written for
     * have not been observed, and a plan made blind is exactly what this module refuses. It is
     * context — "what you already decided" — which is cheaper than asking the model to rediscover it
     * and cannot move the UI on its own.
     */
    fun renderDeferredHint(deferred: List<AniobAction>): String? {
        if (deferred.isEmpty()) return null
        val rendered = deferred.joinToString(" ; ") { describe(it) }
        return "PLAN ALREADY CHOSEN (from the previous reply; these targets were not on the screen " +
            "it was made on, so only act on one you can see now): $rendered"
    }

    /** One planning hint line per action, in the closed vocabulary rather than in prose. */
    private fun describe(action: AniobAction): String = when (action) {
        is AniobAction.Tap -> "tap ${targetOf(action.target)}"
        is AniobAction.LongPress -> "long_press ${targetOf(action.target)}"
        is AniobAction.InputText -> "input ${targetOf(action.target)} = '${action.text.take(40)}'" +
            (if (action.submit) " then submit" else "")
        is AniobAction.Swipe -> "swipe ${action.direction.name}"
        is AniobAction.PressKey -> "key ${action.key.name}"
        is AniobAction.SystemKey -> "key ${action.key.name}"
        is AniobAction.OpenApp -> "open_app ${action.packageName}"
        is AniobAction.Wait -> "wait ${action.durationMs}ms"
        is AniobAction.DeviceControl -> "device ${action.command.wire}"
        is AniobAction.Finish -> "finish"
        is AniobAction.Fail -> "fail"
        else -> action.toolName
    }

    private fun targetOf(target: SemanticTarget): String = when (target) {
        is SemanticTarget.SomIndex -> "id:${target.index}"
        is SemanticTarget.ResourceId -> "view:${target.id.substringAfterLast('/')}"
        is SemanticTarget.Text -> "'${target.text.take(30)}'"
        is SemanticTarget.ContentDesc -> "desc:'${target.desc.take(30)}'"
        else -> target.describe()
    }
}
