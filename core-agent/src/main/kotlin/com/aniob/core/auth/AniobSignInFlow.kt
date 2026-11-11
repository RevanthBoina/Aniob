package com.aniob.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** No loopback callback port could be bound. */
class AniobPortInUseException(cause: Throwable? = null) : IOException("callback port is in use", cause)

/**
 * Receives the browser's redirect. The Android implementation binds `127.0.0.1` only.
 */
interface AniobCallbackListener : Closeable {
    val boundPort: Int get() = -1
    /** @throws AniobPortInUseException when the port is taken. */
    @Throws(IOException::class)
    fun bind()

    /** Configure acceptance before opening the browser. Implementations must never reflect state. */
    fun configureAttempt(state: String) = Unit
    fun complete(success: Boolean) = Unit

    /**
     * Waits for an acceptable callback target (`/path?query`). Unrelated/wrong-state traffic must
     * not consume an attempt. Hold the accepted browser response until [complete]. Null on timeout.
     */
    suspend fun awaitCallback(callbackPath: String, timeoutMs: Long): String?
}

fun interface AniobBrowserLauncher {
    /** Opens [url] in an external browser tab (never an embedded web view). False if impossible. */
    fun open(url: String): Boolean
}

/** Short-lived record that lets a sign-in survive the app being recreated mid-way. */
class AniobPendingSignIn(
    val verifier: String, val state: String, val startedAtMs: Long,
    val nonce: String = AniobPkce.newState(), val clientId: String? = null
) {
    override fun toString(): String = "AniobPendingSignIn(<redacted>)"
}

interface AniobPendingSignInStore {
    @Throws(IOException::class)
    suspend fun save(pending: AniobPendingSignIn)
    suspend fun load(): AniobPendingSignIn?
    suspend fun clear()
}

/**
 * One sign-in attempt, start to finish. Pure orchestration over the ports above, so a test can
 * drive it with a fake browser (an HTTP GET) and a mocked token endpoint.
 *
 * The pending record is cleared on success, failure, cancel and timeout.
 */
class AniobSignInFlow(
    private val config: AniobAuthConfig,
    private val repository: AniobAuthRepository,
    private val listenerFactory: () -> AniobCallbackListener,
    private val browser: AniobBrowserLauncher,
    private val pendingStore: AniobPendingSignInStore,
    private val clock: AniobClock = AniobClock.SYSTEM
) {
    /** Returns null on success, otherwise the typed failure (already reflected in the state flow). */
    suspend fun run(resume: AniobPendingSignIn? = null): AniobAuthError? {
        if (!config.isUsable) return fail(AniobAuthError.NotConfigured)

        val pending = resume ?: AniobPendingSignIn(
            verifier = AniobPkce.generate().verifier,
            state = AniobPkce.newState(),
            startedAtMs = clock.now(),
            clientId = repository.clientIdForSignIn()
        )
        val remaining = config.maxSignInMs - (clock.now() - pending.startedAtMs)
        if (remaining <= 0L) {
            pendingStore.clear()
            return fail(AniobAuthError.CallbackTimeout)
        }

        val listener = listenerFactory()
        val version = repository.markSigningIn()
        var successful = false
        try {
            listener.configureAttempt(pending.state)
            try {
                pendingStore.save(pending)
            } catch (e: IOException) {
                return fail(AniobAuthError.Network(e))
            }
            try {
                listener.bind()
            } catch (e: AniobPortInUseException) {
                return fail(AniobAuthError.CallbackPortBusy)
            } catch (e: IOException) {
                return fail(AniobAuthError.CallbackPortBusy)
            }
            val redirectUri = if (listener.boundPort > 0) {
                val uri = java.net.URI(config.redirectUri)
                java.net.URI(uri.scheme, uri.userInfo, uri.host, listener.boundPort, uri.path, uri.query, uri.fragment).toString()
            } else config.redirectUri
            if (resume == null) {
                val challenge = AniobPkce.challengeFor(pending.verifier)
                val url = AniobAuthorizeUrlBuilder.build(config.copy(clientId = pending.clientId ?: config.clientId, redirectUri = redirectUri), challenge, pending.state, pending.nonce)
                if (!browser.open(url)) return fail(AniobAuthError.BrowserUnavailable)
            }
            val deadline = clock.now() + remaining
            var parsed: AniobCallbackResult
            do {
                val target = listener.awaitCallback(config.redirectPath, (deadline - clock.now()).coerceAtLeast(0L))
                    ?: return fail(AniobAuthError.CallbackTimeout)
                parsed = AniobAuthCallbackParser.parse(target, config.redirectPath, pending.state)
            } while (parsed == AniobCallbackResult.Ignored ||
                (parsed is AniobCallbackResult.Failure && parsed.error == AniobAuthError.StateMismatch))

            return when (parsed) {
                is AniobCallbackResult.Code -> repository.completeSignIn(parsed.code, pending.verifier, pending.nonce, parsed.clientId,
                    pending.clientId ?: config.clientId, version, redirectUri).also { successful = it == null }
                is AniobCallbackResult.Failure -> fail(parsed.error)
                AniobCallbackResult.Ignored -> fail(AniobAuthError.Unparseable)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.markFailed(AniobAuthError.UserCancelled, version) }
            throw e
        } finally {
            withContext(NonCancellable) {
                try { listener.complete(successful) } finally {
                    listener.close()
                    pendingStore.clear()
                }
            }
        }
    }

    private suspend fun fail(error: AniobAuthError): AniobAuthError {
        repository.markFailed(error)
        return error
    }
}
