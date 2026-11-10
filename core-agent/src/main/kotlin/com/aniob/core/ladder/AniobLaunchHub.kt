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
    val narration: String? = null,
    val alternativePackage: String? = null
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

    val userAppPreferences = LinkedHashMap<String, String>()
    @Volatile var lastAmbiguityQuery: String? = null
    @Volatile var lastAlternativeApp: RegisteredApp? = null
    @Volatile var lastChosenApp: RegisteredApp? = null

    fun isSwitchToOther(prompt: String): Boolean {
        val lower = prompt.trim().lowercase(java.util.Locale.ROOT)
        return lower in setOf(
            "use the other one",
            "switch to the other one",
            "open the other one",
            "use other one",
            "the other one",
            "switch app",
            "open the other app",
            "use the other app"
        )
    }

    fun resetPreferences() {
        userAppPreferences.clear()
        lastAmbiguityQuery = null
        lastAlternativeApp = null
        lastChosenApp = null
    }

    /**
     * Deterministic tie-breaker for ambiguous app candidates:
     * 1. Prefer the app designed for the curated role if known.
     * 2. Prefer system / first-party packages over third-party alternatives.
     * 3. Fall back to alphabetical order so the choice is stable and predictable.
     */
    fun breakTie(
        candidates: List<RegisteredApp>,
        roleHint: String? = null,
        isLaunchable: (String) -> Boolean = { true },
        registry: AniobAppRegistry = AniobAppRegistry.getInstance()
    ): RegisteredApp? {
        val launchable = candidates.filter { isLaunchable(it.packageName) }
        if (launchable.isEmpty()) return null
        if (launchable.size == 1) return launchable.first()

        val roleKey = roleHint?.lowercase(java.util.Locale.ROOT)
        if (roleKey != null) {
            val prefPkg = userAppPreferences[roleKey]
            if (prefPkg != null) {
                launchable.firstOrNull { it.packageName == prefPkg }?.let { return it }
            }
        }

        // 1. Curated role
        val rolePkg = if (roleKey != null) registry.role(roleKey) else null
        if (rolePkg != null) {
            launchable.firstOrNull { it.packageName == rolePkg }?.let { return it }
        }
        val roleMatches = launchable.filter { app ->
            registry.roles().values.contains(app.packageName)
        }
        val pool1 = if (roleMatches.isNotEmpty()) roleMatches else launchable

        // 2. System / first-party packages over third-party alternatives
        val systemApps = pool1.filter { app ->
            app.isSystemApp ||
                app.packageName.startsWith("com.google.android.") ||
                app.packageName.startsWith("com.android.")
        }
        val pool2 = if (systemApps.isNotEmpty()) systemApps else pool1

        // 3. Alphabetical order by label, then packageName
        return pool2.minWithOrNull(
            compareBy<RegisteredApp> { it.label.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.packageName }
        )
    }

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
        if (trimmed.isEmpty()) return null

        if (isSwitchToOther(trimmed)) {
            val alt = lastAlternativeApp
            if (alt != null && isLaunchable(alt.packageName)) {
                val query = lastAmbiguityQuery
                if (query != null) {
                    userAppPreferences[query.lowercase(java.util.Locale.ROOT)] = alt.packageName
                }
                val prevChosen = lastChosenApp
                lastChosenApp = alt
                lastAlternativeApp = prevChosen
                return LaunchPlan(
                    packageName = alt.packageName,
                    label = alt.label,
                    launchActivity = alt.launchActivity,
                    source = LaunchPlan.SOURCE_REGISTRY,
                    narration = "Opening ${alt.label}."
                )
            }
        }

        val remainder = LAUNCH_VERB.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        if (remainder.isBlank()) return null

        // 1. The live registry answers with its own tier order (package → label → alias → prefix →
        //    fuzzy) on the app-name clause.
        val clause = stripTrailingClause(remainder)
        when (val direct = registry.resolve(clause)) {
            is AppLookup.Match -> {
                val app = direct.app
                if (!isLaunchable(app.packageName)) return null
                return LaunchPlan(app.packageName, app.label, app.launchActivity, LaunchPlan.SOURCE_REGISTRY)
            }
            is AppLookup.Ambiguous -> {
                val chosen = breakTie(direct.candidates, roleHint = clause, isLaunchable = isLaunchable, registry = registry)
                if (chosen != null) {
                    val other = direct.candidates.firstOrNull { it.packageName != chosen.packageName && isLaunchable(it.packageName) }
                    lastAmbiguityQuery = clause
                    lastChosenApp = chosen
                    lastAlternativeApp = other
                    val narration = "Opening ${chosen.label}. Say 'use the other one' to switch"
                    return LaunchPlan(
                        packageName = chosen.packageName,
                        label = chosen.label,
                        launchActivity = chosen.launchActivity,
                        source = LaunchPlan.SOURCE_REGISTRY,
                        narration = narration,
                        alternativePackage = other?.packageName
                    )
                }
                return null
            }
            is AppLookup.Miss -> Unit
        }

        // 2. The app name at the *start* of the remainder, longest first.
        when (val live = pickAtStart(remainder, registry.all(), LaunchPlan.SOURCE_REGISTRY, isLaunchable, roleHint = clause, registry = registry)) {
            is Pick.Hit -> return live.plan
            Pick.Tied, Pick.None -> Unit
        }

        // 3. Stock package ids, only because the registry had nothing that fits.
        val seeded = registry.resolveWithSeed(clause)
        if (seeded is AppLookup.Match && isLaunchable(seeded.app.packageName)) {
            return LaunchPlan(seeded.app.packageName, seeded.app.label, seeded.app.launchActivity, LaunchPlan.SOURCE_SEED)
        }
        when (val seedPick = pickAtStart(remainder, AniobAppRegistry.seedApps(), LaunchPlan.SOURCE_SEED, isLaunchable, roleHint = clause, registry = registry)) {
            is Pick.Hit -> return seedPick.plan
            Pick.Tied, Pick.None -> Unit
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
        data object Tied : Pick()
        data object None : Pick()
    }

    private fun pickAtStart(
        remainder: String,
        entries: List<RegisteredApp>,
        source: String,
        isLaunchable: (String) -> Boolean,
        roleHint: String? = null,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance()
    ): Pick {
        val lower = remainder.lowercase()
        val matchingEntries = mutableListOf<Pair<RegisteredApp, Int>>()

        for (entry in entries) {
            if (entry.packageName.isBlank() || entry.label.isBlank()) continue
            for (name in listOf(entry.label) + entry.aliases) {
                val candidate = name.trim().lowercase()
                if (candidate.length < MIN_NAME_LENGTH) continue
                if (!startsWithWord(lower, candidate)) continue
                matchingEntries.add(entry to candidate.length)
            }
        }

        if (matchingEntries.isEmpty()) return Pick.None
        val maxLength = matchingEntries.maxOf { it.second }
        val tiedCandidates = matchingEntries
            .filter { it.second == maxLength }
            .map { it.first }
            .distinctBy { it.packageName }
            .filter { isLaunchable(it.packageName) }

        if (tiedCandidates.isEmpty()) return Pick.None
        if (tiedCandidates.size == 1) {
            val winner = tiedCandidates.first()
            return Pick.Hit(LaunchPlan(winner.packageName, winner.label, winner.launchActivity, source))
        }

        val chosen = breakTie(tiedCandidates, roleHint = roleHint, isLaunchable = isLaunchable, registry = registry)
        if (chosen != null) {
            val other = tiedCandidates.firstOrNull { it.packageName != chosen.packageName }
            lastAmbiguityQuery = roleHint
            lastChosenApp = chosen
            lastAlternativeApp = other
            val narration = "Opening ${chosen.label}. Say 'use the other one' to switch"
            return Pick.Hit(
                LaunchPlan(
                    packageName = chosen.packageName,
                    label = chosen.label,
                    launchActivity = chosen.launchActivity,
                    source = source,
                    narration = narration,
                    alternativePackage = other?.packageName
                )
            )
        }
        return Pick.None
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
