package com.aniob.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AniobTokenScopeTest {
    private fun client(scope: Any? = null, includeScope: Boolean = false): AniobTokenClient {
        val body = AniobAuthJson.parseObject(AniobAuthFixtures.tokenBody("access", "refresh"))!!.toMutableMap()
        if (includeScope) body["scope"] = scope else body.remove("scope")
        // The production JSON writer omits null map values; put an explicit wire null into this fixture.
        val json = if (includeScope && scope == null) {
            AniobAuthJson.toJson(body).dropLast(1) + ",\"scope\":null}"
        } else AniobAuthJson.toJson(body)
        return AniobTokenClient(AniobAuthFixtures.config,
            AniobAuthFixtures.FakeTransport { _, _, _ -> AniobAuthFixtures.json(200, json) },
            AniobAuthFixtures.FakeClock(), AniobAuthFixtures.verifier)
    }

    @Test fun `initial omitted scope means exactly the requested scopes`() = runTest {
        val result = client().exchangeCode("code", "verifier", "test_client", "nonce") as AniobTokenResult.Success
        assertEquals(AniobAuthFixtures.config.scopes.split(' ').toSet(), result.tokens.scopes)
    }

    @Test fun `refresh omission preserves previous grant rather than requested scopes`() = runTest {
        val previous = AniobAuthFixtures.tokens(expiresAt = 1L).copy(scopes = setOf("chatgpt.tokens.use.direct", "previous-only"))
        val result = client().refresh(previous) as AniobTokenResult.Success
        assertEquals(previous.scopes, result.tokens.scopes)
    }

    @Test fun `explicit empty narrowed null or malformed scopes never fall back`() = runTest {
        for (scope in listOf("", "openid profile", null, 42)) {
            val c = client(scope, includeScope = true)
            assertEquals(AniobTokenResult.Failure(AniobAuthError.PlanHasNoAccess),
                c.exchangeCode("code", "verifier", "test_client", "nonce"))
            assertEquals(AniobTokenResult.Failure(AniobAuthError.PlanHasNoAccess),
                c.refresh(AniobAuthFixtures.tokens(expiresAt = 1L)))
        }
    }

    @Test fun `explicit valid scope is authoritative and is not expanded`() = runTest {
        val result = client("chatgpt.tokens.use.direct", true)
            .exchangeCode("code", "verifier", "test_client", "nonce") as AniobTokenResult.Success
        assertEquals(setOf("chatgpt.tokens.use.direct"), result.tokens.scopes)
    }
}
