package com.aniob.core.verifier

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.GroundingSource
import com.aniob.core.domain.UiInteractionEvent
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.matches
import com.aniob.core.perception.BurstTargetGuard

/** Interaction evidence only: never folds focus, IME or events into a screen hash. */
object TapEvidence {
    fun isActionable(node: AniobNode, screen: AniobScreenState): Boolean =
        node.isEnabled && node.isVisibleToUser && !node.bounds.isEmpty &&
            (node.isClickable || node.isEditable || node.isCheckable || node.isChecked ||
                node.source != GroundingSource.ACCESSIBILITY || screen.nodes.any {
                    // Legacy observations can ground a tap on a label inside a clickable control.
                    it.isClickable && it.isEnabled && it.isVisibleToUser && it.bounds.encloses(node.bounds)
                })

    /** Distinguish a known disabled/hidden node from one not grounded on this screen. */
    fun unavailableTarget(target: SemanticTarget, screen: AniobScreenState): AniobNode? =
        screen.nodes.singleOrNull { target.matches(it) }?.takeIf { !it.isEnabled || !it.isVisibleToUser }

    fun screenTransition(before: AniobScreenState, after: AniobScreenState): Boolean {
        if (before.packageName.isBlank() || after.packageName.isBlank()) return false
        if (BurstTargetGuard.screenNavigated(before, after)) return true
        val (old, new) = if (before.screenHash.isNotBlank() && after.screenHash.isNotBlank()) {
            before.screenHash to after.screenHash
        } else {
            before.treeHash to after.treeHash
        }
        return old.isNotBlank() && new.isNotBlank() && old != new
    }

    fun sameTarget(a: AniobNode, b: AniobNode): Boolean =
        a.className == b.className && a.viewId == b.viewId &&
            (if (a.viewId.isNotBlank()) !a.bounds.intersect(b.bounds).isEmpty else a.bounds == b.bounds)

    fun findTarget(nodes: List<AniobNode>, target: AniobNode): AniobNode? {
        val candidates = nodes.filter {
            it.isVisibleToUser && !it.bounds.isEmpty && it.className == target.className && it.viewId == target.viewId
        }
        if (target.viewId.isNotBlank() && candidates.size == 1) return candidates.single()
        return candidates.singleOrNull { sameTarget(it, target) }
    }

    private fun AniobRect.encloses(other: AniobRect): Boolean = !other.isEmpty &&
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private fun fields(screen: AniobScreenState, target: AniobNode): List<AniobNode> =
        screen.nodes.filter {
            it.isEditable && it.isEnabled && it.isVisibleToUser && target.bounds.encloses(it.bounds)
        }

    fun interaction(
        before: AniobScreenState,
        after: AniobScreenState,
        target: AniobNode,
        current: AniobNode?,
        includeAcknowledgements: Boolean = true
    ): String? {
        if (before.packageName.isBlank() || before.packageName != after.packageName) return null
        if (current?.isFocused == true && !target.isFocused) return "target gained input focus"
        if (current != null && (target.isChecked != current.isChecked || target.isSelected != current.isSelected)) {
            return "target checked or selected state changed"
        }
        val beforeFields = fields(before, target)
        val afterFields = fields(after, current ?: target)
        if (afterFields.any { field ->
                field.isFocused && findTarget(beforeFields, field)?.isFocused != true
            }) return "editable control in the target gained input focus"

        val inputReady = afterFields.any { it.isFocused } || (current?.isEditable == true && current.isFocused)
        if (after.hasImeWindow && (inputReady || (!before.hasImeWindow &&
                (target.isEditable || beforeFields.isNotEmpty() || afterFields.isNotEmpty()) &&
                after.nodes.none { it.isEditable && it.isFocused }))) {
            if (!before.hasImeWindow) return "keyboard appeared for the input control"
            if (includeAcknowledgements && inputReady) return "input control is focused with the keyboard visible"
        }

        if (!includeAcknowledgements) return null
        val sources = listOfNotNull(target, current) + beforeFields + afterFields
        val matched = after.interactionEvents.firstOrNull { event ->
            event.sequence > before.windowEventSeq && event.uptimeMs >= before.interactionStartUptimeMs &&
                event.packageName == before.packageName && sources.any { node ->
                    node.className.isNotBlank() && event.className == node.className && event.viewId == node.viewId &&
                        (if (node.viewId.isNotBlank()) !event.bounds.intersect(node.bounds).isEmpty
                         else event.bounds == node.bounds && !event.bounds.isEmpty)
                }
        }
        return when (matched?.kind) {
            UiInteractionEvent.Kind.CLICKED -> "matching accessibility click event"
            UiInteractionEvent.Kind.FOCUSED -> "matching accessibility input-focus event"
            null -> null
        }
    }
}
