package com.aniob.app.ui

import com.aniob.core.domain.*
import android.content.Context
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.provider.AniobOmniRouteProvider
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.ThinkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aniob.app.ui.AniobViewModel.Companion.KEY_NAV_BACKSTACK

internal fun AniobViewModel.restoreNavBackStack(): List<AniobRoute> {
    val saved = savedStateHandle.get<String>(KEY_NAV_BACKSTACK) ?: return listOf(AniobRoute.CHAT)
    val routes = saved.split(",").mapNotNull { name ->
        runCatching { AniobRoute.valueOf(name) }.getOrNull()
    }
    return routes.ifEmpty { listOf(AniobRoute.CHAT) }
}

internal fun AniobViewModel.persistNavBackStack(stack: List<AniobRoute>) {
    savedStateHandle[KEY_NAV_BACKSTACK] = stack.joinToString(",") { it.name }
}

fun AniobViewModel.navigateTo(route: AniobRoute) {
    val stack = _navBackStack.value
    if (stack.lastOrNull() == route) return
    val updated = stack + route
    _navBackStack.value = updated
    persistNavBackStack(updated)
}

/** Pops one screen. Returns false when at the root (caller lets the system handle Back). */
fun AniobViewModel.navigateBack(): Boolean {
    val stack = _navBackStack.value
    if (stack.size <= 1) return false
    val updated = stack.dropLast(1)
    _navBackStack.value = updated
    persistNavBackStack(updated)
    return true
}

fun AniobViewModel.resetToChat() {
    _navBackStack.value = listOf(AniobRoute.CHAT)
    persistNavBackStack(listOf(AniobRoute.CHAT))
}

/**
 * Deep-link entry point (UX-0 §2): `aniob://chat|tracker|confirm|result|skills`.
 * Returns true when the link was recognised and handled.
 */
fun AniobViewModel.handleDeepLink(uri: String?): Boolean {
    if (uri.isNullOrBlank() || !uri.startsWith("aniob://")) return false
    val host = uri.removePrefix("aniob://").substringBefore('/').substringBefore('?').lowercase()
    return when (host) {
        "chat" -> { resetToChat(); true }
        "tracker" -> { resetToChat(); setShowTrackerSheet(true); true }
        "result" -> { resetToChat(); true }
        "skills" -> { navigateTo(AniobRoute.SKILLS); true }
        "confirm" -> true
        else -> false
    }
}

/** Exposes the loaded skill list for the Skills screen. */
fun AniobViewModel.getLoadedSkills(): List<com.aniob.core.skills.AniobSkill> =
    semanticSkillMatcher.getLoadedSkills()

/** Explicit background preparation before routing; re-resolves downloads and model changes. */
internal suspend fun AniobViewModel.refreshLocalEngine() = kotlinx.coroutines.withContext(Dispatchers.IO) {
    localLlmProvider.prepare(modelDownloader.resolveInstalledModelPath())
}

fun AniobViewModel.getInstalledModelName(): String? {
    val id = modelDownloader.getDefaultModelId() ?: return null
    return AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id
}

/** Human-readable active brain summary for the top-bar chip. */
fun AniobViewModel.getActiveBrainLabel(): String {
    val installed = getInstalledModelName()
    val useAccount = cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT
    val hasCloud = if (useAccount) app.accountAuth.isSignedIn else _uiState.value.omnirouteApiKey.isNotBlank()
    val cloudModel = if (useAccount) app.accountAuth.model else _uiState.value.omnirouteModel.ifBlank { "auto" }
    val mode = _uiState.value.autoRouterMode.lowercase()
    return when {
        mode == "cloud-only" -> if (hasCloud) "Cloud ($cloudModel)" else "Add Cloud Key"
        mode == "local-only" -> installed ?: "No model"
        installed != null && hasCloud -> "$installed + Cloud"
        installed != null -> installed
        hasCloud -> "Cloud ($cloudModel)"
        else -> "No model"
    }
}

/** Installed model size in whole GB, for actionable offline guidance. */
internal fun AniobViewModel.recommendedModelSizeGb(modelId: String): Int =
    AniobModelDownloader.ALL_MODELS.find { it.id == modelId }?.sizeGb?.toInt() ?: 2

/** Richer routing rationale for the "Why this model?" dialog. */
fun AniobViewModel.getModelChoiceReason(): String {
    val installed = getInstalledModelName() ?: "none installed"
    val hasCloud = _uiState.value.omnirouteApiKey.isNotBlank()
    val cloudDesc = if (hasCloud) {
        val gw = _uiState.value.omnirouteBaseUrl.ifBlank { "auto-gateway" }
        "active (model '${_uiState.value.omnirouteModel.ifBlank { "auto" }}' via $gw)"
    } else {
        "not configured"
    }
    val telemetry = AniobDeviceTelemetry.getRealState(app)
    val batteryToken = if (telemetry.isCharging) "charging" else "on battery ${telemetry.batteryPercent}%"
    val mode = _uiState.value.autoRouterMode
    val decision = _uiState.value.lastRoutingReason
    return "Battery $batteryToken. Mode '$mode' selected '$installed' " +
        "(local ${telemetry.totalRamGb}GB RAM device; Cloud API: $cloudDesc). " +
        "Last decision: $decision"
}

internal fun AniobViewModel.isOmniRouteReady(): Boolean = isCloudConfigured()

/** Settings the gateway provider is built from; a change yields a fresh instance. */
internal fun AniobViewModel.omniRouteFingerprint(): String = listOf(
    _uiState.value.omnirouteApiKey.hashCode().toString(),
    _uiState.value.omnirouteBaseUrl,
    _uiState.value.omnirouteModel
).joinToString("|")

internal fun AniobViewModel.getOmniRouteProvider(sessionId: String? = null): AniobOmniRouteProvider =
    if (sessionId == null) buildOmniRouteProvider(null)
    else omniRouteProviders.get(sessionId, omniRouteFingerprint())

/** Forgets a finished task's providers and logs how many it created (expected: at most 1). */
internal fun AniobViewModel.releaseTaskProviders(taskId: String) {
    val built = omniRouteProviders.release(taskId) + accountStepClients.release(taskId)
    if (built > 0) app.eventLogger.info("AniobViewModel", "Cloud provider instances for $taskId: $built")
    if (built > 1) app.eventLogger.warn("AniobViewModel", "Task $taskId rebuilt its cloud provider ${built - 1} time(s)")
}

internal fun AniobViewModel.buildOmniRouteProvider(sessionId: String?): AniobOmniRouteProvider {
    return AniobOmniRouteProvider(
        apiKey = _uiState.value.omnirouteApiKey,
        baseUrl = AniobProviderConfig.normalizeBaseUrl(_uiState.value.omnirouteBaseUrl)
            .ifBlank { AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL },
        model = AniobProviderConfig.sanitizeModel(_uiState.value.omnirouteModel),
        sessionId = sessionId,
        // Research metric #2: one count per outbound generation request. No-ops when no
        // instrumented task is running (for example a chat-only Q&A).
        onLlmRequest = { app.researchTelemetry.recordLlmCall() },
        onRateLimited = { waitMs ->
            setPhase(AgentPhase.RATE_LIMITED, ThinkSource.MODEL_CLOUD, retryAtMs = System.currentTimeMillis() + waitMs)
        }
    )
}

/**
 * A stored key and a valid address, or a keyless gateway on this device / the local network
 * (OmniRoute's default `REQUIRE_API_KEY=false` setup).
 */
internal fun AniobViewModel.isCloudConfigured(): Boolean = cloudSelection() is com.aniob.core.auth.AniobCloudSelection.Use

/** The cloud the user picked in Settings; the gateway unless they chose their account. */
internal fun AniobViewModel.cloudSource(): com.aniob.core.auth.AniobCloudSource =
    com.aniob.core.auth.AniobCloudSource.fromPref(
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).getString("cloud_source", null)
    )

/**
 * One decision for "is there a usable cloud, and which". An account choice that is not signed in
 * is *unavailable*; it never silently swaps to the gateway (a different key and destination).
 * Whether the step may then drop on-device is still decided by the existing routing, and
 * `cloud-only` is never downgraded.
 */
internal fun AniobViewModel.cloudSelection(): com.aniob.core.auth.AniobCloudSelection =
    com.aniob.core.auth.AniobCloudSourceSelector.select(
        preference = cloudSource(),
        authState = app.accountAuth.state,
        accountFeatureAvailable = app.accountAuth.featureAvailable,
        omniRouteConfigured = AniobProviderConfig.isConfigured(
            _uiState.value.omnirouteApiKey, _uiState.value.omnirouteBaseUrl
        )
    )

/** Q&A provider for the chosen cloud source. */
internal fun AniobViewModel.getCloudProvider(sessionId: String? = null): com.aniob.core.external.AniobCloudLlmProvider =
    if (cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT) {
        app.accountAuth.newClient(sessionId) { app.researchTelemetry.recordLlmCall() }
    } else {
        getOmniRouteProvider(sessionId)
    }

/** UI-step provider for the chosen cloud source. */
internal fun AniobViewModel.getActionStepProvider(sessionId: String? = null): com.aniob.core.external.AniobActionStepProvider =
    if (cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT) {
        if (sessionId == null) app.accountAuth.newClient(null) { app.researchTelemetry.recordLlmCall() }
        else accountStepClients.get(sessionId, "account")
    } else {
        getOmniRouteProvider(sessionId)
    }
