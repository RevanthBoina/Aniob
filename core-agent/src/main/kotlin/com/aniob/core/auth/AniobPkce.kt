package com.aniob.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A PKCE pair. The verifier is a secret and stays out of [toString]. */
class AniobPkcePair(val verifier: String, val challenge: String) {
    val method: String get() = AniobPkce.METHOD
    override fun toString(): String = "AniobPkcePair(<redacted>)"
}

/** RFC 7636, S256 only. */
object AniobPkce {
    const val METHOD = "S256"
    private const val VERIFIER_BYTES = 32 // 32 bytes -> 43 base64url characters
    private const val STATE_BYTES = 24

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(random: SecureRandom = SecureRandom()): AniobPkcePair {
        val verifier = randomToken(VERIFIER_BYTES, random)
        return AniobPkcePair(verifier, challengeFor(verifier))
    }

    /** `BASE64URL(SHA-256(ASCII(verifier)))`, no padding. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    /** The anti-CSRF `state`, generated independently of the verifier. */
    fun newState(random: SecureRandom = SecureRandom()): String = randomToken(STATE_BYTES, random)

    private fun randomToken(byteCount: Int, random: SecureRandom): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}

/** Builds the browser URL. Pure string work, no Android. */
object AniobAuthorizeUrlBuilder {
    private val RESERVED = setOf(
        "response_type", "client_id", "redirect_uri", "scope",
        "code_challenge", "code_challenge_method", "state", "resource"
    )

    fun build(config: AniobAuthConfig, codeChallenge: String, state: String): String {
        val params = LinkedHashMap<String, String>()
        params["response_type"] = "code"
        params["client_id"] = config.clientId
        params["redirect_uri"] = config.redirectUri
        params["scope"] = config.scopes
        params["code_challenge"] = codeChallenge
        params["code_challenge_method"] = AniobPkce.METHOD
        params["state"] = state
        if (config.inferenceBaseUrl.isNotBlank()) params["resource"] = config.inferenceBaseUrl
        // Extras may add parameters but can never override the protocol ones above.
        config.extraAuthorizeParams.forEach { (k, v) -> if (k !in RESERVED) params[k] = v }
        val query = params.entries.joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }
        val base = config.authorizeUrl.trimEnd('?', '&')
        return base + (if (base.contains('?')) "&" else "?") + query
    }

    /** RFC 3986: everything except unreserved characters is %XX of its UTF-8 bytes. */
    fun percentEncode(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val unreserved = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) out.append(c.toChar()) else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }
}

sealed interface AniobCallbackResult {
    /** The code is a secret: redacted in [toString]. */
    class Code(val code: String) : AniobCallbackResult {
        override fun toString(): String = "Code(<redacted>)"
    }

    data class Failure(val error: AniobAuthError) : AniobCallbackResult

    /** Not the callback (a favicon request, a scanner): keep waiting. */
    data object Ignored : AniobCallbackResult
}

object AniobAuthCallbackParser {
    private val WORKSPACE_CODES = setOf("3p_login_workspace_scope_denied")

    /**
     * [requestTarget] is the path and query of the request the browser made, for example
     * `/auth/callback?code=...&state=...`.
     */
    fun parse(requestTarget: String, expectedPath: String, expectedState: String): AniobCallbackResult {
        val path = requestTarget.substringBefore('?').substringBefore('#')
        if (path != expectedPath) return AniobCallbackResult.Ignored
        val query = requestTarget.substringAfter('?', "").substringBefore('#')
        val params = parseQuery(query)

        // Nothing in the callback is trusted until state matches, errors included.
        val state = params["state"]
        if (state.isNullOrEmpty() || !constantTimeEquals(state, expectedState)) {
            return AniobCallbackResult.Failure(AniobAuthError.StateMismatch)
        }
        val error = params["error"]
        if (!error.isNullOrEmpty()) {
            val description = params["error_description"].orEmpty().lowercase()
            val mapped = when {
                error == "access_denied" && !description.contains("workspace") -> AniobAuthError.UserCancelled
                error in WORKSPACE_CODES || error.contains("workspace", ignoreCase = true) ||
                    description.contains("workspace") -> AniobAuthError.WorkspaceOrAccountDenied(error)
                else -> AniobAuthError.AuthorizationRejected(error)
            }
            return AniobCallbackResult.Failure(mapped)
        }
        val code = params["code"]
        if (code.isNullOrEmpty()) return AniobCallbackResult.Failure(AniobAuthError.Unparseable)
        return AniobCallbackResult.Code(code)
    }

    fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (query.isEmpty()) return out
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            if (key !in out) out[key] = decode(pair.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
