package com.aniob.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class AniobGatewayResilienceTest {

    private val nextHtml404 =
        "<!DOCTYPE html><html><head><title>404: This page could not be found.</title></head><body>…</body></html>"

    @Test
    fun `an html 404 is classified and summarised to its title, never echoed`() {
        val failure = AniobGatewayClassifier.classifyHttp(404, "text/html; charset=utf-8", nextHtml404, "https://x/v1")
        assertNotNull(failure)
        assertEquals(GatewayFailureKind.NOT_FOUND, failure!!.kind)
        assertEquals(404, failure.httpCode)
        assertTrue(failure.message!!.startsWith("Omniroute Cloud API error (404)"))
        assertTrue(failure.message!!.contains("404: This page could not be found."))
        assertFalse("the page itself must not leak into the message", failure.message!!.contains("<html"))
    }

    @Test
    fun `a 200 that is html is rejected before any json parsing`() {
        val failure = AniobGatewayClassifier.classifyHttp(200, "text/html", nextHtml404)
        assertEquals(GatewayFailureKind.NOT_JSON, failure?.kind)
    }

    @Test
    fun `json and event streams are accepted, bare json without a content type too`() {
        assertNull(AniobGatewayClassifier.classifyHttp(200, "application/json; charset=utf-8", "{\"choices\":[]}"))
        assertNull(AniobGatewayClassifier.classifyHttp(200, "text/event-stream", "data: {}\n\n"))
        assertNull(AniobGatewayClassifier.classifyHttp(200, null, "{\"choices\":[]}"))
        assertNull(AniobGatewayClassifier.classifyHttp(200, "text/plain", " [1]"))
        assertEquals(GatewayFailureKind.NOT_JSON, AniobGatewayClassifier.classifyHttp(200, "text/plain", "hello")?.kind)
    }

    @Test
    fun `status codes map to kinds and only the right ones are rate limited by a cool-down`() {
        fun kind(code: Int) = AniobGatewayClassifier.classifyHttp(code, "application/json", "{\"error\":\"x\"}")?.kind
        assertEquals(GatewayFailureKind.AUTH, kind(401))
        assertEquals(GatewayFailureKind.AUTH, kind(403))
        assertEquals(GatewayFailureKind.NOT_FOUND, kind(404))
        assertEquals(GatewayFailureKind.CLIENT_ERROR, kind(400))
        assertEquals(GatewayFailureKind.CLIENT_ERROR, kind(429))
        assertEquals(GatewayFailureKind.SERVER_ERROR, kind(502))
        assertEquals("a 400 is request-specific and must not take the gateway out", 0L, GatewayFailureKind.CLIENT_ERROR.cooldownMs)
    }

    @Test
    fun `status code text stays greppable for the callers that match on it`() {
        val failure = AniobGatewayClassifier.classifyHttp(401, "application/json", "{\"error\":\"bad key\"}")
        assertTrue(failure!!.message!!.contains("(401)"))
    }

    @Test
    fun `breaker opens for the cool-down then closes, and success closes it early`() {
        var now = 1_000L
        val breaker = AniobGatewayCircuitBreaker { now }
        assertFalse(breaker.isOpen("a"))
        breaker.recordFailure("a", GatewayFailureKind.NOT_FOUND)
        assertTrue(breaker.isOpen("a"))
        now += GatewayFailureKind.NOT_FOUND.cooldownMs - 1
        assertTrue(breaker.isOpen("a"))
        now += 1
        assertFalse(breaker.isOpen("a"))

        breaker.recordFailure("a", GatewayFailureKind.SERVER_ERROR)
        breaker.recordSuccess("a")
        assertFalse(breaker.isOpen("a"))

        breaker.recordFailure("a", GatewayFailureKind.CLIENT_ERROR)
        assertFalse(breaker.isOpen("a"))
    }

    @Test
    fun `an unreachable local gateway is skipped after one probe and remembered`() {
        var probes = 0
        var now = 0L
        val health = AniobGatewayHealth(
            breaker = AniobGatewayCircuitBreaker { now },
            probe = { _, _ -> probes++; false },
            clock = { now }
        )
        val first = health.admit("http://localhost:20128/v1/chat/completions", isLocal = true)
        assertTrue(first is AniobGatewayHealth.Admission.Skip)
        assertTrue((first as AniobGatewayHealth.Admission.Skip).reason.contains("adb reverse"))
        health.admit("http://localhost:20128/v1/chat/completions", isLocal = true)
        assertEquals("the second look must not spend another probe", 1, probes)
    }

    @Test
    fun `a reachable local gateway is probed once per ttl and a remote one is never probed`() {
        var probes = 0
        var now = 0L
        val health = AniobGatewayHealth(
            breaker = AniobGatewayCircuitBreaker { now },
            probe = { _, _ -> probes++; true },
            clock = { now },
            reachableTtlMs = 10_000L
        )
        val local = "http://localhost:20128/v1/chat/completions"
        assertEquals(AniobGatewayHealth.Admission.Go, health.admit(local, isLocal = true))
        assertEquals(AniobGatewayHealth.Admission.Go, health.admit(local, isLocal = true))
        assertEquals(1, probes)
        now += 10_001
        health.admit(local, isLocal = true)
        assertEquals(2, probes)
        assertEquals(AniobGatewayHealth.Admission.Go, health.admit("https://gw.example/v1/chat/completions", isLocal = false))
        assertEquals(2, probes)
    }

    @Test
    fun `a failed endpoint is skipped until reset`() {
        val health = AniobGatewayHealth(probe = { _, _ -> true })
        val url = "https://gw.example/v1/chat/completions"
        health.recordFailure(url, GatewayFailureKind.NOT_JSON)
        assertTrue(health.admit(url, isLocal = false) is AniobGatewayHealth.Admission.Skip)
        health.reset()
        assertEquals(AniobGatewayHealth.Admission.Go, health.admit(url, isLocal = false))
    }

    @Test
    fun `the real probe sees an open port and a closed one`() {
        ServerSocket(0).use { server ->
            assertTrue(AniobGatewayProbe.isReachable("http://127.0.0.1:${server.localPort}/v1", 500))
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        assertFalse(AniobGatewayProbe.isReachable("http://127.0.0.1:$closedPort/v1", 500))
        assertFalse(AniobGatewayProbe.isReachable("not a url", 500))
    }
}
