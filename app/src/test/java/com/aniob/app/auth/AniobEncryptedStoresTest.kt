package com.aniob.app.auth

import com.aniob.core.auth.AniobAuthTokens
import com.aniob.core.auth.AniobPendingSignIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AniobEncryptedStoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tokens = AniobAuthTokens(
        accessToken = "ACCESS-VALUE", refreshToken = "REFRESH-VALUE", idToken = "ID-VALUE",
        expiresAtEpochMs = 123L, accountId = "acct", planType = "plus", email = "a@b.io"
    )

    @Test fun `save load clear round trip, ciphertext on disk has no plaintext`() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        val store = AniobKeystoreTokenStore(dir, AniobSoftwareCipher())
        assertNull(store.load())
        store.save(tokens)
        assertEquals(tokens, store.load())
        val onDisk = File(dir, "tokens.v1").readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("REFRESH-VALUE"))
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("ACCESS-VALUE"))
        store.save(tokens.copy(refreshToken = "ROTATED"))
        assertEquals("ROTATED", store.load()!!.refreshToken)
        store.clear()
        assertNull(store.load())
        assertFalse(File(dir, "tokens.v1").exists())
    }

    @Test fun `each write uses a fresh IV`() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        val store = AniobKeystoreTokenStore(dir, AniobSoftwareCipher())
        store.save(tokens); val a = File(dir, "tokens.v1").readBytes().copyOf(12)
        store.save(tokens); val b = File(dir, "tokens.v1").readBytes().copyOf(12)
        assertFalse(a.contentEquals(b))
    }

    @Test fun `a corrupted blob is reported as nothing stored and removed`() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        val store = AniobKeystoreTokenStore(dir, AniobSoftwareCipher())
        store.save(tokens)
        val f = File(dir, "tokens.v1")
        val bytes = f.readBytes(); bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x55).toByte(); f.writeBytes(bytes)
        assertNull(store.load())
        assertFalse(f.exists())
        f.writeBytes(byteArrayOf(1, 2, 3)); assertNull(store.load())
    }

    @Test fun `an invalidated key (a different key) is signed out, not a crash`() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        AniobKeystoreTokenStore(dir, AniobSoftwareCipher()).save(tokens)
        assertNull(AniobKeystoreTokenStore(dir, AniobSoftwareCipher()).load())
    }

    @Test fun `pending record round trips and expires after ten minutes`() = runBlocking<Unit> {
        val clock = MutableClock()
        val store = AniobKeystorePendingStore(tmp.newFolder(), AniobSoftwareCipher(), clock)
        store.save(AniobPendingSignIn("v", "s", clock.t))
        assertNotNull(store.load())
        clock.t += 9 * 60_000L; assertTrue(store.load() != null)
        clock.t += 2 * 60_000L; assertNull(store.load())
        assertNull(store.load())
    }
}
