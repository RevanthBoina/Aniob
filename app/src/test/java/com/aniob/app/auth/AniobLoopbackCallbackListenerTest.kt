package com.aniob.app.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

class AniobLoopbackCallbackListenerTest {
    private fun get(port: Int, target: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$target").openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 4000
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to body
    }

    private fun raw(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 4000
            s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readText()
        }

    @Test fun `binds the IPv4 loopback address only`() {
        val l = AniobLoopbackCallbackListener(0)
        l.bind()
        try {
            assertEquals("127.0.0.1", l.boundHost)
        } finally { l.close() }
    }

    @Test fun `accepts one callback, returns the target, shows a static page that reflects nothing`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0); l.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { l.awaitCallback("/auth/callback", 10_000) }
            val (code, body) = get(l.boundPort, "/auth/callback?code=SECRET-CODE&state=ST")
            assertEquals(200, code)
            assertTrue(body.contains("You can return to the app"))
            assertFalse(body.contains("SECRET-CODE")); assertFalse(body.contains("ST&"))
            assertEquals("/auth/callback?code=SECRET-CODE&state=ST", waiting.await())
        } finally { l.close() }
    }

    @Test fun `configured listener ignores wrong state and only answers accepted browser after outcome`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0)
        l.configureAttempt("expected")
        l.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { l.awaitCallback("/auth/callback", 10_000) }
            assertEquals(400, get(l.boundPort, "/auth/callback?code=wrong&state=wrong").first)
            assertEquals(400, get(l.boundPort, "/auth/callback?code=a&code=b&state=expected").first)
            val browser = async(kotlinx.coroutines.Dispatchers.IO) {
                get(l.boundPort, "/auth/callback?code=PRIVATE&state=expected")
            }
            assertEquals("/auth/callback?code=PRIVATE&state=expected", waiting.await())
            assertFalse(browser.isCompleted)
            l.complete(false)
            val page = browser.await().second
            assertTrue(page.contains("Sign-in was not completed"))
            assertFalse(page.contains("PRIVATE"))
        } finally { l.close() }
    }

    @Test fun `a favicon request gets a 404 and the wait continues`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0); l.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { l.awaitCallback("/auth/callback", 10_000) }
            assertEquals(404, get(l.boundPort, "/favicon.ico").first)
            assertEquals(200, get(l.boundPort, "/auth/callback?code=c&state=s").first)
            assertEquals("/auth/callback?code=c&state=s", waiting.await())
        } finally { l.close() }
    }

    @Test fun `a foreign Host header is rejected and does not complete the flow`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0); l.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { l.awaitCallback("/auth/callback", 10_000) }
            val reply = raw(l.boundPort, "GET /auth/callback?code=x&state=y HTTP/1.1\r\nHost: evil.example\r\n\r\n")
            assertTrue(reply.startsWith("HTTP/1.1 400"))
            assertEquals(200, get(l.boundPort, "/auth/callback?code=ok&state=s").first)
            assertEquals("/auth/callback?code=ok&state=s", waiting.await())
        } finally { l.close() }
    }

    @Test fun `both loopback names accept bare and bound port authorities`() = runBlocking<Unit> {
        for (name in listOf("127.0.0.1", "localhost")) for (withPort in listOf(false, true)) {
            val listener = AniobLoopbackCallbackListener(0); listener.bind()
            try {
                val waiting = async(kotlinx.coroutines.Dispatchers.IO) { listener.awaitCallback("/auth/callback", 10_000) }
                val host = name + if (withPort) ":${listener.boundPort}" else ""
                assertTrue(raw(listener.boundPort, "GET /auth/callback?code=c&state=s HTTP/1.1\r\nHost: $host\r\n\r\n").startsWith("HTTP/1.1 200"))
                assertEquals("/auth/callback?code=c&state=s", waiting.await())
            } finally { listener.close() }
        }
    }

    @Test fun `wrong ports duplicate hosts and lookalike names are rejected`() = runBlocking<Unit> {
        val listener = AniobLoopbackCallbackListener(0); listener.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { listener.awaitCallback("/auth/callback", 10_000) }
            for (host in listOf("localhost:0", "127.0.0.1:0", "localhost.evil.test", "user@localhost", "[::1]")) {
                assertTrue(raw(listener.boundPort, "GET /auth/callback HTTP/1.1\r\nHost: $host\r\n\r\n").startsWith("HTTP/1.1 400"))
            }
            assertTrue(raw(listener.boundPort, "GET /auth/callback HTTP/1.1\r\nHost: localhost\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertEquals(200, get(listener.boundPort, "/auth/callback?code=c&state=s").first)
            waiting.await()
        } finally { listener.close() }
    }

    @Test fun `non GET methods are rejected`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0); l.bind()
        try {
            val waiting = async(kotlinx.coroutines.Dispatchers.IO) { l.awaitCallback("/auth/callback", 10_000) }
            assertTrue(raw(l.boundPort, "POST /auth/callback HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 400"))
            get(l.boundPort, "/auth/callback?code=c&state=s"); waiting.await()
        } finally { l.close() }
    }

    @Test fun `no callback returns null at the timeout`() = runBlocking<Unit> {
        val l = AniobLoopbackCallbackListener(0); l.bind()
        try { assertNull(l.awaitCallback("/auth/callback", 600)) } finally { l.close() }
    }

    @Test fun `a busy preferred port falls back to a different free loopback port`() {
        val first = AniobLoopbackCallbackListener(0); first.bind()
        val second = AniobLoopbackCallbackListener(first.boundPort)
        try {
            second.bind()
            assertTrue(second.boundPort > 0)
            assertFalse(second.boundPort == first.boundPort)
            assertEquals("127.0.0.1", second.boundHost)
        } finally { second.close(); first.close() }
    }

    @Test fun `a listener can rebind its port right after it was closed`() {
        val first = AniobLoopbackCallbackListener(0); first.bind()
        val port = first.boundPort
        first.close()
        val again = AniobLoopbackCallbackListener(port)
        try { again.bind(); assertEquals(port, again.boundPort) } finally { again.close() }
    }
}
