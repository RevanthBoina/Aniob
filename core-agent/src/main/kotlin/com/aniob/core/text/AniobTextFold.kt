package com.aniob.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * One place that decides "are these two UI strings the same word?".
 *
 * Why this exists (U-01): Kotlin's `String.lowercase()` is already locale-invariant (it is
 * `toLowerCase(Locale.ROOT)`), so the 171 bare calls do NOT turn "INSTALL" into "ınstall". The real
 * defect is different and still silent:
 *  - a Turkish-locale UI renders "install" with textAllCaps as "İNSTALL"; ROOT-lowercasing gives
 *    "i̇nstall" (an `i` plus U+0307, 8 chars) which never equals "install";
 *  - stylized text ("𝐈𝐍𝐒𝐓𝐀𝐋𝐋", full-width "ＩＮＳＴＡＬＬ", circled "Ⓘ…") is not touched by `lowercase()` at all;
 *  - diacritics ("Réglages" vs "reglages") and zero-width characters defeat `contains`.
 *
 * Pure JVM. Never use the device default locale here.
 */
object AniobTextFold {

    private val ZERO_WIDTH = Regex("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    /** Locale-proof, style-proof, case-proof, diacritic-proof (Latin only) form of [raw]. */
    fun fold(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        // NFKC maps full-width, math-bold/italic, circled and other compatibility letters to plain ones.
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = ZERO_WIDTH.replace(s, "")
        // Turkic dotted/dotless i BEFORE lowercasing, so the result never depends on the device locale.
        s = s.replace('İ', 'i').replace('ı', 'i').replace("ß", "ss")
        s = s.lowercase(Locale.ROOT)
        return SPACES.replace(stripLatinMarks(s), " ").trim()
    }

    /** Only Latin base letters lose their marks: stripping Telugu/Hindi vowel signs would corrupt those scripts. */
    private fun stripLatinMarks(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = StringBuilder(d.length)
        var lastBaseIsLatin = false
        var i = 0
        while (i < d.length) {
            val cp = d.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt() && lastBaseIsLatin) continue
            out.appendCodePoint(cp)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                lastBaseIsLatin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
            }
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
    }

    /** [fold] without punctuation or spaces: "Search…", "[Search]", "search:" all become "search". */
    fun compact(raw: String?): String = NON_ALNUM.replace(fold(raw), "")

    /** Whole-word tokens of the folded text. */
    fun tokens(raw: String?): List<String> = fold(raw).split(NON_ALNUM).filter { it.isNotEmpty() }

    fun same(a: String?, b: String?): Boolean {
        val x = compact(a)
        return x.isNotEmpty() && x == compact(b)
    }

    /** Whole-word / whole-phrase containment on folded tokens ("cab" is NOT inside "vocabulary"). */
    fun containsPhrase(haystack: String?, needle: String?): Boolean {
        val h = tokens(haystack)
        val n = tokens(needle)
        if (n.isEmpty() || n.size > h.size) return false
        for (start in 0..h.size - n.size) {
            if (n.indices.all { h[start + it] == n[it] }) return true
        }
        return false
    }

    /** Leetspeak variants of an already compacted string; '1' is ambiguous (i or l) so both are produced. */
    fun leetVariants(compacted: String): Set<String> {
        if (compacted.none { it in LEET_CHARS }) return setOf(compacted)
        val asI = StringBuilder()
        val asL = StringBuilder()
        for (c in compacted) {
            val m = LEET[c]
            if (c == '1') { asI.append('i'); asL.append('l') }
            else { asI.append(m ?: c); asL.append(m ?: c) }
        }
        return setOf(asI.toString(), asL.toString())
    }

    private val LEET = mapOf('0' to 'o', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b')
    private val LEET_CHARS = LEET.keys + '1'

    /** Edits allowed for a typo of a word that long: none for tiny words, 1 up to 6, 2 from 7. */
    fun typoBudget(length: Int): Int = when {
        length <= 3 -> 0
        length <= 6 -> 1
        else -> 2
    }

    /** Optimal-string-alignment distance (insert/delete/substitute/transpose) with an early exit. Returns max+1 when exceeded. */
    fun editDistance(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            prev2 = prev
            prev = cur
        }
        return prev[b.length].coerceAtMost(max + 1)
    }
}
