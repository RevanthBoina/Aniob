package com.aniob.core.auth

import com.aniob.core.auth.AniobAccountScreenState as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAuthCodecTest {
    @Test fun `tokens round trip`() {
        val t = AniobAuthFixtures.tokens(expiresAt = 99L).copy(idToken = "idt")
        assertEquals(t, AniobAuthCodec.decodeTokens(AniobAuthCodec.encode(t)))
    }

    @Test fun `garbage, wrong version and missing fields decode to null`() {
        assertNull(AniobAuthCodec.decodeTokens("not json".toByteArray()))
        assertNull(AniobAuthCodec.decodeTokens(byteArrayOf(1, 2, 3)))
        assertNull(AniobAuthCodec.decodeTokens("""{"v":2,"access":"a","refresh":"r","exp":1}""".toByteArray()))
        assertNull(AniobAuthCodec.decodeTokens("""{"v":1,"access":"a","exp":1}""".toByteArray()))
    }

    @Test fun `pending round trip`() {
        val p = AniobPendingSignIn("ver", "st", 5L, "nonce", "test_client")
        val back = AniobAuthCodec.decodePending(AniobAuthCodec.encode(p))!!
        assertEquals("nonce", back.nonce)
        assertEquals("test_client", back.clientId)
        assertEquals(Triple("ver", "st", 5L), Triple(back.verifier, back.state, back.startedAtMs))
        assertNull(AniobAuthCodec.decodePending("{}".toByteArray()))
    }

    @Test fun `every auth state maps to a screen state`() {
        assertEquals(S.Intro, S.from(AniobAuthState.SignedOut))
        assertEquals(S.Waiting, S.from(AniobAuthState.SigningIn(1)))
        assertEquals(S.Connected("j***@x.io", "plus"), S.from(AniobAuthState.SignedIn("j***@x.io", 1, "plus")))
        val expired = S.from(AniobAuthState.Expired(AniobAuthError.RefreshRejected("x"))) as S.Problem
        assertEquals("account_error_session_ended", expired.messageKey); assertTrue(expired.retry)
        val ws = S.from(AniobAuthState.Failed(AniobAuthError.WorkspaceOrAccountDenied("c"))) as S.Problem
        assertTrue(ws.workspaceHint); assertTrue(ws.retry)
        val nc = S.from(AniobAuthState.Failed(AniobAuthError.NotConfigured)) as S.Problem
        assertTrue(!nc.retry)
        assertEquals("account_error_timeout", (S.from(AniobAuthState.Failed(AniobAuthError.CallbackTimeout)) as S.Problem).messageKey)
    }
}
