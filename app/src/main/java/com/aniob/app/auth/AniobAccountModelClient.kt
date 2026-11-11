package com.aniob.app.auth

import com.aniob.app.network.AniobHttpClientSingleton
import com.aniob.app.provider.AniobEgressLedger
import com.aniob.core.auth.AniobAccessGrant
import com.aniob.core.auth.AniobAccessResult
import com.aniob.core.auth.AniobAccountHttp
import com.aniob.core.auth.AniobAccountHttpOutcome
import com.aniob.core.auth.AniobAuthJson
import com.aniob.core.auth.AniobAuthConfig
import com.aniob.core.auth.AniobAuthError
import com.aniob.core.auth.AniobAuthRepository
import com.aniob.core.auth.AniobAuthState
import com.aniob.core.auth.AniobClock
import com.aniob.core.auth.AniobResponsesRequest
import com.aniob.core.auth.AniobSecretRedactor
import com.aniob.core.auth.AniobSseParser
import com.aniob.core.auth.AniobStreamAccumulator
import com.aniob.core.auth.AniobStreamEvent
import com.aniob.core.external.AniobActionStepProvider
import com.aniob.core.external.AniobCloudLlmProvider
import com.aniob.core.external.AniobGatewayException
import com.aniob.core.external.AniobGatewayHealth
import com.aniob.core.external.GatewayFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The model service behind the signed-in ChatGPT account. It implements the **same** interfaces as
 * the gateway client (`AniobCloudLlmProvider` for Q&A, `AniobActionStepProvider` for UI steps), so
 * the router treats it as one more cloud source.
 *
 * Failure handling:
 *  - not signed in, or inside a `Retry-After` window, or the address is in its cool-down: a typed
 *    [AniobGatewayException] **before** any socket opens, and `onLlmRequest` is not called, so a
 *    skipped request is not counted as an LLM call;
 *  - 401: ask the repository for a refresh once and resend exactly once;
 *  - 403 / 429 / 5xx / HTML: classified by `AniobAccountHttp` into the existing
 *    [GatewayFailureKind] ladder so `degradeAfterCloudFailure` and the cool-down work unchanged;
 *  - a stream that ends early is a failure, never a made-up answer.
 */
class AniobAccountModelClient(
    private val config: AniobAuthConfig,
    private val repository: AniobAuthRepository,
    private val model: String,
    private val reasoningEffort: String? = null,
    private val sessionId: String? = null,
    private val onLlmRequest: () -> Unit = {},
    private val health: AniobGatewayHealth = AniobGatewayHealth.shared,
    private val clock: AniobClock = AniobClock.SYSTEM,
    private val client: OkHttpClient = AniobHttpClientSingleton.client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : AniobCloudLlmProvider, AniobActionStepProvider {

    override val name: String = AniobAccountHttp.NAME
    override val isConfigured: Boolean
        get() = config.isUsable && repository.state.value is AniobAuthState.SignedIn

    private val endpoint = config.inferenceBaseUrl.trimEnd('/') + "/responses"
    private val session = sessionId ?: UUID.randomUUID().toString()

    // ---- AniobCloudLlmProvider (Q&A). Error replies use the sentinel the trigger already understands.

    override suspend fun generate(prompt: String, systemPrompt: String): String =
        answer { stream(prompt, systemPrompt, null, onDelta = {}, onFirstToken = {}) }

    override suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit): String =
        answer { stream(prompt, systemPrompt, null, onDelta, onFirstToken = {}) }

    private suspend fun answer(block: suspend () -> String): String = try {
        block()
    } catch (e: AniobGatewayException) {
        "Request failed: ${e.message}"
    } catch (e: IOException) {
        "Request failed: ${e.javaClass.simpleName}"
    }

    // ---- AniobActionStepProvider (UI steps). `maxTokens` is not sent: the service rejects it.

    override suspend fun getNextActionRawStreaming(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String?,
        maxTokens: Int,
        onFirstToken: () -> Unit
    ): Result<String> = step { stream(userPrompt, systemPrompt, screenshotBase64, onDelta = {}, onFirstToken) }

    override suspend fun getNextActionRaw(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String?,
        maxTokens: Int
    ): Result<String> = step { stream(userPrompt, systemPrompt, screenshotBase64, onDelta = {}, onFirstToken = {}) }

    private suspend fun step(block: suspend () -> String): Result<String> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Result.failure(e)
    }

    // ---- Transport

    private suspend fun stream(
        userText: String,
        instructions: String,
        screenshotBase64: String?,
        onDelta: (String) -> Unit,
        onFirstToken: () -> Unit
    ): String {
        val started = clock.now()
        val body = AniobResponsesRequest.build(
            model = model,
            instructions = AniobSecretRedactor.redact(instructions),
            userText = AniobSecretRedactor.redact(userText),
            screenshotBase64 = screenshotBase64,
            reasoningEffort = reasoningEffort
        )

        gate()
        var grant = grantOrThrow(repository.obtainAccess())
        var outcome = attempt(grant, body, onDelta, onFirstToken)
        if (outcome is Attempt.Unauthorized) {
            // One refresh, one resend. A second 401 is final.
            grant = grantOrThrow(repository.obtainAccess(rejectedAccessToken = grant.accessToken))
            outcome = attempt(grant, body, onDelta, onFirstToken)
        }
        return when (outcome) {
            is Attempt.Done -> {
                health.recordSuccess(endpoint)
                AniobEgressLedger.record(name, model, userText.length + instructions.length, outcome.text.length, clock.now() - started)
                outcome.text
            }
            is Attempt.Unauthorized -> throw AniobAccountHttp.failure(GatewayFailureKind.AUTH, 401, endpoint, "sign in again in Settings")
            is Attempt.Failed -> throw outcome.error
        }
    }

    /** Refuses before any socket opens: signed out, rate-limited, or in the cool-down. */
    private fun gate() {
        if (repository.state.value !is AniobAuthState.SignedIn) throw AniobAccountHttp.signInNeeded()
        val wait = rateLimitedUntil.get() - clock.now()
        if (wait > 0) {
            throw AniobAccountHttp.failure(GatewayFailureKind.SERVER_ERROR, 429, endpoint, "rate limited, retry in ${wait / 1000 + 1}s")
        }
        if (health.admit(endpoint, isLocal = false) is AniobGatewayHealth.Admission.Skip) {
            throw AniobAccountHttp.failure(GatewayFailureKind.UNREACHABLE, null, endpoint, "recently failed; not retrying yet")
        }
    }

    private fun grantOrThrow(result: AniobAccessResult): AniobAccessGrant = when (result) {
        is AniobAccessResult.Granted -> result.grant
        is AniobAccessResult.Unavailable -> when (val error = result.error) {
            is AniobAuthError.RefreshConfiguration -> throw AniobAccountHttp.failure(
                GatewayFailureKind.CLIENT_ERROR, error.httpStatus, endpoint,
                "connection configuration was rejected; local session retained")
            is AniobAuthError.Network -> throw AniobAccountHttp.failure(GatewayFailureKind.UNREACHABLE, null, endpoint, "could not refresh the session")
            else -> throw AniobAccountHttp.signInNeeded()
        }
    }

    private sealed interface Attempt {
        class Done(val text: String) : Attempt
        data object Unauthorized : Attempt
        class Failed(val error: AniobGatewayException) : Attempt
    }

    private suspend fun attempt(
        grant: AniobAccessGrant,
        body: String,
        onDelta: (String) -> Unit,
        onFirstToken: () -> Unit
    ): Attempt {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${grant.accessToken}")
            .header("User-Agent", "Aniob/1.0")
            .header("Accept", "text/event-stream, application/json")
            .header("session_id", session)
            .apply {
                grant.accountId?.let { header("chatgpt-account-id", it) }
                config.originator.takeUnless { it.isBlank() || it.equals("NONE", true) || it.equals("UNSET", true) }
                    ?.let { header("originator", it) }
                config.betaHeader.takeUnless { it.isBlank() || it.equals("NONE", true) || it.equals("UNSET", true) }
                    ?.let { header("OpenAI-Beta", it) }
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        onLlmRequest()
        val response = try {
            client.newCall(request).awaitResponse()
        } catch (e: IOException) {
            health.recordFailure(endpoint, GatewayFailureKind.UNREACHABLE)
            return Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.UNREACHABLE, null, endpoint, e.javaClass.simpleName))
        }
        return withContext(Dispatchers.IO) { response.use { read(it, onDelta, onFirstToken) } }
    }

    private suspend fun read(response: Response, onDelta: (String) -> Unit, onFirstToken: () -> Unit): Attempt {
        val code = response.code
        val type = response.header("Content-Type")
        if (code !in 200..299) {
            val errorBody = response.peekBody(MAX_ERROR_BYTES).string()
            return when (val outcome = AniobAccountHttp.classify(code, type, errorBody, response.header("Retry-After"), endpoint)) {
                AniobAccountHttpOutcome.Ok -> Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.MALFORMED, code, endpoint, "unexpected reply"))
                AniobAccountHttpOutcome.Unauthorized -> Attempt.Unauthorized
                is AniobAccountHttpOutcome.Denied -> {
                    health.recordFailure(endpoint, GatewayFailureKind.AUTH)
                    val reason = if (outcome.error is AniobAuthError.WorkspaceOrAccountDenied) "account or workspace not allowed" else "plan has no access"
                    Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.AUTH, 403, endpoint, reason))
                }
                is AniobAccountHttpOutcome.RateLimited -> {
                    rateLimitedUntil.set(clock.now() + outcome.retryAfterMs)
                    Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.SERVER_ERROR, 429, endpoint, "rate limited, retry in ${outcome.retryAfterMs / 1000}s"))
                }
                is AniobAccountHttpOutcome.Gateway -> {
                    health.recordFailure(endpoint, outcome.failure.kind)
                    Attempt.Failed(outcome.failure)
                }
            }
        }
        if (type.orEmpty().contains("application/json", ignoreCase = true)) {
            val text = extractResponseText(response.body?.string().orEmpty())
            if (text.isBlank()) return Attempt.Failed(
                AniobAccountHttp.failure(GatewayFailureKind.MALFORMED, code, endpoint, "reply had no text")
            )
            onFirstToken()
            onDelta(text)
            return Attempt.Done(text)
        }
        // A 2xx that is not a stream (an HTML page, say) is rejected before parsing.
        val peek = if (type.orEmpty().contains("event-stream", ignoreCase = true)) "" else response.peekBody(MAX_ERROR_BYTES).string()
        val ok = AniobAccountHttp.classify(code, type, peek, null, endpoint)
        if (ok is AniobAccountHttpOutcome.Gateway) {
            health.recordFailure(endpoint, ok.failure.kind)
            return Attempt.Failed(ok.failure)
        }

        val parser = AniobSseParser()
        val acc = AniobStreamAccumulator()
        var first = true
        fun consume(events: List<AniobStreamEvent>) = events.forEach { e ->
            acc.accept(e)?.let { delta ->
                if (first) { first = false; onFirstToken() }
                onDelta(delta)
            }
        }
        try {
            val source = response.body?.source()
                ?: return Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.MALFORMED, code, endpoint, "empty reply"))
            while (!parser.isFinished) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                consume(parser.feed(line + "\n"))
            }
        } catch (e: IOException) {
            // A transport error is not a clean EOF from a server that omits completion events.
            // Never return a partial action as a successful response after a broken connection.
            health.recordFailure(endpoint, GatewayFailureKind.UNREACHABLE)
            return Attempt.Failed(AniobAccountHttp.failure(
                GatewayFailureKind.UNREACHABLE, code, endpoint, "reply disconnected before completion"
            ))
        }
        consume(parser.finish())

        acc.failure?.let { failure ->
            if (failure.code == "truncated" && acc.text().isNotBlank()) return@let
            val kind = if (failure.code == "truncated") GatewayFailureKind.UNREACHABLE else GatewayFailureKind.CLIENT_ERROR
            if (kind == GatewayFailureKind.UNREACHABLE) health.recordFailure(endpoint, kind)
            return Attempt.Failed(AniobAccountHttp.failure(kind, code, endpoint, AniobSecretRedactor.redact(failure.message)))
        }
        if (!acc.completed && acc.text().isBlank()) {
            return Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.MALFORMED, code, endpoint, "no completion event"))
        }
        val text = acc.text()
        if (text.isBlank()) {
            return Attempt.Failed(AniobAccountHttp.failure(GatewayFailureKind.MALFORMED, code, endpoint, "reply had no text"))
        }
        if (first) {
            onFirstToken()
            onDelta(text)
        }
        return Attempt.Done(text)
    }

    private fun extractResponseText(raw: String): String {
        val root = AniobAuthJson.parseObject(raw) ?: return ""
        AniobAuthJson.str(root["output_text"])?.takeIf { it.isNotBlank() }?.let { return it }
        val choice = (root["choices"] as? List<*>)?.firstOrNull()?.let(AniobAuthJson::obj)
        val message = choice?.get("message")?.let(AniobAuthJson::obj)
        AniobAuthJson.str(message?.get("content"))?.takeIf { it.isNotBlank() }?.let { return it }
        val response = AniobAuthJson.obj(root["response"]) ?: root
        return (response["output"] as? List<*>).orEmpty()
            .mapNotNull(AniobAuthJson::obj)
            .filter { AniobAuthJson.str(it["type"]) == "message" }
            .flatMap { (it["content"] as? List<*>).orEmpty() }
            .mapNotNull(AniobAuthJson::obj)
            .filter { AniobAuthJson.str(it["type"]) == "output_text" }
            .mapNotNull { AniobAuthJson.str(it["text"]) }
            .joinToString("")
    }

    companion object {
        private const val MAX_ERROR_BYTES = 64L * 1024

        /** Shared across instances: providers are rebuilt per step, a `Retry-After` window must outlive them. */
        internal val rateLimitedUntil = AtomicLong(0L)
    }
}
