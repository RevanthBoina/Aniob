package com.aniob.app.auth

import com.aniob.core.auth.AniobAuthCodec
import com.aniob.core.auth.AniobAuthTokens
import com.aniob.core.auth.AniobClock
import com.aniob.core.auth.AniobPendingSignIn
import com.aniob.core.auth.AniobPendingSignInStore
import com.aniob.core.auth.AniobTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * One encrypted blob in a private file. The file lives under `filesDir`, a domain that
 * `backup_rules.xml` and `data_extraction_rules.xml` already exclude from cloud backup and from
 * device transfer. A blob that cannot be opened (key invalidated, file damaged) is deleted and
 * reported as "nothing stored": the user simply signs in again.
 */
internal class AniobEncryptedBlobFile(private val file: File, private val cipher: AniobSecretCipher) {

    suspend fun write(plain: ByteArray) = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(cipher.encrypt(plain))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        } catch (e: GeneralSecurityException) {
            throw IOException("could not encrypt", e)
        } catch (e: ProviderException) {
            throw IOException("keystore unavailable", e)
        }
    }

    /** Null when absent or unreadable; an unreadable file is removed. */
    suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            cipher.decrypt(file.readBytes())
        } catch (e: GeneralSecurityException) {
            file.delete(); null
        } catch (e: IllegalArgumentException) {
            file.delete(); null
        } catch (e: ProviderException) {
            file.delete(); null
        } catch (e: IOException) {
            file.delete(); null
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        Unit
    }
}

/** `AniobTokenStore` over an encrypted file. */
class AniobKeystoreTokenStore(dir: File, cipher: AniobSecretCipher) : AniobTokenStore {
    private val blob = AniobEncryptedBlobFile(File(dir, "tokens.v1"), cipher)

    override suspend fun load(): AniobAuthTokens? {
        val bytes = blob.read() ?: return null
        val tokens = AniobAuthCodec.decodeTokens(bytes)
        if (tokens == null) blob.delete()
        return tokens
    }

    override suspend fun save(tokens: AniobAuthTokens) = blob.write(AniobAuthCodec.encode(tokens))

    override suspend fun clear() = blob.delete()
}

/** The short-lived PKCE record. Expires after [maxAgeMs] and is deleted when read past that. */
class AniobKeystorePendingStore(
    dir: File,
    cipher: AniobSecretCipher,
    private val clock: AniobClock = AniobClock.SYSTEM,
    private val maxAgeMs: Long = 10 * 60 * 1000L
) : AniobPendingSignInStore {
    private val blob = AniobEncryptedBlobFile(File(dir, "pending.v1"), cipher)

    override suspend fun save(pending: AniobPendingSignIn) = blob.write(AniobAuthCodec.encode(pending))

    override suspend fun load(): AniobPendingSignIn? {
        val pending = blob.read()?.let(AniobAuthCodec::decodePending)
        if (pending == null || clock.now() - pending.startedAtMs > maxAgeMs) {
            blob.delete()
            return null
        }
        return pending
    }

    override suspend fun clear() = blob.delete()
}

/** Registered client identity uses the same private encrypted/backup-excluded storage as tokens. */
class AniobKeystoreRegistrationStore(dir: File, cipher: AniobSecretCipher) : com.aniob.core.auth.AniobRegistrationStore {
    private val blob = AniobEncryptedBlobFile(File(dir, "registration.v1"), cipher)
    override suspend fun load(): String? = blob.read()?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    override suspend fun save(clientId: String) = blob.write(clientId.toByteArray(Charsets.UTF_8))
    override suspend fun clear() = blob.delete()
}
