package com.aniob.app.auth

import com.aniob.core.auth.AniobAuthConfig
import com.aniob.core.auth.AniobAuthTokens
import com.aniob.core.auth.AniobClock
import com.aniob.core.auth.AniobHttpReply
import com.aniob.core.auth.AniobTokenStore
import com.aniob.core.auth.AniobTokenTransport
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same blob layout as the Keystore cipher, with an in-memory key, so tests need no device. */
class AniobSoftwareCipher(private val key: SecretKey = newKey()) : AniobSecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 28) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    override fun deleteKey() = Unit

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

class MutableClock(var t: Long = 1_000_000L) : AniobClock {
    override fun now() = t
}

class MemoryTokenStore(var tokens: AniobAuthTokens? = null) : AniobTokenStore {
    override suspend fun load() = tokens
    override suspend fun save(tokens: AniobAuthTokens) { this.tokens = tokens }
    override suspend fun clear() { tokens = null }
}

class ScriptedTokenTransport(private val reply: () -> AniobHttpReply) : AniobTokenTransport {
    var calls = 0
    override suspend fun post(url: String, contentType: String, body: String): AniobHttpReply {
        calls++
        return reply()
    }
}

fun testConfig(inferenceBase: String = "https://models.example.test/api") = AniobAuthConfig(
    clientId = "test_client",
    authorizeUrl = "https://auth.example.test/oauth/authorize",
    tokenUrl = "https://auth.example.test/oauth/token",
    redirectUri = "http://127.0.0.1:1455/auth/callback",
    scopes = "openid profile email offline_access chatgpt.tokens.use.direct",
    inferenceBaseUrl = inferenceBase,
    originator = "aniob-test",
    betaHeader = "responses=test"
)
