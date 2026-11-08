package com.aniob.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** What a caller needs for one request. The only place a bearer value leaves the repository. */
class AniobAccessGrant(val accessToken: String, val accountId: String?) {
    override fun toString(): String = "AniobAccessGrant(<redacted>)"
}

sealed interface AniobAccessResult {
    class Granted(val grant: AniobAccessGrant) : AniobAccessResult {
        override fun toString(): String = "Granted(<redacted>)"
    }

    /** No usable session. [error] says why; the state flow already reflects it. */
    data class Unavailable(val error: AniobAuthError) : AniobAccessResult
}

/**
 * The **only** owner of tokens and sign-in state. Nothing else reads the store.
 *
 * Refresh policy ([obtainAccess]):
 *  - refresh when fewer than [refreshLeadMs] remain, or once after a 401 for the token that was
 *    rejected (the caller then retries its request exactly once);
 *  - concurrent callers are serialised by one [Mutex]; the second one finds the fresh token and
 *    makes no network call, so a burst of requests causes exactly one refresh;
 *  - the rotated refresh token is persisted **before** the new access token is returned;
 *  - a revoked / reused / expired refresh token clears everything and moves to
 *    [AniobAuthState.Expired]; it is never retried, so there is no loop;
 *  - a transient failure keeps the tokens and backs off for [backoffMs].
 */
class AniobAuthRepository(
    private val store: AniobTokenStore,
    private val tokenClient: AniobTokenClient,
    private val clock: AniobClock = AniobClock.SYSTEM,
    private val refreshLeadMs: Long = 5 * 60 * 1000L,
    private val backoffMs: Long = 30_000L,
    private val registrationStore: AniobRegistrationStore = AniobMemoryRegistrationStore()
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AniobAuthState>(AniobAuthState.SignedOut)
    val state: StateFlow<AniobAuthState> = _state.asStateFlow()

    private var cached: AniobAuthTokens? = null
    private var backoffUntil = 0L

    private var initialized = false
    private var sessionVersion = 0L

    suspend fun initialize() = mutex.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        if (initialized) return
        val stored = store.load()
        cached = stored?.takeIf { it.clientId.isNotBlank() && tokenClient.config.requiredScope in it.scopes }
        if (stored != null && cached == null) store.clear()
        _state.value = cached?.let(::signedIn) ?: AniobAuthState.SignedOut
        initialized = true
    }

    suspend fun clientIdForSignIn(): String = mutex.withLock {
        initializeLocked()
        registrationStore.load() ?: cached?.clientId ?: tokenClient.config.clientId
    }

    suspend fun markSigningIn(): Long = mutex.withLock {
        initializeLocked()
        sessionVersion += 1
        _state.value = AniobAuthState.SigningIn(clock.now())
        sessionVersion
    }

    suspend fun markFailed(error: AniobAuthError, expectedVersion: Long? = null) = mutex.withLock {
        if (expectedVersion == null || expectedVersion == sessionVersion) markFailedLocked(error)
    }

    private fun markFailedLocked(error: AniobAuthError) {
        _state.value = if (error is AniobAuthError.UserCancelled) cached?.let(::signedIn) ?: AniobAuthState.SignedOut
            else AniobAuthState.Failed(error)
    }

    /** Exchanges the authorization [code] and stores the result. Returns the failure, if any. */
    suspend fun completeSignIn(
        code: String, codeVerifier: String, nonce: String,
        issuedClientId: String? = null, requestedClientId: String = tokenClient.config.clientId,
        expectedVersion: Long? = null,
        redirectUri: String? = null
    ): AniobAuthError? = mutex.withLock {
        initializeLocked()
        if (expectedVersion != null && expectedVersion != sessionVersion) return@withLock AniobAuthError.UserCancelled
        val registered = registrationStore.load() ?: cached?.clientId
        val expected = registered ?: requestedClientId.takeUnless { it == "dynamic_agent_client" }
        val clientId = issuedClientId ?: expected
        if (clientId.isNullOrBlank() || clientId == "dynamic_agent_client" || (expected != null && clientId != expected)) {
            markFailedLocked(AniobAuthError.ClientMismatch)
            return@withLock AniobAuthError.ClientMismatch
        }
        try { registrationStore.save(clientId) } catch (e: IOException) {
            val error = AniobAuthError.Network(e)
            markFailedLocked(error)
            return@withLock error
        }
        when (val result = tokenClient.exchangeCode(code, codeVerifier, clientId, nonce, redirectUri ?: tokenClient.config.redirectUri)) {
            is AniobTokenResult.Failure -> {
                markFailedLocked(result.error)
                result.error
            }
            is AniobTokenResult.Success -> {
                try {
                    store.save(result.tokens)
                } catch (e: IOException) {
                    val error = AniobAuthError.Network(e)
                    markFailedLocked(error)
                    return@withLock error
                }
                cached = result.tokens
                backoffUntil = 0L
                _state.value = signedIn(result.tokens)
                null
            }
        }
    }

    /**
     * A usable access token, refreshing first when needed. [rejectedAccessToken] is the token a
     * request just got a 401 for; when it is still the stored one a refresh is forced, when it
     * has already been replaced by another caller the new token is returned without a call.
     */
    suspend fun obtainAccess(rejectedAccessToken: String? = null): AniobAccessResult = mutex.withLock {
        initializeLocked()
        val current = cached ?: return@withLock AniobAccessResult.Unavailable(
                (_state.value as? AniobAuthState.Expired)?.reason ?: AniobAuthError.RefreshRejected(null)
            )
        val now = clock.now()
        val rejected = rejectedAccessToken != null && rejectedAccessToken == current.accessToken
        val nearExpiry = current.expiresAtEpochMs - now < refreshLeadMs
        if (!rejected && !nearExpiry) return@withLock granted(current)

        if (now < backoffUntil) {
            // Recently failed transiently: use what we have while it is still valid.
            return@withLock if (!rejected && current.expiresAtEpochMs > now) granted(current)
            else AniobAccessResult.Unavailable(AniobAuthError.Network(IOException("refresh backing off")))
        }

        when (val result = tokenClient.refresh(current)) {
            is AniobTokenResult.Success -> {
                try {
                    store.save(result.tokens) // persist the rotated refresh token first
                } catch (e: IOException) {
                    return@withLock AniobAccessResult.Unavailable(AniobAuthError.Network(e))
                }
                cached = result.tokens
                backoffUntil = 0L
                _state.value = signedIn(result.tokens)
                granted(result.tokens)
            }
            is AniobTokenResult.Failure -> when (val error = result.error) {
                is AniobAuthError.RefreshRejected, AniobAuthError.PlanHasNoAccess -> {
                    store.clear()
                    cached = null
                    _state.value = AniobAuthState.Expired(error)
                    AniobAccessResult.Unavailable(error)
                }
                else -> {
                    backoffUntil = now + backoffMs
                    if (!rejected && current.expiresAtEpochMs > now) granted(current)
                    else AniobAccessResult.Unavailable(error)
                }
            }
        }
    }

    /** Local cleanup is guaranteed even when remote revocation fails. Returns revocation status. */
    suspend fun signOut(forgetRegistration: Boolean = false): Boolean = mutex.withLock {
        initializeLocked()
        sessionVersion += 1
        val previous = cached
        // Clear locally before attempting the bounded network operation.
        store.clear()
        cached = null
        backoffUntil = 0L
        _state.value = AniobAuthState.SignedOut
        if (forgetRegistration) registrationStore.clear()
        previous == null || tokenClient.revoke(previous)
    }

    private fun granted(tokens: AniobAuthTokens) =
        AniobAccessResult.Granted(AniobAccessGrant(tokens.accessToken, tokens.accountId))

    private fun signedIn(tokens: AniobAuthTokens) = AniobAuthState.SignedIn(
        displayLabel = AniobAccountLabel.maskEmail(tokens.email) ?: "ChatGPT account",
        expiresAtEpochMs = tokens.expiresAtEpochMs,
        planType = tokens.planType
    )
}
