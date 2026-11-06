package com.aniob.app.auth

import com.aniob.core.auth.AniobAuthCallbackParser
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
 * It lives only while a sign-in is running, managed in the foreground by AniobSignInService.
 */
class AniobLoopbackCallbackListener(
    private val port: Int,
    private val now: () -> Long = System::currentTimeMillis
) : AniobCallbackListener {

    @Volatile
    private var server: ServerSocket? = null

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

    override suspend fun awaitCallback(callbackPath: String, timeoutMs: Long, expectedState: String?): String? =
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
                val target = client.use { handle(it, callbackPath, expectedState) }
                if (target != null) return@withContext target
            }
            null
        }

    /** Returns the request target when it is the callback with matching state, else null (and the wait goes on). */
    private fun handle(client: Socket, callbackPath: String, expectedState: String? = null): String? {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return null
            var host: String? = null
            var read = requestLine.length
            while (true) {
                val line = reader.readLine() ?: break
                read += line.length
                if (line.isEmpty() || read > MAX_HEADER_BYTES) break
                if (line.startsWith("host:", ignoreCase = true)) host = line.substringAfter(':').trim().lowercase(java.util.Locale.ROOT)
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val target = parts.getOrNull(1)
            if (method != "GET" || target == null || !target.startsWith("/")) {
                respond(client, 400, "Bad request"); return null
            }
            if (host != null && host != "127.0.0.1:$boundPort" && host != "localhost:$boundPort" && host != "127.0.0.1") {
                respond(client, 400, "Bad request"); return null
            }
            if (target.substringBefore('?') != callbackPath) {
                respond(client, 404, "Not found"); return null
            }
            if (!expectedState.isNullOrEmpty()) {
                val query = target.substringAfter('?', "")
                val queryParams = AniobAuthCallbackParser.parseQuery(query)
                val state = queryParams["state"]
                if (state == null || !AniobAuthCallbackParser.constantTimeEquals(state, expectedState)) {
                    respond(client, 400, "Bad request"); return null
                }
            }
            respond(client, 200, SUCCESS_PAGE, html = true)
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

        /** Static. Shows no parameter from the request. The link opens the app through its own deep link. */
        const val SUCCESS_PAGE =
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Aniob</title><style>body{font-family:sans-serif;margin:2.5rem;line-height:1.5}</style></head>" +
                "<body><h1>You can return to the app</h1><p>You can close this tab.</p>" +
                "<p><a href=\"aniob://chat\">Open Aniob</a></p></body></html>"
    }
}
