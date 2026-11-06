package com.aniob.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** The loopback port is taken. Not retried on another port: the registered redirect is fixed. */
class AniobPortInUseException(cause: Throwable? = null) : IOException("callback port is in use", cause)

/**
 * Receives the browser's redirect. The Android implementation binds `127.0.0.1` only.
 */
interface AniobCallbackListener : Closeable {
    /** @throws AniobPortInUseException when the port is taken. */
    @Throws(IOException::class)
    fun bind()

    /**
     * Waits for the callback request whose path is [callbackPath] matching [expectedState] (if supplied),
     * answers it with a static page, closes, and returns the request target (`/path?query`). Null on timeout.
     */
    suspend fun awaitCallback(callbackPath: String, timeoutMs: Long, expectedState: String? = null): String?
}

fun interface AniobBrowserLauncher {
    /** Opens [url] in an external browser tab (never an embedded web view). False if impossible. */
    fun open(url: String): Boolean
}

/** Short-lived record that lets a sign-in survive the app being recreated mid-way. */
class AniobPendingSignIn(val verifier: String, val state: String, val startedAtMs: Long) {
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
            startedAtMs = clock.now()
        )
        val remaining = config.maxSignInMs - (clock.now() - pending.startedAtMs)
        if (remaining <= 0L) {
            pendingStore.clear()
            return fail(AniobAuthError.CallbackTimeout)
        }

        val listener = listenerFactory()
        try {
            repository.markSigningIn()
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
            if (resume == null) {
                val challenge = AniobPkce.challengeFor(pending.verifier)
                val url = AniobAuthorizeUrlBuilder.build(config, challenge, pending.state)
                if (!browser.open(url)) return fail(AniobAuthError.BrowserUnavailable)
            }
            while (true) {
                val remainingTime = config.maxSignInMs - (clock.now() - pending.startedAtMs)
                if (remainingTime <= 0L) {
                    pendingStore.clear()
                    return fail(AniobAuthError.CallbackTimeout)
                }
                val target = listener.awaitCallback(config.redirectPath, remainingTime, pending.state)
                    ?: return fail(AniobAuthError.CallbackTimeout)

                when (val parsed = AniobAuthCallbackParser.parse(target, config.redirectPath, pending.state)) {
                    is AniobCallbackResult.Code -> return repository.completeSignIn(parsed.code, pending.verifier)
                    is AniobCallbackResult.Failure -> {
                        if (parsed.error is AniobAuthError.StateMismatch) {
                            continue
                        }
                        return fail(parsed.error)
                    }
                    AniobCallbackResult.Ignored -> continue
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.markFailed(AniobAuthError.UserCancelled) }
            throw e
        } finally {
            withContext(NonCancellable) {
                pendingStore.clear()
                listener.close()
            }
        }
    }

    private fun fail(error: AniobAuthError): AniobAuthError {
        repository.markFailed(error)
        return error
    }
}
