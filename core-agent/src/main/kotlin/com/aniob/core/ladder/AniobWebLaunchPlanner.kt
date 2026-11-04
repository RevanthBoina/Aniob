package com.aniob.core.ladder

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppLookup

/**
 * A web page the agent decided to open without a model, and where the decision came from.
 *
 * [browserPackage] is a *preference*: the dispatcher tries it first and falls back to the system
 * default browser when that package cannot handle the URL, so a stale value never strands a task.
 */
data class WebLaunchPlan(
    val url: String,
    val label: String,
    val browserPackage: String? = null,
    /** [SOURCE_COMPOUND], [SOURCE_SEARCH] or [SOURCE_APP_FALLBACK]. */
    val source: String
) {
    companion object {
        /** "open linkedin in chrome": the user asked for the browser explicitly. */
        const val SOURCE_COMPOUND = "compound"

        /** "search for lofi in youtube": a site search URL. */
        const val SOURCE_SEARCH = "search"

        /** "open linkedin" where no installed app answers to that name: its web page is the answer. */
        const val SOURCE_APP_FALLBACK = "app_fallback"
    }
}

/**
 * Lookup-only planning for goals that name a *web destination* instead of (or in addition to) an
 * app. Pure Kotlin/JVM, zero model calls.
 *
 * Why this exists. `AniobLaunchHub.plan` answers "which installed app did the user name?". Three
 * field failures were all the same shape of question it could not answer:
 *
 *  - `open linkedin in chrome` — the app name is followed by a browser clause, so the app lookup
 *    either missed or (worse) opened the LinkedIn *app* instead of Chrome, and the rest went to a
 *    model that was down;
 *  - `open linkedin` with LinkedIn not installed — a miss, so the task went to the model;
 *  - `search in youtube` — a site-scoped search, never an app name at all.
 *
 * Each of those has a deterministic answer (a URL), so none of them may depend on a gateway.
 *
 * Rules:
 *  - a compound `<verb> <site> in <browser>` always means the web page, never the app;
 *  - the **fallback** only fires on a registry [AppLookup.Miss]: an installed app always wins, and
 *    an ambiguity is a question for the user, not a reason to open a website;
 *  - an unknown single word is only turned into `https://www.<word>.com` when the user explicitly
 *    asked for a browser. Without that cue a miss stays a miss.
 */
object AniobWebLaunchPlanner {

    const val CHROME_PACKAGE = "com.android.chrome"

    private val COMPOUND = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:open|launch|start|visit|go\\s+to|browse(?:\\s+to)?|load)\\s+" +
            "(?:the\\s+|my\\s+)?(.+?)\\s+(?:in|on|using|with|via|through)\\s+(?:the\\s+|my\\s+)?" +
            "(google\\s+chrome|chrome|web\\s+browser|default\\s+browser|browser)$",
        RegexOption.IGNORE_CASE
    )

    private val LAUNCH_VERB = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:open|launch|start|visit|go\\s+to)\\s+(?:the\\s+|my\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val SEARCH = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:search|find|look\\s*up)(?:\\s+for)?\\s*(.*?)\\s+" +
            "(?:in|on|at|from|using)\\s+(?:the\\s+)?([a-z][a-z .]*?)(?:\\s+app)?$",
        RegexOption.IGNORE_CASE
    )

    private val PERSONAL_ITEM =
        Regex("^(?:please\\s+|kindly\\s+)?\\w+(?:\\s+to)?\\s+my\\s+", RegexOption.IGNORE_CASE)

    private val SITE_SUFFIX =
        Regex("\\s+(?:web\\s*site|website|site|web\\s*page|page|app|web)$", RegexOption.IGNORE_CASE)
    private val CLAUSE_BREAK = Regex(
        "\\s+(?:and\\s+then|and|then|after\\s+that|followed\\s+by)\\s+|[;,]\\s*",
        RegexOption.IGNORE_CASE
    )
    private val DOMAIN = Regex(
        "^(?:https?://)?[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*" +
            "\\.[a-z]{2,24}(?:[/?#]\\S*)?$",
        RegexOption.IGNORE_CASE
    )
    private val SINGLE_WORD = Regex("^[a-z0-9][a-z0-9-]{1,40}$")

    /** `report.pdf` and `notes.txt` look like domains and are files. */
    private val FILE_EXTENSIONS = setOf(
        "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "jpg", "jpeg", "png", "gif",
        "webp", "mp3", "mp4", "mkv", "wav", "zip", "rar", "apk", "json", "xml", "html", "md", "log"
    )

    /** Names people say for a site, and the page that answers to each. */
    private val KNOWN_SITES: Map<String, String> = mapOf(
        "linkedin" to "https://www.linkedin.com",
        "youtube" to "https://www.youtube.com",
        "yt" to "https://www.youtube.com",
        "google" to "https://www.google.com",
        "gmail" to "https://mail.google.com",
        "google drive" to "https://drive.google.com",
        "google maps" to "https://maps.google.com",
        "facebook" to "https://www.facebook.com",
        "fb" to "https://www.facebook.com",
        "instagram" to "https://www.instagram.com",
        "twitter" to "https://x.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://github.com",
        "stackoverflow" to "https://stackoverflow.com",
        "stack overflow" to "https://stackoverflow.com",
        "amazon" to "https://www.amazon.com",
        "flipkart" to "https://www.flipkart.com",
        "netflix" to "https://www.netflix.com",
        "whatsapp" to "https://web.whatsapp.com",
        "telegram" to "https://web.telegram.org",
        "wikipedia" to "https://www.wikipedia.org",
        "spotify" to "https://open.spotify.com",
        "pinterest" to "https://www.pinterest.com",
        "twitch" to "https://www.twitch.tv",
        "discord" to "https://discord.com/app",
        "chatgpt" to "https://chatgpt.com",
        "naukri" to "https://www.naukri.com",
        "indeed" to "https://www.indeed.com"
    )

    /** Sites with a documented search URL. `%s` is the URL-encoded query. */
    private val SEARCH_TEMPLATES: Map<String, String> = mapOf(
        "youtube" to "https://www.youtube.com/results?search_query=%s",
        "yt" to "https://www.youtube.com/results?search_query=%s",
        "google" to AniobIntentResolver.GOOGLE_SEARCH_URL + "%s",
        "amazon" to "https://www.amazon.com/s?k=%s",
        "flipkart" to "https://www.flipkart.com/search?q=%s",
        "wikipedia" to "https://en.wikipedia.org/w/index.php?search=%s",
        "reddit" to "https://www.reddit.com/search/?q=%s",
        "github" to "https://github.com/search?q=%s",
        "linkedin" to "https://www.linkedin.com/search/results/all/?keywords=%s"
    )

    /**
     * The web page [prompt] names, or null when the goal is not a web destination.
     *
     * Order matters: a compound browser phrase first (it overrides an installed app), then a
     * site-scoped search, then — only when [AniobLaunchHub.plan] has no live answer — the fallback
     * for an app that is not installed.
     */
    fun plan(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): WebLaunchPlan? {
        val text = prompt.trim().trimEnd('?', '.', '!', ' ', ',', ';')
        if (text.isEmpty()) return null
        return compound(text, registry)
            ?: siteSearch(text)
            ?: appFallback(text, registry, isLaunchable)
    }

    // "open linkedin in chrome" -> https://www.linkedin.com, preferring Chrome.
    private fun compound(text: String, registry: AniobAppRegistry): WebLaunchPlan? {
        val match = COMPOUND.find(text) ?: return null
        val keyword = cleanName(match.groupValues[1])
        // "my resume" is a personal item, not a site: it never earns a guessed `.com`.
        val personal = PERSONAL_ITEM.containsMatchIn(text)
        val url = urlFor(keyword, allowDotComGuess = !personal) ?: return null
        val browser = browserPackageFor(match.groupValues[2].lowercase(), registry)
        return WebLaunchPlan(url, labelFor(keyword), browser, WebLaunchPlan.SOURCE_COMPOUND)
    }

    // "search for lofi in youtube" / "search in youtube" -> the site's own search page.
    private fun siteSearch(text: String): WebLaunchPlan? {
        val match = SEARCH.find(text) ?: return null
        val site = cleanName(match.groupValues[2])
        val template = SEARCH_TEMPLATES[site] ?: return null
        val query = match.groupValues[1].trim().removePrefix("for ").trim()
        val url = if (query.isEmpty()) {
            KNOWN_SITES[site] ?: return null
        } else {
            template.replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))
        }
        val label = if (query.isEmpty()) labelFor(site) else "Search \"$query\" on ${labelFor(site)}"
        return WebLaunchPlan(url, label, browserPackage = null, source = WebLaunchPlan.SOURCE_SEARCH)
    }

    // "open linkedin" when nothing installed answers to "linkedin".
    private fun appFallback(
        text: String,
        registry: AniobAppRegistry,
        isLaunchable: (String) -> Boolean
    ): WebLaunchPlan? {
        val remainder = LAUNCH_VERB.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val clause = remainder.split(CLAUSE_BREAK, limit = 2).first().trim()
        if (clause.isEmpty()) return null
        // A second goal ("open linkedin and message Sam") cannot end on a page load.
        if (clause.length != remainder.length) return null
        // An installed app, or a question about which one, is never answered with a website.
        if (AniobLaunchHub.plan(text, registry, isLaunchable) != null) return null
        if (registry.resolve(clause) !is AppLookup.Miss) return null
        val name = cleanName(clause)
        val url = urlFor(name, allowDotComGuess = false) ?: return null
        return WebLaunchPlan(url, labelFor(name), browserPackage = null, source = WebLaunchPlan.SOURCE_APP_FALLBACK)
    }

    private fun cleanName(raw: String): String =
        raw.trim().trim('"', '\'').replace(SITE_SUFFIX, "").trim().lowercase()

    private fun urlFor(name: String, allowDotComGuess: Boolean): String? {
        if (name.isEmpty()) return null
        KNOWN_SITES[name]?.let { return it }
        if (DOMAIN.matches(name)) {
            val extension = name.substringBefore('/').substringBefore('?').substringAfterLast('.')
            if (extension !in FILE_EXTENSIONS) return AniobIntentResolver.sanitizeAndValidateUrl(name)
        }
        if (allowDotComGuess && SINGLE_WORD.matches(name)) return "https://www.$name.com"
        return null
    }

    private fun labelFor(name: String): String =
        name.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            if (word.contains('.')) word else word.replaceFirstChar { it.uppercaseChar() }
        }

    private fun browserPackageFor(phrase: String, registry: AniobAppRegistry): String? = when {
        phrase.contains("chrome") ->
            // A cold registry cannot say whether Chrome is installed; the dispatcher falls back to
            // the default browser when the preferred package cannot handle the URL.
            CHROME_PACKAGE.takeIf { registry.isEmpty() || registry.byPackage(CHROME_PACKAGE) != null }
                ?: registry.appForRole(AniobAppRegistry.ROLE_BROWSER)?.packageName
        else -> registry.appForRole(AniobAppRegistry.ROLE_BROWSER)?.packageName
    }
}
