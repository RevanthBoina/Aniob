package com.aniob.app.auth

import com.aniob.core.auth.AniobAuthRepository
import com.aniob.core.auth.AniobAuthTokens
import com.aniob.core.auth.AniobHttpReply
import com.aniob.core.auth.AniobTokenClient
import com.aniob.core.external.AniobGatewayClassifier
import com.aniob.core.external.AniobGatewayException
import com.aniob.core.external.AniobGatewayHealth
import com.aniob.core.external.GatewayFailureKind
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class AniobAccountModelClientTest {
    private class Seen(val auth: String?, val account: String?, val originator: String?, val beta: String?, val body: String)

    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Seen>()
    private var handler: (HttpExchange, Int) -> Unit = { ex, _ -> ex.reply(200, "text/event-stream", ok("hi")) }
    private val clock = MutableClock()

    private fun HttpExchange.reply(code: Int, type: String, body: String, extra: Map<String, String> = emptyMap()) {
        responseHeaders.add("Content-Type", type); extra.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong()); responseBody.use { it.write(bytes) }
    }

    private fun ok(text: String) =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"$text\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n"

    @Before fun start() {
        AniobAccountModelClient.rateLimitedUntil.set(0L)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/responses") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("chatgpt-account-id"),
                ex.requestHeaders.getFirst("originator"), ex.requestHeaders.getFirst("OpenAI-Beta"), body)
            handler(ex, seen.size)
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}/api"

    private fun tokens(access: String = "acc-1", exp: Long = clock.t + 3_600_000L) = AniobAuthTokens(
        access, "ref-1", null, exp, accountId = "acct-9", email = "j@x.io", planType = "plus", clientId = "test_client", scopes = setOf("chatgpt.tokens.use.direct")
    )

    private suspend fun repo(
        store: MemoryTokenStore = MemoryTokenStore(tokens()),
        refresh: () -> AniobHttpReply = { AniobHttpReply(200, "application/json", """{"access_token":"acc-2","refresh_token":"ref-2","expires_in":3600}""") }
    ): Pair<AniobAuthRepository, ScriptedTokenTransport> {
        val t = ScriptedTokenTransport(refresh)
        val r = AniobAuthRepository(store, AniobTokenClient(testConfig(base), t, clock), clock)
        r.initialize()
        return r to t
    }

    private fun client(repo: AniobAuthRepository, counter: IntArray = IntArray(1)) = AniobAccountModelClient(
        config = testConfig(base), repository = repo, model = "m-1", reasoningEffort = "low", sessionId = "sess-1",
        onLlmRequest = { counter[0]++ }, health = AniobGatewayHealth(), clock = clock,
        logger = { _, _ -> }
    )

    private fun failure(r: Result<String>) = r.exceptionOrNull() as AniobGatewayException

    @Test fun `streams text, sends the account headers and the Responses body`() = runBlocking<Unit> {
        val (r, _) = repo()
        val deltas = mutableListOf<String>()
        val out = client(r).generateStreaming("hello", "be brief") { deltas += it }
        assertEquals("hi", out); assertEquals(listOf("hi"), deltas)
        val s = seen.single()
        assertEquals("Bearer acc-1", s.auth); assertNull(s.account)
        assertEquals("aniob-test", s.originator); assertEquals("responses=test", s.beta)
        assertTrue(s.body.contains("\"stream\":true")); assertTrue(s.body.contains("\"store\":false"))
        assertTrue(s.body.contains("\"model\":\"m-1\"")); assertTrue(s.body.contains("\"effort\":\"low\""))
        assertFalse(s.body.contains("max_output_tokens"))
    }

    @Test fun `step provider returns raw text and reports first token`() = runBlocking<Unit> {
        val (r, _) = repo(); var first = 0
        val res = client(r).getNextActionRawStreaming("sys", "obs", null, 512) { first++ }
        assertEquals("hi", res.getOrThrow()); assertEquals(1, first)
    }

    @Test fun `401 refreshes once and resends once with the new token`() = runBlocking<Unit> {
        handler = { ex, n -> if (n == 1) ex.reply(401, "application/json", "{}") else ex.reply(200, "text/event-stream", ok("done")) }
        val (r, transport) = repo(); val counter = IntArray(1)
        val res = client(r, counter).getNextActionRaw("s", "o")
        assertEquals("done", res.getOrThrow())
        assertEquals(2, seen.size); assertEquals(1, transport.calls)
        assertEquals("Bearer acc-1", seen[0].auth); assertEquals("Bearer acc-2", seen[1].auth)
        assertEquals(2, counter[0])
    }

    @Test fun `a second 401 is final, after exactly two requests`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(401, "application/json", "{}") }
        val (r, transport) = repo()
        val f = failure(client(r).getNextActionRaw("s", "o"))
        assertEquals(GatewayFailureKind.AUTH, f.kind); assertEquals(2, seen.size); assertEquals(1, transport.calls)
    }

    @Test fun `403 is an auth failure with a reason, 429 honours Retry-After and then fails fast`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(403, "application/json", """{"detail":"plan does not include this"}""") }
        val (r, _) = repo()
        val c = client(r)
        assertEquals(GatewayFailureKind.AUTH, failure(c.getNextActionRaw("s", "o")).kind)

        // The 403 opened the auth cool-down for that client's health; a 429 is judged on a fresh one.
        handler = { ex, _ -> ex.reply(429, "application/json", "{}", mapOf("Retry-After" to "20")) }
        val f1 = failure(client(r).getNextActionRaw("s", "o"))
        assertTrue(f1.message!!.contains("(429)"))
        val before = seen.size
        val counter = IntArray(1)
        val f2 = failure(client(r, counter).getNextActionRaw("s", "o"))
        assertTrue(f2.message!!.contains("(429)"))
        assertEquals("no request while rate limited", before, seen.size)
        assertEquals("a skipped request is not an LLM call", 0, counter[0])
        clock.t += 21_000L // the window passes
        handler = { ex, _ -> ex.reply(200, "text/event-stream", ok("back")) }
        assertEquals("back", client(r).getNextActionRaw("s", "o").getOrThrow())
    }

    @Test fun `an html error page is classified, its body is never echoed`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(502, "text/html", "<html><head><title>Bad Gateway</title></head><body>INTERNAL-BODY</body></html>") }
        val (r, _) = repo()
        val f = failure(client(r).getNextActionRaw("s", "o"))
        assertEquals(GatewayFailureKind.SERVER_ERROR, f.kind)
        assertTrue(f.message!!.contains("Bad Gateway")); assertFalse(f.message!!.contains("INTERNAL-BODY"))
        assertEquals(GatewayFailureKind.SERVER_ERROR, AniobGatewayClassifier.kindOf(f))
    }

    @Test fun `a 200 html page is rejected before parsing`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(200, "text/html", "<!doctype html><html><title>Login</title></html>") }
        val (r, _) = repo()
        assertEquals(GatewayFailureKind.NOT_JSON, failure(client(r).getNextActionRaw("s", "o")).kind)
    }

    @Test fun `buffered json response is accepted and parsed`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(200, "application/json", """{"response":{"output":[{"type":"message","content":[{"type":"output_text","text":"buffered ok"}]}]}}""") }
        val (r, _) = repo()
        val res = client(r).getNextActionRaw("s", "o")
        assertEquals("buffered ok", res.getOrThrow())
    }

    @Test fun `a mid-stream disconnect is a failure, never a made-up answer`() = runBlocking<Unit> {
        handler = { ex, _ ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            val part = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"par\"}\n\n".toByteArray()
            ex.sendResponseHeaders(200, 100_000L); ex.responseBody.write(part); ex.responseBody.flush(); ex.close()
        }
        val (r, _) = repo()
        val res = client(r).getNextActionRaw("s", "o")
        assertTrue(res.isFailure); assertEquals(GatewayFailureKind.UNREACHABLE, failure(res).kind)
    }

    @Test fun `a stream with a failed event is a typed failure`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(200, "text/event-stream", "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"x\",\"message\":\"Bearer abcdefghijklmnopqrst bad\"}}}\n\n") }
        val (r, _) = repo()
        val f = failure(client(r).getNextActionRaw("s", "o"))
        assertFalse(f.message!!.contains("abcdefghijklmnopqrst"))
    }

    @Test fun `signed out makes no request and is not counted as an LLM call`() = runBlocking<Unit> {
        val (r, _) = repo(MemoryTokenStore(null)); val counter = IntArray(1)
        val f = failure(client(r, counter).getNextActionRaw("s", "o"))
        assertEquals(GatewayFailureKind.AUTH, f.kind); assertTrue(f.message!!.contains("sign in again"))
        assertEquals(0, seen.size); assertEquals(0, counter[0])
    }

    @Test fun `Q and A path returns the sentinel the trigger understands instead of throwing`() = runBlocking<Unit> {
        handler = { ex, _ -> ex.reply(503, "application/json", """{"error":"busy"}""") }
        val (r, _) = repo()
        assertTrue(client(r).generate("q", "s").startsWith("Request failed:"))
        assertNotNull(client(r).name)
    }

    @Test fun `a token that is about to expire is refreshed before the request`() = runBlocking<Unit> {
        val (r, transport) = repo(MemoryTokenStore(tokens(exp = clock.t + 30_000L)))
        client(r).getNextActionRaw("s", "o").getOrThrow()
        assertEquals(1, transport.calls); assertEquals("Bearer acc-2", seen.single().auth)
    }
}
