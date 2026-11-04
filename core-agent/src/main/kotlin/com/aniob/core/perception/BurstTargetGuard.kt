package com.aniob.core.perception

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget

/**
 * Guards a multi-action burst against a screen that moved under it.
 *
 * A burst is planned against one observation, but each action can change the screen. Before
 * action `i > 0` is dispatched, the caller re-reads the window and asks this object two questions:
 * did the app navigate away, and is the planned target still there?
 *
 * Targets are re-identified by `viewId`, then text, then description, then bounds — the same
 * identity ladder the spec names — because element ids are re-assigned on every observation and a
 * stale id would silently point at a different widget.
 */
object BurstTargetGuard {

    /** True when the burst left the package it was planned in. */
    fun screenNavigated(origin: AniobScreenState, current: AniobScreenState): Boolean {
        if (origin.packageName.isBlank() || current.packageName.isBlank()) return false
        return origin.packageName != current.packageName
    }

    /**
     * The live node the action should hit, or null when it is gone.
     *
     * Returns null for actions that carry no element target *and* for targets the origin screen
     * never contained: an unverifiable action must not be dispatched blind.
     */
    fun findTarget(action: AniobAction, origin: AniobScreenState, current: AniobScreenState): AniobNode? {
        val target = action.semanticTarget() ?: return null
        val planned = when (target) {
            is SemanticTarget.SomIndex -> origin.findNodeById(target.index)
            is SemanticTarget.ResourceId -> origin.nodes.firstOrNull { it.viewId == target.id }
            is SemanticTarget.Text -> origin.nodes.firstOrNull {
                it.text.equals(target.text, ignoreCase = !target.exact)
            }
            is SemanticTarget.ContentDesc -> origin.nodes.firstOrNull {
                it.contentDescription.equals(target.desc, ignoreCase = !target.exact)
            }
            else -> null
        } ?: return null
        return match(current.nodes, planned)
    }

    /** Same element on a new observation, matched most-specific first. */
    fun match(nodes: List<AniobNode>, planned: AniobNode): AniobNode? {
        // Do not fall back to a recycled SoM index or to the first ambiguous match.
        val candidates = nodes.filter { node ->
            node.isVisibleToUser && node.className == planned.className && when {
                planned.viewId.isNotBlank() -> node.viewId == planned.viewId
                planned.text.isNotBlank() -> node.text == planned.text
                planned.contentDescription.isNotBlank() -> node.contentDescription == planned.contentDescription
                else -> false
            }
        }
        return candidates.singleOrNull()
    }

    /**
     * Rewrites [action] to name [node] by identity instead of by the id of a past observation.
     *
     * Element ids are re-assigned on every capture, so a burst planned on one screen must re-target
     * before it lands on the next one; a stable `viewId`, text or the node's bounds survive that
     * re-index, a raw index does not.
     */
    fun retarget(action: AniobAction, node: AniobNode): AniobAction {
        val target = when {
            node.viewId.isNotBlank() -> SemanticTarget.ResourceId(node.viewId)
            node.text.isNotBlank() -> SemanticTarget.Text(node.text)
            node.contentDescription.isNotBlank() -> SemanticTarget.ContentDesc(node.contentDescription)
            else -> SemanticTarget.BoundingBox(
                left = node.bounds.left,
                top = node.bounds.top,
                right = node.bounds.right,
                bottom = node.bounds.bottom
            )
        }
        return when (action) {
            is AniobAction.Tap -> action.copy(target = target)
            is AniobAction.LongPress -> action.copy(target = target)
            is AniobAction.InputText -> action.copy(target = target)
            else -> action
        }
    }

    /**
     * Whether the burst can continue with [action]. [reason] explains an abort in the log and in
     * the step record; it is empty when the action may run.
     */
    data class Verdict(val allowed: Boolean, val reason: String = "") {
        /**
         * True when the burst cannot continue but the **task** can: a hidden, re-indexed or
         * disabled target, or a screen that moved on, only means the plan was made for a screen
         * that no longer exists. The right answer is to drop the rest of the burst, observe the
         * screen as it is now and plan one action from it — never to fail the whole task.
         */
        val shouldReplan: Boolean get() = !allowed
    }

    fun check(action: AniobAction, origin: AniobScreenState, current: AniobScreenState): Verdict {
        if (screenNavigated(origin, current)) {
            return Verdict(false, "screen navigated to ${current.packageName}")
        }
        val target = action.semanticTarget() ?: return Verdict(true)
        val node = findTarget(action, origin, current)
            ?: return Verdict(false, "target ${target.describe()} is no longer on screen")
        if (!node.isEnabled) return Verdict(false, "target ${target.describe()} is disabled")
        return Verdict(true)
    }
}
