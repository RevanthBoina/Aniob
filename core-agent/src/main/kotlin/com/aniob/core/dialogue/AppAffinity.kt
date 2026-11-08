package com.aniob.core.dialogue

import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.knowledge.AppCatalogEntry
import com.aniob.core.lexicon.TaskLexicon

/**
 * Maps what the user SAID to an app that is really installed on this device.
 *
 * Two failure modes it removes:
 *  1. A request whose app is implied by the verb ("send a connect request") used to be treated as a
 *     generic message, producing an irrelevant SMS/WhatsApp/Telegram picker.
 *  2. A request that names an app ROLE ("on voice recording") used to be answered with prose because
 *     nothing matched a launcher label.
 *
 * Resolution always goes through the live launcher inventory (labels + aliases), with a package-id
 * fallback; a hardcoded package is never trusted as installed.
 */
object AppAffinity {

    /** Phrase-level affinity for the prompt, or null when the request names no implied app. */
    fun forPrompt(lowerPrompt: String): TaskLexicon.Affinity? = TaskLexicon.affinityFor(lowerPrompt)

    /** Installed apps whose launcher label/alias matches a spoken role or name, best match first. */
    fun findInstalled(spoken: String, apps: List<AppCatalogEntry> = installedApps()): List<AppCatalogEntry> {
        val needles = TaskLexicon.roleKeywords(spoken).map { it.lowercase() }
        if (needles.isEmpty()) return emptyList()
        fun score(app: AppCatalogEntry): Int {
            val label = app.appName.lowercase()
            val aliases = app.aliases.map { it.lowercase() }
            return when {
                needles.any { label == it } -> 100
                needles.any { label.startsWith(it) } -> 80
                needles.any { label.contains(it) } -> 60
                needles.any { a -> aliases.any { it == a || it.contains(a) } } -> 40
                else -> 0
            }
        }
        return apps.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** The installed app an affinity refers to, or null when that app is not on this device. */
    fun resolve(affinity: TaskLexicon.Affinity, apps: List<AppCatalogEntry> = installedApps()): AppCatalogEntry? {
        affinity.labelKeywords.forEach { keyword ->
            apps.firstOrNull { it.appName.contains(keyword, ignoreCase = true) }?.let { return it }
        }
        affinity.knownPackages.forEach { pkg -> apps.firstOrNull { it.packageName == pkg }?.let { return it } }
        return null
    }

    /** Messaging-capable apps that are installed: the only honest source of "which app?" options. */
    fun messagingOptions(apps: List<AppCatalogEntry> = installedApps()): List<String> =
        apps.map { it.appName }
            .filter { label -> TaskLexicon.MESSAGING_ROLES.any { label.contains(it, ignoreCase = true) } }
            .distinct()

    fun installedApps(): List<AppCatalogEntry> =
        runCatching { AniobAppCatalog.getInstance().getAll() }.getOrDefault(emptyList())
}
