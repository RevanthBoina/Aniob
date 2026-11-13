package com.aniob.core.skills

/** Token-level matching: word boundaries, limited typo tolerance, no guessed synonym semantics. */
object SkillRelevance {
    private val noise = setOf("a", "an", "the", "please", "me", "my", "i", "you", "can", "could", "would", "tell", "show", "about", "is", "what", "to", "for", "on", "in", "and")
    fun tokens(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase())
        .map { it.value }.filter { it !in noise }.map(::stem).toSet()
    private fun stem(word: String): String {
        for (suffix in listOf("ing", "ed", "ly", "s")) {
            if (word.length - suffix.length >= 3 && word.endsWith(suffix)) {
                val base = word.dropLast(suffix.length)
                return if (suffix == "ing" && base.length > 3 && base.last() == base[base.lastIndex - 1]) base.dropLast(1) else base
            }
        }
        return word
    }
    private fun near(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous.last() <= 1
    }
    fun score(query: String, trigger: String): Float {
        val q = tokens(query)
        val t = tokens(trigger)
        if (q.isEmpty() || t.isEmpty()) return 0f
        val hits = t.count { term -> q.any { near(it, term) } }
        return .65f * hits / t.size + .35f * hits / maxOf(q.size, hits)
    }
}
