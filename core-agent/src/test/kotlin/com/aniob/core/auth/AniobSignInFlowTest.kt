package com.aniob.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AniobSignInFlowTest {
    private class Pending : AniobPendingSignInStore {
        var value: AniobPendingSignIn? = null
        override suspend fun save(pending: AniobPendingSignIn) { value = pending }
        override suspend fun load() = value
        override suspend fun clear() { value = null }
    }
    private class Listener : AniobCallbackListener {
        var bound = false
        var expectedState: String? = null
        var closed = false
        var completed: Boolean? = null
        val targets = ArrayDeque<String>()
        override fun bind() { bound = true }
        override fun configureAttempt(state: String) { expectedState = state }
        override suspend fun awaitCallback(callbackPath: String, timeoutMs: Long) = targets.removeFirstOrNull()
        override fun complete(success: Boolean) { completed = success }
        override fun close() { closed = true }
    }

    @Test fun `full dynamic attempt binds before browser and passes nonce and issued identity to validation`() = runTest {
        val pending = Pending()
        val listener = Listener()
        var verified = false
        val config = AniobAuthFixtures.config.copy(clientId = "dynamic_agent_client")
        val repo = AniobAuthRepository(AniobAuthFixtures.FakeStore(), AniobTokenClient(config,
            AniobAuthFixtures.FakeTransport { _, _, body ->
                assertEquals("issued", AniobAuthCallbackParser.parseQuery(body)["client_id"])
                AniobAuthFixtures.json(200, AniobAuthFixtures.tokenBody("a", "r"))
            }, idVerifier = AniobIdTokenVerifier { _, client, nonce ->
                verified = client == "issued" && nonce == pending.value?.nonce
                verified
            }))
        val flow = AniobSignInFlow(config, repo, { listener }, { url ->
            assertTrue(listener.bound)
            val params = AniobAuthCallbackParser.parseQuery(url.substringAfter('?'))
            assertEquals("dynamic_agent_client", params["client_id"])
            assertEquals("Aniob", params["agent_name_hint"])
            assertEquals(pending.value!!.nonce, params["nonce"])
            assertEquals(pending.value!!.state, listener.expectedState)
            listener.targets.add("/auth/callback?code=c&state=wrong")
            listener.targets.add("/auth/callback?code=c&state=${params["state"]}&client_id=issued")
            true
        }, pending)
        assertNull(flow.run())
        assertTrue(verified)
        assertEquals(true, listener.completed)
        assertTrue(listener.closed)
        assertNull(pending.value)
    }

    @Test fun `denied browser reply shows failure and clears pending operation`() = runTest {
        val config = AniobAuthFixtures.config
        val pending = Pending()
        val listener = Listener()
        val repo = AniobAuthRepository(AniobAuthFixtures.FakeStore(), AniobTokenClient(config,
            AniobAuthFixtures.FakeTransport { _, _, _ -> error("no token call") }))
        val flow = AniobSignInFlow(config, repo, { listener }, {
            listener.targets.add("/auth/callback?error=access_denied&state=${pending.value!!.state}")
            true
        }, pending)
        assertEquals(AniobAuthError.UserCancelled, flow.run())
        assertEquals(false, listener.completed)
        assertTrue(listener.closed)
        assertNull(pending.value)
    }

    @Test fun `nonce and protocol parameters cannot be overridden by extras`() {
        val config = AniobAuthFixtures.config.copy(extraAuthorizeParams = mapOf(
            "nonce" to "attacker", "client_id" to "attacker", "resource" to "attacker"))
        val params = AniobAuthCallbackParser.parseQuery(AniobAuthorizeUrlBuilder.build(config, "challenge", "state", "nonce").substringAfter('?'))
        assertEquals("nonce", params["nonce"])
        assertEquals("test_client", params["client_id"])
        assertEquals(config.resource, params["resource"])
    }
}
