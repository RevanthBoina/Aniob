package com.aniob.core.auth

import java.util.Base64

internal object AniobAuthFixtures {
    val config = AniobAuthConfig(
        clientId = "test_client",
        authorizeUrl = "https://auth.example.test/oauth/authorize",
        tokenUrl = "https://auth.example.test/oauth/token",
        redirectUri = "http://127.0.0.1:1455/auth/callback",
        scopes = "openid profile email offline_access chatgpt.tokens.use.direct",
        inferenceBaseUrl = "https://models.example.test/api",
        extraAuthorizeParams = linkedMapOf("originator" to "aniob", "state" to "must-not-override")
    )

    val verifier = AniobIdTokenVerifier { _, _, _ -> true } // Only transport/repository unit tests; crypto tested separately.

    private val enc = Base64.getUrlEncoder().withoutPadding()

    /** An unsigned, fake JWT. Never a real credential. */
    fun jwt(payloadJson: String): String =
        enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray()) + "." +
            enc.encodeToString(payloadJson.toByteArray()) + ".c2lnbmF0dXJl"

    fun idToken(account: String = "acct-123", email: String = "jane.doe@example.com", plan: String = "plus") =
        jwt("""{"exp":4102444800,"email":"$email","https://api.openai.com/auth":{"chatgpt_account_id":"$account","chatgpt_plan_type":"$plan"}}""")

    class FakeClock(var t: Long = 1_000_000L) : AniobClock {
        override fun now() = t
    }

    class FakeStore(var tokens: AniobAuthTokens? = null) : AniobTokenStore {
        val log = mutableListOf<String>()
        var failSave = false
        override suspend fun load() = tokens
        override suspend fun save(tokens: AniobAuthTokens) {
            if (failSave) throw java.io.IOException("disk full")
            log += "save:${tokens.refreshToken}"
            this.tokens = tokens
        }
        override suspend fun clear() { log += "clear"; tokens = null }
    }

    class FakeTransport(val respond: (String, String, String) -> AniobHttpReply) : AniobTokenTransport {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun post(url: String, contentType: String, body: String): AniobHttpReply {
            calls += Triple(url, contentType, body)
            return respond(url, contentType, body)
        }
    }

    fun json(status: Int, body: String) = AniobHttpReply(status, "application/json", body)

    fun tokenBody(access: String, refresh: String, expiresIn: Long = 3600) =
        """{"access_token":"$access","refresh_token":"$refresh","id_token":"${idToken()}","expires_in":$expiresIn,"scope":"openid chatgpt.tokens.use.direct"}"""

    fun tokens(access: String = "acc-1", refresh: String = "ref-1", expiresAt: Long) = AniobAuthTokens(
        accessToken = access, refreshToken = refresh, expiresAtEpochMs = expiresAt,
        accountId = "acct-123", email = "jane.doe@example.com", planType = "plus", clientId = "test_client", scopes = setOf("chatgpt.tokens.use.direct")
    )
}
