package com.aniob.core.text

/**
 * Spoken/typed number normalisation: "ten am" -> "10 am", "ten thirty pm" -> "10:30 pm",
 * "half past ten" -> "10:30", "twenty five minutes" -> "25 minutes", "ten fifteen" -> "10:15".
 *
 * Number words are only rewritten when a unit follows (am/pm/o'clock/minute/hour/percent/...) or the
 * phrase is an unambiguous clock idiom, so ordinary prose ("which one", "two options") is untouched.
 * Every parser that reads a number from a prompt goes through [normalize] first: without it, a spoken
 * value silently downgrades a one-intent task into a multi-step screen crawl and makes the completion
 * check look for words the app never displays.
 */
object SpokenNumbers {
    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60)
    private val MERIDIEM = setOf("am", "pm", "a.m.", "p.m.", "a.m", "p.m")
    private val UNITS = MERIDIEM + setOf(
        "o'clock", "oclock", "minute", "minutes", "min", "mins", "hour", "hours", "hr", "hrs",
        "second", "seconds", "sec", "secs", "percent", "%", "times", "time", "x"
    )
    private val SPLIT = Regex("\\s+")
    private val CLOCK = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)", RegexOption.IGNORE_CASE)
    private val HHMM = Regex("(\\d{1,2}):(\\d{2})")
    /** "half past ten", "quarter past/to/present eleven". */
    private val RELATIVE = Regex(
        "\\b(half|quarter)\\s+(past|after|to|before)\\s+(" + (ONES.keys + TENS.keys).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    private fun isNumberWord(w: String) = w in ONES || w in TENS || w == "oh" || w == "o"

    /** Rewrites spoken numbers (and clock idioms) into the digits an app displays. Idempotent. */
    fun normalize(text: String): String {
        if (text.isBlank()) return text
        val relative = RELATIVE.replace(text) { m ->
            val hour = valueOf(m.groupValues[3]) ?: return@replace m.value
            val mins = if (m.groupValues[1].equals("half", true)) 30 else 15
            val forward = m.groupValues[2].lowercase() in setOf("past", "after")
            val base = if (hour in 1..12) hour else hour % 12
            val result = if (forward) (base % 12) * 60 + mins else ((base + 11) % 12) * 60 + (60 - mins)
            "%02d:%02d".format(result / 60 % 12, result % 60).let { if (it.startsWith("00")) "12" + it.drop(2) else it }
        }
        return digits(relative)
    }

    private fun valueOf(word: String): Int? = ONES[word.lowercase()] ?: TENS[word.lowercase()]

    private fun digits(text: String): String {
        val tokens = text.split(SPLIT).filter { it.isNotEmpty() }
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].lowercase().trimEnd(',', '.').replace('-', ' ')
            if (!word.split(' ').all(::isNumberWord)) { out += tokens[i]; i++; continue }
            var j = i
            val run = ArrayList<String>()
            while (j < tokens.size) {
                val parts = tokens[j].lowercase().trimEnd(',', '.').split('-')
                if (parts.all(::isNumberWord)) { run += parts; j++ } else break
            }
            val unit = tokens.getOrNull(j)?.lowercase()?.trimEnd(',', '.')
            val nextIsNumber = run.size > 1
            // A unit must follow, or two number words must form a clock ("ten fifteen").
            if (unit == null || (unit !in UNITS && !nextIsNumber && unit !in MERIDIEM)) { out += tokens[i]; i++; continue }
            val values = ArrayList<Int>()
            var k = 0
            while (k < run.size) {
                val w = run[k]
                when {
                    w == "oh" || w == "o" -> Unit
                    w in TENS && run.getOrNull(k + 1)?.let { ONES[it] in 1..9 } == true -> {
                        values += TENS.getValue(w) + ONES.getValue(run[k + 1]); k++
                    }
                    w in TENS -> values += TENS.getValue(w)
                    else -> values += ONES.getValue(w)
                }
                k++
            }
            val rendered = when {
                values.size == 1 -> values[0].toString()
                values.size == 2 && (unit in MERIDIEM || nextIsNumber) &&
                    values[0] in 1..12 && values[1] in 0..59 ->
                    "${values[0]}:${values[1].toString().padStart(2, '0')}"
                else -> { out += tokens[i]; i++; continue }
            }
            out += rendered
            i = j
        }
        return out.joinToString(" ")
    }

    /** Every surface form a screen may show for one clock value ("ten am" -> "10:00 am", "10 am", "10:00"). */
    fun timeVariants(needle: String): List<String> {
        val normalized = normalize(needle).lowercase()
        val m = CLOCK.find(normalized)
        if (m != null) {
            val h = m.groupValues[1].toInt()
            val mm = m.groupValues[2].ifEmpty { "00" }
            val ap = m.groupValues[3].replace(".", "")
            val h2 = h.toString().padStart(2, '0')
            return listOf("$h:$mm $ap", "$h:$mm$ap", "$h2:$mm $ap", "$h $ap", "$h:$mm", "$h2:$mm").distinct()
        }
        HHMM.find(normalized)?.let { mm ->
            val h = mm.groupValues[1].toInt(); val mi = mm.groupValues[2]
            return listOf("$h:$mi", h.toString().padStart(2, '0') + ":$mi").distinct()
        }
        return emptyList()
    }

    fun looksLikeTime(text: String): Boolean =
        CLOCK.containsMatchIn(normalize(text)) || HHMM.containsMatchIn(normalize(text))
}
