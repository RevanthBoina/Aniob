package com.aniob.core.dialogue

import com.aniob.core.knowledge.AppCatalogEntry

/**
 * Tool-grounded answers for questions ABOUT the device.
 *
 * Root cause it removes: the text-only answer path has no tools and no device facts, so every
 * "what do I have / is X installed / how many" question came back as instructions for the human
 * ("open Settings → Apps → See all apps") and every follow-up drifted into a chat loop.
 *
 * Extension point: register a [QueryTool] per new device fact (battery, storage, Wi-Fi, unread
 * notifications). A tool that returns null simply does not apply, so the router falls through to the
 * model unchanged; adding capability never requires editing the router.
 */
fun interface QueryTool {
    fun tryAnswer(normalizedPrompt: String, apps: List<AppCatalogEntry>): String?
}

object LocalQueryTools {

    private val APPS_CUE = Regex("\\b(apps?|applications)\\b")
    private val LIST_CUE = Regex("\\b(list|show|tell|give|name|what|which|all|installed|available|inventory)\\b")
    private val COUNT = Regex("\\bhow many (apps|applications)\\b")
    private val IS_INSTALLED = Regex(
        "^(?:is|are|do i have|have i got|is there|check if|is)\\s+(.+?)\\s*(?:app|application)?\\s*" +
            "(?:installed|available|on my (?:phone|device|mobile)|in my (?:phone|device|mobile))\\??$"
    )
    private val CAN_YOU = Regex("^(?:can|could|are you able to|do you)\\s+(?:you\\s+)?(.+)$")

    val DEFAULT_TOOLS: List<QueryTool> = listOf(
        QueryTool { p, apps -> countApps(p, apps) },
        QueryTool { p, apps -> listApps(p, apps) },
        QueryTool { p, apps -> installedCheck(p, apps) }
    )

    /** Answer from tools, or null so the caller falls through to the model. */
    fun answer(
        prompt: String,
        apps: List<AppCatalogEntry> = AppAffinity.installedApps(),
        tools: List<QueryTool> = DEFAULT_TOOLS
    ): String? {
        val lower = com.aniob.core.lexicon.TaskLexicon.stripPoliteness(prompt).lowercase().trimEnd('?', '.', '!')
        return tools.firstNotNullOfOrNull { it.tryAnswer(lower, apps) }
    }

    private fun countApps(lower: String, apps: List<AppCatalogEntry>): String? {
        if (!COUNT.containsMatchIn(lower)) return null
        val user = apps.count { !it.isSystemApp }
        return "You have ${apps.size} apps I can open ($user user apps, ${apps.size - user} system apps)."
    }

    private fun listApps(lower: String, apps: List<AppCatalogEntry>): String? {
        if (!APPS_CUE.containsMatchIn(lower) || !LIST_CUE.containsMatchIn(lower)) return null
        if (apps.isEmpty()) {
            return "I'm still reading your app list. Ask me again in a few seconds and I'll name every app I can open."
        }
        val user = apps.filter { !it.isSystemApp }.map { it.appName }.distinct().sortedBy { it.lowercase() }
        val system = apps.filter { it.isSystemApp }.map { it.appName }.distinct().sortedBy { it.lowercase() }
        return buildString {
            append("Here are the ${apps.size} apps installed on this device that I can open.\n\n")
            if (user.isNotEmpty()) append("Your apps (${user.size}): ${user.joinToString(", ")}.\n\n")
            if (system.isNotEmpty()) append("System apps (${system.size}): ${system.joinToString(", ")}.")
        }.trim()
    }

    private fun installedCheck(lower: String, apps: List<AppCatalogEntry>): String? {
        val name = IS_INSTALLED.matchEntire(lower)?.groupValues?.get(1)?.trim()?.removeSuffix(" app")
            ?: CAN_YOU.matchEntire(lower)?.let { m ->
                val inner = m.groupValues[1]
                IS_INSTALLED.matchEntire(inner)?.groupValues?.get(1)?.trim()
            } ?: return null
        if (name.length < 2 || name.split(' ').size > 4) return null
        val hit = apps.firstOrNull { it.appName.equals(name, true) || it.aliases.any { a -> a.equals(name, true) } }
            ?: AppAffinity.findInstalled(name, apps).firstOrNull()
        return if (hit != null) "Yes — ${hit.appName} is installed and I can open it." else null
    }
}
