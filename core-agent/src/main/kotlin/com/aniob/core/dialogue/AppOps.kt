package com.aniob.core.dialogue

import com.aniob.core.knowledge.AppCatalogEntry
import com.aniob.core.lexicon.TaskLexicon

/**
 * "Delete Discord", "uninstall that game", "clear Instagram's cache" -> an operation plus the
 * installed app it refers to.
 *
 * Before this, such a request had no route at all: the loop minimised the app, opened a browser and
 * searched the web for it. The operation is resolved here; the platform dialog that performs it stays
 * the confirmation step, because removal cannot be undone.
 */
object AppOps {

    enum class Op(val wire: String) { UNINSTALL("uninstall"), CLEAR_DATA("clear_data"), APP_INFO("app_info") }

    data class Request(val op: Op, val spokenTarget: String)

    private val PATTERNS: List<Pair<Regex, Op>> = listOf(
        Regex("^\\b(?:uninstall|delete|remove|get rid of|drop)\\b\\s+(.+?)(?:\\s+(?:app|application|from my (?:phone|device)))?$") to Op.UNINSTALL,
        Regex("^\\bclear\\s+(?:the\\s+)?(?:data|storage|cache)\\s+(?:of|for|from)\\s+(.+?)(?:\\s+app)?$") to Op.CLEAR_DATA,
        Regex("^\\b(?:app info|application info|details|permissions)\\s+(?:of|for)\\s+(.+?)(?:\\s+app)?$") to Op.APP_INFO
    )

    fun parse(prompt: String): Request? {
        val lower = TaskLexicon.stripPoliteness(prompt).lowercase().trimEnd('.', '!', '?')
        for ((rx, op) in PATTERNS) {
            val m = rx.matchEntire(lower) ?: continue
            val target = m.groupValues[1].trim().removeSuffix(" app").removePrefix("the ").removePrefix("my ").trim()
            if (target.isBlank() || target in TaskLexicon.PRONOUNS) continue
            return Request(op, target)
        }
        return null
    }

    fun resolve(request: Request, apps: List<AppCatalogEntry> = AppAffinity.installedApps()): AppCatalogEntry? =
        apps.firstOrNull { it.appName.equals(request.spokenTarget, ignoreCase = true) }
            ?: apps.firstOrNull { it.aliases.any { a -> a.equals(request.spokenTarget, ignoreCase = true) } }
            ?: AppAffinity.findInstalled(request.spokenTarget, apps).firstOrNull()
}
