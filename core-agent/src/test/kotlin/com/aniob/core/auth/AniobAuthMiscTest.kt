package com.aniob.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAuthMiscTest {
    @Test fun `redactor removes bearer, jwt, query params and json fields`() {
        val jwt = AniobAuthFixtures.idToken()
        val text = "Authorization: Bearer abc.DEF-123_456 and id=$jwt " +
            "GET /cb?code=SECRETCODE&state=SECRETSTATE&x=1 body {\"refresh_token\":\"RT-SECRET\",\"ok\":1} refresh_token=RT2"
        val out = AniobSecretRedactor.redact(text)
        listOf("abc.DEF-123_456", jwt, "SECRETCODE", "SECRETSTATE", "RT-SECRET", "RT2").forEach {
            assertFalse("leaked $it in $out", out.contains(it))
        }
        assertTrue(out.contains("x=1"))
        assertEquals("plain text stays", AniobSecretRedactor.redact("plain text stays"))
    }

    @Test fun `config validation fails closed`() {
        val ok = AniobAuthFixtures.config
        assertTrue(ok.isUsable)
        assertFalse(ok.copy(clientId = "").isUsable)
        assertFalse(ok.copy(tokenUrl = "http://auth.example.test/oauth/token").isUsable)
        assertFalse(ok.copy(inferenceBaseUrl = "http://models.example.test").isUsable)
        assertFalse(ok.copy(redirectUri = "http://0.0.0.0:1455/auth/callback").isUsable)
        assertFalse(ok.copy(redirectUri = "http://example.com/cb").isUsable)
        assertTrue(ok.copy(redirectUri = "http://localhost:1455/auth/callback").isUsable)
        assertEquals(1455, ok.redirectPort)
        assertEquals("/auth/callback", ok.redirectPath)
        assertFalse(ok.toString().contains("test_client"))
    }

    @Test fun `extra params parse`() {
        assertEquals(
            mapOf("a" to "1", "b" to "true"),
            AniobAuthConfig.parseExtraParams("a=1&b=true&=skip&&")
        )
    }

    @Test fun `router picks the account only when signed in and never swaps to the gateway`() {
        val signedIn = AniobAuthState.SignedIn("j***", 1L, "plus")
        fun pick(pref: AniobCloudSource, state: AniobAuthState, feature: Boolean = true, omni: Boolean = true) =
            AniobCloudSourceSelector.select(pref, state, feature, omni)

        assertEquals(AniobCloudSelection.Use(AniobCloudSource.ACCOUNT), pick(AniobCloudSource.ACCOUNT, signedIn))
        for (state in listOf(AniobAuthState.SignedOut, AniobAuthState.SigningIn(1), AniobAuthState.Expired(AniobAuthError.RefreshRejected(null)))) {
            val r = pick(AniobCloudSource.ACCOUNT, state)
            assertEquals(AniobCloudSelection.Unavailable(AniobCloudSource.ACCOUNT, "Sign in again in Settings."), r)
        }
        // Gateway is configured, yet an account choice is not silently redirected to it.
        assertTrue(pick(AniobCloudSource.ACCOUNT, AniobAuthState.SignedOut, omni = true) is AniobCloudSelection.Unavailable)
        assertTrue(pick(AniobCloudSource.ACCOUNT, signedIn, feature = false) is AniobCloudSelection.Unavailable)
        // The existing default path is untouched.
        assertEquals(AniobCloudSelection.Use(AniobCloudSource.OMNIROUTE), pick(AniobCloudSource.OMNIROUTE, AniobAuthState.SignedOut))
        assertTrue(pick(AniobCloudSource.OMNIROUTE, signedIn, omni = false) is AniobCloudSelection.Unavailable)
        assertEquals(AniobCloudSource.OMNIROUTE, AniobCloudSource.fromPref(null))
        assertEquals(AniobCloudSource.ACCOUNT, AniobCloudSource.fromPref("account"))
    }

    @Test fun `cloud-only never allows a local fallback`() {
        assertFalse(AniobCloudSourceSelector.allowsLocalFallback("cloud-only"))
        assertFalse(AniobCloudSourceSelector.allowsLocalFallback("CLOUD-ONLY"))
        listOf("auto", "balanced", "local-first", "local-only").forEach {
            assertTrue(AniobCloudSourceSelector.allowsLocalFallback(it))
        }
    }

    @Test fun `request body shape`() {
        val body = AniobAuthJson.parseObject(AniobResponsesRequest.build("m1", "", "hi", "QUJD", "low"))!!
        assertEquals("m1", body["model"]); assertEquals(true, body["stream"]); assertEquals(false, body["store"])
        assertEquals("You are a helpful assistant.", body["instructions"])
        assertFalse(body.containsKey("max_output_tokens"))
        assertEquals("low", AniobAuthJson.obj(body["reasoning"])!!["effort"])
        @Suppress("UNCHECKED_CAST")
        val content = ((body["input"] as List<Map<String, Any?>>)[0]["content"]) as List<Map<String, Any?>>
        assertEquals("input_text", content[0]["type"]); assertEquals("input_image", content[1]["type"])
        assertEquals("data:image/jpeg;base64,QUJD", content[1]["image_url"])
        assertFalse(AniobAuthJson.parseObject(AniobResponsesRequest.build("m", "i", "t"))!!.containsKey("reasoning"))
    }

    @Test fun `model catalog parses, validates and falls back`() {
        val cat = AniobAccountModelCatalog.parse("default: m2\nmodels:\n  - id: m1\n    label: \"One\"\n  - id: m2\n    label: Two\n  - id: bad id!\n")
        assertEquals(listOf("m1", "m2"), cat.models.map { it.id })
        assertEquals("m2", cat.defaultModelId)
        assertEquals("Two", cat.labelFor("m2"))
        assertEquals("typed-model", cat.resolve(" typed-model "))
        assertEquals("m2", cat.resolve("not valid!"))
        assertEquals(AniobAccountModelCatalog.FALLBACK_MODEL, AniobAccountModelCatalog.parse("").defaultModelId)
        assertNull(AniobAccountModelCatalog.validate("gpt-5.4-mini"))
    }
}
