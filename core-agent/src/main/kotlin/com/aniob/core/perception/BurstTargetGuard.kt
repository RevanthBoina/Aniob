package com.aniob.core.perception

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import kotlin.math.abs

/**
 * Guards a multi-action burst against a screen that moved under it.
 *
 * A burst is planned against one observation, but each action can change the screen. Before
 * action `i > 0` is dispatched, the caller re-reads the window and asks this object two questions:
 * did the app navigate away, and is the planned target still there?
 *
 * Targets are re-identified via a true ladder:
 * viewId -> normalized text -> contentDescription -> bounds proximity (<= 6% viewport drift).
 */
object BurstTargetGuard {

    private const val COORDINATE_TOLERANCE_RATIO = 0.06

    /** True when the burst navigated or drifted unexpectedly. */
    fun screenNavigated(origin: AniobScreenState, current: AniobScreenState): Boolean {
        if (origin.packageName.isBlank() || current.packageName.isBlank()) return false
        if (origin.packageName != current.packageName) return true
        if (origin.activityName.isNotBlank() && current.activityName.isNotBlank() && origin.activityName != current.activityName) {
            return true
        }
        return false
    }

    /** Normalises labels by stripping dynamic noise: timestamps, relative times, and volatile counters (B-4, F-5). */
    fun normalizeLabel(text: String): String {
        var s = text
        if (s.contains(" | ")) {
            val segments = s.split(" | ")
            s = segments.firstOrNull { it.isNotBlank() } ?: s
        }
        s = s.replace(Regex("""\b\d{1,2}:\d{2}(\s*(?:am|pm|AM|PM))?\b"""), "")
        s = s.replace(Regex("""\b\d+\s*(?:s|m|h|d|min|mins|sec|secs|hr|hrs|days?)\s*ago\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\b\d+\s*(?:new|unread|items?|messages?)\b""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\b\d+\b"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * The live node the action should hit, or null when it is gone.
     */
    fun findTarget(action: AniobAction, origin: AniobScreenState, current: AniobScreenState): AniobNode? {
        val target = action.semanticTarget() ?: return null
        val planned = when (target) {
            is SemanticTarget.SomIndex -> origin.findNodeById(target.index)
            is SemanticTarget.ResourceId -> origin.nodes.firstOrNull { it.viewId == target.id }
            is SemanticTarget.Text -> origin.nodes.firstOrNull {
                it.text.equals(target.text, ignoreCase = !target.exact) ||
                    normalizeLabel(it.text).equals(normalizeLabel(target.text), ignoreCase = true)
            }
            is SemanticTarget.ContentDesc -> origin.nodes.firstOrNull {
                it.contentDescription.equals(target.desc, ignoreCase = !target.exact) ||
                    normalizeLabel(it.contentDescription).equals(normalizeLabel(target.desc), ignoreCase = true)
            }
            is SemanticTarget.BoundingBox -> origin.nodes.minByOrNull {
                abs(it.bounds.left - target.left) + abs(it.bounds.top - target.top)
            }
            else -> null
        } ?: return null
        return match(current.nodes, planned)
    }

    /**
     * True identity matching ladder (B-4):
     * Tier 1: viewId match
     * Tier 2: text match (normalized)
     * Tier 3: contentDescription match
     * Tier 4: bounds proximity (center drift <= 6% viewport)
     * Includes bounds proximity + sibling tie-breaker when multiple candidates exist.
     */
    fun match(nodes: List<AniobNode>, planned: AniobNode): AniobNode? {
        val visible = nodes.filter { it.isVisibleToUser }
        if (visible.isEmpty()) return null

        val viewportW = nodes.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1080) ?: 1080
        val viewportH = nodes.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1920) ?: 1920
        val maxDriftX = viewportW * COORDINATE_TOLERANCE_RATIO
        val maxDriftY = viewportH * COORDINATE_TOLERANCE_RATIO

        fun isWithinProximity(node: AniobNode): Boolean {
            val dx = abs(node.centerX - planned.centerX)
            val dy = abs(node.centerY - planned.centerY)
            return dx <= maxDriftX && dy <= maxDriftY
        }

        // Tier 1: viewId match
        if (planned.viewId.isNotBlank()) {
            val byId = visible.filter { it.viewId == planned.viewId }
            if (byId.size == 1 && isWithinProximity(byId.single())) return byId.single()
            if (byId.size > 1) {
                val closest = byId.filter(::isWithinProximity).minByOrNull {
                    abs(it.centerX - planned.centerX) + abs(it.centerY - planned.centerY)
                }
                if (closest != null) return closest
            }
        }

        // Tier 2: text match (exact or normalized)
        val plannedNormText = normalizeLabel(planned.text)
        if (plannedNormText.isNotBlank()) {
            val byText = visible.filter {
                (it.text.equals(planned.text, ignoreCase = true) ||
                    normalizeLabel(it.text).equals(plannedNormText, ignoreCase = true)) &&
                    (it.className == planned.className || planned.viewId.isBlank())
            }
            if (byText.size == 1 && isWithinProximity(byText.single())) return byText.single()
            if (byText.size > 1) {
                // Sibling / bounds tie-breaker (reject surviving wrong row if drifted)
                val closest = byText.filter(::isWithinProximity).minByOrNull {
                    abs(it.centerX - planned.centerX) + abs(it.centerY - planned.centerY)
                }
                if (closest != null) return closest
            }
        }

        // Tier 3: contentDescription match
        val plannedNormDesc = normalizeLabel(planned.contentDescription)
        if (plannedNormDesc.isNotBlank()) {
            val byDesc = visible.filter {
                (it.contentDescription.equals(planned.contentDescription, ignoreCase = true) ||
                    normalizeLabel(it.contentDescription).equals(plannedNormDesc, ignoreCase = true)) &&
                    (it.className == planned.className || planned.viewId.isBlank())
            }
            if (byDesc.size == 1 && isWithinProximity(byDesc.single())) return byDesc.single()
            if (byDesc.size > 1) {
                val closest = byDesc.filter(::isWithinProximity).minByOrNull {
                    abs(it.centerX - planned.centerX) + abs(it.centerY - planned.centerY)
                }
                if (closest != null) return closest
            }
        }

        // Tier 4: bounds proximity for icon-only / unlabelled controls
        val byClass = visible.filter { it.className == planned.className && isWithinProximity(it) }
        return byClass.minByOrNull {
            abs(it.centerX - planned.centerX) + abs(it.centerY - planned.centerY)
        }
    }

    /**
     * Rewrites [action] to name [node] by identity instead of by the id of a past observation.
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
