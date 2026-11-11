package com.aniob.core.outcome

/** Exact correction phrases only; an ordinary task mentioning "worked" remains a new task. */
object AniobOutcomeCorrection {
    private val phrase = Regex("""(?i)^\s*(?:that was correct|it worked|that worked|mark (?:it|that) correct|the task was done)\s*[.!]?\s*$""")
    fun matches(text: String): Boolean = phrase.matches(text)
}
