package com.aniob.core.knowledge

import java.io.File

/**
 * Legacy value type kept for source compatibility with the app module and existing tests.
 *
 * [launchActivity] is carried through from [RegisteredApp] so a caller that only holds catalog
 * entries can still dispatch an explicit `<pkg>/<pkg>.Activity` intent.
 */
data class AppCatalogEntry(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val aliases: List<String> = emptyList(),
    val launchActivity: String? = null
) {
    fun toRegistered(launchActivity: String? = this.launchActivity): RegisteredApp =
        RegisteredApp(packageName, appName, isSystemApp, launchActivity, aliases)

    companion object {
        fun from(app: RegisteredApp): AppCatalogEntry =
            AppCatalogEntry(app.packageName, app.label, app.isSystemApp, app.aliases, app.launchActivity)
    }
}

/**
 * Compatibility facade over [AniobAppRegistry].
 *
 * The old class was the *only* store and it was write-only in practice: the accessibility service
 * and the package receiver wrote `app_catalog.json`, nothing read it back, and the in-memory map was
 * therefore empty at cold start. Every call site that still says `AniobAppCatalog` now talks to the
 * registry instead, so no call site had to change — but the data is persisted **and hydrated**, and
 * it is launchable-only, because the bootstrap builds it from `queryIntentActivities(MAIN/LAUNCHER)`.
 *
 * Nothing here is a second source of truth: `saveToFile` writes the registry's own cache.
 */
class AniobAppCatalog private constructor() {

    private val registry: AniobAppRegistry get() = AniobAppRegistry.getInstance()

    @Synchronized
    fun registerApp(entry: AppCatalogEntry) = registry.upsert(entry.toRegistered())

    @Synchronized
    fun registerApp(entry: RegisteredApp) = registry.upsert(entry)

    @Synchronized
    fun removeApp(packageName: String) = registry.remove(packageName)

    @Synchronized
    fun getAll(): List<AppCatalogEntry> = registry.all().map { AppCatalogEntry.from(it) }

    sealed class AppResolution {
        data class ExactMatch(val entry: AppCatalogEntry) : AppResolution()
        data class Ambiguous(val candidates: List<AppCatalogEntry>) : AppResolution()
        object NotFound : AppResolution()
    }

    @Synchronized
    fun findCandidates(query: String): List<AppCatalogEntry> = when (val found = registry.resolve(query)) {
        is AppLookup.Match -> listOf(AppCatalogEntry.from(found.app))
        is AppLookup.Ambiguous -> found.candidates.map { AppCatalogEntry.from(it) }
        is AppLookup.Miss -> emptyList()
    }

    @Synchronized
    fun resolveApp(query: String): AppResolution = when (val found = registry.resolve(query)) {
        is AppLookup.Match -> AppResolution.ExactMatch(AppCatalogEntry.from(found.app))
        is AppLookup.Ambiguous -> AppResolution.Ambiguous(found.candidates.map { AppCatalogEntry.from(it) })
        is AppLookup.Miss -> AppResolution.NotFound
    }

    /**
     * Package for a spoken name, or null when the name is ambiguous.
     *
     * Ambiguity deliberately returns null instead of "best guess": picking between "Settings" and
     * "Settings Hub" is exactly the decision that must go back to the user, or to a rung that can
     * ask.
     */
    @Synchronized
    fun findPackageForName(query: String): String? = when (val found = registry.resolve(query)) {
        is AppLookup.Match -> found.app.packageName
        is AppLookup.Ambiguous -> null
        is AppLookup.Miss -> null
    }

    @Synchronized
    fun toJson(): String {
        val entries = getAll().joinToString(",") { entry ->
            val aliasJson = entry.aliases.joinToString(",") { "\"${escapeJson(it)}\"" }
            """{"packageName":"${escapeJson(entry.packageName)}","appName":"${escapeJson(entry.appName)}",""" +
                """"isSystem":${entry.isSystemApp},"aliases":[$aliasJson]}"""
        }
        return "[$entries]"
    }

    @Synchronized
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# Aniob Installed Applications Catalog")
        sb.appendLine("| App Name | Package Name | System App |")
        sb.appendLine("|---|---|---|")
        getAll().sortedBy { it.appName.lowercase() }.forEach {
            val safeName = it.appName.replace("|", "\\|").replace("\n", " ").replace("\r", " ")
            sb.appendLine("| $safeName | `${it.packageName}` | ${if (it.isSystemApp) "Yes" else "No"} |")
        }
        return sb.toString()
    }

    /**
     * Writes the human-readable artefacts the accessibility service used to write **and** the
     * machine cache that cold start reads. The cache is the fix: a file that is only ever written is
     * a file that cannot help the next launch.
     */
    @Synchronized
    fun saveToFile(jsonFile: File, mdFile: File) {
        try {
            jsonFile.writeText(toJson())
            mdFile.parentFile?.mkdirs()
            mdFile.writeText(toMarkdown())
            registry.saveTo(File(jsonFile.parentFile ?: File("."), CACHE_FILE_NAME))
        } catch (e: Exception) {
            System.err.println("AniobAppCatalog saveToFile error: ${e.message}")
        }
    }

    /** Cold-start hydration from the cache written by [saveToFile]. Returns true when it had data. */
    @Synchronized
    fun loadFromFile(directory: File): Boolean = registry.loadFrom(File(directory, CACHE_FILE_NAME))

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        /** Cache file name, shared with `AniobAppBootstrap` so there is one path convention. */
        const val CACHE_FILE_NAME = "app_registry.tsv"

        private val instance = AniobAppCatalog()
        fun getInstance(): AniobAppCatalog = instance
    }
}
