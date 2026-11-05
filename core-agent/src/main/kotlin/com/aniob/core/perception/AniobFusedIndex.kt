package com.aniob.core.perception

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.GroundingSource
import kotlin.math.max

/**
 * Unified element index fusing Accessibility tree nodes and OCR elements into one searchable collection.
 * Supports exact match, fuzzy text search, and coordinate hit testing.
 */
class AniobFusedIndex(
    val elements: List<AniobNode> = emptyList()
) {
    private val byId: Map<Int, AniobNode> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<Int, AniobNode>(elements.size * 2).apply {
            for (node in elements) {
                putIfAbsent(node.id, node)
            }
        }
    }

    fun findById(id: Int): AniobNode? = byId[id]

    fun exactMatch(query: String, ignoreCase: Boolean = true): List<AniobNode> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return elements.filter {
            it.text.equals(q, ignoreCase = ignoreCase) ||
            it.contentDescription.equals(q, ignoreCase = ignoreCase) ||
            it.viewId.equals(q, ignoreCase = ignoreCase) ||
            it.tooltip.equals(q, ignoreCase = ignoreCase)
        }
    }

    fun fuzzyTextSearch(query: String, minSimilarity: Double = 0.65): List<Pair<AniobNode, Double>> {
        val q = com.aniob.core.text.AniobTextFold.compact(query)
        if (q.isEmpty()) return emptyList()

        return elements.mapNotNull { node ->
            val targets = listOf(node.text, node.contentDescription, node.tooltip)
                .filter { it.isNotBlank() }
                .map { com.aniob.core.text.AniobTextFold.compact(it) }
                .filter { it.isNotEmpty() }

            val bestScore = targets.maxOfOrNull { textSimilarity(q, it, minSimilarity) } ?: 0.0
            if (bestScore >= minSimilarity) node to bestScore else null
        }.sortedWith(
            compareByDescending<Pair<AniobNode, Double>> { it.second }
                .thenByDescending { it.first.isClickable }
                .thenBy { it.first.bounds.area }
        )
    }

    fun hitTest(x: Int, y: Int): AniobNode? {
        val candidates = elements.filter { it.bounds.contains(x, y) && it.isVisibleToUser }
        // Prefer smallest area (most specific leaf element) and clickable items
        return candidates.minWithOrNull(
            compareBy<AniobNode> { !it.isClickable }
                .thenBy { it.bounds.area }
        )
    }

    val sourceDistribution: Map<GroundingSource, Int>
        get() = elements.groupingBy { it.source }.eachCount()

    companion object {
        fun textSimilarity(s1: String, s2: String, minSimilarity: Double = 0.0): Double {
            if (s1 == s2) return 1.0
            if (s1.isEmpty() || s2.isEmpty()) return 0.0
            val minLen = minOf(s1.length, s2.length)
            val maxLen = maxOf(s1.length, s2.length)
            // A 2-letter query is a substring of half the labels on screen ("on" in "Notifications").
            // Containment only counts as a match when the shorter side is a meaningful share of the longer.
            if (minLen >= 3 && (s1.contains(s2) || s2.contains(s1))) {
                return max(0.75, minLen.toDouble() / maxLen.toDouble())
            }
            // Mathematical Upper-Bound Pruning:
            // Since levenshteinDistance(s1, s2) >= maxLen - minLen,
            // maximum possible similarity is minLen / maxLen.
            val maxPossibleSimilarity = minLen.toDouble() / maxLen.toDouble()
            if (minSimilarity > 0.0 && maxPossibleSimilarity < minSimilarity) {
                return 0.0
            }
            val maxAllowedDistance = if (minSimilarity > 0.0) {
                ((1.0 - minSimilarity) * maxLen.toDouble()).toInt()
            } else {
                maxLen
            }
            val distance = levenshteinDistance(s1, s2, maxAllowedDistance)
            if (distance > maxAllowedDistance) return 0.0
            return 1.0 - (distance.toDouble() / maxLen.toDouble())
        }

        /**
         * Prefix/Suffix-Stripped + Ukkonen Banded Levenshtein Distance in O(k * min(N, M)) time and O(min(N, M)) space.
         */
        private fun levenshteinDistance(s1: String, s2: String, maxDistance: Int = Int.MAX_VALUE): Int {
            if (s1 == s2) return 0
            if (s1.isEmpty()) return s2.length
            if (s2.isEmpty()) return s1.length

            // 1. Exact Prefix/Suffix Invariance: dist(u + x + v, u + y + v) == dist(x, y)
            var prefix = 0
            val minTotalLen = minOf(s1.length, s2.length)
            while (prefix < minTotalLen && s1[prefix] == s2[prefix]) {
                prefix++
            }
            var s1End = s1.length
            var s2End = s2.length
            while (s1End > prefix && s2End > prefix && s1[s1End - 1] == s2[s2End - 1]) {
                s1End--
                s2End--
            }

            val len1 = s1End - prefix
            val len2 = s2End - prefix
            if (len1 == 0) return len2
            if (len2 == 0) return len1
            if (kotlin.math.abs(len1 - len2) > maxDistance) return maxDistance + 1

            val (shorter, sOff, cols, longer, lOff, rows) = if (len1 <= len2) {
                Tuple6(s1, prefix, len1, s2, prefix, len2)
            } else {
                Tuple6(s2, prefix, len2, s1, prefix, len1)
            }

            val band = maxDistance.coerceAtMost(rows)
            var prev = IntArray(cols + 1) { it }
            var curr = IntArray(cols + 1) { maxDistance + 1 }

            for (i in 1..rows) {
                curr[0] = i
                val cLong = longer[lOff + i - 1]
                val from = maxOf(1, i - band)
                val to = minOf(cols, i + band)
                if (from > 1) curr[from - 1] = maxDistance + 1
                var rowMin = curr[0]

                for (j in from..to) {
                    val cost = if (cLong == shorter[sOff + j - 1]) 0 else 1
                    val v = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost
                    )
                    curr[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (to < cols) curr[to + 1] = maxDistance + 1
                if (rowMin > maxDistance) return maxDistance + 1

                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[cols]
        }

        private data class Tuple6(
            val s1: String, val off1: Int, val len1: Int,
            val s2: String, val off2: Int, val len2: Int
        )
    }
}
