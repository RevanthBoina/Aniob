package com.aniob.app.auth

import android.app.Application
import android.content.Context
import com.aniob.app.BuildConfig
import com.aniob.core.auth.AniobAccountModelCatalog
import com.aniob.core.auth.AniobAuthConfig
import com.aniob.core.auth.AniobAuthRepository
import com.aniob.core.auth.AniobAuthState
import com.aniob.core.auth.AniobClock
import com.aniob.core.auth.AniobCloudSource
import com.aniob.core.auth.AniobTokenClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Builds and owns the account sign-in objects for the process. Everything is lazy, so an app
 * build with no client id configured pays nothing and shows no UI ([featureAvailable] is false).
 */
class AniobAccountContainer(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "account_auth")

    /** Null (feature hidden) unless every required BuildConfig value is present and well formed. */
    val config: AniobAuthConfig? by lazy {
        val configuredClientId = unset(BuildConfig.ACCOUNT_AUTH_CLIENT_ID)
        val isDynamic = configuredClientId.isBlank()
        val clientId = if (isDynamic) "dynamic_agent_client" else configuredClientId

        val extraParams = AniobAuthConfig.parseExtraParams(BuildConfig.ACCOUNT_AUTH_EXTRA_PARAMS).toMutableMap()
        if (isDynamic || clientId == "dynamic_agent_client") {
            extraParams["ext_agent_host_id"] = getOrCreateExtAgentHostId()
        }

        val c = AniobAuthConfig(
            clientId = clientId,
            authorizeUrl = unset(BuildConfig.ACCOUNT_AUTH_AUTHORIZE_URL).ifBlank { "https://auth.openai.com/api/accounts/authorize" },
            tokenUrl = unset(BuildConfig.ACCOUNT_AUTH_TOKEN_URL).ifBlank { "https://auth.openai.com/api/accounts/oauth/token" },
            redirectUri = unset(BuildConfig.ACCOUNT_AUTH_REDIRECT_URI).ifBlank { "http://127.0.0.1:1455/auth/callback" },
            scopes = unset(BuildConfig.ACCOUNT_AUTH_SCOPES).ifBlank { "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct" },
            inferenceBaseUrl = unset(BuildConfig.ACCOUNT_AUTH_INFERENCE_URL).ifBlank { "https://api.openai.com/v1" },
            extraAuthorizeParams = extraParams,
            originator = unset(BuildConfig.ACCOUNT_AUTH_ORIGINATOR).ifBlank { "aniob" },
            betaHeader = unset(BuildConfig.ACCOUNT_AUTH_BETA_HEADER)
        )
        c.takeIf { it.isUsable }
    }

    val featureAvailable: Boolean get() = config != null

    private val cipher by lazy { AniobKeystoreCipher("aniob_account_auth_v1") }
    val tokenStore by lazy { AniobKeystoreTokenStore(dir, cipher) }
    val pendingStore by lazy { AniobKeystorePendingStore(dir, cipher) }

    /** The only owner of tokens. Null when the feature is not configured. */
    val repository: AniobAuthRepository? by lazy {
        config?.let { c ->
            AniobAuthRepository(tokenStore, AniobTokenClient(c, AniobOkHttpTokenTransport(), AniobClock.SYSTEM))
                .also { repo -> scope.launch { repo.initialize() } }
        }
    }

    val coordinator: AniobSignInCoordinator? by lazy {
        val c = config
        val r = repository
        if (c != null && r != null) AniobSignInCoordinator(app, c, r, pendingStore, scope) else null
    }

    val catalog: AniobAccountModelCatalog by lazy {
        val text = try {
            app.assets.open("aniob_config/account_models.yaml").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ""
        }
        AniobAccountModelCatalog.parse(text)
    }

    val state: AniobAuthState get() = repository?.state?.value ?: AniobAuthState.SignedOut
    val isSignedIn: Boolean get() = state is AniobAuthState.SignedIn

    var cloudSource: AniobCloudSource
        get() = AniobCloudSource.fromPref(prefs.getString(KEY_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_SOURCE, value.prefValue).apply()

    var model: String
        get() = catalog.resolve(prefs.getString(KEY_MODEL, null))
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** Blank means "let the service decide". */
    var reasoningEffort: String
        get() = prefs.getString(KEY_EFFORT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EFFORT, value).apply()

    fun newClient(sessionId: String? = null, onLlmRequest: () -> Unit = {}): AniobAccountModelClient {
        val c = requireNotNull(config) { "account sign-in is not configured" }
        val r = requireNotNull(repository)
        return AniobAccountModelClient(c, r, model, reasoningEffort.ifBlank { null }, sessionId, onLlmRequest)
    }

    /** Sign out everywhere on this device: tokens, any pending sign-in, and the Keystore key. */
    suspend fun signOutEverywhere() {
        coordinator?.cancel()
        repository?.signOut()
        pendingStore.clear()
        tokenStore.clear()
        // If the key cannot be removed, no blob is left that it could decrypt, so this is informational.
        val keyRemoved = try {
            cipher.deleteKey()
            true
        } catch (e: java.security.KeyStoreException) {
            false
        }
        (app as? com.aniob.app.AniobApplication)?.eventLogger?.info("AniobAccount", "signed out everywhere; keystore key removed=$keyRemoved")
    }

    /** Delete this provider: sign out, forget the model choices, and return the cloud to the gateway. */
    suspend fun deleteProvider() {
        signOutEverywhere()
        prefs.edit().remove(KEY_MODEL).remove(KEY_EFFORT).putString(KEY_SOURCE, AniobCloudSource.OMNIROUTE.prefValue).apply()
    }

    private fun getOrCreateExtAgentHostId(): String {
        val existing = prefs.getString(KEY_EXT_AGENT_HOST_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = "urn:uuid:${java.util.UUID.randomUUID()}"
        prefs.edit().putString(KEY_EXT_AGENT_HOST_ID, newId).apply()
        return newId
    }

    private companion object {
        /** `.env` values cannot be empty (see .env.example), so "no value" is spelled UNSET or NONE. */
        fun unset(value: String): String = if (value.isBlank() || value == "UNSET" || value == "NONE") "" else value

        const val KEY_SOURCE = "cloud_source"
        const val KEY_MODEL = "account_model"
        const val KEY_EFFORT = "account_effort"
        const val KEY_EXT_AGENT_HOST_ID = "account_ext_agent_host_id"
    }
}
