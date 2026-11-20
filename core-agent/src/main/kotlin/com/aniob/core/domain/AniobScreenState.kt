package com.aniob.core.domain

import com.aniob.core.perception.AniobFusedIndex
import com.aniob.core.perception.AniobPerceptionTier

/**
 * Screen snapshot capturing actionable nodes, fused element index, and structural hash for perception.
 */
data class AniobScreenState(
    val packageName: String,
    val activityName: String = "",
    val nodes: List<AniobNode> = emptyList(),
    val fusedIndex: AniobFusedIndex = AniobFusedIndex(nodes),
    val perceptionTier: AniobPerceptionTier = AniobPerceptionTier.FAST,
    val treeHash: String = "",
    val screenshotBase64: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val changedRegion: AniobRect? = null,
    val isEventDrivenSkip: Boolean = false,
    /**
     * 64-bit FNV-1a identity of package, activity and visible interactive structure.
     * Present on both compact and legacy captures; empty on observations without that identity.
     */
    val screenHash: String = "",
    /** Explicit kind of pipeline that generated this observation ("compact", "legacy", "mock"). */
    val observationKind: String = "",
    /** Monotonic window-event sequence counter at the time of this capture. */
    val windowEventSeq: Long = 0L,
    /** Whether an active input-method (IME/keyboard) window is visible. */
    val hasImeWindow: Boolean = false,
    /** Recent input events, separate from both screen hashes. */
    val interactionEvents: List<UiInteractionEvent> = emptyList(),
    /** Platform uptime watermark; events generated earlier cannot confirm this dispatch. */
    val interactionStartUptimeMs: Long = 0L
) {
    fun findNodeById(id: Int): AniobNode? = fusedIndex.findById(id) ?: nodes.find { it.id == id }

    fun findNodeByText(textQuery: String, ignoreCase: Boolean = true): AniobNode? =
        fusedIndex.exactMatch(textQuery, ignoreCase).firstOrNull() ?:
        fusedIndex.fuzzyTextSearch(textQuery).firstOrNull()?.first ?:
        nodes.find {
            it.text.contains(textQuery, ignoreCase) ||
            it.contentDescription.contains(textQuery, ignoreCase)
        }

    val actionableNodeCount: Int get() = nodes.size
}

/** Minimal event source identity; never includes field text or sequential node IDs. */
data class UiInteractionEvent(
    val kind: Kind,
    val sequence: Long,
    val uptimeMs: Long,
    val packageName: String,
    val className: String,
    val viewId: String,
    val bounds: AniobRect
) {
    enum class Kind { CLICKED, FOCUSED }
}
