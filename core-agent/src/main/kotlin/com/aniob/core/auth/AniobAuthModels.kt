package com.aniob.core.auth

import java.net.URI

/** Injectable clock so token-lifetime logic is deterministic in tests. */
fun interface AniobClock {
    fun now(): Long

    companion object {
        val SYSTEM: AniobClock = AniobClock { System.currentTimeMillis() }
    }
}

/**
 * The credential set for one signed-in account.
 *
 * Every secret field is hidden from [toString], so an accidental `"$tokens"` in a log line or an
 * exception message cannot leak it. Only `AniobAuthRepository` hands the access token to callers.
 */
data class AniobAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String? = null,
    val expiresAtEpochMs: Long,
    val accountId: String? = null,
    val planType: String? = null,
    /** For display only, and shown masked. Never a trust decision. */
    val email: String? = null,
    val clientId: String = "",
    val scopes: Set<String> = emptySet()
) {
    override fun toString(): String = "AniobAuthTokens(<redacted>)"
}

/** What the rest of the app may observe about the sign-in. Carries no secret. */
sealed interface AniobAuthState {
    data object SignedOut : AniobAuthState
    data class SigningIn(val startedAtMs: Long) : AniobAuthState
    data class SignedIn(
        val displayLabel: String,
        val expiresAtEpochMs: Long,
        val planType: String?
    ) : AniobAuthState

    /** Tokens were revoked or refused; the user has to sign in again. */
    data class Expired(val reason: AniobAuthError) : AniobAuthState
    data class Failed(val error: AniobAuthError) : AniobAuthState
}

/**
 * Typed sign-in failures. [messageKey] names a string resource the UI renders; a server body or
 * an exception message is never carried here, because either may contain a token or an address.
 */
sealed class AniobAuthError(val messageKey: String) {
    data object UserCancelled : AniobAuthError("account_error_cancelled")
    data object StateMismatch : AniobAuthError("account_error_state_mismatch")
    data object CallbackTimeout : AniobAuthError("account_error_timeout")
    data object CallbackPortBusy : AniobAuthError("account_error_port_busy")
    data object BrowserUnavailable : AniobAuthError("account_error_no_browser")
    data object PlanHasNoAccess : AniobAuthError("account_error_no_access")
    data object Unparseable : AniobAuthError("account_error_unreadable")
    data object InvalidIdentity : AniobAuthError("account_error_identity")
    data object ClientMismatch : AniobAuthError("account_error_client")
    data object NotConfigured : AniobAuthError("account_error_not_configured")

    data class CodeExchangeRejected(val httpStatus: Int, val oauthErrorCode: String?) :
        AniobAuthError("account_error_exchange_rejected")

    data class RefreshConfiguration(val httpStatus: Int) : AniobAuthError("account_error_refresh_config")

    data class AuthorizationRejected(val oauthErrorCode: String?) :
        AniobAuthError("account_error_authorization_rejected")

    /** invalid_grant, or a refresh token that was reused, expired or invalidated. */
    data class RefreshRejected(val oauthErrorCode: String?) :
        AniobAuthError("account_error_session_ended")

    /** The account or workspace chosen in the browser is outside the registration's scope. */
    data class WorkspaceOrAccountDenied(val code: String?) :
        AniobAuthError("account_error_workspace_denied")

    /** Only the exception type is kept for display; its message could hold an address. */
    class Network(val cause: Throwable?) : AniobAuthError("account_error_network") {
        override fun toString(): String = "Network(${cause?.javaClass?.simpleName ?: "unknown"})"
    }
}

/** Persistence port. The Android implementation encrypts with a Keystore key. */
interface AniobTokenStore {
    /** Null when nothing is stored, or when the stored blob cannot be decrypted. */
    suspend fun load(): AniobAuthTokens?

    @Throws(java.io.IOException::class)
    suspend fun save(tokens: AniobAuthTokens)

    suspend fun clear()
}

/**
 * Everything that varies between builds. Pure data: the app fills it from BuildConfig fields.
 */
data class AniobAuthConfig(
    val clientId: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val redirectUri: String,
    val scopes: String,
    val inferenceBaseUrl: String,
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    /** Sent as the `originator` request header. */
    val originator: String = "aniob",
    /** Value of the `OpenAI-Beta` header; empty means do not send it. */
    val betaHeader: String = "",
    /** The `client_id` is sensitive-ish and is never printed. */
    val maxSignInMs: Long = 10 * 60 * 1000L,
    val issuer: String = "https://auth.openai.com",
    val resource: String = "https://api.openai.com/v1",
    val requiredScope: String = "chatgpt.tokens.use.direct"
) {
    override fun toString(): String = "AniobAuthConfig(<redacted>)"

    val redirectPort: Int get() = parse(redirectUri)?.port?.takeIf { it > 0 } ?: 80
    val redirectPath: String get() = parse(redirectUri)?.path?.ifEmpty { "/" } ?: "/"

    /** Reasons this configuration cannot be used; empty when it is usable. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (clientId.isBlank() || clientId in setOf("UNSET", "NONE")) out += "client id is missing"
        if (!isHttps(authorizeUrl)) out += "authorize address must be https"
        if (!isHttps(tokenUrl)) out += "token address must be https"
        if (!isHttps(inferenceBaseUrl)) out += "model address must be https"
        if (!isHttps(issuer) || !isHttps(resource)) out += "issuer and resource must be https"
        if (requiredScope !in scopes.split(' ')) out += "required plan scope missing"
        if (scopes.isBlank()) out += "scopes are missing"
        if (!isLoopbackHttp(redirectUri)) out += "redirect must be an http loopback address"
        return out
    }

    val isUsable: Boolean get() = problems().isEmpty()

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1")

        private fun parse(value: String): URI? = try {
            URI(value.trim())
        } catch (e: java.net.URISyntaxException) {
            null
        }

        fun isHttps(value: String): Boolean {
            val uri = parse(value) ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && uri.rawQuery == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }

        fun isLoopbackHttp(value: String): Boolean {
            val uri = parse(value) ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("http", ignoreCase = true) && host in LOOPBACK_HOSTS &&
                uri.port in 1..65535 && uri.path == "/auth/callback" &&
                uri.userInfo == null && uri.rawQuery == null && uri.fragment == null
        }

        /** `a=b&c=d` (as written in a properties file) to an ordered map. Blank keys are dropped. */
        fun parseExtraParams(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            raw.split('&').forEach { pair ->
                val key = pair.substringBefore('=', "").trim()
                if (key.isNotEmpty()) out[key] = pair.substringAfter('=', "").trim()
            }
            return out
        }
    }
}

/** Registration is retained on logout, forgotten on provider deletion. No bearer credentials. */
interface AniobRegistrationStore {
    suspend fun load(): String?
    suspend fun save(clientId: String)
    suspend fun clear()
}

/** Used by isolated tests/nonpersistent hosts; Android supplies an encrypted implementation. */
class AniobMemoryRegistrationStore : AniobRegistrationStore {
    private var id: String? = null
    override suspend fun load() = id
    override suspend fun save(clientId: String) { id = clientId }
    override suspend fun clear() { id = null }
}
