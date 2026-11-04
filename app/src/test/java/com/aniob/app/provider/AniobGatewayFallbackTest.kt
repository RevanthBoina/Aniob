package com.aniob.app.provider

import com.aniob.core.external.AniobGatewayException
import com.aniob.core.external.AniobGatewayHealth
import com.aniob.core.external.GatewayFailureKind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The field failure: a gateway address that answers an HTML 404 page. The provider must classify it
 * once, say so in one line, stop re-sending to it, and hand the caller a typed failure it can
 * downgrade on, instead of leaking the page or retrying 4-6 times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AniobGatewayFallbackTest {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private var status = 404
    private var contentType = "text/html; charset=utf-8"
    private var body = "<!DOCTYPE html><html><head><title>404: This page could not be found.</title></head></html>"

    @Before
    fun setUp() {
        hits.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    private fun provider(health: AniobGatewayHealth, llmCalls: AtomicInteger = AtomicInteger()) =
        AniobOmniRouteProvider(
            apiKey = "",
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            onLlmRequest = { llmCalls.incrementAndGet() },
            health = health
        )

    @Test
    fun `an html 404 becomes a typed one-line failure and the endpoint is not retried`() = runBlocking {
        val health = AniobGatewayHealth()
        val calls = AtomicInteger()
        val first = provider(health, calls).getNextActionRawStreaming("system", "user")

        assertTrue(first.isFailure)
        val error = first.exceptionOrNull()
        assertTrue("typed failure expected but was $error", error is AniobGatewayException)
        assertEquals(GatewayFailureKind.NOT_FOUND, (error as AniobGatewayException).kind)
        assertTrue(error.message!!.contains("404: This page could not be found."))
        assertFalse("the page must not leak", error.message!!.contains("<html"))
        val hitsAfterFirst = hits.get()
        assertEquals("one rejected request, no streaming-then-plain double send", 1, hitsAfterFirst)

        // The second step of the same run: no request leaves the device, and nothing is counted.
        val second = provider(health, calls).getNextActionRawStreaming("system", "user")
        assertTrue(second.isFailure)
        assertEquals(hitsAfterFirst, hits.get())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a 200 html page is rejected before json parsing`() = runBlocking {
        status = 200
        val result = provider(AniobGatewayHealth()).getNextActionRaw("system", "user")
        val error = result.exceptionOrNull()
        assertTrue(error is AniobGatewayException)
        assertEquals(GatewayFailureKind.NOT_JSON, (error as AniobGatewayException).kind)
    }

    @Test
    fun `a valid json reply still succeeds`() = runBlocking {
        status = 200
        contentType = "application/json"
        body = """{"choices":[{"message":{"role":"assistant","content":"{\"type\":\"finish\"}"}}]}"""
        val result = provider(AniobGatewayHealth()).getNextActionRaw("system", "user")
        assertTrue(result.isSuccess)
        assertEquals("""{"type":"finish"}""", result.getOrThrow())
    }

    @Test
    fun `an unreachable local gateway fails fast without sending anything`() = runBlocking {
        val dead = server.address.port
        server.stop(0)
        val calls = AtomicInteger()
        val p = AniobOmniRouteProvider(
            apiKey = "",
            baseUrl = "http://127.0.0.1:$dead/v1",
            onLlmRequest = { calls.incrementAndGet() },
            health = AniobGatewayHealth()
        )
        val started = System.currentTimeMillis()
        val result = p.getNextActionRawStreaming("system", "user")
        assertTrue(result.isFailure)
        assertEquals(GatewayFailureKind.UNREACHABLE, (result.exceptionOrNull() as AniobGatewayException).kind)
        assertEquals("no request was counted", 0, calls.get())
        assertTrue("must answer well inside a connect timeout", System.currentTimeMillis() - started < 3_000)
        // tearDown stops an already stopped server, which is a no-op.
    }

    @Test
    fun `the implicit candidate ladder no longer includes the hosted cloud`() {
        val candidates = AniobProviderConfig.candidateEndpoints("http://localhost:20128/v1")
        assertEquals(
            listOf(
                "http://localhost:20128/v1/chat/completions",
                "http://10.0.2.2:20128/v1/chat/completions"
            ),
            candidates
        )
        assertFalse(candidates.any { it.contains("omniroute.online") })
        // Picking the hosted address explicitly still works.
        assertEquals(
            listOf("https://omniroute.online/v1/chat/completions"),
            AniobProviderConfig.candidateEndpoints(AniobProviderConfig.OMNIROUTE_CLOUD_BASE_URL)
        )
    }
}
