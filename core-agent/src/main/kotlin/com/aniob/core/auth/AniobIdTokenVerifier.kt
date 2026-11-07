package com.aniob.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Verifies identity before any session is committed. Default is deliberately fail-closed. */
fun interface AniobIdTokenVerifier {
    suspend fun verify(token: String, clientId: String, nonce: String): Boolean

    companion object {
        val REJECT = AniobIdTokenVerifier { _, _, _ -> false }
    }
}

fun interface AniobJwksTransport {
    @Throws(IOException::class)
    suspend fun get(url: String): AniobHttpReply
}

/** RS256 only. JWKS is fetched from the configured issuer, never from token-supplied URLs. */
class AniobJwksIdTokenVerifier(
    private val issuer: String,
    private val transport: AniobJwksTransport,
    private val clock: AniobClock = AniobClock.SYSTEM
) : AniobIdTokenVerifier {
    private val lock = Mutex()
    private var keys: List<Map<String, Any?>> = emptyList()
    private var fetchedAt = Long.MIN_VALUE
    private var lastAttemptAt: Long? = null

    override suspend fun verify(token: String, clientId: String, nonce: String): Boolean {
        if (token.length > 64 * 1024 || nonce.isBlank() || clientId.isBlank()) return false
        val pieces = token.split('.')
        if (pieces.size != 3) return false
        val header = decode(pieces[0]) ?: return false
        val claims = decode(pieces[1]) ?: return false
        if (header["alg"] != "RS256" || header.containsKey("crit")) return false
        val kid = AniobAuthJson.str(header["kid"]) ?: return false
        val key = findKey(kid) ?: return false
        val verified = try {
            val n = BigInteger(1, Base64.getUrlDecoder().decode(AniobAuthJson.str(key["n"]) ?: return false))
            val e = BigInteger(1, Base64.getUrlDecoder().decode(AniobAuthJson.str(key["e"]) ?: return false))
            if (n.bitLength() !in 2048..8192 || e.bitLength() > 32 || e.toLong() < 3 || !e.testBit(0)) return false
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(pieces[2]))
            }
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!verified) return false
        val now = clock.now() / 1000
        val audience = claims["aud"]
        val audiences = when (audience) {
            is String -> listOf(audience)
            is List<*> -> audience.filterIsInstance<String>()
            else -> return false
        }
        return claims["iss"] == issuer && clientId in audiences &&
            (audiences.size <= 1 || claims["azp"] == clientId) &&
            AniobAuthJson.long(claims["exp"])?.let { it > now } == true &&
            (AniobAuthJson.long(claims["nbf"]) ?: 0L) <= now &&
            !AniobAuthJson.str(claims["sub"]).isNullOrBlank() &&
            (claims["nonce"] as? String)?.let { AniobAuthCallbackParser.constantTimeEquals(it, nonce) } == true
    }

    private suspend fun findKey(kid: String): Map<String, Any?>? = lock.withLock {
        fun cached() = keys.singleOrNull {
            it["kid"] == kid && it["kty"] == "RSA" &&
                (it["use"] == null || it["use"] == "sig") &&
                (it["alg"] == null || it["alg"] == "RS256") &&
                (it["key_ops"] == null || (it["key_ops"] as? List<*>)?.contains("verify") == true)
        }
        if (fetchedAt != Long.MIN_VALUE && clock.now() - fetchedAt < 60 * 60_000L) {
            cached()?.let { return@withLock it }
        }
        // Unknown-key refresh is bounded too: random kid values must not flood the issuer.
        if (lastAttemptAt?.let { clock.now() - it < 30_000L } == true) return@withLock null
        lastAttemptAt = clock.now()
        val reply = try { transport.get(issuer.trimEnd('/') + "/.well-known/jwks.json") }
        catch (e: IOException) { return@withLock null }
        if (reply.status != 200 || reply.body.length > 1024 * 1024) return@withLock null
        val root = AniobAuthJson.parseObject(reply.body) ?: return@withLock null
        keys = (root["keys"] as? List<*>)?.mapNotNull(AniobAuthJson::obj)?.take(100) ?: return@withLock null
        fetchedAt = clock.now()
        cached()
    }

    private fun decode(part: String): Map<String, Any?>? = try {
        AniobAuthJson.parseObject(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8))
    } catch (e: IllegalArgumentException) { null }
}
