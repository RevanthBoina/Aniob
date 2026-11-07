package com.aniob.app.auth

import com.aniob.core.auth.AniobCallbackListener
import com.aniob.core.auth.AniobPortInUseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Receives the browser redirect on `127.0.0.1:<port>` and nowhere else.
 *
 * - bound to the IPv4 loopback address only, never a wildcard address;
 * - answers exactly one request on the callback path, with a fixed page that reflects nothing
 *   from the request, then closes;
 * - rejects a `Host` header that is not the loopback address (a DNS-rebinding guard);
 * - any other path gets a 404 and the wait continues, so a favicon request cannot end the flow.
 *
 * It lives only while sign-in is running; a foreground service protects the browser round-trip.
 */
class AniobLoopbackCallbackListener(
    private val port: Int,
    private val now: () -> Long = System::currentTimeMillis
) : AniobCallbackListener {

    @Volatile
    private var server: ServerSocket? = null
    private var expectedState: String? = null
    private var accepted: Socket? = null

    override fun configureAttempt(state: String) { expectedState = state }

    override fun complete(success: Boolean) {
        val client = accepted ?: return
        accepted = null
        try { client.use { respond(it, 200, if (success) SUCCESS_PAGE else FAILURE_PAGE, html = true) } }
        catch (e: IOException) { lastCloseError = e.javaClass.simpleName }
    }

    /** The actual port (useful when 0 is requested in tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    /** The address the socket is bound to; always the IPv4 loopback address. */
    val boundHost: String? get() = server?.inetAddress?.hostAddress

    override fun bind() {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByAddress(LOOPBACK), port), 1)
            socket.soTimeout = POLL_MS
            server = socket
        } catch (e: BindException) {
            socket.close()
            throw AniobPortInUseException(e)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    override suspend fun awaitCallback(callbackPath: String, timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            val socket = server ?: return@withContext null
            val deadline = now() + timeoutMs
            while (now() < deadline) {
                currentCoroutineContext().ensureActive()
                val client = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    return@withContext null // closed from another thread (cancel)
                }
                val target = try { handle(client, callbackPath, deadline) } finally {
                    if (accepted !== client) client.close()
                }
                if (target != null) return@withContext target
            }
            null
        }

    /** Returns the request target when it is the callback, else null (and the wait goes on). */
    private fun handle(client: Socket, callbackPath: String, deadline: Long): String? {
        try {
            val readDeadline = minOf(deadline, now() + READ_TIMEOUT_MS)
            val input = client.getInputStream()
            var remaining = MAX_HEADER_BYTES
            fun line(): String? {
                val result = StringBuilder()
                while (remaining-- > 0) {
                    val wait = readDeadline - now()
                    if (wait <= 0L) return null
                    client.soTimeout = wait.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
                    val c = input.read()
                    if (c == -1) return null
                    if (c == 10) return result.toString().removeSuffix("\r")
                    result.append(c.toChar())
                }
                return null
            }
            val requestLine = line() ?: return null
            var host: String? = null
            while (true) {
                val header = line() ?: return null
                if (header.isEmpty()) break
                if (header.startsWith("host:", ignoreCase = true)) {
                    if (host != null) { respond(client, 400, "Bad request"); return null }
                    host = header.substringAfter(':').trim().lowercase(java.util.Locale.ROOT)
                }
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val target = parts.getOrNull(1)
            if (method != "GET" || target == null || !target.startsWith("/")) {
                respond(client, 400, "Bad request"); return null
            }
            if (host == null || (host != "127.0.0.1:$boundPort" && host != "localhost:$boundPort" && host != "127.0.0.1")) {
                respond(client, 400, "Bad request"); return null
            }
            if (target.substringBefore('?') != callbackPath) {
                respond(client, 404, "Not found"); return null
            }
            val state = expectedState
            if (state != null) {
                val parsed = com.aniob.core.auth.AniobAuthCallbackParser.parse(target, callbackPath, state)
                if (parsed is com.aniob.core.auth.AniobCallbackResult.Failure &&
                    (parsed.error == com.aniob.core.auth.AniobAuthError.StateMismatch ||
                        parsed.error == com.aniob.core.auth.AniobAuthError.Unparseable)) {
                    respond(client, 400, "Bad request"); return null
                }
                accepted = client
            } else respond(client, 200, SUCCESS_PAGE, html = true)
            return target
        } catch (e: IOException) {
            return null
        }
    }

    private fun respond(client: Socket, status: Int, body: String, html: Boolean = false) {
        val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; else -> "Bad Request" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: ${if (html) "text/html" else "text/plain"}; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(headers.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    /** Set when closing failed; a socket that is already gone is the goal, so this is informational. */
    @Volatile
    var lastCloseError: String? = null
        private set

    override fun close() {
        try { accepted?.close() } catch (e: IOException) { lastCloseError = e.javaClass.simpleName }
        accepted = null
        val socket = server
        server = null
        if (socket == null) return
        try {
            socket.close()
        } catch (e: IOException) {
            lastCloseError = e.javaClass.simpleName
        }
    }

    companion object {
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)
        private const val POLL_MS = 250
        private const val READ_TIMEOUT_MS = 3_000
        private const val MAX_HEADER_BYTES = 8 * 1024

        private const val FAILURE_PAGE = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\">" +
            "<title>Aniob</title><h1>Sign-in was not completed</h1><p>Return to the app for details.</p>" +
            "<a href=\"aniob://chat\">Open Aniob</a></html>"

        /** Static. Shows no parameter from the request. The link opens the app through its own deep link. */
        const val SUCCESS_PAGE =
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Aniob</title><style>body{font-family:sans-serif;margin:2.5rem;line-height:1.5}</style></head>" +
                "<body><h1>You can return to the app</h1><p>Account connected.</p><p>You can close this tab.</p>" +
                "<p><a href=\"aniob://chat\">Open Aniob</a></p></body></html>"
    }
}
