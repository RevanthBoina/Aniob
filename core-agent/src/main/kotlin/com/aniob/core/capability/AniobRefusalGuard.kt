package com.aniob.core.capability

/**
 * Detects the one reply an automation agent must never produce: "I can't operate your phone".
 *
 * A refusal is not a parse error to shrug off with a Wait. It is a model that was never told, or
 * forgot, that it is connected to the device. [AniobStructuredOutput] uses this to turn the repair
 * retry into a correction ([AniobCapabilityManifest.REFUSAL_CORRECTION]) instead of a generic schema
 * complaint, which is what actually gets the next reply to be an action.
 *
 * Pure Kotlin JVM. Conservative on purpose: a reply that contains a `"tool"` field is an action,
 * never a refusal, however apologetic its `thought` is.
 */
object AniobRefusalGuard {

    private val PATTERNS: List<Regex> = listOf(
        Regex(
            """(?i)\b(?:i|i'm|i am)\s+(?:can(?:'|no)?t|cannot|can\s+not|(?:am|'m)\s+(?:not able|unable) to|""" +
                """do(?:n't| not) have (?:the ability|access|permission|a way))\b[^.\n]{0,80}""" +
                """\b(?:phone|device|mobile|screen|apps?|tap|click|type|operate|control|access|open|""" +
                """notifications?|clipboard|sms|calls?|volume|files?)"""
        ),
        Regex("""(?i)\bas an ai(?: language model| model| assistant)?\b[^.\n]{0,60}\b(?:can(?:'|no)?t|cannot|unable|don't have|do not have)"""),
        Regex("""(?i)\b(?:don't|do not) have (?:access to|control (?:of|over)|the ability to (?:control|operate|access))\b[^.\n]{0,40}\b(?:phone|device|mobile|screen)"""),
        Regex("""(?i)\bi(?:'m| am) (?:just |only )?a (?:text|language)[- ]based\b""")
    )

    fun isRefusal(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (raw.contains("\"tool\"") || raw.contains("\"action\"")) return false
        return PATTERNS.any { it.containsMatchIn(raw) }
    }
}
