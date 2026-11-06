package com.aniob.app.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aniob.app.AniobApplication
import com.aniob.core.auth.AniobAccountModelCatalog
import com.aniob.core.auth.AniobAccountScreenState
import com.aniob.core.auth.AniobAuthState
import com.aniob.core.auth.AniobCloudSource
import com.aniob.core.external.AniobGatewayClassifier
import com.aniob.core.external.GatewayFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AniobAccountTestResult { OK, AUTH, SERVICE, BUSY, OFFLINE, REJECTED }

data class AniobAccountSettingsUi(
    val useAsCloud: Boolean,
    val model: String,
    val effort: String,
    val modelError: Boolean = false,
    val testing: Boolean = false,
    val testResult: AniobAccountTestResult? = null
)

/**
 * Presentation logic for the account card. The sign-in itself lives in `AniobSignInCoordinator`;
 * opening this screen makes no model call. Only [sendTest] does, and only when the user taps it.
 */
class AniobAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as AniobApplication).accountAuth

    val available: Boolean = container.featureAvailable
    val catalog: AniobAccountModelCatalog get() = container.catalog

    val screenState: StateFlow<AniobAccountScreenState> =
        (container.repository?.state ?: MutableStateFlow<AniobAuthState>(AniobAuthState.SignedOut))
            .map(AniobAccountScreenState::from)
            .stateIn(viewModelScope, SharingStarted.Eagerly, AniobAccountScreenState.Intro)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AniobAccountSettingsUi> = _settings.asStateFlow()

    init {
        // A sign-in the system interrupted continues here; nothing is bound otherwise.
        container.coordinator?.resumeIfPending()
    }

    private fun read() = AniobAccountSettingsUi(
        useAsCloud = container.cloudSource == AniobCloudSource.ACCOUNT,
        model = container.model,
        effort = container.reasoningEffort
    )

    fun startSignIn() { container.coordinator?.start() }
    fun cancelSignIn() { container.coordinator?.cancel() }

    fun signOut() { viewModelScope.launch { container.repository?.signOut(); refresh() } }
    fun signOutEverywhere() { viewModelScope.launch { container.signOutEverywhere(); refresh() } }
    fun deleteProvider() { viewModelScope.launch { container.deleteProvider(); refresh() } }

    private fun refresh() { _settings.value = read() }

    fun setUseAsCloud(enabled: Boolean) {
        container.cloudSource = if (enabled) AniobCloudSource.ACCOUNT else AniobCloudSource.OMNIROUTE
        refresh()
    }

    fun selectModel(id: String) {
        val error = AniobAccountModelCatalog.validate(id) != null
        if (!error) container.model = id
        _settings.update { it.copy(model = if (error) it.model else container.model, modelError = error, testResult = null) }
    }

    fun setEffort(value: String) {
        container.reasoningEffort = value
        _settings.update { it.copy(effort = value, testResult = null) }
    }

    /** One real, tiny inference call. A model list alone would not prove inference works. */
    fun sendTest() {
        if (_settings.value.testing || !container.isSignedIn) return
        _settings.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val result = try {
                container.newClient()
                    .getNextActionRaw("You are a connection test. Reply briefly.", "Reply with the single word: ok")
                    .fold(
                        onSuccess = { AniobAccountTestResult.OK },
                        onFailure = { map(it) }
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                AniobAccountTestResult.AUTH
            }
            _settings.update { it.copy(testing = false, testResult = result) }
        }
    }

    private fun map(error: Throwable): AniobAccountTestResult = when (AniobGatewayClassifier.kindOf(error)) {
        GatewayFailureKind.AUTH -> AniobAccountTestResult.AUTH
        GatewayFailureKind.NOT_JSON, GatewayFailureKind.NOT_FOUND, GatewayFailureKind.MALFORMED -> AniobAccountTestResult.SERVICE
        GatewayFailureKind.SERVER_ERROR, GatewayFailureKind.RATE_LIMIT -> AniobAccountTestResult.BUSY
        GatewayFailureKind.CLIENT_ERROR -> AniobAccountTestResult.REJECTED
        GatewayFailureKind.UNREACHABLE -> AniobAccountTestResult.OFFLINE
    }
}
