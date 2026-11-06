package com.aniob.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobCallbackParserTest {
    private val path = "/auth/callback"
    private fun parse(target: String) = AniobAuthCallbackParser.parse(target, path, "S1")

    @Test fun `valid code and state`() {
        val r = parse("/auth/callback?code=abc%2F123&state=S1")
        assertTrue(r is AniobCallbackResult.Code)
        assertEquals("abc/123", (r as AniobCallbackResult.Code).code)
        assertEquals("Code(<redacted>)", r.toString())
    }

    @Test fun `ignores unrelated paths`() {
        assertEquals(AniobCallbackResult.Ignored, parse("/other?code=abc&state=S1"))
    }

    @Test fun `state mismatch fails closed before looking at error or code`() {
        assertEquals(
            AniobCallbackResult.Failure(AniobAuthError.StateMismatch),
            parse("/auth/callback?code=abc&state=WRONG")
        )
        assertEquals(
            AniobCallbackResult.Failure(AniobAuthError.StateMismatch),
            parse("/auth/callback?error=access_denied&state=WRONG")
        )
    }

    @Test fun `access denied maps to user cancelled`() {
        assertEquals(
            AniobCallbackResult.Failure(AniobAuthError.UserCancelled),
            parse("/auth/callback?error=access_denied&state=S1")
        )
    }

    @Test fun `workspace denial is distinct`() {
        val r = parse("/auth/callback?error=3p_login_workspace_scope_denied&state=S1")
        assertTrue(r is AniobCallbackResult.Failure)
        assertTrue((r as AniobCallbackResult.Failure).error is AniobAuthError.WorkspaceOrAccountDenied)
    }

    @Test fun `missing code is unparseable`() {
        assertEquals(
            AniobCallbackResult.Failure(AniobAuthError.Unparseable),
            parse("/auth/callback?state=S1")
        )
    }
}
