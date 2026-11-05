package com.aniob.core.grounding

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.GroundingSource
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.matches

/**
 * Turns a [SemanticTarget] into concrete pixels against a **live** screen capture using the fused element index.
 * Records the resolving perception source (accessibility node, OCR, or visual model).
 */
object AniobGroundingResolver {

    /** A successfully grounded element plus the exact pixels and resolving source. */
    data class Resolved(
        val node: AniobNode,
        val x: Int,
        val y: Int,
        val source: GroundingSource = node.source
    )

    /**
     * Fused resolution priority:
     * 1. SoM index -> node with that id in the fused index
     * 2. resource id -> exact view-id match
     * 3. text / content description -> exact match over fused index, then fuzzy text match
     */
    fun resolve(target: SemanticTarget, liveScreen: AniobScreenState): Resolved? = when (target) {
        is SemanticTarget.SomIndex -> {
            val node = liveScreen.findNodeById(target.index)
            if (node == null || !node.isVisibleToUser || !node.isEnabled) null
            else node.toResolved()
        }

        is SemanticTarget.ResourceId -> {
            val matches = liveScreen.fusedIndex.elements.filter {
                it.isVisibleToUser && it.isEnabled && it.viewId.equals(target.id, ignoreCase = false)
            }
            if (matches.size == 1) matches.first().toResolved()
            else if (matches.size > 1) {
                val clickables = matches.filter { it.isClickable }
                if (clickables.size == 1) clickables.first().toResolved() else null
            } else null
        }

        is SemanticTarget.Text -> resolveText(target, liveScreen)

        is SemanticTarget.ContentDesc -> resolveContentDesc(target, liveScreen)

        is SemanticTarget.NormalizedCoordinate -> resolveNormalizedCoordinate(target, liveScreen)

        is SemanticTarget.BoundingBox -> resolveBoundingBox(target, liveScreen)
    }

    // A coordinate is not evidence of an element. Require an actual visible node rather
    // than inventing one; unsupported canvas targets fail closed until grounded by perception.
    private fun resolveNormalizedCoordinate(target: SemanticTarget.NormalizedCoordinate, screen: AniobScreenState): Resolved? {
        val candidates = screen.nodes.filter { it.isVisibleToUser && it.isEnabled && target.matches(it) }
        val node = candidates.minByOrNull { it.bounds.area } ?: return null
        return Resolved(node, target.normX, target.normY, GroundingSource.VISUAL_MODEL)
    }

    private fun resolveBoundingBox(target: SemanticTarget.BoundingBox, screen: AniobScreenState): Resolved? {
        if (target.left >= target.right || target.top >= target.bottom) return null
        val candidates = screen.nodes.filter { it.isVisibleToUser && it.isEnabled && target.matches(it) }
        val node = candidates.minByOrNull { it.bounds.area } ?: return null
        return Resolved(node, (target.left + target.right) / 2, (target.top + target.bottom) / 2, GroundingSource.VISUAL_MODEL)
    }

    private fun resolveText(target: SemanticTarget.Text, screen: AniobScreenState): Resolved? {
        val eligible = screen.fusedIndex.elements.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { com.aniob.core.text.AniobTextFold.same(it.text, target.text) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }
        if (target.exact) return null

        val fuzzyHits = screen.fusedIndex.fuzzyTextSearch(target.text, minSimilarity = 0.70)
            .filter { it.first.isVisibleToUser && it.first.isEnabled }
        if (fuzzyHits.isNotEmpty()) {
            val topScore = fuzzyHits.first().second
            val topTied = fuzzyHits.filter { kotlin.math.abs(it.second - topScore) <= 1e-4 }.map { it.first }
            val clickableTied = topTied.filter { it.isClickable || it.isEditable }
            if (clickableTied.size == 1) return clickableTied.first().toResolved()
            return if (topTied.size == 1) topTied.first().toResolved() else null
        }
        return null
    }

    private fun resolveContentDesc(target: SemanticTarget.ContentDesc, screen: AniobScreenState): Resolved? {
        val eligible = screen.fusedIndex.elements.filter { it.isVisibleToUser && it.isEnabled }
        val exactHits = eligible.filter { com.aniob.core.text.AniobTextFold.same(it.contentDescription, target.desc) }
        if (exactHits.size == 1) return exactHits.first().toResolved()
        if (exactHits.size > 1) {
            val clickables = exactHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }
        if (target.exact) return null

        val partialHits = eligible.filter { com.aniob.core.text.AniobTextFold.containsPhrase(it.contentDescription, target.desc) }
        if (partialHits.size == 1) return partialHits.first().toResolved()
        if (partialHits.size > 1) {
            val clickables = partialHits.filter { it.isClickable }
            if (clickables.size == 1) return clickables.first().toResolved()
            return null
        }

        val fuzzyHits = screen.fusedIndex.fuzzyTextSearch(target.desc, minSimilarity = 0.72)
            .filter { it.first.isVisibleToUser && it.first.isEnabled && it.first.contentDescription.isNotBlank() }
        if (fuzzyHits.size == 1) {
            return fuzzyHits.first().first.toResolved()
        }
        return null
    }

    private fun AniobNode.toResolved(): Resolved = Resolved(node = this, x = centerX, y = centerY, source = source)

    fun resolveContainer(target: SemanticTarget?, liveScreen: AniobScreenState): AniobNode? {
        val scrollables = liveScreen.nodes.filter { it.isScrollable }
        if (scrollables.isEmpty()) return null
        val anchor = target?.let { resolve(it, liveScreen) }?.node ?: return scrollables.maxByOrNull { it.bounds.area }
        return scrollables.firstOrNull { contains(it, anchor) } ?: scrollables.maxByOrNull { it.bounds.area }
    }

    private fun contains(container: AniobNode, child: AniobNode): Boolean =
        container.bounds.left <= child.bounds.left && container.bounds.top <= child.bounds.top &&
            container.bounds.right >= child.bounds.right && container.bounds.bottom >= child.bounds.bottom
}
