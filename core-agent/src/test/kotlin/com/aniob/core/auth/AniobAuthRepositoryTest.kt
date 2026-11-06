package com.aniob.core.auth

import com.aniob.core.auth.AniobAuthFixtures.FakeClock
import com.aniob.core.auth.AniobAuthFixtures.FakeStore
import com.aniob.core.auth.AniobAuthFixtures.FakeTransport
import com.aniob.core.auth.AniobAuthFixtures.json
import com.aniob.core.auth.AniobAuthFixtures.tokenBody
import com.aniob.core.auth.AniobAuthFixtures.tokens
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AniobAuthRepositoryTest {
    private val clock = FakeClock()

    private fun repo(store: FakeStore, transport: FakeTransport) =
        AniobAuthRepository(store, AniobTokenClient(AniobAuthFixtures.config, transport, clock), clock)

    @Test fun `sign in exchanges the code with a form body and stores the account`() = runTest {
        val store = FakeStore()
        val transport = FakeTransport { _, _, _ -> json(200, tokenBody("acc-new", "ref-new")) }
        val repository = repo(store, transport)

        assertNull(repository.completeSignIn("the-code", "the-verifier"))

        val (url, type, body) = transport.calls.single()
        assertEquals("https://auth.example.test/oauth/token", url)
        assertEquals("application/x-www-form-urlencoded", type)
        val form = AniobAuthCallbackParser.parseQuery(body)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("http://127.0.0.1:1455/auth/callback", form["redirect_uri"])
        assertEquals("test_client", form["client_id"])
        assertEquals(AniobAuthFixtures.config.inferenceBaseUrl, form["resource"])

        val state = repository.state.value as AniobAuthState.SignedIn
        assertEquals("j***@example.com", state.displayLabel)
        assertEquals("plus", state.planType)
        assertEquals(clock.t + 3_600_000L, state.expiresAtEpochMs)
        assertEquals("acct-123", store.tokens!!.accountId)
    }

    @Test fun `a rejected code is a typed error and stores nothing`() = runTest {
        val store = FakeStore()
        val repository = repo(store, FakeTransport { _, _, _ -> json(400, """{"error":"invalid_grant"}""") })
        val error = repository.completeSignIn("c", "v")
        assertEquals(AniobAuthError.CodeExchangeRejected(400, "invalid_grant"), error)
        assertNull(store.tokens)
        assertTrue(repository.state.value is AniobAuthState.Failed)
    }

    @Test fun `a fresh token is returned without any network call`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 3_600_000L))
        val transport = FakeTransport { _, _, _ -> error("must not be called") }
        val result = repo(store, transport).obtainAccess()
        assertEquals("acc-1", ((result as AniobAccessResult.Granted).grant).accessToken)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun `near expiry refreshes with a JSON body and persists the rotated token before use`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 60_000L)) // < 5 min left
        val transport = FakeTransport { _, _, _ -> json(200, tokenBody("acc-2", "ref-2")) }
        val result = repo(store, transport).obtainAccess()

        val (_, type, body) = transport.calls.single()
        assertEquals("application/json", type)
        val sent = AniobAuthJson.parseObject(body)!!
        assertEquals("refresh_token", sent["grant_type"])
        assertEquals("ref-1", sent["refresh_token"])
        assertEquals("test_client", sent["client_id"])
        assertEquals(AniobAuthFixtures.config.inferenceBaseUrl, sent["resource"])

        assertEquals(listOf("save:ref-2"), store.log) // saved, and nothing else
        assertEquals("acc-2", (result as AniobAccessResult.Granted).grant.accessToken)
        assertEquals("ref-2", store.tokens!!.refreshToken)
    }

    @Test fun `many concurrent callers cause exactly one refresh`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 1_000L))
        val transport = object : AniobTokenTransport {
            var calls = 0
            override suspend fun post(url: String, contentType: String, body: String): AniobHttpReply {
                calls++
                delay(50)
                return json(200, tokenBody("acc-2", "ref-2"))
            }
        }
        val repository = AniobAuthRepository(store, AniobTokenClient(AniobAuthFixtures.config, transport, clock), clock)
        val results = (1..8).map { async { repository.obtainAccess() } }.awaitAll()
        assertEquals(1, transport.calls)
        assertTrue(results.all { (it as AniobAccessResult.Granted).grant.accessToken == "acc-2" })
    }

    @Test fun `a 401 forces one refresh, and a second caller holding the old token does not refresh again`() = runTest {
        val store = FakeStore(tokens(access = "acc-1", expiresAt = clock.t + 3_600_000L))
        val transport = FakeTransport { _, _, _ -> json(200, tokenBody("acc-2", "ref-2")) }
        val repository = repo(store, transport)
        val first = repository.obtainAccess(rejectedAccessToken = "acc-1") as AniobAccessResult.Granted
        val second = repository.obtainAccess(rejectedAccessToken = "acc-1") as AniobAccessResult.Granted
        assertEquals("acc-2", first.grant.accessToken)
        assertEquals("acc-2", second.grant.accessToken)
        assertEquals(1, transport.calls.size)
    }

    @Test fun `invalid_grant clears tokens, expires the session and never loops`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 1_000L))
        val transport = FakeTransport { _, _, _ -> json(400, """{"error":{"code":"refresh_token_reused"}}""") }
        val repository = repo(store, transport)

        val r1 = repository.obtainAccess()
        assertEquals(AniobAccessResult.Unavailable(AniobAuthError.RefreshRejected("refresh_token_reused")), r1)
        assertNull(store.tokens)
        assertEquals(listOf("clear"), store.log)
        assertTrue(repository.state.value is AniobAuthState.Expired)

        repository.obtainAccess() // second attempt: no tokens, so no network call
        assertEquals(1, transport.calls.size)
    }

    @Test fun `a 5xx keeps the tokens, backs off, and still serves a token that has not expired`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 60_000L))
        val transport = FakeTransport { _, _, _ -> json(503, """{"error":"overloaded"}""") }
        val repository = repo(store, transport)

        val r1 = repository.obtainAccess()
        assertEquals("acc-1", (r1 as AniobAccessResult.Granted).grant.accessToken)
        assertNotNull(store.tokens)
        repository.obtainAccess() // inside the back-off window: no second request
        assertEquals(1, transport.calls.size)
        assertTrue(repository.state.value is AniobAuthState.SignedIn)
    }

    @Test fun `an html portal on a refresh is a network problem, never a revoked session`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t - 1L))
        val transport = FakeTransport { _, _, _ -> AniobHttpReply(403, "text/html", "<html><title>Portal</title></html>") }
        val result = repo(store, transport).obtainAccess()
        assertTrue((result as AniobAccessResult.Unavailable).error is AniobAuthError.Network)
        assertNotNull(store.tokens)
    }

    @Test fun `a transport failure is Network and keeps the session`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t - 1L))
        val transport = FakeTransport { _, _, _ -> throw IOException("offline") }
        val result = repo(store, transport).obtainAccess()
        assertTrue((result as AniobAccessResult.Unavailable).error is AniobAuthError.Network)
        assertNotNull(store.tokens)
    }

    @Test fun `initialize restores a session and sign out clears only this device`() = runTest {
        val store = FakeStore(tokens(expiresAt = clock.t + 3_600_000L))
        val repository = repo(store, FakeTransport { _, _, _ -> error("no") })
        repository.initialize()
        assertTrue(repository.state.value is AniobAuthState.SignedIn)
        repository.signOut()
        assertEquals(AniobAuthState.SignedOut, repository.state.value)
        assertNull(store.tokens)
        assertFalse(repository.obtainAccess() is AniobAccessResult.Granted)
    }

    @Test fun `secrets never appear in toString`() {
        val t = tokens(access = "SECRET-ACCESS", refresh = "SECRET-REFRESH", expiresAt = 1L)
        listOf(t.toString(), AniobAccessGrant("SECRET-ACCESS", "a").toString(), AniobPendingSignIn("SECRET-V", "S", 1).toString(), AniobPkce.generate().toString())
            .forEach { assertFalse(it, it.contains("SECRET")) }
    }

    private fun assertNotNull(v: Any?) = org.junit.Assert.assertNotNull(v)
}
