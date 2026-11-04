package com.aniob.core.safety

/**
 * Finds one-time verification codes in notification/SMS text and masks them everywhere else.
 *
 * The code itself only ever lives in the app-layer `AniobNotificationHub` vault, for a few minutes,
 * and leaves it through two doors: the clipboard (`otp_copy`) and a focused field (`otp_paste`).
 * Every string that can reach a prompt, a log or the chat goes through [mask] first, so the model
 * can *use* a code without ever *seeing* it.
 *
 * Pure Kotlin JVM. Precision over recall: a code needs an OTP-ish keyword nearby, so an order number
 * or an amount in an ordinary notification is never mistaken for a credential.
 */
object AniobOtpExtractor {

    private const val KEYWORDS =
        """(?:otp|one[- ]?time(?:\s+(?:password|passcode|code|pin))?|verification(?:\s+code)?|passcode|security\s+code|""" +
            """auth(?:entication)?\s+code|login\s+code|confirmation\s+code|pin|code)"""

    // "OTP is 483920", "code: 483 920", "483920 is your verification code"
    private val AFTER_KEYWORD = Regex(
        """(?i)\b$KEYWORDS\b[^\d\n]{0,25}?(\d(?:[ -]?\d){3,7})(?![\d])"""
    )
    private val BEFORE_KEYWORD = Regex(
        """(?i)(?<![\d.,])(\d(?:[ -]?\d){3,7})(?![\d.,])[^\d\n]{0,25}?\b$KEYWORDS\b"""
    )
    private val GOOGLE_STYLE = Regex("""\bG-(\d{6})\b""")
    private val MONEY_BEFORE = Regex("""(?i)(?:rs\.?|inr|usd|eur|₹|\$|€|£)\s*$""")

    /** The code digits (separators removed) or null when [text] carries none. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        GOOGLE_STYLE.find(text)?.let { return it.groupValues[1] }
        for (regex in listOf(AFTER_KEYWORD, BEFORE_KEYWORD)) {
            for (match in regex.findAll(text)) {
                val group = match.groups[1] ?: continue
                val before = text.substring(0, group.range.first)
                if (MONEY_BEFORE.containsMatchIn(before)) continue
                val digits = group.value.filter { it.isDigit() }
                if (digits.length in 4..8) return digits
            }
        }
        return null
    }

    /** [text] with the verification code (if any) replaced by bullets, safe for prompts, logs and chat. */
    fun mask(text: String): String {
        val code = extract(text) ?: return text
        var masked = text
        // The code may appear grouped ("483 920"); mask both the grouped and the plain form.
        val grouped = Regex(code.toList().joinToString("[ -]?") { Regex.escape(it.toString()) })
        masked = grouped.replace(masked, "••••••")
        return masked
    }
}
