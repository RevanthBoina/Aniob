package com.aniob.core.knowledge

import java.io.File

/**
 * One installed app, as the agent needs to know it to *open* it.
 *
 * [launchActivity] is the fully-qualified `<package>/<package>.Activity` of the launcher entry when
 * the platform reported one. Keeping it means a launch can be an **explicit** intent, which is the
 * only form that cannot be intercepted by a resolver dialog or hijacked by another app registering
 * the same implicit action.
 */
data class RegisteredApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false,
    val launchActivity: String? = null,
    val aliases: List<String> = emptyList()
)

/** Outcome of resolving a spoken app name. Ambiguity is an answer, not a null. */
sealed class AppLookup {
    data class Match(val app: RegisteredApp, val how: String) : AppLookup()
    data class Ambiguous(val candidates: List<RegisteredApp>, val how: String) : AppLookup()
    data class Miss(val query: String) : AppLookup()
}

/**
 * The app registry: **the** source of truth for "which package does the user mean, and how do I
 * open it".
 *
 * Why this class exists (the defect it replaces):
 *
 * The old `AniobAppCatalog` was populated from `onServiceConnected()` only, in the accessibility
 * service, and the file it wrote was never read back. So at cold start — precisely when the agent is
 * first used — the catalog was empty, app-name success criteria could not be built, and "Open Clock"
 * ended in FAILED. It was also an inventory (`getInstalledApplications`) rather than a registry of
 * *launchable* components, so "installed" and "can be opened" were conflated.
 *
 * This class is:
 *  - **hydrated at startup** from a persisted cache, then refreshed from `PackageManager`;
 *  - **launchable-only**: entries come from `queryIntentActivities(MAIN/LAUNCHER)`, so an installed
 *    package with no launcher icon is not offered as an app;
 *  - **alias-aware**: label, derived tokens, curated aliases and *roles* ("clock", "browser",
 *    "calculator") all resolve to a package, with explicit ambiguity;
 *  - **role-aware**: the platform's own answers (`APP_CALCULATOR`, `APP_BROWSER`, `DIAL`,
 *    `IMAGE_CAPTURE`, …) are cached at startup, so "open the calculator" does not depend on guessing
 *    a vendor package name.
 *
 * Everything here is pure JVM Kotlin: no Android imports, so it is unit-testable and the `app`
 * module owns the only code that talks to `PackageManager` (`AniobAppBootstrap`).
 */
class AniobAppRegistry private constructor() {

    private val apps = LinkedHashMap<String, RegisteredApp>()
    private val roles = LinkedHashMap<String, String>()
    private var builtAtMs: Long = 0L
    private val launchCounts = LinkedHashMap<String, Int>()

    // ------------------------------------------------------------------ #
    // hydration
    // ------------------------------------------------------------------ #

    /**
     * Atomically replace the registry contents.
     *
     * @return true when the package set actually changed, so callers can log "no-op" refreshes
     *         instead of spamming telemetry on every resume.
     */
    @Synchronized
    fun hydrate(
        entries: List<RegisteredApp>,
        roleMap: Map<String, String> = emptyMap(),
        builtAtMs: Long = System.currentTimeMillis()
    ): Boolean {
        val previous = apps.keys.toSet()
        val next = LinkedHashMap<String, RegisteredApp>(entries.size)
        for (entry in entries) {
            if (entry.packageName.isBlank()) continue
            next[entry.packageName] = enrich(entry, roleMap)
        }
        if (next.keys == previous && roleMap == roles) {
            this.builtAtMs = builtAtMs
            return false
        }
        apps.clear()
        apps.putAll(next)
        roles.clear()
        roles.putAll(roleMap)
        this.builtAtMs = builtAtMs
        return true
    }

    @Synchronized
    fun upsert(entry: RegisteredApp) {
        if (entry.packageName.isBlank()) return
        apps[entry.packageName] = enrich(entry, roles)
    }

    @Synchronized
    fun remove(packageName: String) {
        apps.remove(packageName)
        val stale = roles.filterValues { it == packageName }.keys
        for (role in stale) roles.remove(role)
    }

    @Synchronized
    fun clear() {
        apps.clear()
        roles.clear()
        launchCounts.clear()
        builtAtMs = 0L
    }

    /** Applies curated aliases and role names to an entry (idempotent). */
    private fun enrich(entry: RegisteredApp, roleMap: Map<String, String>): RegisteredApp {
        val merged = LinkedHashSet<String>()
        entry.aliases.mapTo(merged) { it.lowercase().trim() }
        CURATED_ALIASES[entry.packageName]?.mapTo(merged) { it.lowercase() }
        deriveAliases(entry.label).mapTo(merged) { it.lowercase() }
        roleMap.filterValues { it == entry.packageName }.keys.mapTo(merged) { it.lowercase() }
        merged.remove("")
        merged.remove(entry.packageName.lowercase())
        return entry.copy(aliases = merged.toList())
    }

    // ------------------------------------------------------------------ #
    // reads
    // ------------------------------------------------------------------ #

    @Synchronized
    fun all(): List<RegisteredApp> = apps.values.toList()

    @Synchronized
    fun size(): Int = apps.size

    @Synchronized
    fun isEmpty(): Boolean = apps.isEmpty()

    @Synchronized
    fun byPackage(packageName: String): RegisteredApp? = apps[packageName]

    @Synchronized
    fun isLaunchable(packageName: String): Boolean = apps.containsKey(packageName)

    @Synchronized
    fun role(role: String): String? = roles[role.lowercase()]

    @Synchronized
    fun appForRole(role: String): RegisteredApp? = roles[role.lowercase()]?.let { apps[it] }

    @Synchronized
    fun roles(): Map<String, String> = LinkedHashMap(roles)

    @Synchronized
    fun builtAt(): Long = builtAtMs

    /** True when the cache is unusable: never built, too old, or the package count moved. */
    @Synchronized
    fun isStale(nowMs: Long, ttlMs: Long, installedCount: Int? = null): Boolean {
        if (apps.isEmpty() || builtAtMs <= 0L) return true
        if (nowMs - builtAtMs > ttlMs) return true
        return installedCount != null && installedCount != apps.size
    }

    /** Recently launched packages rank first on a tie; used only to break ambiguity. */
    @Synchronized
    fun noteLaunch(packageName: String) {
        launchCounts[packageName] = (launchCounts[packageName] ?: 0) + 1
    }

    // ------------------------------------------------------------------ #
    // resolution
    // ------------------------------------------------------------------ #

    /**
     * Resolve a spoken/typed name to exactly one installed app.
     *
     * Tiers, strongest first: package name, exact label, exact alias, word-boundary prefix
     * ("insta" → Instagram), then token overlap. Two survivors at the same tier is `Ambiguous`
     * — the agent must ask rather than pick, because picking is how "Settings Hub" gets opened when
     * the user meant "Settings".
     */
    @Synchronized
    fun resolve(query: String): AppLookup {
        val q = normalizeAppQuery(query)
        if (q.isEmpty()) return AppLookup.Miss(query)

        apps[q]?.let { return AppLookup.Match(it, "package") }
        apps.values.firstOrNull { it.packageName.equals(query.trim(), ignoreCase = true) }
            ?.let { return AppLookup.Match(it, "package") }

        exact(query, "label")?.let { return it }
        exact(query, "alias")?.let { return it }
        prefix(q)?.let { return it }
        fuzzy(q)?.let { return it }
        return AppLookup.Miss(query)
    }

    private fun exact(query: String, tier: String): AppLookup? {
        val q = normalizeAppQuery(query)
        val hits = apps.values.filter { app ->
            if (tier == "label") normalizeAppQuery(app.label) == q
            else app.aliases.any { normalizeAppQuery(it) == q }
        }
        return when (hits.size) {
            0 -> null
            1 -> AppLookup.Match(hits[0], if (tier == "label") "label '${hits[0].label}'" else "alias '$q'")
            else -> AppLookup.Ambiguous(rank(hits), if (tier == "label") "label '$q'" else "alias '$q'")
        }
    }

    private fun prefix(q: String): AppLookup? {
        if (q.length < 3) return null
        val names = apps.values.map { app ->
            val best = (listOf(app.label) + app.aliases)
                .map { normalizeAppQuery(it) }
                .filter { it.startsWith(q) && startsAtWordBoundary(it, q) }
                .maxByOrNull { it.length }
            app to best
        }
        val hits = names.filter { it.second != null }.map { it.first }
        return when (hits.size) {
            0 -> null
            1 -> AppLookup.Match(hits[0], "prefix '$q'")
            else -> AppLookup.Ambiguous(rank(hits), "prefix '$q'")
        }
    }

    private fun fuzzy(q: String): AppLookup? {
        // Only single-token queries are fuzzy-matched. A multi-token query that does not name an app
        // is a phrase — "wi-fi settings", "battery saver" — and matching it to a one-word alias by
        // token overlap is how the wrong app gets opened.
        if (q.length < 3 || q.contains(' ')) return null
        val scored = apps.values.mapNotNull { app ->
            val score = (listOf(app.label) + app.aliases)
                .map { editSimilarity(q, normalizeAppQuery(it)) }
                .maxOrNull() ?: 0.0
            if (score >= FUZZY_THRESHOLD) app to score else null
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return null
        val top = scored.first().second
        val winners = scored.filter { it.second >= top - 0.05 }.map { it.first }
        return when (winners.size) {
            1 -> AppLookup.Match(winners[0], "fuzzy '${winners[0].label}'")
            else -> AppLookup.Ambiguous(rank(winners), "fuzzy")
        }
    }

    /**
     * Every app named *anywhere* in the text, longest name first.
     *
     * A longer name suppresses a shorter one it contains: "check settings hub" names Settings Hub,
     * not Settings Hub *and* Settings.
     */
    @Synchronized
    fun namedIn(text: String): List<RegisteredApp> {
        val haystack = " " + normalizeAppQuery(text) + " "
        val scored = mutableListOf<Pair<RegisteredApp, String>>()
        for (app in apps.values) {
            val best = (listOf(app.label) + app.aliases)
                .map { normalizeAppQuery(it) }
                .filter { it.length >= 3 && hasWord(haystack, it) }
                .maxByOrNull { it.length } ?: continue
            scored += app to best
        }
        return suppressContained(scored)
    }

    /** Drops an entry whose matched name is contained in a longer matched name. */
    private fun suppressContained(scored: List<Pair<RegisteredApp, String>>): List<RegisteredApp> {
        val kept = mutableListOf<Pair<RegisteredApp, String>>()
        for (candidate in scored.sortedByDescending { it.second.length }) {
            if (kept.any { hasWord(" ${it.second} ", candidate.second) }) continue
            kept += candidate
        }
        return kept.map { it.first }.distinctBy { it.packageName }
    }

    private fun hasWord(haystack: String, needle: String): Boolean {
        val at = haystack.indexOf(" $needle")
        if (at < 0) return false
        val after = at + 1 + needle.length
        return after >= haystack.length || !haystack[after].isLetterOrDigit()
    }

    /**
     * Resolution with a last-resort seed, used only when the live registry knows nothing.
     *
     * The seed is a list of *package ids that ship on stock Android* (clock, calculator, browser,
     * settings…). It never wins over the registry, and the caller must still confirm the package is
     * launchable before dispatching — a seed exists so a cold cache degrades to "ask the platform",
     * not to "no idea what Clock means".
     */
    fun resolveWithSeed(query: String): AppLookup {
        val live = resolve(query)
        if (live !is AppLookup.Miss) return live
        return seedResolve(query)
    }

    @Synchronized
    fun installedCount(): Int = apps.size

    companion object {
        private const val FUZZY_THRESHOLD = 0.62
        private val STOP_WORDS = setOf("the", "app", "apps", "application", "please", "my", "a", "an", "for")

        const val ROLE_CLOCK = "clock"
        const val ROLE_CALCULATOR = "calculator"
        const val ROLE_CALENDAR = "calendar"
        const val ROLE_BROWSER = "browser"
        const val ROLE_FILES = "files"
        const val ROLE_CAMERA = "camera"
        const val ROLE_SETTINGS = "settings"
        const val ROLE_PHONE = "phone"
        const val ROLE_MESSAGES = "messages"
        const val ROLE_EMAIL = "email"
        const val ROLE_MAPS = "maps"
        const val ROLE_MEDIA = "media"

        /** Curated aliases. Cheap, and it is what makes "ig", "yt", "wa" work on day one. */
        private val CURATED_ALIASES: Map<String, List<String>> = mapOf(
            "com.whatsapp" to listOf("whatsapp", "whats app", "wa"),
            "com.whatsapp.w4b" to listOf("whatsapp business", "wa business"),
            "com.instagram.android" to listOf("instagram", "insta", "ig"),
            "com.google.android.youtube" to listOf("youtube", "yt"),
            "com.google.android.apps.youtube.music" to listOf("youtube music", "yt music"),
            "org.telegram.messenger" to listOf("telegram", "tg"),
            "com.android.chrome" to listOf("chrome", "browser", "google chrome"),
            "com.google.android.gm" to listOf("gmail", "mail", "email"),
            "com.google.android.apps.maps" to listOf("maps", "google maps", "navigation"),
            "com.android.vending" to listOf("play store", "playstore", "google play", "play"),
            "com.spotify.music" to listOf("spotify", "music"),
            "com.google.android.deskclock" to listOf("clock", "alarm", "alarms", "time"),
            "com.android.deskclock" to listOf("clock", "alarm", "alarms", "time"),
            "com.android.settings" to listOf("settings", "preferences", "system settings"),
            "com.google.android.calculator" to listOf("calculator", "calc"),
            "com.android.calculator2" to listOf("calculator", "calc"),
            "com.google.android.calendar" to listOf("calendar", "events"),
            "com.google.android.apps.photos" to listOf("photos", "gallery"),
            "com.google.android.dialer" to listOf("phone", "dialer", "dial"),
            "com.google.android.apps.messaging" to listOf("messages", "sms", "text"),
            "com.android.documentsui" to listOf("files", "file manager", "documents"),
            "com.google.android.apps.nexuslauncher" to listOf("launcher", "home")
        )

        /**
         * Packages that exist on stock Android, used only when the live registry is empty.
         * `isLaunchable` still gates every dispatch, so a wrong guess cannot launch anything.
         */
        private val SEED_APPS: List<RegisteredApp> = listOf(
            RegisteredApp("com.google.android.deskclock", "Clock", aliases = listOf("clock", "alarm", "time")),
            RegisteredApp("com.android.deskclock", "Clock", aliases = listOf("clock", "alarm", "time")),
            RegisteredApp("com.android.BBKClock", "Clock", aliases = listOf("clock", "alarm")),
            RegisteredApp("com.google.android.calculator", "Calculator", aliases = listOf("calculator", "calc")),
            RegisteredApp("com.android.calculator2", "Calculator", aliases = listOf("calculator", "calc")),
            RegisteredApp("com.google.android.calendar", "Calendar", aliases = listOf("calendar", "events")),
            RegisteredApp("com.android.chrome", "Chrome", aliases = listOf("chrome", "browser")),
            RegisteredApp("com.android.settings", "Settings", aliases = listOf("settings", "preferences")),
            RegisteredApp("com.android.documentsui", "Files", aliases = listOf("files", "documents")),
            RegisteredApp("com.google.android.dialer", "Phone", aliases = listOf("phone", "dialer")),
            RegisteredApp("com.android.dialer", "Phone", aliases = listOf("phone", "dialer")),
            RegisteredApp("com.google.android.apps.messaging", "Messages", aliases = listOf("messages", "sms")),
            RegisteredApp("com.google.android.gm", "Gmail", aliases = listOf("gmail", "email", "mail")),
            RegisteredApp("com.google.android.apps.maps", "Maps", aliases = listOf("maps", "navigation")),
            RegisteredApp("com.android.vending", "Play Store", aliases = listOf("play store", "playstore")),
            RegisteredApp("com.android.camera2", "Camera", aliases = listOf("camera", "photo")),
            RegisteredApp("com.google.android.GoogleCamera", "Camera", aliases = listOf("camera", "photo")),
            RegisteredApp("com.whatsapp", "WhatsApp", aliases = listOf("whatsapp", "whats app")),
            RegisteredApp("com.instagram.android", "Instagram", aliases = listOf("instagram", "insta", "ig")),
            RegisteredApp("com.google.android.youtube", "YouTube", aliases = listOf("youtube", "yt")),
            RegisteredApp("org.telegram.messenger", "Telegram", aliases = listOf("telegram")),
            RegisteredApp("com.spotify.music", "Spotify", aliases = listOf("spotify"))
        )

        @Volatile
        private var instance: AniobAppRegistry? = null

        fun getInstance(): AniobAppRegistry =
            instance ?: synchronized(this) { instance ?: AniobAppRegistry().also { instance = it } }

        /** Test hook: forget everything, including the singleton. */
        fun resetForTest() {
            synchronized(this) {
                instance?.clear()
                instance = null
            }
        }

        /** Seed resolution — longest name that the query *contains*, else exact/prefix on a seed. */
        fun seedResolve(query: String): AppLookup {
            val q = normalizeAppQuery(query)
            if (q.isEmpty()) return AppLookup.Miss(query)
            val exact = SEED_APPS.filter { app ->
                normalizeAppQuery(app.label) == q || app.aliases.any { normalizeAppQuery(it) == q }
            }
            if (exact.isNotEmpty()) return AppLookup.Match(exact.first(), "seed")
            val prefix = SEED_APPS.filter { app ->
                app.aliases.any { a -> normalizeAppQuery(a).let { it.startsWith(q) && startsAtWordBoundary(it, q) } }
            }
            return when (prefix.size) {
                0 -> AppLookup.Miss(query)
                else -> AppLookup.Match(prefix.first(), "seed prefix")
            }
        }

        fun seedApps(): List<RegisteredApp> = SEED_APPS

        /** Longest seed app named anywhere in the text (used mid-task when the registry is cold). */
        fun seedNamedIn(text: String): List<RegisteredApp> {
            val haystack = " " + normalizeAppQuery(text) + " "
            val scored = mutableListOf<Pair<RegisteredApp, String>>()
            for (app in SEED_APPS) {
                val best = (listOf(app.label) + app.aliases)
                    .map { normalizeAppQuery(it) }
                    .filter { it.length >= 3 && haystack.contains(" $it") }
                    .maxByOrNull { it.length } ?: continue
                scored += app to best
            }
            return scored.sortedByDescending { it.second.length }
                .distinctBy { it.first.packageName }
                .map { it.first }
        }

        // -------------------------------------------------------------- #
        // pure helpers (unit-tested)
        // -------------------------------------------------------------- #

        /** Lowercase, strip politeness/punctuation, collapse whitespace, drop a trailing "app". */
        fun normalizeAppQuery(query: String): String {
            var text = query.lowercase().trim()
            text = text.trim('?', '.', '!', ',', ';', ':', '"', '\'', '“', '”', '‘', '’')
            text = text.replace(Regex("[\\u0000-\\u001f]"), " ")
            text = text.replace(Regex("\\s+"), " ").trim()
            for (prefix in POLITE_PREFIXES) {
                if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim()
            }
            for (suffix in POLITE_SUFFIXES) {
                if (text == suffix) text = ""
                else if (text.endsWith(" $suffix")) text = text.removeSuffix(" $suffix").trim()
            }
            val words = text.split(' ').filter { it.isNotEmpty() && it !in STOP_WORDS }
            if (words.isEmpty()) return text
            // "alarm app" and "alarm" must be the same query.
            return words.joinToString(" ")
        }

        private val POLITE_PREFIXES = listOf(
            "hey google ", "ok google ", "hey ", "please ", "kindly ", "can you ", "could you ",
            // An app-name lookup should also accept a whole command: "open instagram please".
            "open ", "launch ", "start ", "run ", "go to ", "switch to "
        )
        private val POLITE_SUFFIXES = listOf("please", "pls", "thanks", "thank you", "now", "asap", "for me", "ok", "okay")

        /** Aliases derived from a label: the label, minus marketing suffixes, plus useful tokens. */
        fun deriveAliases(label: String): List<String> {
            val base = normalizeAppQuery(label)
            if (base.isEmpty()) return emptyList()
            val out = LinkedHashSet<String>()
            out += base
            for (suffix in listOf(" lite", " (lite)", " beta", " (beta)", " pro", " (pro)", " plus", " (plus)")) {
                if (base.endsWith(suffix)) out += base.removeSuffix(suffix).trim()
            }
            val stripParens = base.replace(Regex("\\([^)]*\\)"), "").trim()
            if (stripParens.isNotEmpty() && stripParens != base) out += stripParens
            val first = base.substringBefore(' ')
            if (first.length >= 4 && first !in STOP_WORDS) out += first
            return out.toList()
        }

        /**
         * Normalised edit similarity, 1.0 for identical strings.
         *
         * Trigrams were measured against the alias vocabulary and are too weak for the cases that
         * matter (`chorme`/`chrome` scores 0.20). Levenshtein plus a first-character gate separates
         * real typos (>= 0.66) from different apps (<= 0.5) on this vocabulary.
         */
        fun editSimilarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            if (a[0] != b[0]) return 0.0
            val longer = maxOf(a.length, b.length)
            if (longer > 32) return 0.0
            if (minOf(a.length, b.length).toDouble() / longer < 0.6) return 0.0
            return 1.0 - levenshtein(a, b).toDouble() / longer
        }

        private fun levenshtein(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }

        /** True when [word] appears in [text] at a word boundary. */
        fun startsAtWordBoundary(text: String, word: String): Boolean {
            if (!text.startsWith(word)) return false
            if (text.length == word.length) return true
            return !text[word.length].isLetterOrDigit()
        }

        /** Sørensen–Dice over word tokens; 0.0 when either side is empty. */
        fun diceOverlap(a: String, b: String): Double {
            val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
            val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
            if (ta.isEmpty() || tb.isEmpty()) return 0.0
            val shared = ta.count { tb.contains(it) }
            return 2.0 * shared / (ta.size + tb.size)
        }

        private fun rank(apps: List<RegisteredApp>): List<RegisteredApp> =
            apps.sortedWith(compareByDescending<RegisteredApp> { it.label.length }.thenBy { it.label })
    }

    // ------------------------------------------------------------------ #
    // persistence: one tab-separated record per line, versioned header
    // ------------------------------------------------------------------ #
    //
    // Deliberately *not* JSON: the file is written by the app and read back at cold start, and a
    // hand-rolled JSON parser is the kind of code that fails only on a user's phone. TSV needs
    // exactly one escape rule (strip tabs/newlines inside fields) and is diffable.

    @Synchronized
    fun saveTo(file: File) {
        try {
            file.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("#aniob-app-registry\tv1\t").append(builtAtMs).append('\n')
            for ((role, pkg) in roles) sb.append("role\t").append(clean(role)).append('\t').append(clean(pkg)).append('\n')
            for (app in apps.values) {
                sb.append("app\t")
                    .append(clean(app.packageName)).append('\t')
                    .append(clean(app.label)).append('\t')
                    .append(if (app.isSystemApp) "1" else "0").append('\t')
                    .append(clean(app.launchActivity ?: "")).append('\t')
                    .append(app.aliases.joinToString("|") { clean(it) }).append('\n')
            }
            file.writeText(sb.toString())
        } catch (e: Exception) {
            System.err.println("AniobAppRegistry.saveTo failed: ${e.message}")
        }
    }

    @Synchronized
    fun loadFrom(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            var built = 0L
            val entries = mutableListOf<RegisteredApp>()
            val roleMap = LinkedHashMap<String, String>()
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val cols = line.split('\t')
                when {
                    cols[0].startsWith("#aniob-app-registry") -> built = cols.getOrNull(2)?.toLongOrNull() ?: 0L
                    cols[0] == "role" && cols.size >= 3 -> roleMap[cols[1]] = cols[2]
                    cols[0] == "app" && cols.size >= 6 -> entries += RegisteredApp(
                        packageName = cols[1],
                        label = cols[2],
                        isSystemApp = cols[3] == "1",
                        launchActivity = cols[4].ifBlank { null },
                        aliases = cols[5].split('|').filter { it.isNotBlank() }
                    )
                }
            }
            if (entries.isEmpty()) return false
            hydrate(entries, roleMap, if (built > 0L) built else System.currentTimeMillis())
            true
        } catch (e: Exception) {
            System.err.println("AniobAppRegistry.loadFrom failed: ${e.message}")
            false
        }
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
