package com.aniob.core.ladder

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppCatalogEntry
import com.aniob.core.knowledge.AppLookup
import com.aniob.core.knowledge.RegisteredApp

/**
 * The one app launch the agent decided to perform, and where the answer came from.
 *
 * [launchActivity] is the fully-qualified `<package>/<package>.Activity` the platform reported for
 * the launcher entry, when one is known. Carrying it means the dispatch can be an **explicit**
 * intent: no chooser, no resolver dialog, and nothing else can answer in the app's place. It is
 * optional because a seed (or an older cache) may not know the component; the launcher then falls
 * back to `getLaunchIntentForPackage`, which is still checked for null before dispatch.
 */
data class LaunchPlan(
    val packageName: String,
    val label: String,
    val launchActivity: String? = null,
    /** [SOURCE_REGISTRY] when a live, launchable app answered; [SOURCE_SEED] for the stock fallback. */
    val source: String = SOURCE_REGISTRY,
    val narration: String? = null
) {
    companion object {
        const val SOURCE_REGISTRY = "registry"
        const val SOURCE_SEED = "seed"
    }
}

/**
 * App launch planning for the ladder — step 0 **and** mid-task.
 *
 * The hub is the single place where "which app did the user name, and is it openable?" is answered
 * without a model. Two callers depend on it:
 *
 *  - [AniobIntentResolver] asks [plan] before it returns any shortcut, so an app shortcut always
 *    names a package and is always marked observable;
 *  - [AniobExecutionRouter] asks [plan] at step 0 and [planMidTask] afterwards, and uses the
 *    per-task memory ([reset] / [noteLaunched] / [wasLaunched]) so a multi-step goal never
 *    re-launches the app it already opened.
 *
 * Rules, each one a defect that was observed in the field:
 *
 *  - the task must **begin** with a launch verb, so a mention inside a sentence never launches;
 *  - the app name must sit at the **start** of the remainder, on a word boundary ("Open Wi-Fi
 *    settings" is a settings sub-screen, not the Settings app);
 *  - the longest name wins, and an equal-length tie is *dropped* — asking is better than guessing;
 *  - an entry the platform says is not launchable is never returned;
 *  - the stock seed is consulted only after the live registry has nothing, and never overrides it.
 *
 * Pure Kotlin/JVM: no Android imports, so the policy is unit-testable.
 */
object AniobLaunchHub {
    private val rolePackages = mapOf(
        "maps" to listOf("com.google.android.apps.maps"),
        "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "browser" to listOf("com.android.chrome"), "settings" to listOf("com.android.settings")
    )
    private var lastChosen: RegisteredApp? = null
    private var lastAlternative: RegisteredApp? = null

    internal fun breakTie(candidates: List<RegisteredApp>, query: String,
        isLaunchable: (String) -> Boolean, registry: AniobAppRegistry): RegisteredApp? {
        val launchable = candidates.distinctBy { it.packageName }.filter { isLaunchable(it.packageName) }
        val role = rolePackages.keys.firstOrNull { query.lowercase(java.util.Locale.ROOT).contains(it) }
        val preferred = listOfNotNull(role?.let(registry::role)) + rolePackages[role].orEmpty()
        preferred.forEach { pkg -> launchable.firstOrNull { it.packageName == pkg }?.let { return it } }
        val system = launchable.filter { it.isSystemApp || it.packageName.startsWith("com.google.android.") || it.packageName.startsWith("com.android.") }
        return system.ifEmpty { launchable }.minWithOrNull(compareBy<RegisteredApp> { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.packageName })
    }

    fun isSwitchToOther(prompt: String): Boolean = prompt.trim().lowercase(java.util.Locale.ROOT) in setOf(
        "use the other one", "switch to the other one", "open the other one", "use the other app")

    private fun ambiguousPlan(candidates: List<RegisteredApp>, query: String,
        isLaunchable: (String) -> Boolean, registry: AniobAppRegistry, source: String): LaunchPlan? {
        val chosen = breakTie(candidates, query, isLaunchable, registry) ?: return null
        lastChosen = chosen
        lastAlternative = candidates.sortedBy { it.packageName }.firstOrNull {
            it.packageName != chosen.packageName && isLaunchable(it.packageName)
        }
        return LaunchPlan(chosen.packageName, chosen.label, chosen.launchActivity, source,
            "Opening ${chosen.label}. Say 'use the other one' to switch.")
    }


    /** `open|launch|start|run|go to|switch to|fire up <name>` with optional politeness/articles. */
    private val LAUNCH_VERB = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:open|launch|start|run|go\\s+to|switch\\s+to|fire\\s+up)\\s+" +
            "(?:the\\s+|my\\s+|your\\s+|our\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Where the app name stops and the rest of the goal begins:
     * "Open Clock **and** set an alarm" → "Clock"; "Launch Calculator**,** then add 2+2" → "Calculator".
     */
    private val CLAUSE_BREAK = Regex(
        "\\s+(?:and\\s+then|and|then|after\\s+that|followed\\s+by)\\s+|[;,]\\s*",
        RegexOption.IGNORE_CASE
    )

    private const val MIN_NAME_LENGTH = 2

    // ------------------------------------------------------------------ #
    // planning
    // ------------------------------------------------------------------ #

    /**
     * The app named at the start of an imperative task, or null.
     *
     * @param prompt the task as typed by the user.
     * @param registry the live registry (defaults to the process singleton).
     * @param isLaunchable the platform's installed-check; a stale cache entry must not dispatch.
     */
    fun plan(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): LaunchPlan? {
        val trimmed = prompt.trim()
        if (isSwitchToOther(trimmed)) {
            val alternative = lastAlternative?.takeIf { isLaunchable(it.packageName) } ?: return null
            lastAlternative = lastChosen
            lastChosen = alternative
            return LaunchPlan(alternative.packageName, alternative.label, alternative.launchActivity,
                narration = "Opening ${alternative.label}.")
        }
        if (trimmed.isEmpty()) return null
        val remainder = LAUNCH_VERB.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        if (remainder.isBlank()) return null

        // 1. The live registry answers with its own tier order (package → label → alias → prefix →
        //    fuzzy) on the app-name clause. Doing it this way — instead of only prefix-scanning —
        //    is what keeps an exact label ahead of a longer derived alias: "Settings Hub" derives
        //    the alias "settings", and it must not tie with the real Settings app for "open settings".
        val clause = stripTrailingClause(remainder)
        when (val direct = registry.resolve(clause)) {
            is AppLookup.Match -> {
                val app = direct.app
                if (!isLaunchable(app.packageName)) return null
                return LaunchPlan(app.packageName, app.label, app.launchActivity, LaunchPlan.SOURCE_REGISTRY)
            }
            // An ambiguity in the live registry is answered with null: falling through to a seed
            // would turn "which Settings?" into a coin flip.
            is AppLookup.Ambiguous -> return ambiguousPlan(direct.candidates, clause, isLaunchable, registry, LaunchPlan.SOURCE_REGISTRY)
            is AppLookup.Miss -> Unit
        }

        // 2. The app name at the *start* of the remainder, longest first ("open Chrome browser"
        //    names Chrome even though "chrome browser" is not an app). A live tie is still a
        //    question, and a live entry the platform rejects is a "no", never a seed lookup.
        when (val live = pickAtStart(remainder, registry.all(), LaunchPlan.SOURCE_REGISTRY, isLaunchable)) {
            is Pick.Hit -> return live.plan
            is Pick.Tied -> return ambiguousPlan(live.candidates, clause, isLaunchable, registry, LaunchPlan.SOURCE_REGISTRY)
            Pick.None -> Unit
        }

        // 3. Stock package ids, only because the registry had nothing that fits. Ties among seeds
        //    (three stock "Clock" packages) are collapsed deterministically by resolveWithSeed,
        //    whose answer is still gated by isLaunchable.
        val seeded = registry.resolveWithSeed(clause)
        if (seeded is AppLookup.Match && isLaunchable(seeded.app.packageName)) {
            return LaunchPlan(seeded.app.packageName, seeded.app.label, seeded.app.launchActivity, LaunchPlan.SOURCE_SEED)
        }
        when (val seedPick = pickAtStart(remainder, AniobAppRegistry.seedApps(), LaunchPlan.SOURCE_SEED, isLaunchable)) {
            is Pick.Hit -> return seedPick.plan
            is Pick.Tied, Pick.None -> Unit
        }
        return null
    }

    /**
     * The web page the task names, when no installed app is the right answer.
     *
     * Covers the compound `open <site> in chrome|browser` (the browser clause wins over an
     * installed app), a site-scoped search, and the web fallback for an app that is **not
     * installed** (`open linkedin` with no LinkedIn app). All three are lookups: a gateway outage
     * must never be the reason these fail. See [AniobWebLaunchPlanner] for the rules.
     */
    fun planWeb(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): WebLaunchPlan? = AniobWebLaunchPlanner.plan(prompt, registry, isLaunchable)

    private val TRAILING_APP = Regex(
        "\\s(?:in|on|using|via)\\s+(?:the\\s+|my\\s+)?([a-z0-9][a-z0-9 .]{1,30}?)(?:\\s+app)?$",
        RegexOption.IGNORE_CASE
    )
    private val SEARCH_IN_APP = Regex(
        "^(?:please\\s+|kindly\\s+)?(?:search|find|look\\s*up)\\s+(?:in|on)\\s+(?:the\\s+)?" +
            "([a-z0-9][a-z0-9 .]{1,30}?)(?:\\s+app)?\\s+for\\s+.+$",
        RegexOption.IGNORE_CASE
    )

    /**
     * The app a single-goal task says it happens in: "search for Jensen in linkedin", "send a
     * connection request to Priya on LinkedIn", "search in YouTube for lofi".
     *
     * [plan] only reads an app named after a launch verb, so these tasks used to start with a model
     * call whose whole job was to open the app. Opening it is a lookup; what happens inside the app
     * is still planned from the observed screen. Returns null for a multi-clause goal (the order of
     * its apps is the planner's business), for an unknown or ambiguous name, and for a package the
     * platform cannot launch.
     */
    fun planInAppTask(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): LaunchPlan? {
        val text = prompt.trim().trimEnd('?', '.', '!', ' ', ',', ';')
        if (text.isEmpty() || CLAUSE_BREAK.containsMatchIn(text)) return null
        val name = (SEARCH_IN_APP.find(text) ?: TRAILING_APP.find(text))?.groupValues?.get(1)?.trim()
            ?: return null
        if (name.length < MIN_NAME_LENGTH) return null
        val app = exactApp(name, registry) ?: return null
        if (!isLaunchable(app.packageName)) return null
        return LaunchPlan(app.packageName, app.label, app.launchActivity, LaunchPlan.SOURCE_REGISTRY)
    }

    /**
     * The one app whose package, label or alias is exactly [name]. A prefix or fuzzy hit is good
     * enough after "open …", where the user is naming an app; it is not good enough for a word that
     * merely follows "in"/"on" ("play it on speaker").
     */
    internal fun exactApp(name: String, registry: AniobAppRegistry): RegisteredApp? {
        val match = registry.resolve(name) as? AppLookup.Match ?: return null
        val exact = match.how == "package" || match.how.startsWith("label") || match.how.startsWith("alias")
        return match.app.takeIf { exact }
    }

    /**
     * An app the goal names later in the task ("… then open Instagram"), skipping the apps this
     * task already opened and the one that is already in front.
     *
     * That skip is what stops a multi-step goal from re-launching its first app in a loop: after
     * step 0 the app in front *is* the app the prompt names first, and it must not be opened again.
     */
    fun planMidTask(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true },
        currentPackage: String? = null,
        exclude: Set<String> = emptySet()
    ): LaunchPlan? {
        if (prompt.isBlank()) return null
        val target = AniobAppLaunchResolver.resolveAnywhere(
            prompt = prompt,
            entries = entriesFor(registry),
            isInstalled = isLaunchable,
            exclude = exclude,
            currentPackage = currentPackage
        ) ?: return null
        val known = registry.byPackage(target.packageName)
        return LaunchPlan(
            packageName = target.packageName,
            label = target.appName,
            launchActivity = known?.launchActivity,
            source = if (known != null) LaunchPlan.SOURCE_REGISTRY else LaunchPlan.SOURCE_SEED
        )
    }

    /**
     * Live entries, or the stock seed when the registry has not been hydrated yet — a cold process
     * must still recognise "then open Instagram" instead of waiting for the platform scan.
     */
    private fun entriesFor(registry: AniobAppRegistry): List<AppCatalogEntry> =
        if (registry.isEmpty()) {
            AniobAppRegistry.seedApps().map {
                AppCatalogEntry(it.packageName, it.label, it.isSystemApp, it.aliases)
            }
        } else {
            AniobAppLaunchResolver.entriesFrom(registry)
        }

    /** Result of matching names at the start of the remainder: hit, equal-length tie, or nothing. */
    private sealed class Pick {
        data class Hit(val plan: LaunchPlan) : Pick()
        data class Tied(val candidates: List<RegisteredApp>) : Pick()
        data object None : Pick()
    }

    private fun pickAtStart(
        remainder: String,
        entries: List<RegisteredApp>,
        source: String,
        isLaunchable: (String) -> Boolean
    ): Pick {
        val lower = remainder.lowercase()
        var best: RegisteredApp? = null
        var bestLength = 0
        val tied = linkedMapOf<String, RegisteredApp>()

        for (entry in entries) {
            if (entry.packageName.isBlank() || entry.label.isBlank()) continue
            for (name in listOf(entry.label) + entry.aliases) {
                val candidate = name.trim().lowercase()
                if (candidate.length < MIN_NAME_LENGTH) continue
                if (!startsWithWord(lower, candidate)) continue
                when {
                    candidate.length > bestLength -> {
                        best = entry
                        bestLength = candidate.length
                        tied.clear()
                        tied[entry.packageName] = entry
                    }
                    candidate.length == bestLength -> tied[entry.packageName] = entry
                }
            }
        }

        val winner = best ?: return Pick.None
        if (tied.size > 1) return Pick.Tied(tied.values.toList())
        if (!isLaunchable(winner.packageName)) return Pick.None
        return Pick.Hit(LaunchPlan(winner.packageName, winner.label, winner.launchActivity, source))
    }

    /**
     * "Clock and set an alarm" → "Clock".
     *
     * Only used for the name lookup fallback (a typo such as "instgram" is a single token that no
     * verbose name starts with), never for dispatch: the resolved package is still checked against
     * the registry and against [plan]'s launchable gate.
     */
    fun stripTrailingClause(remainder: String): String =
        remainder.split(CLAUSE_BREAK, limit = 2).first().trim()

    /**
     * True when opening [packageName] is the **whole** request ("open clock", "launch the Instagram
     * app") and not the first hop of a longer goal ("open WhatsApp and send hi to Mom", "open Chrome
     * browser history").
     *
     * [plan] deliberately answers both shapes, because a multi-step task still starts by opening its
     * app. Anything that wants to *finish* on the launch (the fast lane) must ask this first:
     * the text after the launch verb has to be an app name and nothing else.
     */
    fun isSoleLaunch(
        prompt: String,
        packageName: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance()
    ): Boolean {
        val trimmed = prompt.trim().trimEnd('?', '.', '!', ',', ';', ' ')
        val remainder = LAUNCH_VERB.find(trimmed)?.groupValues?.get(1)?.trim() ?: return false
        if (remainder.isBlank() || stripTrailingClause(remainder) != remainder) return false
        val withoutAppWord = remainder.replace(APP_WORD_SUFFIX, "").trim()
        return listOf(remainder, withoutAppWord).distinct().filter { it.isNotBlank() }.any { name ->
            val found = registry.resolveWithSeed(name)
            found is AppLookup.Match && found.app.packageName.equals(packageName, ignoreCase = true)
        }
    }

    private val APP_WORD_SUFFIX = Regex("\\s+app(?:lication)?$", RegexOption.IGNORE_CASE)

    /** True when [text] starts with [word] and the next character is not a letter or digit. */
    private fun startsWithWord(text: String, word: String): Boolean {
        if (!text.startsWith(word)) return false
        if (text.length == word.length) return true
        return !text[word.length].isLetterOrDigit()
    }

    // ------------------------------------------------------------------ #
    // per-task launch memory
    // ------------------------------------------------------------------ #

    private val launched = LinkedHashSet<String>()
    private var taskSignature: String? = null

    /**
     * Starts a new task. Calling it again with the same signature is a no-op, so the router can
     * (and does) call it on every planning round without forgetting what this task already opened.
     */
    @Synchronized
    fun reset(signature: String) {
        if (signature == taskSignature) return
        launched.clear()
        taskSignature = signature
    }

    @Synchronized
    fun noteLaunched(packageName: String) {
        if (packageName.isNotBlank()) launched += packageName
    }

    @Synchronized
    fun wasLaunched(packageName: String): Boolean = launched.contains(packageName)

    /** Apps this task has already opened, for a caller that needs to exclude them explicitly. */
    @Synchronized
    fun launchedPackages(): Set<String> = LinkedHashSet(launched)

    @Synchronized
    fun clearMemory() {
        launched.clear()
        taskSignature = null
        lastChosen = null
        lastAlternative = null
    }

    // ------------------------------------------------------------------ #
    // verification & diagnostics
    // ------------------------------------------------------------------ #

    /**
     * A launch is only done when the screen afterwards belongs to the app we asked for. A dispatch
     * that "succeeded" but foregrounded something else (a chooser, a splash of another app, the
     * launcher) is not success.
     */
    fun verifyForeground(expectedPackage: String, observedPackage: String?): Boolean =
        !observedPackage.isNullOrBlank() && observedPackage.equals(expectedPackage, ignoreCase = true)

    fun verifyForeground(expectedPackage: String, observed: AniobScreenState): Boolean =
        verifyForeground(expectedPackage, observed.packageName)

    /** `<pkg>/<pkg>.Activity` → `<pkg>.Activity`, the form `Intent.setClassName` expects. */
    fun activityClassName(launchActivity: String?): String? =
        launchActivity?.substringAfter('/', "")?.trim()?.ifBlank { null }

    fun describe(plan: LaunchPlan?): String = when {
        plan == null -> "No app named at the start of the task"
        plan.source == LaunchPlan.SOURCE_SEED ->
            "Opening ${plan.label} (${plan.packageName}) from stock seeds — no model call"
        else -> "Opening ${plan.label} (${plan.packageName}) — resolved from the installed-app registry"
    }
}
