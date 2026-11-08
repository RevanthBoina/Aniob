package com.aniob.core.auth

import com.aniob.core.external.AniobGatewayClassifier
import com.aniob.core.external.AniobGatewayException
import com.aniob.core.external.GatewayFailureKind

/** What to do with a reply from the signed-in account's model service. */
sealed interface AniobAccountHttpOutcome {
    data object Ok : AniobAccountHttpOutcome

    /** 401: refresh once and retry the request exactly once. */
    data object Unauthorized : AniobAccountHttpOutcome

    /** 403: the account may not use this service, or the workspace is out of scope. */
    data class Denied(val error: AniobAuthError) : AniobAccountHttpOutcome

    /** 429: wait [retryAfterMs] before the next request. */
    data class RateLimited(val retryAfterMs: Long) : AniobAccountHttpOutcome

    /** 5xx, HTML, 404, other 4xx: handled by the existing gateway ladder and cool-down. */
    data class Gateway(val failure: AniobGatewayException) : AniobAccountHttpOutcome
}

object AniobAccountHttp {
    const val NAME = "ACCOUNT_CLOUD"

    /** [retryAfterHeader] is the raw `Retry-After` value, if any. */
    fun classify(
        status: Int,
        contentType: String?,
        body: String,
        retryAfterHeader: String?,
        endpoint: String?
    ): AniobAccountHttpOutcome {
        // Content is checked before anything is parsed: a web page is never an answer.
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val html = AniobGatewayClassifier.looksLikeHtml(body) || type == "text/html" || type == "application/xhtml+xml"
        if (status in 200..299) {
            return if (html) {
                AniobAccountHttpOutcome.Gateway(failure(GatewayFailureKind.NOT_JSON, status, endpoint, "expected a stream but got a web page"))
            } else AniobAccountHttpOutcome.Ok
        }
        if (html && status != 401 && status != 429) {
            val kind = if (status >= 500) GatewayFailureKind.SERVER_ERROR else GatewayFailureKind.NOT_JSON
            return AniobAccountHttpOutcome.Gateway(failure(kind, status, endpoint, summaryOf(body)))
        }
        return when {
            status == 401 -> AniobAccountHttpOutcome.Unauthorized
            status == 403 -> AniobAccountHttpOutcome.Denied(deniedError(body))
            status == 429 -> AniobAccountHttpOutcome.RateLimited(AniobRetryAfter.millis(retryAfterHeader))
            status == 404 || status == 405 || status == 410 ->
                AniobAccountHttpOutcome.Gateway(failure(GatewayFailureKind.NOT_FOUND, status, endpoint, summaryOf(body)))
            status in 400..499 ->
                AniobAccountHttpOutcome.Gateway(failure(GatewayFailureKind.CLIENT_ERROR, status, endpoint, summaryOf(body)))
            else ->
                AniobAccountHttpOutcome.Gateway(failure(GatewayFailureKind.SERVER_ERROR, status, endpoint, summaryOf(body)))
        }
    }

    /** The exception thrown to the existing ladder; keeps the `(<code>)` message shape callers match on. */
    fun failure(kind: GatewayFailureKind, status: Int?, endpoint: String?, summary: String) =
        AniobGatewayException(kind, status, endpoint, "Account model error (${status ?: "-"}): $summary")

    fun signInNeeded(): AniobGatewayException = AniobGatewayException(
        GatewayFailureKind.AUTH, null, null,
        "Account model error (401): sign in again in Settings."
    )

    private fun deniedError(body: String): AniobAuthError {
        val json = AniobAuthJson.parseObject(body)
        val error = AniobAuthJson.obj(json?.get("error"))
        val code = AniobAuthJson.str(error?.get("code")) ?: AniobAuthJson.str(json?.get("error"))
            ?: AniobAuthJson.str(json?.get("code"))
        val haystack = (code.orEmpty() + " " + AniobAuthJson.str(json?.get("detail")).orEmpty()).lowercase()
        return if (haystack.contains("workspace") || haystack.contains("organization")) {
            AniobAuthError.WorkspaceOrAccountDenied(code)
        } else {
            AniobAuthError.PlanHasNoAccess
        }
    }

    /** One short line from a JSON error, else a neutral phrase. Never a page, never a token. */
    fun summaryOf(body: String): String {
        if (AniobGatewayClassifier.looksLikeHtml(body)) return AniobGatewayClassifier.summarize(body)
        val json = AniobAuthJson.parseObject(body) ?: return "no details"
        val error = json["error"]
        val message = AniobAuthJson.str(AniobAuthJson.obj(error)?.get("message"))
            ?: AniobAuthJson.str(json["detail"]) ?: AniobAuthJson.str(error)
        return AniobSecretRedactor.redact(message ?: "no details").replace(Regex("\\s+"), " ").take(160)
    }
}
