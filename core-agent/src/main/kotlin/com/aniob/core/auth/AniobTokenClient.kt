package com.aniob.core.auth

import com.aniob.core.external.AniobGatewayClassifier
import java.io.IOException

/** A transport-level reply. The body is only ever parsed here, never logged or shown. */
class AniobHttpReply(val status: Int, val contentType: String?, val body: String) {
    override fun toString(): String = "AniobHttpReply(status=$status)"
}

/** POSTs a body to the token address. The Android side adds proxy, timeouts and TLS. */
interface AniobTokenTransport {
    @Throws(IOException::class)
    suspend fun post(url: String, contentType: String, body: String): AniobHttpReply
}

sealed interface AniobTokenResult {
    class Success(val tokens: AniobAuthTokens) : AniobTokenResult {
        override fun toString(): String = "Success(<redacted>)"
    }

    data class Failure(val error: AniobAuthError) : AniobTokenResult
}

/** Form-encoded account protocol; identity and granted scopes are validated before persistence. */
class AniobTokenClient(
    internal val config: AniobAuthConfig,
    private val transport: AniobTokenTransport,
    private val clock: AniobClock = AniobClock.SYSTEM,
    private val idVerifier: AniobIdTokenVerifier = AniobIdTokenVerifier.REJECT
) {
    suspend fun exchangeCode(code: String, codeVerifier: String, clientId: String, nonce: String, redirectUri: String = config.redirectUri): AniobTokenResult =
        call(form(
            "grant_type" to "authorization_code", "client_id" to clientId,
            "code" to code, "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier, "resource" to config.resource
        ), previous = null, clientId = clientId, nonce = nonce)

    suspend fun refresh(previous: AniobAuthTokens): AniobTokenResult = call(form(
        "client_id" to previous.clientId, "grant_type" to "refresh_token",
        "refresh_token" to previous.refreshToken, "resource" to config.resource
    ), previous = previous, clientId = previous.clientId, nonce = null)

    /** Best effort server revocation; local logout always proceeds. */
    suspend fun revoke(tokens: AniobAuthTokens): Boolean = try {
        transport.post(config.issuer.trimEnd('/') + "/api/accounts/oauth/revoke", "application/x-www-form-urlencoded",
            form("client_id" to tokens.clientId, "token" to tokens.refreshToken, "token_type_hint" to "refresh_token")).status == 200
    } catch (e: IOException) { false }

    private fun form(vararg values: Pair<String, String>): String = values.joinToString("&") { (k, v) ->
        AniobAuthorizeUrlBuilder.percentEncode(k) + "=" + AniobAuthorizeUrlBuilder.percentEncode(v)
    }

    private suspend fun call(
        body: String,
        previous: AniobAuthTokens?,
        clientId: String,
        nonce: String?
    ): AniobTokenResult {
        val reply = try {
            transport.post(config.tokenUrl, "application/x-www-form-urlencoded", body)
        } catch (e: IOException) {
            return AniobTokenResult.Failure(AniobAuthError.Network(e))
        }
        val status = reply.status
        val isJson = AniobGatewayClassifier.isJsonContentType(reply.contentType) ||
            reply.body.trimStart().startsWith("{")
        // A web page (captive portal, proxy error) is never fed to the JSON parser, and it is
        // never evidence that a refresh token was rejected: a 403 portal must not wipe a session.
        if (AniobGatewayClassifier.looksLikeHtml(reply.body) || !isJson) {
            return AniobTokenResult.Failure(
                if (status in 200..299) AniobAuthError.Unparseable
                else AniobAuthError.Network(IOException("token service returned a non-JSON reply (http $status)"))
            )
        }
        val json = AniobAuthJson.parseObject(reply.body)
            ?: return AniobTokenResult.Failure(AniobAuthError.Unparseable)

        if (status !in 200..299) {
            if (status >= 500 || status == 429 || status == 408) {
                return AniobTokenResult.Failure(AniobAuthError.Network(IOException("token service http $status")))
            }
            return AniobTokenResult.Failure(failureFor(status, oauthErrorCode(json), previous != null))
        }
        return parseSuccess(json, previous, clientId, nonce)
    }

    private fun failureFor(status: Int, code: String?, isRefresh: Boolean): AniobAuthError =
        if (isRefresh && code in TERMINAL_REFRESH_ERRORS) AniobAuthError.RefreshRejected(code)
        else if (isRefresh) AniobAuthError.RefreshConfiguration(status)
        else AniobAuthError.CodeExchangeRejected(status, code)

    /** `{"error":"invalid_grant"}` or `{"error":{"code":"refresh_token_reused"}}`. */
    private fun oauthErrorCode(json: Map<String, Any?>): String? {
        val error = json["error"]
        return AniobAuthJson.str(error)
            ?: AniobAuthJson.str(AniobAuthJson.obj(error)?.get("code"))
            ?: AniobAuthJson.str(json["code"])
    }

    private suspend fun parseSuccess(
        json: Map<String, Any?>, previous: AniobAuthTokens?, clientId: String, nonce: String?
    ): AniobTokenResult {
        val access = AniobAuthJson.str(json["access_token"])
            ?: return AniobTokenResult.Failure(AniobAuthError.Unparseable)
        val refresh = AniobAuthJson.str(json["refresh_token"]) ?: previous?.refreshToken
            ?: return AniobTokenResult.Failure(AniobAuthError.Unparseable)
        val idToken = if (previous == null) AniobAuthJson.str(json["id_token"]) else previous.idToken

        if (previous == null && (idToken == null || nonce == null || !idVerifier.verify(idToken, clientId, nonce))) {
            return AniobTokenResult.Failure(AniobAuthError.InvalidIdentity)
        }
        val scopes = if (json.containsKey("scope")) {
            (json["scope"] as? String).orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        } else previous?.scopes ?: config.scopes.split(' ').filter { it.isNotBlank() }.toSet()
        // RFC 6749 §5.1: omission means unchanged scope, not an empty grant. An explicit
        // empty/narrowed scope is still authoritative and must never use the fallback.
        if (config.requiredScope !in scopes) return AniobTokenResult.Failure(AniobAuthError.PlanHasNoAccess)
        val idClaims = if (previous == null) AniobJwtClaimsReader.read(idToken) else null
        val now = clock.now()
        val lifetime = AniobAuthJson.long(json["expires_in"]) ?: DEFAULT_LIFETIME_MS / 1000
        if (lifetime !in 1..(7 * 24 * 60 * 60L)) return AniobTokenResult.Failure(AniobAuthError.Unparseable)
        val expiresAt = now + lifetime * 1000L

        return AniobTokenResult.Success(
            AniobAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                idToken = idToken,
                expiresAtEpochMs = expiresAt,
                accountId = idClaims?.accountId ?: previous?.accountId,
                planType = idClaims?.planType ?: previous?.planType,
                email = idClaims?.email ?: previous?.email,
                clientId = clientId, scopes = scopes
            )
        )
    }

    private companion object {
        val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_reused", "refresh_token_expired", "refresh_token_invalidated")
        const val DEFAULT_LIFETIME_MS = 60 * 60 * 1000L
    }
}
