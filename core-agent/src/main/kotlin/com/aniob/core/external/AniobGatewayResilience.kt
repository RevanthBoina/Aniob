package com.aniob.core.external

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Why a cloud gateway request produced nothing usable.
 *
 * Every kind is a reason to **downgrade the step**, not to fail the task: the caller drops to the
 * on-device model and the deterministic resolvers. [cooldownMs] is how long the endpoint is left
 * alone afterwards, so one dead gateway costs one request instead of one request per action.
 */
enum class GatewayFailureKind(val cooldownMs: Long) {
    /** 401 / 403: the key was rejected. Retrying cannot help until the user changes it. */
    AUTH(300_000L),

    /** HTML (or any non-JSON) where an API answer was expected: wrong address, portal, web root. */
    NOT_JSON(120_000L),

    /** 404 / 405 / 410: the path does not exist on that host. */
    NOT_FOUND(120_000L),

    /** Any other 4xx. Often specific to the request (400 on `response_format`), so no cool-down. */
    CLIENT_ERROR(0L),

    /** 5xx, including a proxy's 502/503 page. */
    SERVER_ERROR(30_000L),

    /** DNS, connect, timeout, or the 500 ms reachability probe failed. */
    UNREACHABLE(30_000L),

    /** A JSON reply with no assistant message in it. */
    MALFORMED(30_000L)
}

/**
 * A gateway failure with its [kind]. An [IOException] so every existing `catch (IOException)` that
 * already treats "this endpoint did not work" as "try the next one" keeps working unchanged.
 *
 * The message keeps the historical `Omniroute Cloud API error (<code>): …` shape: callers match on
 * `(401)` / `(403)` / `(400)` in it.
 */
class AniobGatewayException(
    val kind: GatewayFailureKind,
    val httpCode: Int?,
    val endpoint: String?,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Turns an HTTP response into either "usable" (`null`) or a classified [AniobGatewayException].
 *
 * The bug this prevents: a gateway address that points at a web root answers `404` with an HTML
 * page. That page used to travel all the way to the user as the failure reason, was fed to the JSON
 * parser on a 2xx, and made the streaming path retry the same dead host. Content is checked
 * *before* any parsing, and the failure message is a one-line summary, never the page.
 *
 * Pure JVM.
 */
object AniobGatewayClassifier {

    private const val SUMMARY_LIMIT = 160
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun isJsonContentType(contentType: String?): Boolean {
        val type = mediaType(contentType)
        return type == "application/json" || type.endsWith("+json") || type == "text/json"
    }

    fun isEventStream(contentType: String?): Boolean = mediaType(contentType) == "text/event-stream"

    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(256).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<head") ||
            head.startsWith("<body") || (head.startsWith("<") && head.contains("<title"))
    }

    /**
     * Null when the reply is a usable API answer; otherwise the classified failure.
     *
     * A 2xx is accepted when its content type is JSON or an event stream, or when the type is
     * missing / `text/plain` and the body starts like JSON (some self-hosted gateways do that).
     * Anything that looks like a web page is rejected whatever its status or header says.
     */
    fun classifyHttp(code: Int, contentType: String?, body: String, endpoint: String? = null): AniobGatewayException? {
        val html = looksLikeHtml(body) || mediaType(contentType).let { it == "text/html" || it == "application/xhtml+xml" }
        if (code in 200..299) {
            if (html) return notJson(code, contentType, body, endpoint)
            if (isJsonContentType(contentType) || isEventStream(contentType)) return null
            val type = mediaType(contentType)
            val head = body.trimStart().take(1)
            val jsonLike = head == "{" || head == "[" || body.trimStart().startsWith("data:")
            if ((type.isEmpty() || type == "text/plain" || type == "application/octet-stream") && jsonLike) return null
            return notJson(code, contentType, body, endpoint)
        }
        val summary = summarize(body)
        val kind = when {
            code == 401 || code == 403 -> GatewayFailureKind.AUTH
            html && code in 400..499 && code != 404 -> GatewayFailureKind.NOT_JSON
            code == 404 || code == 405 || code == 410 -> GatewayFailureKind.NOT_FOUND
            code in 400..499 -> GatewayFailureKind.CLIENT_ERROR
            else -> GatewayFailureKind.SERVER_ERROR
        }
        return AniobGatewayException(kind, code, endpoint, "Omniroute Cloud API error ($code): $summary")
    }

    private fun notJson(code: Int, contentType: String?, body: String, endpoint: String?) =
        AniobGatewayException(
            GatewayFailureKind.NOT_JSON,
            code,
            endpoint,
            "Omniroute Cloud API error ($code): expected JSON but got " +
                "${mediaType(contentType).ifEmpty { "no content type" }} — ${summarize(body)}"
        )

    /** One line, never a page: an HTML body becomes its `<title>`. */
    fun summarize(body: String, limit: Int = SUMMARY_LIMIT): String {
        if (body.isBlank()) return "empty response"
        if (looksLikeHtml(body)) {
            val title = TITLE.find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return "web page (HTML)" + if (title.isNotEmpty()) ": ${title.take(limit)}" else ""
        }
        return body.replace(Regex("\\s+"), " ").trim().take(limit)
    }

    /** The failure kind of any throwable the transport raised; unknown I/O is [GatewayFailureKind.UNREACHABLE]. */
    fun kindOf(error: Throwable?): GatewayFailureKind =
        (error as? AniobGatewayException)?.kind ?: GatewayFailureKind.UNREACHABLE

    private fun mediaType(contentType: String?): String =
        contentType.orEmpty().substringBefore(';').trim().lowercase()
}

/**
 * Per-endpoint cool-down. Thread-safe; the clock is injectable so tests do not sleep.
 *
 * Without it every action of a run re-sent the same doomed request to the same dead host, and a
 * 404 gateway turned one task into 4–6 model calls and a minute of waiting.
 */
class AniobGatewayCircuitBreaker(private val clock: () -> Long = System::currentTimeMillis) {
    private val openUntil = ConcurrentHashMap<String, Long>()

    fun isOpen(key: String): Boolean {
        val until = openUntil[key] ?: return false
        if (clock() >= until) {
            openUntil.remove(key, until)
            return false
        }
        return true
    }

    fun recordFailure(key: String, kind: GatewayFailureKind) {
        if (kind.cooldownMs > 0) openUntil[key] = clock() + kind.cooldownMs
    }

    fun recordSuccess(key: String) {
        openUntil.remove(key)
    }

    fun reset() = openUntil.clear()
}

/** Cheap TCP reachability test. Pure JVM sockets, so it is unit-testable against a local server. */
object AniobGatewayProbe {
    const val DEFAULT_TIMEOUT_MS = 500

    fun isReachable(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        val host = uri.host ?: return false
        val port = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: IOException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

/**
 * Decides, before any request is built, whether an endpoint is worth contacting.
 *
 *  - an endpoint inside its cool-down is skipped without touching the network;
 *  - a **local** endpoint (`localhost:20128`, `10.0.2.2`, a LAN address) must accept a TCP
 *    connection within [probeTimeoutMs]. This is the `adb reverse tcp:20128 tcp:20128` check: with
 *    no reverse tunnel the phone's own loopback has nothing on that port, and the answer is
 *    "no" in half a second instead of after a full connect timeout per attempt.
 *
 * A passing probe is cached for [reachableTtlMs], a failing one opens the breaker for
 * [GatewayFailureKind.UNREACHABLE]'s cool-down. [reset] is called when the user edits the gateway.
 */
class AniobGatewayHealth(
    val breaker: AniobGatewayCircuitBreaker = AniobGatewayCircuitBreaker(),
    private val probe: (String, Int) -> Boolean = { url, timeout -> AniobGatewayProbe.isReachable(url, timeout) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val probeTimeoutMs: Int = AniobGatewayProbe.DEFAULT_TIMEOUT_MS,
    private val reachableTtlMs: Long = 10_000L
) {
    private val reachableUntil = ConcurrentHashMap<String, Long>()

    sealed class Admission {
        data object Go : Admission()
        data class Skip(val reason: String) : Admission()
    }

    fun admit(endpoint: String, isLocal: Boolean): Admission {
        if (breaker.isOpen(endpoint)) return Admission.Skip("gateway recently failed; not retrying yet")
        if (!isLocal) return Admission.Go
        val cached = reachableUntil[endpoint]
        if (cached != null && clock() < cached) return Admission.Go
        if (probe(endpoint, probeTimeoutMs)) {
            reachableUntil[endpoint] = clock() + reachableTtlMs
            return Admission.Go
        }
        reachableUntil.remove(endpoint)
        breaker.recordFailure(endpoint, GatewayFailureKind.UNREACHABLE)
        return Admission.Skip("local gateway is not reachable (is `adb reverse tcp:20128 tcp:20128` active?)")
    }

    fun recordSuccess(endpoint: String) {
        breaker.recordSuccess(endpoint)
    }

    fun recordFailure(endpoint: String, kind: GatewayFailureKind) {
        reachableUntil.remove(endpoint)
        breaker.recordFailure(endpoint, kind)
    }

    fun reset() {
        breaker.reset()
        reachableUntil.clear()
    }

    companion object {
        /** Process-wide instance: providers are rebuilt per step, health must outlive them. */
        val shared = AniobGatewayHealth()
    }
}
