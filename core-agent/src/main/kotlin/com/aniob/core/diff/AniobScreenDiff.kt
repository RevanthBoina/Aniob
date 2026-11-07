package com.aniob.core.diff

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobFingerprint

/**
 * 4-Level Waterfall Screen Perception & Differential Pipeline.
 *
 * Pin-to-pin comparison:
 * - Worst: adb shell screencap (DroidBot https://github.com/honeynet/droidbot) ~500ms, heavy process fork.
 * - Better: MediaProjection API - requires repetitive runtime user consent dialogues.
 * - Best: AccessibilityService.takeScreenshot() API 30+ (ARTEMIS https://github.com/google/artemis) 30-80ms.
 * - Aniob Improvement: 4-level waterfall:
 *   1. Event-Driven Skip: If no accessibility event occurred since last step, skip screenshot entirely (0ms).
 *   2. Perceptual Tree Hash Cache: If structural node hash is unchanged, reuse cached screenshot state (0ms).
 *   3. Changed Region Crop: If only a bounding sub-region changed, compute diff rect.
 *   4. Downscaled JPEG q80 <=1120px (MobileAgent-Android trick): Downscale bitmap payload by ~85% with zero OCR loss.
 */
object AniobScreenDiff {

    data class WaterfallResult(
        val waterfallLevelUsed: Int, // 1 to 4
        val shouldCaptureFullScreenshot: Boolean,
        val changedRegion: AniobRect?,
        val treeHash: String,
        val isIdenticalToPrevious: Boolean
    )

    /**
     * Executes the differential waterfall between previous screen and current raw node state.
     */
    fun evaluateWaterfall(
        hasAccessibilityContentChanged: Boolean,
        currentNodes: List<AniobNode>,
        previousScreen: AniobScreenState?
    ): WaterfallResult {
        // Level 1: Event-Driven Skip (O(1) zero-iteration skip when previous treeHash is known)
        if (previousScreen != null && !hasAccessibilityContentChanged) {
            val cachedHash = previousScreen.treeHash.ifBlank { computeTreeHash(currentNodes) }
            return WaterfallResult(
                waterfallLevelUsed = 1,
                shouldCaptureFullScreenshot = false,
                changedRegion = null,
                treeHash = cachedHash,
                isIdenticalToPrevious = true
            )
        }

        // Compute new structural tree hash covering all observed nodes and attributes
        val currentFingerprint = computeTreeHash(currentNodes)

        // Level 2: Perceptual Tree Hash Match (0ms capture)
        if (previousScreen != null && previousScreen.treeHash == currentFingerprint) {
            return WaterfallResult(
                waterfallLevelUsed = 2,
                shouldCaptureFullScreenshot = false,
                changedRegion = null,
                treeHash = currentFingerprint,
                isIdenticalToPrevious = true
            )
        }

        // Level 3: Region Crop Calculation
        val changedBounds = computeChangedRegion(previousScreen?.nodes ?: emptyList(), currentNodes)
        if (previousScreen != null && changedBounds != null && changedBounds.area < 500_000) {
            return WaterfallResult(
                waterfallLevelUsed = 3,
                shouldCaptureFullScreenshot = true, // capture and crop
                changedRegion = changedBounds,
                treeHash = currentFingerprint,
                isIdenticalToPrevious = false
            )
        }

        // Level 4: Full Screenshot Downscaled JPEG q80 (max dimension <= 1120px)
        return WaterfallResult(
            waterfallLevelUsed = 4,
            shouldCaptureFullScreenshot = true,
            changedRegion = null,
            treeHash = currentFingerprint,
            isIdenticalToPrevious = false
        )
    }

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
    private const val FNV_PRIME = 0x100000001b3L

    private fun fnvMixString(hash: Long, s: String): Long {
        var h = hash
        for (i in 0 until s.length) {
            h = (h xor s[i].code.toLong()) * FNV_PRIME
        }
        return (h xor 0xFFL) * FNV_PRIME
    }

    private fun fnvMixInt(hash: Long, v: Int): Long =
        ((hash xor v.toLong()) * FNV_PRIME)

    private fun fnvMixBoolean(hash: Long, b: Boolean): Long =
        ((hash xor (if (b) 1L else 0L)) * FNV_PRIME)

    /**
     * 64-bit FNV-1a structural screen hash (F-2).
     */
    fun computeScreenHash64(packageName: String, activityName: String, structuralSignature: String): String {
        var h = FNV_OFFSET_BASIS
        h = fnvMixString(h, packageName)
        h = fnvMixString(h, activityName)
        h = fnvMixString(h, structuralSignature)
        return java.lang.Long.toUnsignedString(h, 16)
    }

    /**
     * Zero-allocation 64-bit FNV-1a streaming structural tree hash.
     * Eliminates multi-KB joinToString allocations and reduces collision probability from 2^-32 to 2^-64.
     */
    fun computeTreeHash(nodes: List<AniobNode>): String {
        var h = FNV_OFFSET_BASIS
        for (node in nodes) {
            h = fnvMixInt(h, node.id)
            h = fnvMixString(h, node.className)
            h = fnvMixString(h, node.text)
            h = fnvMixString(h, node.contentDescription)
            h = fnvMixString(h, node.viewId)
            h = fnvMixInt(h, node.bounds.left)
            h = fnvMixInt(h, node.bounds.top)
            h = fnvMixInt(h, node.bounds.right)
            h = fnvMixInt(h, node.bounds.bottom)
            h = fnvMixBoolean(h, node.isEnabled)
            h = fnvMixBoolean(h, node.isClickable)
            h = fnvMixBoolean(h, node.isEditable)
            h = fnvMixBoolean(h, node.isScrollable)
            h = fnvMixBoolean(h, node.isVisibleToUser)
            h = fnvMixBoolean(h, node.isSelected)
            h = fnvMixBoolean(h, node.isChecked)
        }
        return java.lang.Long.toUnsignedString(h, 16)
    }

    private fun nodeSignatureHash(n: AniobNode): Long {
        var h = FNV_OFFSET_BASIS
        h = fnvMixString(h, n.viewId)
        h = fnvMixString(h, n.className)
        h = fnvMixString(h, n.text)
        h = fnvMixString(h, n.contentDescription)
        h = fnvMixInt(h, n.bounds.left)
        h = fnvMixInt(h, n.bounds.top)
        h = fnvMixInt(h, n.bounds.right)
        h = fnvMixInt(h, n.bounds.bottom)
        h = fnvMixBoolean(h, n.isSelected)
        h = fnvMixBoolean(h, n.isChecked)
        return h
    }

    private fun computeChangedRegion(
        oldNodes: List<AniobNode>,
        newNodes: List<AniobNode>
    ): AniobRect? {
        val oldSignatures = HashSet<Long>(oldNodes.size * 2)
        for (n in oldNodes) oldSignatures.add(nodeSignatureHash(n))

        val newSignatures = HashSet<Long>(newNodes.size * 2)
        for (n in newNodes) newSignatures.add(nodeSignatureHash(n))

        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        var anyDiff = false

        for (node in newNodes) {
            if (!oldSignatures.contains(nodeSignatureHash(node)) && !node.bounds.isEmpty) {
                anyDiff = true
                minL = minOf(minL, node.bounds.left)
                minT = minOf(minT, node.bounds.top)
                maxR = maxOf(maxR, node.bounds.right)
                maxB = maxOf(maxB, node.bounds.bottom)
            }
        }

        for (node in oldNodes) {
            if (!newSignatures.contains(nodeSignatureHash(node)) && !node.bounds.isEmpty) {
                anyDiff = true
                minL = minOf(minL, node.bounds.left)
                minT = minOf(minT, node.bounds.top)
                maxR = maxOf(maxR, node.bounds.right)
                maxB = maxOf(maxB, node.bounds.bottom)
            }
        }

        return if (anyDiff && minL < maxR && minT < maxB) {
            AniobRect(minL, minT, maxR, maxB)
        } else null
    }
}
