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
 * Targets are re-identified by stable identity. Ambiguity or lost identity asks for a replan;
 * proximity alone must never select a different recipient, amount or control.
 */
object BurstTargetGuard {

    /** True when the burst navigated or drifted unexpectedly. */
    fun screenNavigated(origin: AniobScreenState, current: AniobScreenState): Boolean {
        if (origin.packageName.isBlank() || current.packageName.isBlank()) return false
        if (origin.packageName != current.packageName) return true
        if (origin.activityName.isNotBlank() && current.activityName.isNotBlank() && origin.activityName != current.activityName) {
            return true
        }
        return false
    }

    /** Only whitespace is cosmetic. Digits, counters and suffixes may identify a recipient. */
    fun normalizeLabel(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun findTarget(action: AniobAction, origin: AniobScreenState, current: AniobScreenState): AniobNode? {
        val target = action.semanticTarget() ?: return null
        val planned = when (target) {
            is SemanticTarget.SomIndex -> origin.findNodeById(target.index)
            is SemanticTarget.ResourceId -> origin.nodes.singleOrNull { it.viewId == target.id }
            is SemanticTarget.Text -> origin.nodes.singleOrNull {
                normalizeLabel(it.text).equals(normalizeLabel(target.text), ignoreCase = !target.exact)
            }
            is SemanticTarget.ContentDesc -> origin.nodes.singleOrNull {
                normalizeLabel(it.contentDescription).equals(normalizeLabel(target.desc), ignoreCase = !target.exact)
            }
            is SemanticTarget.BoundingBox -> origin.nodes.singleOrNull {
                it.bounds.left == target.left && it.bounds.top == target.top &&
                    it.bounds.right == target.right && it.bounds.bottom == target.bottom
            }
            else -> null
        } ?: return null
        return match(current.nodes, planned)
    }

    /** Never fall back to proximity when a named widget disappears or has duplicate identities. */
    fun match(nodes: List<AniobNode>, planned: AniobNode): AniobNode? = nodes.filter { node ->
        node.isVisibleToUser && node.className == planned.className && when {
            planned.viewId.isNotBlank() -> node.viewId == planned.viewId &&
                (planned.text.isBlank() || normalizeLabel(node.text) == normalizeLabel(planned.text)) &&
                (planned.contentDescription.isBlank() || normalizeLabel(node.contentDescription) == normalizeLabel(planned.contentDescription))
            planned.text.isNotBlank() -> normalizeLabel(node.text) == normalizeLabel(planned.text)
            planned.contentDescription.isNotBlank() -> normalizeLabel(node.contentDescription) == normalizeLabel(planned.contentDescription)
            else -> node.viewId.isBlank() && node.text.isBlank() && node.contentDescription.isBlank() && node.bounds == planned.bounds
        }
    }.singleOrNull()

    /**
     * Rewrites [action] to name [node] by identity instead of by the id of a past observation.
     */
    fun retarget(action: AniobAction, node: AniobNode): AniobAction {
        val target = when {
            node.viewId.isNotBlank() -> SemanticTarget.ResourceId(node.viewId)
            node.text.isNotBlank() -> SemanticTarget.Text(node.text, exact = true)
            node.contentDescription.isNotBlank() -> SemanticTarget.ContentDesc(node.contentDescription, exact = true)
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
     * Whether the burst can continue with [action].
     */
    data class Verdict(
        val allowed: Boolean,
        val reason: String = "",
        val matchedNode: AniobNode? = null
    ) {
        val shouldReplan: Boolean get() = !allowed
    }

    fun check(action: AniobAction, origin: AniobScreenState, current: AniobScreenState): Verdict {
        if (screenNavigated(origin, current)) {
            return Verdict(false, "screen navigated to ${current.packageName}", null)
        }
        val target = action.semanticTarget() ?: return Verdict(true, "", null)
        val node = findTarget(action, origin, current)
            ?: return Verdict(false, "target ${target.describe()} is no longer on screen", null)
        if (!node.isEnabled) return Verdict(false, "target ${target.describe()} is disabled", null)
        return Verdict(true, "", node)
    }
}
