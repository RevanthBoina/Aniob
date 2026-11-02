package com.aniob.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aniob.app.knowledge.AniobAppBootstrap
import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.knowledge.RegisteredApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reflex 1: Package-change spinal reflex (Tasker/macroDroid trigger provider).
 *
 * `PACKAGE_ADDED` / `PACKAGE_REMOVED` / `PACKAGE_REPLACED` update the *same* registry the ladder
 * resolves app names from, so an app that was just installed can be opened by the very next task and
 * an uninstalled one is never offered again. A full background refresh follows, because a package
 * change can also move a *role* (the newly installed browser becomes "the browser").
 *
 * Both writes go through [AniobAppCatalog], which is a facade over `AniobAppRegistry` — there is one
 * source of truth, and the TSV cache cold start reads is rewritten by the same call.
 */
class AniobPackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_PACKAGE_ADDED &&
            action != ACTION_PACKAGE_REMOVED &&
            action != ACTION_PACKAGE_REPLACED
        ) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        // EXTRA_REPLACING is set when a package is removed as part of an update/upgrade: the app is
        // still installed, so the entry must not be dropped in the middle of the replacement.
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        receiverScope.launch {
            val catalog = AniobAppCatalog.getInstance()
            try {
                when (action) {
                    ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REPLACED -> {
                        val pm = appContext.packageManager
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        val label = pm.getApplicationLabel(appInfo)?.toString() ?: packageName
                        val isSystem =
                            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        // Only a package with a launcher entry is an app the agent can open; storing
                        // it otherwise would offer "installed but unlaunchable" as a target.
                        val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()
                        if (launchIntent != null) {
                            val component = launchIntent.component
                            catalog.registerApp(
                                RegisteredApp(
                                    packageName = packageName,
                                    label = label,
                                    isSystemApp = isSystem,
                                    launchActivity = component?.let { "${it.packageName}/${it.className}" }
                                )
                            )
                        }
                    }
                    ACTION_PACKAGE_REMOVED -> if (!replacing) catalog.removeApp(packageName)
                }
                saveCatalog(appContext, catalog)
                // Roles can change with an install/uninstall: re-ask the platform in the background.
                AniobAppBootstrap.refreshAsync(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Package reflex failed for $packageName", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun saveCatalog(context: Context, catalog: AniobAppCatalog) {
        val jsonFile = File(context.filesDir, "app_catalog.json")
        val mdFile = File(context.filesDir, "apps/app-registry.md")
        mdFile.parentFile?.mkdirs()
        catalog.saveToFile(jsonFile, mdFile)
    }

    companion object {
        private const val TAG = "AniobPackageReceiver"
        private const val ACTION_PACKAGE_ADDED = Intent.ACTION_PACKAGE_ADDED
        private const val ACTION_PACKAGE_REMOVED = Intent.ACTION_PACKAGE_REMOVED
        private const val ACTION_PACKAGE_REPLACED = Intent.ACTION_PACKAGE_REPLACED
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
