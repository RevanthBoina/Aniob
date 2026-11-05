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

/**
 * Authorization-code exchange (form encoded) and refresh (JSON), the two shapes the reference
 * command-line client uses against the same address. Responses are classified by status and
 * content type before parsing, like `AniobGatewayClassifier` does for the model gateway.
 */
class AniobTokenClient(
    private val config: AniobAuthConfig,
    private val transport: AniobTokenTransport,
    private val clock: AniobClock = AniobClock.SYSTEM
) {
    suspend fun exchangeCode(code: String, codeVerifier: String): AniobTokenResult {
        val form = listOf(
            "grant_type" to "authorization_code",
            "client_id" to config.clientId,
            "code" to code,
            "redirect_uri" to config.redirectUri,
            "code_verifier" to codeVerifier,
            "resource" to "https://api.openai.com/v1"
        ).joinToString("&") { (k, v) ->
            AniobAuthorizeUrlBuilder.percentEncode(k) + "=" + AniobAuthorizeUrlBuilder.percentEncode(v)
        }
        return call(form, "application/x-www-form-urlencoded", previous = null, isRefresh = false)
    }

    /** [previous] supplies the refresh token when the server does not rotate it. */
    suspend fun refresh(previous: AniobAuthTokens): AniobTokenResult {
        val json = AniobAuthJson.toJson(
            linkedMapOf(
                "client_id" to config.clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to previous.refreshToken,
                "resource" to "https://api.openai.com/v1"
            )
        )
        return call(json, "application/json", previous = previous, isRefresh = true)
    }

    private suspend fun call(
        body: String,
        contentType: String,
        previous: AniobAuthTokens?,
        isRefresh: Boolean
    ): AniobTokenResult {
        val reply = try {
            transport.post(config.tokenUrl, contentType, body)
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
            return AniobTokenResult.Failure(failureFor(status, oauthErrorCode(json), isRefresh))
        }
        return parseSuccess(json, previous)
    }

    private fun failureFor(status: Int, code: String?, isRefresh: Boolean): AniobAuthError =
        if (isRefresh) AniobAuthError.RefreshRejected(code)
        else AniobAuthError.CodeExchangeRejected(status, code)

    /** `{"error":"invalid_grant"}` or `{"error":{"code":"refresh_token_reused"}}`. */
    private fun oauthErrorCode(json: Map<String, Any?>): String? {
        val error = json["error"]
        return AniobAuthJson.str(error)
            ?: AniobAuthJson.str(AniobAuthJson.obj(error)?.get("code"))
            ?: AniobAuthJson.str(json["code"])
    }

    private fun parseSuccess(json: Map<String, Any?>, previous: AniobAuthTokens?): AniobTokenResult {
        val access = AniobAuthJson.str(json["access_token"])
            ?: return AniobTokenResult.Failure(AniobAuthError.Unparseable)
        val refresh = AniobAuthJson.str(json["refresh_token"]) ?: previous?.refreshToken
            ?: return AniobTokenResult.Failure(AniobAuthError.Unparseable)
        val idToken = AniobAuthJson.str(json["id_token"]) ?: previous?.idToken

        val accessClaims = AniobJwtClaimsReader.read(access)
        val idClaims = AniobJwtClaimsReader.read(idToken)
        val now = clock.now()
        val expiresAt = AniobAuthJson.long(json["expires_in"])?.let { now + it * 1000L }
            ?: accessClaims?.expiresAtEpochSeconds?.let { it * 1000L }
            ?: (now + DEFAULT_LIFETIME_MS)

        return AniobTokenResult.Success(
            AniobAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                idToken = idToken,
                expiresAtEpochMs = expiresAt,
                accountId = idClaims?.accountId ?: accessClaims?.accountId ?: previous?.accountId,
                planType = idClaims?.planType ?: accessClaims?.planType ?: previous?.planType,
                email = idClaims?.email ?: accessClaims?.email ?: previous?.email
            )
        )
    }

    private companion object {
        const val DEFAULT_LIFETIME_MS = 60 * 60 * 1000L
    }
}
