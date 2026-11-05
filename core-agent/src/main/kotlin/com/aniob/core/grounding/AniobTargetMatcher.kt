package com.aniob.core.grounding

import com.aniob.core.domain.AniobNode
import com.aniob.core.text.AniobTextFold

/**
 * Deterministic "which on-screen element did the user mean?" — tiers from strictest to loosest.
 * Precision over recall: when nothing matches, or several equally-good elements tie, it says so
 * instead of picking one. The LLM matcher ([AniobTargetMatchPrompt]) is only consulted on those two outcomes.
 */
object AniobTargetMatcher {

    enum class Tier { EXACT, PHRASE, LEET, TYPO, SYNONYM }

    sealed interface Result {
        data class Match(val node: AniobNode, val tier: Tier) : Result
        data class Ambiguous(val nodes: List<AniobNode>) : Result
        data object None : Result
    }

    /** Standard mobile-UX synonym groups, compacted/folded. Extend here, nowhere else. */
    private val SYNONYMS: List<Set<String>> = listOf(
        setOf("buy", "checkout", "purchase", "order", "placeorder", "paynow"),
        setOf("search", "find", "searchquery", "magnifier", "lookup"),
        setOf("settings", "preferences", "options", "configure", "gear"),
        setOf("download", "get", "install", "savetodevice"),
        setOf("send", "submit", "post", "reply"),
        setOf("back", "navigateup", "up", "previous"),
        setOf("close", "dismiss", "cancel", "done", "x"),
        setOf("menu", "more", "overflow", "morevertical", "hamburger"),
        setOf("share", "sendto"),
        setOf("delete", "remove", "trash", "bin"),
        setOf("signin", "login", "logon"),
        setOf("signup", "register", "createaccount", "join")
    )

    fun match(query: String, actionCategory: String, candidates: List<AniobNode>): Result {
        val q = AniobTextFold.compact(query)
        if (q.isEmpty()) return Result.None
        val eligible = candidates.filter { it.isVisibleToUser && it.isEnabled }
        val scored = eligible.mapNotNull { node -> bestTier(q, query, node)?.let { node to it } }
        if (scored.isEmpty()) return Result.None
        val best = scored.minOf { it.second.ordinal }
        var tied = scored.filter { it.second.ordinal == best }.map { it.first }
        val tier = Tier.values()[best]
        // Contextual pruning: a label that is not itself clickable only wins when nothing clickable ties with it.
        val preferred: (AniobNode) -> Boolean =
            if (AniobTextFold.compact(actionCategory) == "forminput") { n -> n.isEditable } else { n -> n.isClickable || n.isEditable }
        tied.filter(preferred).takeIf { it.isNotEmpty() }?.let { tied = it }
        // A text view and its clickable container often carry the same label at the same place: that is one target.
        tied = tied.distinctBy { it.bounds }
        return if (tied.size == 1) Result.Match(tied.first(), tier) else Result.Ambiguous(tied)
    }

    private fun bestTier(q: String, rawQuery: String, node: AniobNode): Tier? {
        val labels = listOf(node.text, node.contentDescription, node.tooltip, node.paneTitle).filter { it.isNotBlank() }
        var best: Tier? = null
        for (label in labels) {
            val l = AniobTextFold.compact(label)
            if (l.isEmpty()) continue
            val t = tierFor(q, rawQuery, label, l) ?: continue
            if (best == null || t.ordinal < best.ordinal) best = t
        }
        return best
    }

    private fun tierFor(q: String, rawQuery: String, rawLabel: String, l: String): Tier? {
        if (l == q) return Tier.EXACT
        // Very short queries ("on", "ok", "x") only ever match exactly: substring/typo matching them is guessing.
        if (q.length < 3) return null
        if (AniobTextFold.containsPhrase(rawLabel, rawQuery)) return Tier.PHRASE
        if (AniobTextFold.leetVariants(q).any { it == l } || AniobTextFold.leetVariants(l).any { it == q }) return Tier.LEET
        val budget = AniobTextFold.typoBudget(maxOf(q.length, l.length)).coerceAtMost(AniobTextFold.typoBudget(minOf(q.length, l.length) + 1))
        if (budget > 0 && AniobTextFold.editDistance(q, l, budget) <= budget) return Tier.TYPO
        // single-word query vs one word of a longer label ("dwnload" vs "Download now")
        if (AniobTextFold.tokens(rawQuery).size == 1) {
            val qb = AniobTextFold.typoBudget(q.length)
            if (qb > 0 && AniobTextFold.tokens(rawLabel).any { AniobTextFold.editDistance(q, it, qb) <= qb }) return Tier.TYPO
        }
        val group = SYNONYMS.firstOrNull { q in it }
        if (group != null && (l in group || AniobTextFold.tokens(rawLabel).any { it in group })) return Tier.SYNONYM
        return null
    }
}
