package com.aniob.core.auth

/**
 * Removes credential-shaped text from anything that may be logged, persisted or shown to a model.
 * Applied by the app's event logger and to the text sent to the signed-in account's model.
 */
object AniobSecretRedactor {
    const val MASK = "[REDACTED]"

    private val BEARER = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val JWT_PREFIXED = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
    private val JWT_GENERIC = Regex("\\b[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\b")
    private val QUERY_PARAM = Regex(
        "(?i)\\b(code|state|refresh_token|access_token|id_token|code_verifier|client_secret)=[^&\\s\"',;]+"
    )
    private val JSON_FIELD = Regex(
        "(?i)(\"(?:refresh_token|access_token|id_token|code_verifier)\"\\s*:\\s*\")[^\"]*(\")"
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var s = BEARER.replace(text, "Bearer $MASK")
        s = JSON_FIELD.replace(s, "$1$MASK$2")
        s = QUERY_PARAM.replace(s) { "${it.groupValues[1]}=$MASK" }
        s = JWT_PREFIXED.replace(s, MASK)
        s = JWT_GENERIC.replace(s, MASK)
        return s
    }
}
