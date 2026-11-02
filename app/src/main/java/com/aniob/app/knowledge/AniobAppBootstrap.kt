package com.aniob.app.knowledge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Learns the device's apps **once, at startup**, and keeps a cache of it.
 *
 * This is the "default way" to open anything. The rule is that the agent never asks a model which
 * package "Clock" or "Instagram" means: the platform already answered that, for every app that has a
 * launcher icon, at boot of our process. What the agent does at runtime is a lookup.
 *
 * Lifecycle:
 *
 *  1. `Application.onCreate` → [install]: the persisted cache is loaded **synchronously** (a few ms,
 *     TSV), so the very first task already resolves app names — no cold-start window in which the
 *     registry is empty (that window is what made "Open Clock" fail intermittently).
 *  2. A background [refresh] then asks `PackageManager` for the truth and rewrites the cache. It is
 *     launchable-only (`MAIN`/`LAUNCHER`), so "installed" and "openable" stop being conflated.
 *  3. `PACKAGE_ADDED`/`PACKAGE_REMOVED` (already handled by `AniobPackageReceiver`) update the same
 *     registry through `AniobAppCatalog`, and the cache is rewritten.
 *
 * Roles — "the calculator", "the browser", "the clock" — are resolved with the platform's own
 * category intents and cached as role → package. That is why "open the calculator" works on a
 * Samsung, a Xiaomi and a Pixel without three hardcoded package names.
 *
 * Requires `MAIN`/`LAUNCHER` in the manifest's `<queries>` (Android 11 package visibility).
 */
object AniobAppBootstrap {

    private const val TAG = "AniobAppBootstrap"

    /** Rebuild at most once a week: package inventories change slowly, and a probe is not free. */
    const val TTL_MS = 7L * 24 * 3600 * 1000

    /**
     * Re-verify at most once a day even while the week-long TTL is still open.
     *
     * `PACKAGE_ADDED` covers installs made while the process was dead, but a manifest receiver can
     * be missed (force-stop, an OEM that defers broadcasts). A daily background probe is cheap and
     * keeps a phone that has not been used for a while from resolving names against a stale list.
     */
    const val SOFT_TTL_MS = 24L * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var lastRefreshAtMs: Long = 0L

    private fun cacheFile(context: Context): File = File(context.filesDir, AniobAppCatalog.CACHE_FILE_NAME)

    /**
     * Call from `Application.onCreate`.
     *
     * Synchronous part: read the cache (so app names resolve immediately). Asynchronous part: verify
     * it against the platform and rebuild if stale.
     */
    fun install(app: Application) {
        val loaded = runCatching { AniobAppCatalog.getInstance().loadFromFile(app.filesDir) }
            .getOrDefault(false)
        val registry = AniobAppRegistry.getInstance()
        if (loaded) {
            android.util.Log.i(TAG, "registry cache hydrated: ${registry.size()} apps, " +
                "roles=${registry.roles().keys}")
        }
        val recheck = !loaded || AniobAppRegistry.getInstance()
            .isStale(System.currentTimeMillis(), SOFT_TTL_MS)
        scope.launch { runCatching { refresh(app, force = recheck) } }
    }

    /**
     * Ask the platform what is installed, launchable and which package owns each role.
     *
     * @return the number of launchable apps registered.
     */
    fun refresh(context: Context, force: Boolean = false): Int {
        val registry = AniobAppRegistry.getInstance()
        val now = System.currentTimeMillis()
        if (!force && !registry.isStale(now, TTL_MS)) return registry.size()
        if (!refreshing.compareAndSet(false, true)) return registry.size()

        return try {
            val pm = context.packageManager
            val selfPackage = context.packageName
            val apps = queryLaunchableApps(pm, selfPackage)
            val roles = resolveRoles(pm, selfPackage)
            if (apps.isEmpty() && !registry.isEmpty()) {
                // A locked screen / mid-boot PackageManager can return nothing. Never wipe a good
                // registry because of a transient empty answer.
                android.util.Log.w(TAG, "launcher query returned 0 apps; keeping the cached registry")
                return registry.size()
            }
            val changed = registry.hydrate(apps, roles, now)
            AniobAppCatalog.getInstance().saveToFile(
                File(context.filesDir, "app_catalog.json"),
                File(context.filesDir, "apps/app-registry.md")
            )
            lastRefreshAtMs = now
            android.util.Log.i(
                TAG,
                "registry ${if (changed) "rebuilt" else "unchanged"}: ${apps.size} launchable apps, " +
                    "roles=${roles.entries.joinToString { "${it.key}=${it.value}" }}"
            )
            apps.size
        } catch (e: Exception) {
            android.util.Log.e(TAG, "refresh failed: ${e.message}", e)
            registry.size()
        } finally {
            refreshing.set(false)
        }
    }

    /** Force a rebuild (Settings screen, or after a batch of installs). */
    fun refreshAsync(context: Context) {
        scope.launch { runCatching { refresh(context, force = true) } }
    }

    // ------------------------------------------------------------------ #
    // probing
    // ------------------------------------------------------------------ #

    /**
     * Every app with a launcher entry, with its explicit launch component.
     *
     * `getInstalledApplications` is deliberately **not** used: it lists packages that cannot be
     * opened (providers, stub apps), which is how a "known" app becomes an unlaunchable one.
     */
    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(pm: PackageManager, selfPackage: String): List<RegisteredApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val out = LinkedHashMap<String, RegisteredApp>()
        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val packageName = activityInfo.packageName ?: continue
            if (packageName == selfPackage) continue             // we are not a target
            if (out.containsKey(packageName)) continue           // one entry per package
            val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                ?: activityInfo.name?.substringAfterLast('.')
                ?: packageName
            val isSystem = runCatching {
                (pm.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
            out[packageName] = RegisteredApp(
                packageName = packageName,
                label = label.trim(),
                isSystemApp = isSystem,
                launchActivity = activityInfo.name?.let { "$packageName/$it" }
            )
        }
        return out.values.toList()
    }

    /** role → package, answered by the platform rather than by a table of vendor package names. */
    @Suppress("DEPRECATION")
    private fun resolveRoles(pm: PackageManager, selfPackage: String): Map<String, String> {
        val roles = LinkedHashMap<String, String>()
        fun put(role: String, intent: Intent) {
            val resolved = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
                .getOrNull()
            val pkg = resolved?.activityInfo?.packageName ?: return
            if (pkg == selfPackage) return
            roles[role] = pkg
        }

        put(AniobAppRegistry.ROLE_CLOCK, Intent("android.intent.action.SHOW_ALARMS"))
        put(AniobAppRegistry.ROLE_CALCULATOR,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALCULATOR"))
        put(AniobAppRegistry.ROLE_CALENDAR,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALENDAR"))
        put(AniobAppRegistry.ROLE_BROWSER,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_BROWSER"))
        put(AniobAppRegistry.ROLE_FILES,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_FILES"))
        put(AniobAppRegistry.ROLE_MEDIA,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_MUSIC"))
        put(AniobAppRegistry.ROLE_MESSAGES,
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_MESSAGING"))
        put(AniobAppRegistry.ROLE_CAMERA, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        put(AniobAppRegistry.ROLE_SETTINGS, Intent(Settings.ACTION_SETTINGS))
        put(AniobAppRegistry.ROLE_PHONE, Intent(Intent.ACTION_DIAL))
        runCatching { put(AniobAppRegistry.ROLE_EMAIL, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))) }
        runCatching { put(AniobAppRegistry.ROLE_MAPS, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))) }

        // A role is only useful if the package is one we can actually launch.
        val keep = roles.filterValues { pkg -> pm.getLaunchIntentForPackage(pkg) != null }
        return keep
    }

    /** Diagnostics for the Settings/Doctor screen. */
    fun describe(context: Context): String {
        val registry = AniobAppRegistry.getInstance()
        val age = if (registry.builtAt() <= 0L) "never" else {
            val minutes = (System.currentTimeMillis() - registry.builtAt()) / 60_000
            "$minutes min ago"
        }
        return buildString {
            append("apps=${registry.size()} (built $age)")
            if (registry.roles().isNotEmpty()) {
                append(", roles=")
                append(registry.roles().entries.joinToString { "${it.key}→${it.value}" })
            }
            if (lastRefreshAtMs > 0) append(", last refresh ok")
            append(", cache=${cacheFile(context).name}")
        }
    }
}
