package com.aniob.core.auth

import com.aniob.core.auth.AniobAuthFixtures.FakeClock
import com.aniob.core.auth.AniobAuthFixtures.FakeStore
import com.aniob.core.auth.AniobAuthFixtures.FakeTransport
import com.aniob.core.auth.AniobAuthFixtures.json
import com.aniob.core.auth.AniobAuthFixtures.tokenBody
import com.aniob.core.auth.AniobAuthFixtures.tokens
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AniobRegistrationTest {
    private val clock = FakeClock()
    private val config = AniobAuthFixtures.config.copy(clientId = "dynamic_agent_client")
    private val registration = AniobMemoryRegistrationStore()
    private val store = FakeStore()

    private fun repository(
        transport: AniobTokenTransport,
        verifier: AniobIdTokenVerifier = AniobAuthFixtures.verifier
    ) = AniobAuthRepository(store, AniobTokenClient(config, transport, clock, verifier), clock,
        registrationStore = registration)

    @Test fun `issued identity is saved before exchange and used after restart for refresh`() = runTest {
        val transport = object : AniobTokenTransport {
            val clients = mutableListOf<String?>()
            override suspend fun post(url: String, contentType: String, body: String): AniobHttpReply {
                assertEquals("issued-client", registration.load())
                assertEquals("application/x-www-form-urlencoded", contentType)
                clients += AniobAuthCallbackParser.parseQuery(body)["client_id"]
                return json(200, tokenBody("access", "refresh"))
            }
        }
        val first = repository(transport)
        assertEquals("dynamic_agent_client", first.clientIdForSignIn())
        assertNull(first.completeSignIn("code", "verifier", "nonce", "issued-client"))
        assertEquals("issued-client", store.tokens!!.clientId)
        val restarted = repository(transport)
        restarted.initialize()
        assertEquals("issued-client", restarted.clientIdForSignIn())
        restarted.obtainAccess(rejectedAccessToken = "access")
        assertEquals(listOf("issued-client", "issued-client"), transport.clients)
    }

    @Test fun `missing initial client and conflicting existing client never exchange`() = runTest {
        val transport = FakeTransport { _, _, _ -> error("no exchange expected") }
        val repo = repository(transport)
        assertEquals(AniobAuthError.ClientMismatch, repo.completeSignIn("c", "v", "n"))
        registration.save("old-client")
        assertEquals(AniobAuthError.ClientMismatch, repo.completeSignIn("c", "v", "n", "other-client"))
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun `registration survives exchange failure and normal logout but deletion forgets it`() = runTest {
        val transport = FakeTransport { _, _, _ -> json(400, """{"error":"invalid_grant"}""") }
        val repo = repository(transport)
        assertNotNull(repo.completeSignIn("c", "v", "n", "issued-client"))
        assertEquals("issued-client", repo.clientIdForSignIn())
        repo.signOut()
        assertEquals("issued-client", repo.clientIdForSignIn())
        repo.signOut(forgetRegistration = true)
        assertEquals("dynamic_agent_client", repo.clientIdForSignIn())
    }

    @Test fun `invalid identity cannot create a session`() = runTest {
        val repo = repository(FakeTransport { _, _, _ -> json(200, tokenBody("a", "r")) }, AniobIdTokenVerifier.REJECT)
        assertEquals(AniobAuthError.InvalidIdentity, repo.completeSignIn("c", "v", "n", "issued-client"))
        assertNull(store.tokens)
        assertFalse(repo.state.value is AniobAuthState.SignedIn)
    }

    @Test fun `missing granted plan scope cannot create a session`() = runTest {
        val reply = tokenBody("a", "r").replace("openid chatgpt.tokens.use.direct", "openid")
        val repo = repository(FakeTransport { _, _, _ -> json(200, reply) })
        assertEquals(AniobAuthError.PlanHasNoAccess, repo.completeSignIn("c", "v", "n", "issued-client"))
        assertNull(store.tokens)
    }

    @Test fun `nonterminal refresh configuration error preserves credentials`() = runTest {
        store.tokens = tokens(expiresAt = clock.t - 1)
        val repo = repository(FakeTransport { _, _, _ -> json(400, """{"error":"invalid_client"}""") })
        val result = repo.obtainAccess() as AniobAccessResult.Unavailable
        assertEquals(AniobAuthError.RefreshConfiguration(400), result.error)
        assertNotNull(store.tokens)
    }

    @Test fun `refresh with explicitly narrowed scopes removes unusable session`() = runTest {
        store.tokens = tokens(expiresAt = clock.t - 1)
        val repo = repository(FakeTransport { _, _, _ -> json(200,
            """{"access_token":"a","scope":"openid","expires_in":3600}""") })
        assertEquals(AniobAuthError.PlanHasNoAccess, (repo.obtainAccess() as AniobAccessResult.Unavailable).error)
        assertNull(store.tokens)
    }

    @Test fun `late startup and stale attempt cannot resurrect a signed out session`() = runTest {
        val repo = repository(FakeTransport { _, _, _ -> error("must not exchange") })
        val version = repo.markSigningIn()
        repo.signOut()
        repo.initialize()
        assertEquals(AniobAuthState.SignedOut, repo.state.value)
        assertEquals(AniobAuthError.UserCancelled, repo.completeSignIn("c", "v", "n", "issued-client", expectedVersion = version))
        assertNull(store.tokens)
    }

    @Test fun `logout clears locally even when revocation is offline`() = runTest {
        store.tokens = tokens(expiresAt = clock.t + 3600_000)
        val repo = repository(FakeTransport { _, _, _ -> throw java.io.IOException("offline") })
        assertFalse(repo.signOut())
        assertNull(store.tokens)
        assertEquals(AniobAuthState.SignedOut, repo.state.value)
    }

    @Test fun `callback retains issued client and rejects duplicate fields`() {
        val parsed = AniobAuthCallbackParser.parse("/auth/callback?state=s&code=c&client_id=issued", "/auth/callback", "s")
        assertEquals("issued", (parsed as AniobCallbackResult.Code).clientId)
        val duplicate = AniobAuthCallbackParser.parse("/auth/callback?state=s&code=c&code=x", "/auth/callback", "s")
        assertTrue(duplicate is AniobCallbackResult.Failure)
    }
}
