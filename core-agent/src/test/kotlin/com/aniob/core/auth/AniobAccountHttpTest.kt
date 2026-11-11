package com.aniob.core.auth

import com.aniob.core.external.GatewayFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAccountHttpTest {
    private fun c(status: Int, type: String?, body: String, retry: String? = null) =
        AniobAccountHttp.classify(status, type, body, retry, "https://x.test/responses")

    @Test fun `ok stream`() = assertEquals(AniobAccountHttpOutcome.Ok, c(200, "text/event-stream; charset=utf-8", "data: x"))

    @Test fun `401 means refresh once`() = assertEquals(AniobAccountHttpOutcome.Unauthorized, c(401, "application/json", "{}"))

    @Test fun `403 plan versus workspace`() {
        assertEquals(AniobAccountHttpOutcome.Denied(AniobAuthError.PlanHasNoAccess), c(403, "application/json", """{"detail":"not included in your plan"}"""))
        val ws = c(403, "application/json", """{"error":{"code":"workspace_not_allowed"}}""") as AniobAccountHttpOutcome.Denied
        assertTrue(ws.error is AniobAuthError.WorkspaceOrAccountDenied)
    }

    @Test fun `429 honours Retry-After, with a default and a cap`() {
        assertEquals(AniobAccountHttpOutcome.RateLimited(7_000), c(429, "application/json", "{}", "7"))
        assertEquals(AniobAccountHttpOutcome.RateLimited(AniobRetryAfter.DEFAULT_MS), c(429, "application/json", "{}", null))
        assertEquals(AniobAccountHttpOutcome.RateLimited(AniobRetryAfter.MAX_MS), c(429, "application/json", "{}", "999999"))
    }

    @Test fun `an html error page becomes a classified gateway failure with only its title`() {
        val out = c(502, "text/html", "<html><head><title>Bad Gateway</title></head><body>secret page body</body></html>") as AniobAccountHttpOutcome.Gateway
        assertEquals(GatewayFailureKind.SERVER_ERROR, out.failure.kind)
        assertTrue(out.failure.message!!.contains("Bad Gateway"))
        assertFalse(out.failure.message!!.contains("secret page body"))
        val ok = c(200, "text/html", "<!doctype html><html></html>") as AniobAccountHttpOutcome.Gateway
        assertEquals(GatewayFailureKind.NOT_JSON, ok.failure.kind)
    }

    @Test fun `404 and 400 map to the existing kinds, and a token in an error is redacted`() {
        assertEquals(GatewayFailureKind.NOT_FOUND, (c(404, "application/json", "{}") as AniobAccountHttpOutcome.Gateway).failure.kind)
        val bad = c(400, "application/json", """{"detail":"bad header Bearer abcdefghijklmnop1234"}""") as AniobAccountHttpOutcome.Gateway
        assertEquals(GatewayFailureKind.CLIENT_ERROR, bad.failure.kind)
        assertFalse(bad.failure.message!!.contains("abcdefghijklmnop1234"))
    }

    @Test fun `redirects and subscription error codes classify accurately`() {
        assertEquals(GatewayFailureKind.NOT_JSON, (c(302, "text/html", "") as AniobAccountHttpOutcome.Gateway).failure.kind)
        assertTrue(c(429, "application/json", """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""") is AniobAccountHttpOutcome.RateLimited)
        assertEquals(
            AniobAccountHttpOutcome.Denied(AniobAuthError.PlanHasNoAccess),
            c(400, "application/json", """{"error":{"code":"subscription_sharing_user_not_eligible"}}""")
        )
        assertEquals(
            GatewayFailureKind.NOT_FOUND,
            (c(400, "application/json", """{"error":{"code":"model_not_found"}}""") as AniobAccountHttpOutcome.Gateway).failure.kind
        )
    }
}
