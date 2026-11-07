package com.aniob.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AniobIdTokenVerifierTest {
    private val clock = AniobAuthFixtures.FakeClock()
    private val issuer = "https://issuer.example.test"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val key get() = pair.public as RSAPublicKey
    private fun b64(bytes: ByteArray) = encoder.encodeToString(bytes)
    private fun claims() = linkedMapOf<String, Any?>("iss" to issuer, "aud" to "client", "sub" to "subject",
        "nonce" to "nonce", "exp" to clock.t / 1000 + 3600)
    private fun jwt(claims: Map<String, Any?> = claims(), kid: String = "key-1", alg: String = "RS256"): String {
        val body = b64(AniobAuthJson.toJson(mapOf("alg" to alg, "kid" to kid)).toByteArray()) + "." +
            b64(AniobAuthJson.toJson(claims).toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update(body.toByteArray(Charsets.US_ASCII)); sign()
        }
        return "$body.${b64(signature)}"
    }
    private fun jwks(kid: String = "key-1") = AniobAuthJson.toJson(mapOf("keys" to listOf(mapOf(
        "kid" to kid, "kty" to "RSA", "alg" to "RS256", "use" to "sig",
        "n" to b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()),
        "e" to b64(key.publicExponent.toByteArray())
    ))))
    private fun verifier() = AniobJwksIdTokenVerifier(issuer, { url ->
        assertEquals("$issuer/.well-known/jwks.json", url)
        AniobHttpReply(200, "application/json", jwks())
    }, clock)

    @Test fun `valid signed identity verifies`() = runTest {
        assertTrue(verifier().verify(jwt(), "client", "nonce"))
    }

    @Test fun `issuer audience nonce expiry subject and algorithm must all match`() = runTest {
        val verifier = verifier()
        listOf(
            claims().apply { put("iss", "https://other.example.test") },
            claims().apply { put("aud", "other") },
            claims().apply { put("nonce", "other") },
            claims().apply { put("exp", clock.t / 1000) },
            claims().apply { remove("sub") },
            claims().apply { put("nbf", clock.t / 1000 + 3600) },
            claims().apply { put("aud", listOf("client", "other")) }
        ).forEach { assertFalse(verifier.verify(jwt(it), "client", "nonce")) }
        assertFalse(verifier.verify(jwt(alg = "none"), "client", "nonce"))
        assertFalse(verifier.verify(jwt(alg = "HS256"), "client", "nonce"))
        assertFalse(verifier.verify(jwt(), "client", ""))
    }

    @Test fun `tampered payload and malformed tokens fail closed`() = runTest {
        val token = jwt()
        val parts = token.split('.')
        val forged = parts[0] + "." + b64(AniobAuthJson.toJson(claims().apply { put("sub", "forged") }).toByteArray()) + "." + parts[2]
        assertFalse(verifier().verify(forged, "client", "nonce"))
        listOf("", "not.a.jwt", "a.b.c.d", "x".repeat(65 * 1024)).forEach {
            assertFalse(verifier().verify(it, "client", "nonce"))
        }
    }

    @Test fun `keys are cached and unknown key triggers bounded rotation lookup`() = runTest {
        var calls = 0
        var document = jwks()
        val verifier = AniobJwksIdTokenVerifier(issuer, { calls++; AniobHttpReply(200, "application/json", document) }, clock)
        assertTrue(verifier.verify(jwt(), "client", "nonce"))
        assertTrue(verifier.verify(jwt(), "client", "nonce"))
        assertEquals(1, calls)
        assertFalse(verifier.verify(jwt(kid = "unknown"), "client", "nonce"))
        assertEquals(1, calls)
        clock.t += 31_000
        document = jwks("rotated")
        assertTrue(verifier.verify(jwt(kid = "rotated"), "client", "nonce"))
        assertEquals(2, calls)
    }

    @Test fun `JWKS outage is never verification success`() = runTest {
        val verifier = AniobJwksIdTokenVerifier(issuer, { throw java.io.IOException("offline") }, clock)
        assertFalse(verifier.verify(jwt(), "client", "nonce"))
    }

    companion object {
        // Generated in memory; never persisted or used outside unit tests.
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
