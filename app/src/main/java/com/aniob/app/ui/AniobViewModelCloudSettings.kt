package com.aniob.app.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.provider.OmniRouteConnectionStatus
import com.aniob.app.provider.testOmniRouteConnection
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun AniobViewModel.setAutoRouterMode(mode: String) {
    val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("autorouter_mode", mode).apply()
    _uiState.update { it.copy(autoRouterMode = mode) }
}

/**
 * Persists the Cloud AI (OmniRoute) settings entered in Settings.
 *
 * The key goes to the Keystore-backed secret store; the gateway address and model are not
 * secrets and stay in the ordinary prefs file. Both a key and a valid address are required
 * before cloud routing is allowed, because `localhost` on a phone is the phone itself. Clearing
 * both fields is a real "turn Cloud AI off" action, not a validation failure.
 */
fun AniobViewModel.saveCloudSettings(apiKey: String, model: String, baseUrl: String) {
    // A new address or key deserves a fresh chance: forget which gateways were marked down.
    com.aniob.core.external.AniobGatewayHealth.shared.reset()
    val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
    val key = apiKey.trim()

    if (key.isEmpty() && address.isEmpty()) {
        secretStore.setOmniRouteApiKey("")
        secretStore.setOmniRouteBaseUrl("")
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).edit()
            .putString("omniroute_model", AniobProviderConfig.OMNIROUTE_MODEL).apply()
        _uiState.update {
            it.copy(
                omnirouteApiKey = "",
                omnirouteBaseUrl = "",
                omnirouteModel = AniobProviderConfig.OMNIROUTE_MODEL,
                omnirouteConnectionStatus = "Cloud API key cleared",
                cloudTest = CloudTestUiState(
                    result = CloudTestResult.SAVED,
                    message = "Cloud AI is off. Add a key whenever you want it back."
                )
            )
        }
        app.systemState.refresh()
        return
    }

    val validationError = when {
        address.isEmpty() -> "Add the address of the computer running OmniRoute."
        // A self-hosted gateway on this device or the local network usually has no key.
        key.isEmpty() && !AniobProviderConfig.isKeylessGatewayAllowed(address) ->
            "Add the key from your OmniRoute dashboard."
        else -> AniobProviderConfig.validateBaseUrl(address)
            ?: AniobProviderConfig.validateModel(model)
    }
    if (validationError != null) {
        _uiState.update {
            it.copy(
                cloudTest = CloudTestUiState(
                    result = CloudTestResult.INVALID_SETUP,
                    message = validationError
                )
            )
        }
        return
    }
    if (!secretStore.isAvailable) {
        _uiState.update {
            it.copy(
                cloudTest = CloudTestUiState(
                    result = CloudTestResult.INVALID_SETUP,
                    message = "This device cannot store the key securely, so Cloud AI stays off."
                )
            )
        }
        return
    }

    val cleanedModel = AniobProviderConfig.sanitizeModel(model)
    secretStore.setOmniRouteApiKey(key)
    secretStore.setOmniRouteBaseUrl(address)
    app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).edit()
        .putString("omniroute_model", cleanedModel).apply()
    _uiState.update {
        it.copy(
            omnirouteApiKey = key,
            omnirouteBaseUrl = address,
            omnirouteModel = cleanedModel,
            omnirouteConnectionStatus = "Saved ✓ · Model: $cleanedModel",
            cloudTest = CloudTestUiState(
                result = CloudTestResult.SAVED,
                message = "Saved. Run Test connection to confirm the gateway answers."
            )
        )
    }
    app.systemState.refresh()
}

/**
 * Probes the entered gateway/key without saving them, so a typo never replaces a working setup.
 * Runs off the main thread via [testOmniRouteConnection] and publishes the outcome to the
 * Settings banner.
 */
fun AniobViewModel.testCloudConnection(apiKey: String, baseUrl: String) {
    if (_uiState.value.cloudTest.testing) return
    com.aniob.core.external.AniobGatewayHealth.shared.reset()
    val key = apiKey.trim()
    val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
    val validationError = when {
        address.isEmpty() -> "Add the address of the computer running OmniRoute."
        key.isEmpty() && !AniobProviderConfig.isKeylessGatewayAllowed(address) ->
            "Add the key from your OmniRoute dashboard."
        else -> AniobProviderConfig.validateBaseUrl(address)
    }
    if (validationError != null) {
        _uiState.update {
            it.copy(
                cloudTest = CloudTestUiState(
                    result = CloudTestResult.INVALID_SETUP,
                    message = validationError
                )
            )
        }
        return
    }

    viewModelScope.launch {
        _uiState.update { it.copy(cloudTest = CloudTestUiState(testing = true)) }
        val status = testOmniRouteConnection(key, address)
        val (result, message) = when (status) {
            OmniRouteConnectionStatus.SUCCESS ->
                CloudTestResult.SUCCESS to "The gateway answered for this key. Save to keep it."
            OmniRouteConnectionStatus.BAD_KEY ->
                CloudTestResult.BAD_KEY to "The gateway rejected this key. Copy it again from the OmniRoute dashboard."
            OmniRouteConnectionStatus.UNREACHABLE ->
                CloudTestResult.UNREACHABLE to "No usable answer from that address. Check Wi-Fi and the address, confirm OmniRoute is running, and that it has at least one provider connected."
        }
        _uiState.update {
            it.copy(cloudTest = CloudTestUiState(result = result, message = message))
        }
    }
}

/** Clears stale feedback as soon as the user edits a field. */
fun AniobViewModel.clearCloudTestResult() {
    if (_uiState.value.cloudTest != CloudTestUiState()) {
        _uiState.update { it.copy(cloudTest = CloudTestUiState()) }
    }
}

/** Backwards-compatible entry point that keeps the previously saved gateway address. */
@JvmOverloads
fun AniobViewModel.updateSettings(apiKey: String, model: String, baseUrl: String = _uiState.value.omnirouteBaseUrl) {
    saveCloudSettings(apiKey, model, baseUrl)
}

/** Sets the OmniRoute gateway base URL. Not a secret, so it is persisted in plaintext prefs. */
fun AniobViewModel.setOmniRouteBaseUrl(baseUrl: String) {
    com.aniob.core.external.AniobGatewayHealth.shared.reset()
    val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
    secretStore.setOmniRouteBaseUrl(address)
    _uiState.update {
        it.copy(omnirouteBaseUrl = address, cloudTest = CloudTestUiState())
    }
}

/**
 * Saves the provided OmniRoute settings and performs a live diagnostic test against the gateway.
 */
fun AniobViewModel.testOmniRouteSettings(
    apiKey: String = _uiState.value.omnirouteApiKey,
    baseUrl: String = _uiState.value.omnirouteBaseUrl,
    model: String = _uiState.value.omnirouteModel
) {
    updateSettings(apiKey = apiKey, model = model, baseUrl = baseUrl)
    viewModelScope.launch {
        _uiState.update {
            it.copy(
                omnirouteTesting = true,
                omnirouteConnectionStatus = "Testing connection..."
            )
        }
        val diag = com.aniob.app.provider.verifyOmniRouteGateway(
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim(),
            model = com.aniob.app.provider.AniobProviderConfig.coerceModel(model)
        )
        _uiState.update {
            it.copy(
                omnirouteTesting = false,
                omnirouteConnectionStatus = diag.message,
                omnirouteDiscoveredModels = if (diag.availableModels.isNotEmpty()) diag.availableModels else it.omnirouteDiscoveredModels
            )
        }
    }
}

/**
 * Fetches the live `/v1/models` list from the configured OmniRoute gateway.
 */
fun AniobViewModel.refreshOmniRouteModels(
    apiKey: String = _uiState.value.omnirouteApiKey,
    baseUrl: String = _uiState.value.omnirouteBaseUrl
) {
    viewModelScope.launch {
        _uiState.update { it.copy(omnirouteConnectionStatus = "Loading models from gateway...") }
        val models = com.aniob.app.provider.fetchOmniRouteModels(apiKey.trim(), baseUrl.trim())
        _uiState.update {
            it.copy(
                omnirouteDiscoveredModels = models,
                omnirouteConnectionStatus = if (models.isNotEmpty()) {
                    "Loaded ${models.size} models from gateway ✓"
                } else {
                    "Could not load model list — check gateway address or key"
                }
            )
        }
    }
}
