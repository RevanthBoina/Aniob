package com.aniob.app.provider

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import com.aniob.app.network.AniobHttpClientSingleton
import com.aniob.core.external.AniobActionStepProvider
import com.aniob.core.external.AniobCloudLlmProvider
import com.aniob.core.external.AniobGatewayClassifier
import com.aniob.core.external.AniobGatewayException
import com.aniob.core.external.AniobGatewayHealth
import com.aniob.core.external.AniobRateLimitBackoff
import com.aniob.core.external.GatewayFailureKind
import com.aniob.core.speed.JsonPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OmniRoute cloud provider client (https://github.com/diegosouzapw/OmniRoute).
 *
 * Talks to an OmniRoute gateway's `/v1/chat/completions` and `/v1/models` endpoints.
 * Supports:
 * - Local Termux / desktop gateway (`http://localhost:20128/v1`, `http://10.0.2.2:20128/v1`, or custom LAN/VPS URL)
 * - Hosted OmniRoute Cloud (`https://omniroute.online/v1`), only when the user selects it: it is no
 *   longer an implicit fallback (see [AniobProviderConfig.candidateEndpoints])
 * - Zero-Config Auto-Combo channels (`auto`, `auto/fast`, `auto/smart`, `auto/vision`, `auto/coding`, `auto/cheap`)
 *   and explicit `<provider>/<model>` or custom combo targets
 * - `x-omniroute-no-memory` and `X-OmniRoute-Session-Id` headers for deterministic UI action steps
 *   and LKGP / cache-affinity session stickiness.
 *
 * Resilience: every response is classified (status + `Content-Type`) *before* it is parsed, a dead
 * or unreachable endpoint goes into a cool-down shared across providers ([AniobGatewayHealth]), and
 * a local gateway must accept a TCP connection within 500 ms before any request is built. All of
 * that surfaces as a typed [AniobGatewayException] so the caller can drop to the on-device model.
 *
 * When [jsonMode] is enabled, all generation requests enforce `response_format:
 * json_object` with temperature 0.1 so the ladder can trust structured output.
 */
class AniobOmniRouteProvider(
    private val apiKey: String,
    private val baseUrl: String = AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL,
    private val model: String = "auto",
    private val jsonMode: Boolean = false,
    private val sessionId: String? = null,
    /**
     * Invoked once per outbound generation request, immediately before the socket is opened.
     * `AniobViewModel` wires this to `ResearchTelemetryManager.recordLlmCall()`; the default no-op
     * keeps tests and one-off callers unchanged. The Settings connection probe does not use it.
     */
    private val onLlmRequest: () -> Unit = {},
    /**
     * Process-wide cool-down + reachability state. Providers are rebuilt per step, so the state
     * that stops a dead gateway from being re-tried on every action has to live outside them.
     */
    private val health: AniobGatewayHealth = AniobGatewayHealth.shared,
    /** U-03: called with the wait (ms) just before an in-step 429 backoff, so the UI can say so honestly. */
    private val onRateLimited: (waitMs: Long) -> Unit = {}
) : AniobCloudLlmProvider, AniobActionStepProvider {
    override val name: String = "OMNIROUTE_CLOUD"
    override val isConfigured: Boolean get() = apiKey.isNotBlank() || isLocalGateway(baseUrl)

    private val client: OkHttpClient =
        AniobHttpClientSingleton.client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /**
     * Cleared the first time a gateway answers the streaming shape with a 4xx, so a gateway that
     * cannot stream costs one rejected round-trip per session instead of one per action.
     */
    @Volatile
    private var streamingSupported = true

    override suspend fun generate(prompt: String, systemPrompt: String): String = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) {
            return@withContext "Omniroute API key is not configured. Please add it in Settings."
        }
        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            val effectiveModel = AniobProviderConfig.coerceModel(model)
            val payload = buildChatPayload(effectiveModel, messages, maxTokens = 1024, enforceJsonObject = jsonMode)
            val endpoints = admittedEndpoints()
            var lastError = "Unreachable"
            for (endpoint in endpoints) {
                try {
                    val (responseResult, gatewayFailure) = sendWithRateLimitBackoff(endpoint, payload, jsonMode)
                    val code = responseResult.code
                    val body = responseResult.body
                    if (gatewayFailure == null) {
                        health.recordSuccess(endpoint)
                        val fullContent = try {
                            extractAssistantContent(body)
                        } catch (e: org.json.JSONException) {
                            health.recordFailure(endpoint, GatewayFailureKind.MALFORMED)
                            lastError = "Error from Omniroute Cloud ($code): reply had no assistant message"
                            continue
                        }
                        val routedModel = responseResult.routedModel ?: effectiveModel
                        AniobEgressLedger.record(
                            name,
                            routedModel,
                            prompt.length + systemPrompt.length,
                            fullContent.length,
                            System.currentTimeMillis() - startTime
                        )
                        return@withContext fullContent
                    } else {
                        health.recordFailure(endpoint, gatewayFailure.kind, gatewayFailure.retryAfterMs)
                        lastError = "Error from Omniroute Cloud ${gatewayFailure.message?.removePrefix("Omniroute Cloud API error ")}"
                        if (code == 401 || code == 403) return@withContext lastError
                    }
                } catch (ioe: IOException) {
                    health.recordFailure(endpoint, AniobGatewayClassifier.kindOf(ioe))
                    lastError = "Request failed: ${ioe.message}"
                }
            }
            lastError
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        }
    }

    override suspend fun generateStreaming(
        prompt: String,
        systemPrompt: String,
        onDelta: (String) -> Unit
    ): String = streamingResult(prompt, systemPrompt, null, onDelta)

    suspend fun generateStreaming(
        prompt: String,
        systemPrompt: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit
    ): String = streamingResult(prompt, systemPrompt, screenshotBase64, onDelta)

    /** Whether this provider instance enforces structured JSON output. */
    fun isJsonMode() = jsonMode

    /** Returns a copy with JsonMode enabled for callers that need strict JSON. */
    fun withJsonMode(): AniobOmniRouteProvider = AniobOmniRouteProvider(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        jsonMode = true,
        sessionId = sessionId,
        onLlmRequest = onLlmRequest,
        health = health
    )

    /**
     * The endpoints worth contacting right now.
     *
     * An endpoint in its cool-down, or a local one that does not accept a TCP connection within
     * 500 ms (no `adb reverse tcp:20128 tcp:20128`, proxy down), is dropped here — before a request
     * is built and before [onLlmRequest] counts a call that never left the device. When nothing is
     * left the failure is immediate and typed, so the caller can fall back to the on-device model.
     */
    private fun admittedEndpoints(): List<String> {
        val candidates = AniobProviderConfig.candidateEndpoints(baseUrl)
        val skipped = mutableListOf<String>()
        val admitted = candidates.filter { endpoint ->
            when (val verdict = health.admit(endpoint, isLocalGateway(endpoint))) {
                AniobGatewayHealth.Admission.Go -> true
                is AniobGatewayHealth.Admission.Skip -> {
                    skipped += verdict.reason
                    false
                }
            }
        }
        if (admitted.isEmpty()) {
            throw AniobGatewayException(
                kind = GatewayFailureKind.UNREACHABLE,
                httpCode = null,
                endpoint = candidates.firstOrNull(),
                message = "Request failed: gateway unavailable (${skipped.firstOrNull() ?: "no endpoint configured"})"
            )
        }
        return admitted
    }

    private suspend fun streamingResult(
        prompt: String,
        systemPrompt: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) {
            val msg = "Omniroute API key is not configured. Please add it in Settings."
            return@withContext msg
        }
        val startTime = System.currentTimeMillis()
        try {
            val messages = buildMessages(systemPrompt, prompt, screenshotBase64)
            val effectiveModel = resolveModelForVision(AniobProviderConfig.coerceModel(model), screenshotBase64)
            val payload = buildChatPayload(effectiveModel, messages, maxTokens = 1024, enforceJsonObject = jsonMode)
            val endpoints = admittedEndpoints()

            var lastError = "Request failed: gateway unreachable"
            for (endpoint in endpoints) {
                try {
                    val (responseResult, gatewayFailure) = sendWithRateLimitBackoff(endpoint, payload, jsonMode)
                    val code = responseResult.code
                    val body = responseResult.body
                    if (gatewayFailure == null) {
                        health.recordSuccess(endpoint)
                        val full = try {
                            extractAssistantContent(body)
                        } catch (e: org.json.JSONException) {
                            health.recordFailure(endpoint, GatewayFailureKind.MALFORMED)
                            lastError = "Error from Omniroute Cloud ($code): reply had no assistant message"
                            continue
                        }
                        val routedModel = responseResult.routedModel ?: effectiveModel
                        AniobEgressLedger.record(
                            name,
                            routedModel,
                            prompt.length + systemPrompt.length,
                            full.length,
                            System.currentTimeMillis() - startTime
                        )
                        onDelta(full)
                        return@withContext full
                    } else {
                        health.recordFailure(endpoint, gatewayFailure.kind, gatewayFailure.retryAfterMs)
                        lastError = "Error from Omniroute Cloud ${gatewayFailure.message?.removePrefix("Omniroute Cloud API error ")}"
                        if (code == 401 || code == 403) {
                            return@withContext lastError
                        }
                    }
                } catch (ioe: IOException) {
                    health.recordFailure(endpoint, AniobGatewayClassifier.kindOf(ioe))
                    lastError = "Request failed: ${ioe.message}"
                }
            }
            lastError
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "Request failed: ${e.message}"
            msg
        }
    }

    /**
     * Warms the gateway connection (DNS + TLS + HTTP/2) with one cheap `GET /models`.
     *
     * The first chat request on a cold profile pays the handshake; doing it while the user is
     * still reading the screen removes that from time-to-first-action. This is not a model call:
     * nothing is generated and [onLlmRequest] is not invoked. Every failure is ignored.
     */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) return@withContext
        runCatching {
            // A gateway that is down or inside its cool-down is not worth a 4 s warm-up.
            admittedEndpoints()
            val warmClient = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
            for (endpoint in AniobProviderConfig.candidateModelsEndpoints(baseUrl)) {
                val request = Request.Builder()
                    .url(endpoint)
                    .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}") }
                    .get()
                    .build()
                if (warmClient.newCall(request).execute().use { it.isSuccessful }) return@runCatching
            }
        }
        Unit
    }

    /**
     * Streaming twin of [getNextActionRaw]: returns as soon as one complete JSON object is on the
     * wire instead of waiting for the gateway to finish the reply.
     *
     * [onFirstToken] fires on the first content delta so the caller can timestamp time-to-first
     * token. A gateway that answers the streaming shape with a 4xx is remembered in
     * [streamingSupported] and every later step uses the plain call, so an incompatible gateway
     * costs one rejected round-trip per session rather than one per action.
     */
    override suspend fun getNextActionRawStreaming(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String?,
        maxTokens: Int,
        onFirstToken: () -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !isLocalGateway(baseUrl)) {
            return@withContext Result.failure(IllegalStateException("Omniroute API key is not configured"))
        }
        if (!streamingSupported) {
            return@withContext getNextActionRaw(systemPrompt, userPrompt, screenshotBase64, maxTokens)
        }
        val startTime = System.currentTimeMillis()
        try {
            val messages = buildMessages(systemPrompt, userPrompt, screenshotBase64)
            val effectiveModel = resolveModelForVision(AniobProviderConfig.coerceModel(model), screenshotBase64)
            // `response_format` is left out of the streaming request: several gateways reject it
            // with a 400 for streamed completions, and a rejected request costs more time than the
            // field saves. The prompt already asks for one bare JSON object and the scanner below
            // tolerates prose around it, so the reply stays parseable. Temperature is kept.
            val payload = buildChatPayload(effectiveModel, messages, maxTokens, enforceJsonObject = true)
                .apply { remove("response_format") }
                .put("stream", true)
            var lastFailure: Exception = IOException("OmniRoute gateway unreachable")
            for (endpoint in admittedEndpoints()) {
                onLlmRequest()
                val attempt = withRateLimitBackoff { streamOnce(endpoint, payload, onFirstToken) }
                if (attempt.isSuccess) {
                    health.recordSuccess(endpoint)
                    val raw = attempt.getOrThrow()
                    AniobEgressLedger.record(
                        name,
                        effectiveModel,
                        userPrompt.length + systemPrompt.length,
                        raw.length,
                        System.currentTimeMillis() - startTime
                    )
                    return@withContext Result.success(raw)
                }
                val error = attempt.exceptionOrNull()
                if (error is StreamingRejected) {
                    streamingSupported = false
                    return@withContext getNextActionRaw(systemPrompt, userPrompt, screenshotBase64, maxTokens)
                }
                lastFailure = error as? Exception ?: lastFailure
                // Only a classified HTTP failure opens the cool-down here. A dropped SSE is plain
                // I/O and is retried once in the non-streaming shape below, which records it.
                if (error is AniobGatewayException) health.recordFailure(endpoint, error.kind, error.retryAfterMs)
                if (error?.message?.contains("(401)") == true || error?.message?.contains("(403)") == true) {
                    return@withContext Result.failure(lastFailure)
                }
            }
            // A dropped SSE (proxy idle timeout, Wi-Fi handover) must not fail the whole task: the
            // same request goes out once in the plain shape, which is what non-streaming gateways
            // use anyway. Auth and shape rejections returned above and never reach this.
            if (lastFailure is IOException && lastFailure !is AniobGatewayException) {
                return@withContext getNextActionRaw(systemPrompt, userPrompt, screenshotBase64, maxTokens)
            }
            Result.failure(lastFailure)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun streamOnce(
        endpoint: String,
        payload: JSONObject,
        onFirstToken: () -> Unit
    ): Result<String> {
        val request = buildRequest(endpoint, payload, noMemory = true).newBuilder()
            .addHeader("Accept", "text/event-stream")
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result: Result<String> = try {
                        readReply(call, response, onFirstToken)
                    } catch (e: Exception) {
                        Result.failure(e)
                    } finally {
                        response.close()
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    /**
     * Reads an SSE reply, returning the first complete JSON object as soon as it closes.
     *
     * Gateways that ignore `stream` answer with an ordinary JSON body; that shape is accepted too,
     * so the caller never has to branch on the transport.
     */
    private fun readReply(call: Call, response: Response, onFirstToken: () -> Unit): Result<String> {
        val endpoint = call.request().url.toString()
        if (!response.isSuccessful) {
            val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
            val failure = AniobGatewayClassifier.classifyHttp(
                response.code, response.header("Content-Type"), text, endpoint, response.header("Retry-After")
            ) ?: AniobGatewayException(
                    GatewayFailureKind.SERVER_ERROR, response.code, endpoint, "Omniroute Cloud API error (${response.code})"
                )
            // Only a request-shaped 4xx (400/422...) is worth a plain retry. 404, auth and
            // HTML answers are about the *gateway*: re-sending the same URL cannot change them.
            return Result.failure(
                if (failure.kind == GatewayFailureKind.CLIENT_ERROR) StreamingRejected(failure.message.orEmpty()) else failure
            )
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (!AniobGatewayClassifier.isEventStream(contentType)) {
            val body = response.body?.string().orEmpty()
            AniobGatewayClassifier.classifyHttp(response.code, contentType, body, endpoint)
                ?.let { return Result.failure(it) }
            val text = runCatching { extractAssistantContent(body) }.getOrElse { body }
            if (text.isNotBlank()) onFirstToken()
            return Result.success(text)
        }
        val source = response.body?.source() ?: return Result.failure(IOException("empty stream body"))
        val accumulated = StringBuilder()
        var firstDeltaSeen = false
        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("delta").optString("content")
                }.getOrNull()
                if (delta.isNullOrEmpty()) continue
                if (!firstDeltaSeen) {
                    firstDeltaSeen = true
                    onFirstToken()
                }
                JsonPrefix.appendBounded(accumulated, delta)
                val complete = JsonPrefix.firstCompleteObject(accumulated)
                if (complete != null) {
                    call.cancel() // the rest of the reply cannot improve the action
                    return Result.success(complete)
                }
            }
        } catch (_: IOException) {
            // A cancel after a complete object lands here; the text below is still valid.
        }
        val text = accumulated.toString().trim()
        if (text.isEmpty()) return Result.failure(IOException("stream produced no content"))
        return Result.success(JsonPrefix.firstCompleteObject(text) ?: text)
    }

    /** A 4xx on the streaming shape; the caller retries once without `stream`. */
    private class StreamingRejected(message: String) : IOException(message)

    private fun buildMessages(systemPrompt: String, userPrompt: String, screenshotBase64: String?): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        if (!screenshotBase64.isNullOrBlank()) {
            val userContent = JSONArray()
            userContent.put(JSONObject().apply { put("type", "text"); put("text", userPrompt) })
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$screenshotBase64") })
            })
            messages.put(JSONObject().apply { put("role", "user"); put("content", userContent) })
        } else {
            messages.put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
        }
        return messages
    }

    suspend fun getNextAction(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null
    ): Result<AniobAction> =
        // Delegates to the raw fetch and the single canonical parser. No second parser lives here.
        getNextActionRaw(systemPrompt, userPrompt, screenshotBase64).mapCatching { raw ->
            AniobActionSchema.parseActionJson(raw)
        }

    /**
     * Returns the raw model content instead of a pre-parsed action.
     *
     * Providers must return *data*, not behavior (finding #3): the caller routes this through
     * `AniobStructuredOutput.parseOrRepair` so cloud output shares the one action vocabulary and
     * benefits from the single repair retry.
     */
    override suspend fun getNextActionRaw(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String?,
        /** Output budget for this call; burst replies are capped so decode latency stays low. */
        maxTokens: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val messages = buildMessages(systemPrompt, userPrompt, screenshotBase64)
            val effectiveModel = resolveModelForVision(AniobProviderConfig.coerceModel(model), screenshotBase64)
            val payload = buildChatPayload(
                targetModel = effectiveModel,
                messages = messages,
                maxTokens = maxTokens,
                enforceJsonObject = true
            )
            val endpoints = admittedEndpoints()
            var lastFailure: Exception = IOException("OmniRoute gateway unreachable")

            for (endpoint in endpoints) {
                onLlmRequest()
                val attempt = withRateLimitBackoff { executeAsyncWithJsonFallback(endpoint, payload) }
                if (attempt.isSuccess) {
                    val responseResult = attempt.getOrThrow()
                    health.recordSuccess(endpoint)
                    val rawContent = try {
                        extractAssistantContent(responseResult.body)
                    } catch (e: org.json.JSONException) {
                        health.recordFailure(endpoint, GatewayFailureKind.MALFORMED)
                        lastFailure = AniobGatewayException(
                            GatewayFailureKind.MALFORMED, responseResult.code, endpoint,
                            "Omniroute Cloud API error (${responseResult.code}): reply had no assistant message", e
                        )
                        continue
                    }
                    val routedModel = responseResult.routedModel ?: effectiveModel
                    AniobEgressLedger.record(
                        name,
                        routedModel,
                        userPrompt.length + systemPrompt.length,
                        rawContent.length,
                        System.currentTimeMillis() - startTime
                    )
                    return@withContext Result.success(rawContent)
                } else {
                    val ex = attempt.exceptionOrNull() as? Exception ?: Exception("OmniRoute request failed")
                    lastFailure = ex
                    health.recordFailure(endpoint, AniobGatewayClassifier.kindOf(ex), (ex as? AniobGatewayException)?.retryAfterMs)
                    // Do not fail over to other hosts if key was explicitly rejected by the gateway
                    if (ex.message?.contains("(401)") == true || ex.message?.contains("(403)") == true) {
                        return@withContext Result.failure(ex)
                    }
                }
            }
            Result.failure(lastFailure)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class HttpOutcome(
        val code: Int,
        val body: String,
        val routedModel: String?,
        val routedProvider: String?,
        val decisionHeader: String?,
        val contentType: String? = null,
        val retryAfter: String? = null
    )

    private fun buildChatPayload(
        targetModel: String,
        messages: JSONArray,
        maxTokens: Int,
        enforceJsonObject: Boolean,
        stream: Boolean = false
    ): JSONObject = JSONObject().apply {
        put("model", targetModel)
        put("messages", messages)
        put("max_tokens", maxTokens)
        put("stream", stream)
        if (enforceJsonObject) {
            put("temperature", 0.1)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }
    }

    private fun buildRequest(endpoint: String, payload: JSONObject, noMemory: Boolean): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (noMemory) {
            builder.addHeader("x-omniroute-no-memory", "true")
        }
        if (!sessionId.isNullOrBlank()) {
            builder.addHeader("X-OmniRoute-Session-Id", sessionId)
        }
        return builder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun executeWithJsonFallback(
        endpoint: String,
        payload: JSONObject,
        enforceJson: Boolean
    ): HttpOutcome {
        val primaryReq = buildRequest(endpoint, payload, noMemory = enforceJson)
        client.newCall(primaryReq).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 400 && enforceJson && payload.has("response_format")) {
                // Retry once without response_format for upstream providers in OmniRoute that reject json_object
                val fallbackPayload = JSONObject(payload.toString()).apply { remove("response_format") }
                val fallbackReq = buildRequest(endpoint, fallbackPayload, noMemory = true)
                client.newCall(fallbackReq).execute().use { retryResp ->
                    return HttpOutcome(
                        code = retryResp.code,
                        body = retryResp.body?.string().orEmpty(),
                        routedModel = retryResp.header("X-OmniRoute-Model"),
                        routedProvider = retryResp.header("X-OmniRoute-Provider"),
                        decisionHeader = retryResp.header("X-OmniRoute-Decision"),
                        contentType = retryResp.header("Content-Type"),
                        retryAfter = retryResp.header("Retry-After")
                    )
                }
            }
            return HttpOutcome(
                code = response.code,
                body = body,
                routedModel = response.header("X-OmniRoute-Model"),
                routedProvider = response.header("X-OmniRoute-Provider"),
                decisionHeader = response.header("X-OmniRoute-Decision"),
                contentType = response.header("Content-Type"),
                retryAfter = response.header("Retry-After")
            )
        }
    }

    /**
     * U-03. One blocking request; on a 429 it waits (bounded by `Retry-After` and a 10 s total budget) and
     * retries at most twice. When the budget does not allow, the 429 is returned like any other failure so
     * the caller opens the circuit breaker and degrades to the on-device model instead of hammering the gateway.
     */
    private suspend fun sendWithRateLimitBackoff(
        endpoint: String,
        payload: JSONObject,
        jsonMode: Boolean
    ): Pair<HttpOutcome, AniobGatewayException?> {
        var attempt = 0
        var waited = 0L
        while (true) {
            onLlmRequest()
            val outcome = executeWithJsonFallback(endpoint, payload, enforceJson = jsonMode)
            val failure = AniobGatewayClassifier.classifyHttp(
                outcome.code, outcome.contentType, outcome.body, endpoint, outcome.retryAfter
            )
            if (failure?.kind != GatewayFailureKind.RATE_LIMIT) return outcome to failure
            val wait = AniobRateLimitBackoff.waitMs(attempt, failure.retryAfterMs, waited, (0L..250L).random())
                ?: return outcome to failure
            onRateLimited(wait)
            delay(wait)
            waited += wait
            attempt++
        }
    }

    /** Same budget for the Result-returning transports (streaming and async). */
    private suspend fun <T> withRateLimitBackoff(call: suspend () -> Result<T>): Result<T> {
        var attempt = 0
        var waited = 0L
        while (true) {
            val result = call()
            val failure = result.exceptionOrNull() as? AniobGatewayException
            if (failure?.kind != GatewayFailureKind.RATE_LIMIT) return result
            val wait = AniobRateLimitBackoff.waitMs(attempt, failure.retryAfterMs, waited, (0L..250L).random())
                ?: return result
            onRateLimited(wait)
            delay(wait)
            waited += wait
            attempt++
            onLlmRequest()
        }
    }

    private suspend fun executeAsyncWithJsonFallback(
        endpoint: String,
        payload: JSONObject
    ): Result<HttpOutcome> {
        val first = enqueueCall(buildRequest(endpoint, payload, noMemory = true))
        if (first.isSuccess) return first
        val errMsg = first.exceptionOrNull()?.message.orEmpty()
        if (errMsg.contains("(400)") && payload.has("response_format")) {
            val fallbackPayload = JSONObject(payload.toString()).apply { remove("response_format") }
            return enqueueCall(buildRequest(endpoint, fallbackPayload, noMemory = true))
        }
        return first
    }

    private suspend fun enqueueCall(request: Request): Result<HttpOutcome> {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string().orEmpty()
                        // Status *and* content are checked before anything is parsed: an HTML page
                        // behind a 200 is a failure, and a failure's message is a summary, not the page.
                        val failure = AniobGatewayClassifier.classifyHttp(
                            response.code, response.header("Content-Type"), body, call.request().url.toString(),
                            response.header("Retry-After")
                        )
                        if (failure != null) {
                            if (continuation.isActive) continuation.resume(Result.failure(failure))
                            return
                        }
                        val outcome = HttpOutcome(
                            code = response.code,
                            body = body,
                            routedModel = response.header("X-OmniRoute-Model"),
                            routedProvider = response.header("X-OmniRoute-Provider"),
                            decisionHeader = response.header("X-OmniRoute-Decision"),
                            contentType = response.header("Content-Type"),
                            retryAfter = response.header("Retry-After")
                        )
                        if (continuation.isActive) {
                            continuation.resume(Result.success(outcome))
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(Result.failure(e))
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 60L

        internal fun isLocalGateway(url: String): Boolean {
            val host = try {
                java.net.URI(AniobProviderConfig.normalizeBaseUrl(url)).host
            } catch (e: Exception) {
                null
            } ?: return false
            return AniobProviderConfig.isLocalNetworkHost(host)
        }

        internal fun resolveModelForVision(configuredModel: String, screenshotBase64: String?): String {
            if (!screenshotBase64.isNullOrBlank() && configuredModel == "auto") {
                return "auto"
            }
            return configuredModel
        }

        /**
         * Extracts the assistant text content from an OpenAI / OmniRoute chat completion response.
         * Supports string content, multimodal array content blocks, and reasoning_content fallback.
         */
        internal fun extractAssistantContent(body: String): String {
            val trimmed = body.trim()
            if (trimmed.startsWith("data:") || trimmed.contains("\ndata:")) {
                val sb = StringBuilder()
                trimmed.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotBlank() && it != "[DONE]" }
                    .forEach { line ->
                        try {
                            val chunk = JSONObject(line)
                            val choices = chunk.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val choice = choices.getJSONObject(0)
                                val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
                                val text = delta?.optString("content", "")
                                    ?: delta?.optString("text", "")
                                    ?: ""
                                if (text.isNotEmpty()) sb.append(text)
                            }
                        } catch (e: Exception) {
                            android.util.Log.v("AniobOmniRouteProvider", "Skipping unparseable SSE line: ${e.message}")
                        }
                    }
                if (sb.isNotEmpty()) return sb.toString()
            }
            val rootJson = JSONObject(body)
            val choices = rootJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice0 = choices.getJSONObject(0)
                val message = choice0.optJSONObject("message") ?: choice0.optJSONObject("delta")
                if (message != null) {
                    val rawContent = message.opt("content")
                    when (rawContent) {
                        is String -> if (rawContent.isNotBlank()) return rawContent
                        is JSONArray -> {
                            val sb = StringBuilder()
                            for (i in 0 until rawContent.length()) {
                                val part = rawContent.opt(i)
                                when (part) {
                                    is JSONObject -> {
                                        val text = part.optString("text", "")
                                        if (text.isNotEmpty()) sb.append(text)
                                    }
                                    is String -> sb.append(part)
                                }
                            }
                            if (sb.isNotEmpty()) return sb.toString()
                        }
                    }
                    val reasoning = message.optString("reasoning_content", "").ifBlank {
                        message.optString("reasoning", "")
                    }
                    if (reasoning.isNotBlank()) return reasoning
                }
            }
            val directOutput = rootJson.optString("output_text", "")
            if (directOutput.isNotBlank()) return directOutput
            throw org.json.JSONException("No assistant message content in OmniRoute response")
        }
    }
}

enum class OmniRouteConnectionStatus {
    SUCCESS,
    BAD_KEY,
    UNREACHABLE
}

data class OmniRouteDiagnostics(
    val status: OmniRouteConnectionStatus,
    val resolvedEndpoint: String = "",
    val latencyMs: Long = 0L,
    val routedModel: String? = null,
    val routedProvider: String? = null,
    val omniRouteVersion: String? = null,
    val availableModels: List<String> = emptyList(),
    val message: String = ""
)

/**
 * Fetches available models and combos from an OmniRoute gateway's `GET /v1/models` endpoint.
 */
suspend fun fetchOmniRouteModels(apiKey: String, baseUrl: String): List<String> = withContext(Dispatchers.IO) {
    val client = AniobHttpClientSingleton.client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    for (modelsUrl in AniobProviderConfig.candidateModelsEndpoints(baseUrl)) {
        try {
            val reqBuilder = Request.Builder()
                .url(modelsUrl)
                .get()
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: return@use
                    val models = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id", "").trim()
                        if (id.isNotEmpty()) models.add(id)
                    }
                    if (models.isNotEmpty()) return@withContext models.distinct()
                }
            }
        } catch (_: Exception) {
            // Try next candidate
        }
    }
    emptyList()
}

/**
 * Performs an end-to-end diagnostic check against the OmniRoute gateway (`/v1/models` + `/v1/chat/completions`).
 */
suspend fun verifyOmniRouteGateway(
    apiKey: String,
    baseUrl: String,
    model: String = "auto"
): OmniRouteDiagnostics = withContext(Dispatchers.IO) {
    val trimmedKey = apiKey.trim()
    if (trimmedKey.isBlank() && !AniobOmniRouteProvider.isLocalGateway(baseUrl)) {
        return@withContext OmniRouteDiagnostics(
            status = OmniRouteConnectionStatus.BAD_KEY,
            message = "Enter your Cloud Access Key first."
        )
    }
    if (AniobProviderConfig.validateBaseUrl(baseUrl) != null) {
        return@withContext OmniRouteDiagnostics(
            status = OmniRouteConnectionStatus.UNREACHABLE,
            message = "Invalid gateway address: ${AniobProviderConfig.validateBaseUrl(baseUrl)}"
        )
    }
    val client = AniobHttpClientSingleton.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    val discoveredModels = runCatching { fetchOmniRouteModels(trimmedKey, baseUrl) }.getOrDefault(emptyList())
    val targetModel = AniobProviderConfig.coerceModel(model)
    val payload = JSONObject().apply {
        put("model", targetModel)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "ping")
            })
        })
        put("max_tokens", 1)
    }

    var sawHtml = false
    for (endpoint in AniobProviderConfig.candidateEndpoints(baseUrl)) {
        val startMs = System.currentTimeMillis()
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-omniroute-no-memory", "true")
        if (trimmedKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $trimmedKey")
        }
        val request = reqBuilder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val routedModel = response.header("X-OmniRoute-Model") ?: targetModel
                val routedProvider = response.header("X-OmniRoute-Provider")
                val version = response.header("X-OmniRoute-Version")
                val replyBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                // A 2xx that is not JSON is a web page, not a gateway: never report it as connected.
                val notJson = response.isSuccessful &&
                    AniobGatewayClassifier.classifyHttp(response.code, response.header("Content-Type"), replyBody, endpoint) != null
                if (notJson || AniobGatewayClassifier.looksLikeHtml(replyBody)) sawHtml = true
                when {
                    response.code in 200..299 && !notJson -> {
                        val providerSuffix = if (!routedProvider.isNullOrBlank()) " via $routedProvider" else ""
                        val modelCountSuffix = if (discoveredModels.isNotEmpty()) " · ${discoveredModels.size} models available" else ""
                        return@withContext OmniRouteDiagnostics(
                            status = OmniRouteConnectionStatus.SUCCESS,
                            resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                            latencyMs = latency,
                            routedModel = routedModel,
                            routedProvider = routedProvider,
                            omniRouteVersion = version,
                            availableModels = discoveredModels,
                            message = "Connected ✓ (${latency}ms · $routedModel$providerSuffix$modelCountSuffix)"
                        )
                    }
                    response.code == 401 || response.code == 403 -> {
                        return@withContext OmniRouteDiagnostics(
                            status = OmniRouteConnectionStatus.BAD_KEY,
                            resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                            latencyMs = latency,
                            availableModels = discoveredModels,
                            message = "Authentication failed (${response.code}): check your Cloud Access Key."
                        )
                    }
                    else -> {
                        // If /v1/models succeeded on this gateway (e.g. fresh OmniRoute install before connecting an upstream provider),
                        // report reachable gateway with helpful guidance.
                        if (discoveredModels.isNotEmpty()) {
                            return@withContext OmniRouteDiagnostics(
                                status = OmniRouteConnectionStatus.SUCCESS,
                                resolvedEndpoint = endpoint.removeSuffix(AniobProviderConfig.CHAT_COMPLETIONS_PATH),
                                latencyMs = latency,
                                omniRouteVersion = version,
                                availableModels = discoveredModels,
                                message = "Gateway reachable ✓ (${discoveredModels.size} models cataloged; connect an upstream provider for '$targetModel')"
                            )
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Try next candidate endpoint
        } catch (_: Exception) {
            // Try next candidate endpoint
        }
    }

    if (discoveredModels.isNotEmpty()) {
        return@withContext OmniRouteDiagnostics(
            status = OmniRouteConnectionStatus.SUCCESS,
            resolvedEndpoint = AniobProviderConfig.resolveBaseUrl(baseUrl),
            availableModels = discoveredModels,
            message = "Gateway reachable ✓ (${discoveredModels.size} models discovered)"
        )
    }

    OmniRouteDiagnostics(
        status = OmniRouteConnectionStatus.UNREACHABLE,
        resolvedEndpoint = AniobProviderConfig.resolveBaseUrl(baseUrl),
        message = if (sawHtml) {
            "The address ${AniobProviderConfig.resolveBaseUrl(baseUrl)} answered with a web page, not the OmniRoute API — " +
                "check the address (it should end with /v1)."
        } else {
            "Gateway unreachable at ${AniobProviderConfig.resolveBaseUrl(baseUrl)} — verify server address or network."
        }
    )
}

suspend fun testOmniRouteConnection(apiKey: String, baseUrl: String): OmniRouteConnectionStatus =
    verifyOmniRouteGateway(apiKey = apiKey, baseUrl = baseUrl).status
