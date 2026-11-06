package com.aniob.core.auth

import java.util.Base64

/**
 * The few fields read out of a token. This is **not** verification: the signature is never
 * checked, so nothing here may drive a security decision. It exists to find the account id the
 * model service expects in a header, the plan name, a masked label and an expiry fallback.
 */
data class AniobJwtClaims(
    val expiresAtEpochSeconds: Long?,
    val email: String?,
    val accountId: String?,
    val planType: String?
)

object AniobJwtClaimsReader {
    /** The nested claim that carries the ChatGPT account facts. */
    const val AUTH_CLAIM = "https://api.openai.com/auth"
    const val PROFILE_CLAIM = "https://api.openai.com/profile"

    private val decoder = Base64.getUrlDecoder() // accepts input without '=' padding

    /** Null for anything malformed. Never throws. */
    fun read(jwt: String?): AniobJwtClaims? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split('.')
        if (parts.size < 2 || parts[1].isEmpty()) return null
        val payload = try {
            String(decoder.decode(parts[1]), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val claims = AniobAuthJson.parseObject(payload) ?: return null
        val auth = AniobAuthJson.obj(claims[AUTH_CLAIM])
        val profile = AniobAuthJson.obj(claims[PROFILE_CLAIM])
        return AniobJwtClaims(
            expiresAtEpochSeconds = AniobAuthJson.long(claims["exp"]),
            email = AniobAuthJson.str(claims["email"]) ?: AniobAuthJson.str(profile?.get("email")),
            accountId = AniobAuthJson.str(auth?.get("chatgpt_account_id")),
            planType = AniobAuthJson.str(auth?.get("chatgpt_plan_type"))
        )
    }
}

/** Display helpers. */
object AniobAccountLabel {
    /** `jane.doe@example.com` -> `j***@example.com`. A string without `@` is fully masked. */
    fun maskEmail(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        return email.first() + "***" + email.substring(at)
    }
}
