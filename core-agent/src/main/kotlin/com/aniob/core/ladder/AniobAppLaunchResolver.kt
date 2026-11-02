package com.aniob.core.ladder

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppCatalogEntry

/**
 * An app the task names, resolved from the installed-app registry without asking a model.
 *
 * [launchActivity] is the registry's explicit `<pkg>/<pkg>.Activity` component when the platform
 * reported one, so the dispatch can be an explicit intent that no resolver dialog can intercept.
 */
data class AppLaunchTarget(
    val packageName: String,
    val appName: String,
    val launchActivity: String? = null
)

/**
 * Resolves "open <app> and …" to a direct app launch, so step 0 costs zero model calls.
 *
 * The model is expensive and, on a cold task, it is asked something it cannot know better: which
 * package the user means by "Clock". The catalog already knows. Resolving it here turns the first
 * action of most real tasks into a single `OpenApp` dispatch, and the model only sees the screen that
 * actually matters.
 *
 * Matching is deliberately conservative:
 * - the prompt must begin with an open/launch/start verb (the same imperative test the intent gate
 *   uses), so a mention of an app inside a sentence never triggers a launch;
 * - the app name must appear at the **start** of the remainder, on a word boundary;
 * - the longest matching name wins, and a tie is treated as ambiguous and ignored.
 *
 * [resolveAnywhere] is the mid-task sibling: it looks at the whole prompt and is used only for apps
 * that this task has not opened yet (see [AniobLaunchHub.planMidTask]).
 */
object AniobAppLaunchResolver {

    private val LAUNCH_PREFIX = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:open|launch|start|run|go\\s+to|switch\\s+to|fire\\s+up)\\s+" +
            "(?:the\\s+|my\\s+|your\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param prompt the task text as typed by the user.
     * @param entries installed apps; an empty list disables the shortcut.
     * @param isInstalled optional check so a stale catalog entry cannot launch a removed app.
     */
    fun resolve(
        prompt: String,
        entries: List<AppCatalogEntry>,
        isInstalled: (String) -> Boolean = { true }
    ): AppLaunchTarget? {
        val trimmed = prompt.trim()
        val remainder = LAUNCH_PREFIX.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        if (remainder.isBlank()) return null
        return pickAtStart(remainder.lowercase(), entries, isInstalled)
    }

    /**
     * Any app named anywhere in the prompt, longest name first.
     *
     * Used mid-task ("open Clock … then open Instagram"). Apps in [exclude] and [currentPackage] are
     * skipped by the caller, which is what keeps a multi-step task from re-launching its first app.
     */
    fun resolveAnywhere(
        prompt: String,
        entries: List<AppCatalogEntry>,
        isInstalled: (String) -> Boolean = { true },
        exclude: Set<String> = emptySet(),
        currentPackage: String? = null
    ): AppLaunchTarget? {
        val haystack = " " + prompt.lowercase().replace(Regex("\\s+"), " ") + " "
        val scored = mutableListOf<Pair<AppCatalogEntry, Int>>()
        for (entry in entries) {
            if (entry.packageName.isBlank() || entry.packageName in exclude) continue
            if (currentPackage != null && entry.packageName == currentPackage) continue
            for (name in listOf(entry.appName) + entry.aliases) {
                val candidate = name.trim().lowercase()
                if (candidate.length < 3) continue
                if (!containsAtWordBoundary(haystack, candidate)) continue
                scored += entry to candidate.length
            }
        }
        val topLength = scored.maxOfOrNull { it.second } ?: return null
        val winners = scored.filter { it.second == topLength }.map { it.first }
        // Two apps named equally specifically ("Settings" and "Settings Hub" both abbreviated) is a
        // question, not a coin flip: no launch beats the wrong launch.
        if (winners.map { it.packageName }.distinct().size > 1) return null
        val best = winners.first()
        if (!isInstalled(best.packageName)) return null
        return AppLaunchTarget(best.packageName, best.appName, best.launchActivity)
    }

    /** Convenience for callers that hold the registry rather than a list. */
    fun entriesFrom(registry: AniobAppRegistry): List<AppCatalogEntry> =
        registry.all().map {
            AppCatalogEntry(it.packageName, it.label, it.isSystemApp, it.aliases, it.launchActivity)
        }

    private fun pickAtStart(
        lower: String,
        entries: List<AppCatalogEntry>,
        isInstalled: (String) -> Boolean
    ): AppLaunchTarget? {
        var best: AppLaunchTarget? = null
        var bestLength = 0
        var tied = false

        for (entry in entries) {
            if (entry.packageName.isBlank() || entry.appName.isBlank()) continue
            for (name in listOf(entry.appName) + entry.aliases) {
                val candidate = name.trim().lowercase()
                if (candidate.length < 2) continue
                if (!startsWithWord(lower, candidate)) continue
                when {
                    candidate.length > bestLength -> {
                        best = AppLaunchTarget(entry.packageName, entry.appName, entry.launchActivity)
                        bestLength = candidate.length
                        tied = false
                    }
                    candidate.length == bestLength && best?.packageName != entry.packageName -> tied = true
                }
            }
        }
        val target = best ?: return null
        if (tied) return null
        if (!isInstalled(target.packageName)) return null
        return target
    }

    /**
     * True when [needle] appears in [haystack] at a word boundary.
     *
     * Every occurrence is checked, not just the first: "the metronome app, open settings" names
     * Settings even though an earlier substring happened to sit inside another word.
     */
    private fun containsAtWordBoundary(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(" $needle", from)
            if (at < 0) return false
            val after = at + 1 + needle.length
            if (after >= haystack.length || !haystack[after].isLetterOrDigit()) return true
            from = at + 1
        }
    }

    /** True when [text] starts with [word] and the character after it is not a letter or digit. */
    private fun startsWithWord(text: String, word: String): Boolean {
        if (!text.startsWith(word)) return false
        if (text.length == word.length) return true
        return !text[word.length].isLetterOrDigit()
    }
}
