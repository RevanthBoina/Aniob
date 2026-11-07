package com.aniob.app.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.aniob.app.AniobApplication
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.service.AniobAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One reactive snapshot of every system gate the UI reads. Never a non-reactive local read. */
data class SystemState(
    val accessibilityConnected: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val networkAvailable: Boolean = true,
    val modelReady: Boolean = false,
    val localModelReady: Boolean = false,
    val cloudReady: Boolean = false,
    val checkedAt: Long = 0L
)

/**
 * Single reactive truth for permission/service state (UX-0 §1, kills U2).
 *
 * Sources push updates: accessibility service lifecycle, connectivity callback, package changes and
 * Activity `onResume`. Every banner/card collects this flow — a manual Refresh button may
 * remain as a secondary affordance but is never the mechanism.
 */
class AniobSystemState(private val app: AniobApplication) {

    private val downloader = AniobModelDownloader(app)
    private val secretStore = com.aniob.app.data.AniobSecretStore(app)

    private val _state = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = _state.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var observerScope: CoroutineScope? = null

    fun start() {
        if (started) return
        started = true
        registerConnectivityCallback()
        refresh()
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch {
                val account = app.accountAuth
                combine(account.source, account.repository?.state ?: MutableStateFlow<com.aniob.core.auth.AniobAuthState>(
                    com.aniob.core.auth.AniobAuthState.SignedOut)) { _, _ -> Unit }.collect { refresh() }
            }
        }
    }

    /** Re-reads every gate. Cheap: called from onResume, service callbacks and package events. */
    fun refresh() {
        val localReady = isLocalModelReady()
        val cloudReady = com.aniob.core.auth.AniobCloudSourceSelector.select(
            app.accountAuth.cloudSource, app.accountAuth.state, app.accountAuth.featureAvailable,
            com.aniob.app.provider.AniobProviderConfig.isConfigured(
                secretStore.getOmniRouteApiKey(), secretStore.getOmniRouteBaseUrl())
        ) is com.aniob.core.auth.AniobCloudSelection.Use
        _state.update {
            SystemState(
                accessibilityConnected = AniobAccessibilityService.isServiceConnected,
                overlayGranted = canDrawOverlays(),
                notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                batteryUnrestricted = isBatteryUnrestricted(),
                networkAvailable = isNetworkAvailable(),
                modelReady = localReady || cloudReady,
                localModelReady = localReady,
                cloudReady = cloudReady,
                checkedAt = System.currentTimeMillis()
            )
        }
    }

    /** Push-based update from the accessibility service, avoiding a full re-probe. */
    fun onAccessibilityChanged(connected: Boolean) {
        _state.update { it.copy(accessibilityConnected = connected, checkedAt = System.currentTimeMillis()) }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(app)

    private fun isBatteryUnrestricted(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isLocalModelReady(): Boolean {
        val defaultId = downloader.getDefaultModelId()
        val hasLocalModel = !defaultId.isNullOrBlank() && downloader.isModelInstalled(defaultId)
        return hasLocalModel
    }

    private fun registerConnectivityCallback() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _state.update { it.copy(networkAvailable = true, checkedAt = System.currentTimeMillis()) }
            }

            override fun onLost(network: Network) {
                refresh()
            }
        }
        networkCallback = callback
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        } catch (_: Exception) {
            // Some OEMs reject the request; onResume refresh keeps the state honest.
        }
    }

    fun stop() {
        observerScope?.cancel()
        observerScope = null
        started = false
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already unregistered.
        }
        networkCallback = null
    }

    /** Deep link to the accessibility settings page, used by setup and recovery surfaces. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }

    companion object {
        fun of(app: AniobApplication): AniobSystemState = app.systemState
    }
}
