package com.aniob.core.ladder

import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppLookup

/**
 * A zero-token way to satisfy a goal.
 *
 * [requiresObservation] is the field that fixes the "Open Clock" bug: a shortcut that opens an app
 * can only be called done by looking at the screen afterwards, so it must never be turned into a
 * `DirectIntent` (which finishes without a post-condition and is therefore reported as FAILED).
 * Reflex shortcuts — flashlight, battery, dial, open URL — are the opposite: their effect *is* the
 * dispatch, and they may finish immediately.
 */
data class ResolvedIntentShortcut(
    val action: String,
    val targetPackage: String? = null,
    val uriString: String? = null,
    val category: String? = null,
    val extras: Map<String, String> = emptyMap(),
    /** `Intent.setType` — the contact insert intent is addressed by its MIME type. */
    val dataType: String? = null,
    /** Fully-qualified `<pkg>/<pkg>.Activity` when the registry knew it; explicit beats implicit. */
    val launchActivity: String? = null,
    /** True when the only honest way to confirm this is to observe the screen after dispatch. */
    val requiresObservation: Boolean = false,
    /** App label for the transcript ("Opening Clock…"), when the target is an app. */
    val label: String? = null
)

/**
 * System Intent Shortcut Resolver.
 *
 * Resolves standard system commands directly to Android Intent targets in <15ms with 0 tokens.
 *
 * **What changed and why.** This object used to own a hand-written table of app names
 * (`"open clock" -> SHOW_ALARMS`, `"open instagram" -> com.instagram.android`, …) and that table was
 * the root of the launch failures:
 *
 *  - `"open clock"` returned an action with `targetPackage = null`, so nothing could be
 *    installed-checked and nothing could be observed afterwards; the step ended FAILED even when the
 *    clock app was open;
 *  - every app not in the table fell through to the model, i.e. the *default* way to open an app
 *    cost tokens;
 *  - a hardcoded package (`com.instagram.android`) fired an unobservable intent on a device where
 *    that package does not exist, instead of escalating.
 *
 * App opening is now a **lookup**: [AniobLaunchHub] resolves the name against [AniobAppRegistry]
 * (built at startup from the platform's own launcher inventory) and returns a package — plus the
 * launcher component when known. Only genuine *system reflexes* (torch, battery, dial, URL, geo)
 * remain literal here, because for those the dispatch really is the outcome.
 */
object AniobIntentResolver {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val ACTION_FLASHLIGHT = "android.intent.action.FLASHLIGHT"

    const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    const val ACTION_SET_TIMER = "android.intent.action.SET_TIMER"
    const val ACTION_SHOW_ALARMS = "android.intent.action.SHOW_ALARMS"
    const val ACTION_SHOW_TIMERS = "android.intent.action.SHOW_TIMERS"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val ACTION_SETTINGS = "android.settings.SETTINGS"

    // Clock extras (android.provider.AlarmClock) and contact extras (ContactsContract.Intents.Insert).
    const val EXTRA_ALARM_HOUR = "android.intent.extra.alarm.HOUR"
    const val EXTRA_ALARM_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val EXTRA_ALARM_LENGTH = "android.intent.extra.alarm.LENGTH"
    const val EXTRA_ALARM_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"
    const val EXTRA_CONTACT_NAME = "name"
    const val EXTRA_CONTACT_PHONE = "phone"
    const val EXTRA_CONTACT_EMAIL = "email"
    const val CONTACT_MIME_TYPE = "vnd.android.cursor.dir/contact"

    // Settings panels: stable, documented actions instead of a walk through the Settings tree.
    const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
    const val ACTION_BATTERY_SAVER_SETTINGS = "android.settings.BATTERY_SAVER_SETTINGS"

    /**
     * The battery *usage* screen (`Settings.ACTION_BATTERY_SAVER_SETTINGS`'s sibling). Opening the
     * saver toggle when the prompt asked to see the battery level is a wrong turn, so the two
     * screens are separate shortcuts.
     */
    const val ACTION_BATTERY_USAGE = "android.intent.action.POWER_USAGE_SUMMARY"
    const val ACTION_DATA_USAGE_SETTINGS = "android.settings.DATA_USAGE_SETTINGS"
    const val ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS"
    const val ACTION_SOUND_SETTINGS = "android.settings.SOUND_SETTINGS"
    const val ACTION_LOCATION_SOURCE_SETTINGS = "android.settings.LOCATION_SOURCE_SETTINGS"
    const val ACTION_APPLICATION_SETTINGS = "android.settings.APPLICATION_SETTINGS"
    const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"
    const val ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    const val ACTION_AIRPLANE_MODE_SETTINGS = "android.settings.AIRPLANE_MODE_SETTINGS"
    const val ACTION_DATE_SETTINGS = "android.settings.DATE_SETTINGS"
    const val ACTION_SECURITY_SETTINGS = "android.settings.SECURITY_SETTINGS"
    const val ACTION_STORAGE_SETTINGS = "android.settings.INTERNAL_STORAGE_SETTINGS"
    const val ACTION_LOCALE_SETTINGS = "android.settings.LOCALE_SETTINGS"
    const val ACTION_DEVELOPMENT_SETTINGS = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"

    const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="
    const val CATEGORY_APP_CALCULATOR = "android.intent.category.APP_CALCULATOR"
    const val CATEGORY_APP_CALENDAR = "android.intent.category.APP_CALENDAR"
    const val CATEGORY_APP_BROWSER = "android.intent.category.APP_BROWSER"
    const val CATEGORY_APP_FILES = "android.intent.category.APP_FILES"
    const val ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE"

    // System actions surfaced through extras so the executor can dispatch
    // them without an explicit Android Intent (pure JVM core-agent).
    const val EXTRA_SYSTEM_ACTION = "aniob_system_action"
    const val SYSTEM_ACTION_FLASHLIGHT = "turn_on_flashlight"
    const val SYSTEM_ACTION_GET_DEVICE_INFO = "get_device_info"

    private const val ACTION_GEO = "geo:0,0?q="

    /**
     * Resolves natural language commands to direct Android Intent actions if possible.
     *
     * Returns null when the command is not a shortcut at all — the ladder then treats it as a normal
     * task. A **non-null result is never a promise that the goal is met**: check
     * [ResolvedIntentShortcut.requiresObservation] before finishing on it.
     */
    fun resolve(command: String): ResolvedIntentShortcut? {
        val raw = command.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase().trim().trimEnd('?', '.', '!', ' ', ',', ';')

        // Parameterised shortcuts run first: "set an alarm for 7:30" must not fall into the bare
        // "set alarm" branch that only opens the editor, and "open Wi-Fi settings" must not be
        // answered by launching the Settings app.
        return parameterised(raw, lower)
            ?: systemReflex(lower)
            ?: dial(lower)
            ?: url(raw, lower)
            ?: maps(raw, lower)
            ?: appViaRegistry(raw)
            ?: clockOrAlarm(lower)
            ?: appCategory(lower)
    }

    // ------------------------------------------------------------------ #
    // reflexes: dispatch IS the outcome, nothing to observe
    // ------------------------------------------------------------------ #

    private fun systemReflex(lower: String): ResolvedIntentShortcut? = when (lower) {
        "turn on flashlight", "flashlight on", "open flashlight", "turn on the flashlight",
        "open the flashlight", "flashlight" -> ResolvedIntentShortcut(
            action = ACTION_FLASHLIGHT,
            extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_FLASHLIGHT)
        )
        // "show alarms" / "open timers" used to fall through to a bare category intent
        // (ACTION_SHOW_TIMERS was declared and never used). They are the two documented ways to
        // land straight on the list, so they answer themselves — but the list a person asked to see
        // is still observed before the run may close.
        "show alarms", "open alarms", "show my alarms", "see my alarms" -> ResolvedIntentShortcut(
            action = ACTION_SHOW_ALARMS,
            requiresObservation = true,
            label = "Alarms"
        )
        "show timers", "open timers", "show my timers", "see my timers" -> ResolvedIntentShortcut(
            action = ACTION_SHOW_TIMERS,
            requiresObservation = true,
            label = "Timers"
        )
        "check battery", "battery status", "what is my battery", "check battery level", "battery" ->
            ResolvedIntentShortcut(
                action = ACTION_MAIN,
                extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_GET_DEVICE_INFO)
            )
        else -> null
    }

    private fun dial(lower: String): ResolvedIntentShortcut? {
        if (!lower.startsWith("call ")) return null
        val number = lower.removePrefix("call ").filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return null
        return ResolvedIntentShortcut(action = ACTION_DIAL, uriString = "tel:$number")
    }

    private fun url(raw: String, lower: String): ResolvedIntentShortcut? {
        // These prefixes are ASCII, so their length is identical in `raw` and in `lower`.
        for (prefix in listOf("open website ", "open url ", "go to website ", "browse ")) {
            if (!lower.startsWith(prefix)) continue
            val validated = sanitizeAndValidateUrl(raw.substring(prefix.length).trim()) ?: return null
            return ResolvedIntentShortcut(action = ACTION_VIEW, uriString = validated)
        }
        return null
    }

    private fun maps(raw: String, lower: String): ResolvedIntentShortcut? = when {
        lower == "open maps" || lower == "maps" || lower == "launch maps" || lower == "open map" ->
            ResolvedIntentShortcut(action = ACTION_VIEW, uriString = ACTION_GEO)
        lower.startsWith("navigate to ") -> {
            // Destination, not a URL: keep the user's capitalisation, escape what a URI cannot hold.
            val destination = raw.trim().substring("navigate to ".length).trim()
            if (destination.isBlank()) null
            else ResolvedIntentShortcut(action = ACTION_VIEW, uriString = ACTION_GEO + escapeQuery(destination))
        }
        else -> null
    }

    /** `navigate to X` carries a destination, not a URL: escape it instead of validating it. */
    private fun escapeQuery(text: String): String =
        text.replace("%", "%25").replace(" ", "%20").replace("#", "%23").replace("&", "%26")

    // ------------------------------------------------------------------ #
    // apps: a lookup, never a table
    // ------------------------------------------------------------------ #

    /**
     * The one path for "open <app>", at any point in a task.
     *
     * The registry is preferred; a seed of stock-Android package ids is the last resort so a cold
     * cache degrades to "ask the platform", not to "no idea what Clock means". The caller still
     * confirms the package is launchable, so a wrong seed can never dispatch.
     */
    private fun appViaRegistry(raw: String): ResolvedIntentShortcut? {
        val plan = AniobLaunchHub.plan(raw, AniobAppRegistry.getInstance()) ?: return null
        return appShortcut(plan)
    }

    private fun appShortcut(plan: LaunchPlan): ResolvedIntentShortcut = ResolvedIntentShortcut(
        action = ACTION_MAIN,
        targetPackage = plan.packageName,
        category = CATEGORY_LAUNCHER,
        launchActivity = plan.launchActivity,
        requiresObservation = true,
        label = plan.label
    )

    /**
     * "set an alarm" / "show clock" / "clock".
     *
     * These used to be implicit intents (`SET_ALARM`, `SHOW_ALARMS`) with no package. They now open
     * the *clock app* when one is known, because "the app is in the foreground" is verifiable and
     * "some activity handled SET_ALARM" is not. When no clock package is known the implicit intent is
     * still returned, marked unobservable, so the caller can decide to escalate instead of claiming
     * success.
     */
    private fun clockOrAlarm(lower: String): ResolvedIntentShortcut? {
        val alarmPhrases = listOf("set alarm", "set an alarm", "set the alarm", "add an alarm")
        val clockPhrases = listOf("clock", "show clock", "open clock app")
        val isAlarm = alarmPhrases.any { lower == it || lower.startsWith("$it ") }
        val isClock = clockPhrases.any { lower == it }
        if (!isAlarm && !isClock) return null

        val registry = AniobAppRegistry.getInstance()
        val clock = registry.appForRole(AniobAppRegistry.ROLE_CLOCK) ?: when (val found = registry.resolveWithSeed("clock")) {
            is AppLookup.Match -> found.app
            else -> null
        }
        if (clock != null) {
            return ResolvedIntentShortcut(
                action = ACTION_MAIN,
                targetPackage = clock.packageName,
                category = CATEGORY_LAUNCHER,
                launchActivity = clock.launchActivity,
                extras = if (isAlarm) mapOf("android.intent.extra.alarm_editor" to "true") else emptyMap(),
                requiresObservation = true,
                label = clock.label
            )
        }
        return ResolvedIntentShortcut(
            action = if (isAlarm) ACTION_SET_ALARM else "android.intent.action.SHOW_ALARMS",
            requiresObservation = false,
            label = "Clock"
        )
    }

    /** Last resort for the platform's own app categories: name a package, never a bare category. */
    private fun appCategory(lower: String): ResolvedIntentShortcut? {
        val role = when {
            lower == "open calculator" || lower == "calculator" || lower == "launch calculator" -> AniobAppRegistry.ROLE_CALCULATOR
            lower == "open calendar" || lower == "calendar" || lower == "launch calendar" -> AniobAppRegistry.ROLE_CALENDAR
            lower == "open browser" || lower == "browser" || lower == "launch browser" -> AniobAppRegistry.ROLE_BROWSER
            lower == "open files" || lower == "files" || lower == "launch files" -> AniobAppRegistry.ROLE_FILES
            lower == "open camera" || lower == "launch camera" || lower == "camera" -> AniobAppRegistry.ROLE_CAMERA
            lower == "open settings" || lower == "settings" || lower == "launch settings" -> AniobAppRegistry.ROLE_SETTINGS
            else -> null
        } ?: return null

        val registry = AniobAppRegistry.getInstance()
        val app = registry.appForRole(role) ?: when (val found = registry.resolveWithSeed(role)) {
            is AppLookup.Match -> found.app
            else -> null
        }
        if (app != null) {
            return ResolvedIntentShortcut(
                action = ACTION_MAIN,
                targetPackage = app.packageName,
                category = CATEGORY_LAUNCHER,
                launchActivity = app.launchActivity,
                requiresObservation = true,
                label = app.label
            )
        }
        // No package known: hand back the category intent, unobservable, so the caller escalates
        // rather than firing an intent whose effect it cannot check.
        val categoryIntent = when (role) {
            AniobAppRegistry.ROLE_CALCULATOR -> CATEGORY_APP_CALCULATOR
            AniobAppRegistry.ROLE_CALENDAR -> CATEGORY_APP_CALENDAR
            AniobAppRegistry.ROLE_BROWSER -> CATEGORY_APP_BROWSER
            AniobAppRegistry.ROLE_FILES -> CATEGORY_APP_FILES
            AniobAppRegistry.ROLE_CAMERA -> ACTION_IMAGE_CAPTURE
            else -> null
        }
        if (categoryIntent == null) return null
        return ResolvedIntentShortcut(action = ACTION_MAIN, category = categoryIntent, requiresObservation = true)
    }


    // ---------------------------------------------------------------------------------------------
    // Parameterised shortcuts
    //
    // Each of these turns a sentence that names its own parameters into the one intent that
    // carries them: a clock time into SET_ALARM, a duration into SET_TIMER, a panel name into its
    // documented Settings action, a name+number into the prefilled contact editor. `requiresObservation`
    // is set per case: a silent alarm is done when dispatched, an alarm the prompt wants to *see*
    // is not, and the contact editor always needs its Save tap.
    // ---------------------------------------------------------------------------------------------

    /** `7:30`, `7.30`, `7:30pm` — a separator or a meridiem is required to be a clock time. */
    private val TIME_WITH_MINUTES =
        Regex("""\b(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?\b""", RegexOption.IGNORE_CASE)
    private val TIME_WITH_MERIDIEM =
        Regex("""\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE)
    private val DURATION_PART =
        Regex("""(\d+)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""", RegexOption.IGNORE_CASE)
    private val PHONE_NUMBER = Regex("""\+?\d[\d\s()\-]{4,}""")
    private val BROWSER_CUES = listOf("google", "chrome", "browser", "the web", "web")

    /** Prompts that demand something visible on screen cannot complete silently. */
    private val WANTS_VISIBLE_RESULT = listOf("leaving", "showing", "on screen", "visible", "display")

    /**
     * A prompt that names a second clause is not finished by the first clause's dispatch:
     * "set an alarm for 7:30 and open instagram" still owes the instagram half.
     */
    private val SECOND_GOAL = Regex(
        """\b(then|and then|after that|also|and open|and set|and start|and turn|and enable|and disable|and add|and create|and search|and check)\b"""
    )

    /** A status request has to be read off the screen, not opened: "open settings and check battery". */
    private val STATUS_QUESTION = Regex("""\b(check|how much|how many|what is|what's|tell me|read the)\b""")

    /** A create verb close to the noun; "cancel my alarm" must never set one. */
    private val ALARM_SET_VERB = Regex("""\b(set|create|add|put)\b[^.]{0,25}\balarm\b""")
    private val TIMER_SET_VERB = Regex("""\b(start|set|create|add)\b[^.]{0,25}\btimer\b""")
    private val CANCEL_PREFIXES = listOf("cancel ", "delete ", "remove ", "dismiss ", "stop ", "pause ")

    private val OPEN_VERBS = listOf("open", "show", "launch", "start", "go to", "navigate to", "take me to", "view")
    private val TOGGLE_VERBS = listOf("turn on", "turn off", "enable", "disable", "switch on", "switch off")

    private class SettingsPanel(val action: String, val label: String, val keys: List<String>) {
        /** Compiled once: the embedded scan runs against every prompt that reaches this object. */
        val patterns: List<Regex> = keys.map { Regex("\\b" + Regex.escape(it) + "\\b") }
    }

    /**
     * Long enough to cover what the panel screens are normally called, short enough that one key
     * does not swallow ordinary sentences: a key matches the *whole* prompt once the opening verb
     * and a trailing "settings" are stripped, never a substring of a longer task.
     */
    private val SETTINGS_PANELS = listOf(
        SettingsPanel(ACTION_WIFI_SETTINGS, "Wi-Fi settings", listOf("wifi", "wi-fi", "wireless", "wlan")),
        SettingsPanel(ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", listOf("bluetooth")),
        SettingsPanel(
            ACTION_BATTERY_USAGE,
            "Battery usage",
            listOf("battery usage", "battery screen", "battery level", "battery")
        ),
        SettingsPanel(
            ACTION_BATTERY_SAVER_SETTINGS,
            "Battery saver settings",
            listOf("battery saver", "power saving", "power saving mode")
        ),
        SettingsPanel(ACTION_DATA_USAGE_SETTINGS, "Data usage settings", listOf("data usage", "mobile data", "cellular data")),
        SettingsPanel(ACTION_DISPLAY_SETTINGS, "Display settings", listOf("display", "brightness", "screen timeout")),
        SettingsPanel(ACTION_SOUND_SETTINGS, "Sound settings", listOf("sound", "volume", "ringtone", "vibration")),
        SettingsPanel(ACTION_LOCATION_SOURCE_SETTINGS, "Location settings", listOf("location", "location services", "gps")),
        SettingsPanel(ACTION_APPLICATION_SETTINGS, "Apps settings", listOf("apps", "applications", "installed apps", "app list")),
        SettingsPanel(ACTION_NOTIFICATION_SETTINGS, "Notification settings", listOf("notifications", "notification settings")),
        SettingsPanel(ACTION_ACCESSIBILITY_SETTINGS, "Accessibility settings", listOf("accessibility")),
        SettingsPanel(ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode settings", listOf("airplane mode", "flight mode")),
        SettingsPanel(ACTION_DATE_SETTINGS, "Date and time settings", listOf("date and time", "date settings")),
        SettingsPanel(ACTION_SECURITY_SETTINGS, "Security settings", listOf("security", "screen lock", "lock screen")),
        SettingsPanel(ACTION_STORAGE_SETTINGS, "Storage settings", listOf("storage", "internal storage")),
        SettingsPanel(ACTION_LOCALE_SETTINGS, "Language settings", listOf("language", "language and input", "keyboard settings")),
        SettingsPanel(ACTION_DEVELOPMENT_SETTINGS, "Developer options", listOf("developer options", "developer settings"))
    )

    private fun parameterised(raw: String, lower: String): ResolvedIntentShortcut? {
        if (lower.contains("alarm")) parseAlarm(lower)?.let { return it }
        if (lower.contains("timer")) parseTimer(lower)?.let { return it }
        parseSettingsPanel(lower)?.let { return it }
        parseContactInsert(raw, lower)?.let { return it }
        parseWebSearch(lower)?.let { return it }
        return null
    }

    /**
     * `set an alarm for 7:30 am` -> SET_ALARM with hour/minute, plus `SKIP_UI` when the prompt does
     * not ask to see the result. Without a recognisable time this returns null on purpose: opening
     * an empty editor is still the right first step, but it is not the finished task.
     */
    private fun parseAlarm(text: String): ResolvedIntentShortcut? {
        if (CANCEL_PREFIXES.any { text.startsWith(it) }) return null
        if (!ALARM_SET_VERB.containsMatchIn(text)) return null
        val (hour, minute) = parseClockTime(text) ?: return null
        // "leaving the alarm list showing 7:30 AM" asks for a visible end state: the silent
        // SKIP_UI path would set an alarm that the requested screen cannot show. A second clause or
        // a status question is the same situation for a different reason: the dispatch is only the
        // first half of the prompt, so the run must continue from the editor.
        val wantsVisible = WANTS_VISIBLE_RESULT.any { text.contains(it) } ||
            SECOND_GOAL.containsMatchIn(text) || STATUS_QUESTION.containsMatchIn(text)
        return ResolvedIntentShortcut(
            action = ACTION_SET_ALARM,
            extras = buildMap {
                put(EXTRA_ALARM_HOUR, hour.toString())
                put(EXTRA_ALARM_MINUTES, minute.toString())
                if (!wantsVisible) put(EXTRA_ALARM_SKIP_UI, "true")
            },
            requiresObservation = wantsVisible,
            label = "Alarm ${"%02d:%02d".format(hour, minute)}"
        )
    }

    /** `set a timer for 10 minutes` -> SET_TIMER with the length in seconds. */
    private fun parseTimer(text: String): ResolvedIntentShortcut? {
        if (CANCEL_PREFIXES.any { text.startsWith(it) }) return null
        if (!TIMER_SET_VERB.containsMatchIn(text)) return null
        val seconds = parseDurationSeconds(text) ?: return null
        // Same rule as the alarm: a second clause means the timer is not the whole prompt.
        val wantsVisible = WANTS_VISIBLE_RESULT.any { text.contains(it) } ||
            SECOND_GOAL.containsMatchIn(text) || STATUS_QUESTION.containsMatchIn(text)
        return ResolvedIntentShortcut(
            action = ACTION_SET_TIMER,
            extras = buildMap {
                put(EXTRA_ALARM_LENGTH, seconds.toString())
                if (!wantsVisible) put(EXTRA_ALARM_SKIP_UI, "true")
            },
            requiresObservation = wantsVisible,
            label = "Timer ${seconds}s"
        )
    }

    /** Hour/minute from a clock time, or null when the text contains no unambiguous time. */
    internal fun parseClockTime(text: String): Pair<Int, Int>? {
        TIME_WITH_MINUTES.find(text)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            val meridiem = match.groupValues[3].lowercase()
            if (minute !in 0..59) return null
            val hour = if (meridiem.isEmpty()) {
                rawHour
            } else {
                if (rawHour !in 1..12) return null
                (rawHour % 12) + if (meridiem.startsWith("p")) 12 else 0
            }
            if (hour !in 0..23) return null
            return hour to minute
        }
        TIME_WITH_MERIDIEM.find(text)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return null
            if (rawHour !in 1..12) return null
            val pm = match.groupValues[2].lowercase().startsWith("p")
            return ((rawHour % 12) + if (pm) 12 else 0) to 0
        }
        return null
    }

    /** Total seconds in a duration phrase ("1 hour 30 minutes"), or null when there is none. */
    internal fun parseDurationSeconds(text: String): Int? {
        var total = 0
        var found = false
        for (match in DURATION_PART.findAll(text)) {
            val amount = match.groupValues[1].toIntOrNull() ?: continue
            val unit = match.groupValues[2].lowercase()
            val multiplier = when {
                unit.startsWith("h") -> 3600
                unit.startsWith("m") -> 60
                else -> 1
            }
            total += amount * multiplier
            found = true
        }
        if (!found || total <= 0 || total > 86_400) return null
        return total
    }

    /**
     * `open Wi-Fi settings` -> the documented panel action, so the agent does not walk the
     * Settings tree. A toggle verb keeps the run alive: opening the panel is not turning Wi-Fi on.
     */
    private fun parseSettingsPanel(text: String): ResolvedIntentShortcut? {
        val stripped = AniobTaskIntentClassifier.stripPreamble(text)
        var core = stripped
        for (verb in TOGGLE_VERBS + OPEN_VERBS) {
            if (core.startsWith("$verb ")) {
                core = core.removePrefix("$verb ").trim()
                break
            }
        }
        core = core.removePrefix("the ").removeSuffix(" settings").removeSuffix(" panel").trim()
        if (core.isNotEmpty()) {
            val panel = SETTINGS_PANELS.firstOrNull { candidate -> candidate.keys.any { it == core } }
            if (panel != null) {
                val toggling = TOGGLE_VERBS.any { stripped.startsWith("$it ") }
                // A status question ("check battery") is not answered by opening the panel.
                val status = STATUS_QUESTION.containsMatchIn(text)
                return ResolvedIntentShortcut(
                    action = panel.action,
                    requiresObservation = toggling || status,
                    label = panel.label
                )
            }
        }

        // "open settings and open Wi-Fi settings, leaving the screen showing Wi-Fi" names the panel
        // inside a longer sentence. Worth opening directly — but only a prompt whose remaining words
        // just describe what to look at can end there; anything else keeps the run alive.
        // Only a sentence that actually asks to open something may end at a panel: "check battery"
        // is a status question, and answering it with a settings screen would be a wrong turn.
        if (OPEN_VERBS.none { text.contains("$it ") }) return null
        val (panel, end) = embeddedPanel(text) ?: return null
        var tail = text.substring(end).trim()
        for (suffix in listOf("settings", "screen", "panel")) {
            if (tail.startsWith(suffix)) tail = tail.removePrefix(suffix).trim()
        }
        tail = tail.trimStart(',', '.', ' ').trim()
        // The tail may describe the result ("leaving the screen showing Wi-Fi") or name another
        // goal ("and wifi settings", "and turn it off"); only the former ends the run here.
        val secondGoal = SECOND_GOAL.containsMatchIn(tail)
        val tailIsJustTheResult = tail.isEmpty() ||
            (WANTS_VISIBLE_RESULT.any { tail.startsWith(it) } && !secondGoal)
        val statusQuestion = STATUS_QUESTION.containsMatchIn(text)
        return ResolvedIntentShortcut(
            action = panel.action,
            requiresObservation = !tailIsJustTheResult || statusQuestion,
            label = panel.label
        )
    }

    /** First settings-panel name in the sentence, with the index just past its name. */
    /**
     * The settings panel named **first** in the sentence, with the index just past its name.
     *
     * Table order is not sentence order: "open airplane mode and wifi settings" has to open
     * Airplane mode, so the earliest match wins. When two keys start at the same offset ("battery"
     * inside "battery saver") the longer key wins, because the longer name is the specific one.
     */
    private fun embeddedPanel(text: String): Pair<SettingsPanel, Int>? {
        var matchPanel: SettingsPanel? = null
        var matchStart = Int.MAX_VALUE
        var matchEnd = 0
        var matchLength = 0
        for (panel in SETTINGS_PANELS) {
            for (pattern in panel.patterns) {
                val match = pattern.find(text) ?: continue
                val length = match.range.last - match.range.first + 1
                val earlier = match.range.first < matchStart
                val longerAtSameOffset = match.range.first == matchStart && length > matchLength
                if (earlier || longerAtSameOffset) {
                    matchPanel = panel
                    matchStart = match.range.first
                    matchEnd = match.range.last + 1
                    matchLength = length
                }
            }
        }
        return matchPanel?.let { it to matchEnd }
    }

    /**
     * `add contact John with number 5551234` -> INSERT with NAME/PHONE on the contact editor.
     *
     * The editor still needs its Save tap, so this is always a partial shortcut: it removes the
     * form filling (the part the model usually fumbles) and leaves one visible, verified step.
     */
    private fun parseContactInsert(command: String, text: String): ResolvedIntentShortcut? {
        if (!text.contains("contact")) return null
        val inserting = listOf("add ", "create ", "new ", "save ", "insert ").any { text.startsWith(it) } ||
            text.contains("add a contact") || text.contains("new contact") || text.contains("add contact")
        if (!inserting) return null
        val phoneDigits = PHONE_NUMBER.find(text)?.value?.filter { it.isDigit() || it == '+' } ?: return null
        if (phoneDigits.count { it.isDigit() } < 5) return null
        val name = contactNameFrom(command) ?: ""
        val extras = buildMap {
            put(EXTRA_CONTACT_PHONE, phoneDigits)
            if (name.isNotBlank()) put(EXTRA_CONTACT_NAME, name)
        }
        return ResolvedIntentShortcut(
            action = ACTION_INSERT,
            dataType = CONTACT_MIME_TYPE,
            extras = extras,
            requiresObservation = true,
            label = if (name.isBlank()) "New contact" else "New contact $name"
        )
    }

    /** Best-effort display name from "add contact John …", title-cased; null when unsure. */
    private fun contactNameFrom(command: String): String? {
        val afterContact = command.substringAfter("contact", "").trim()
        if (afterContact.isEmpty()) return null
        val lower = afterContact.lowercase()
        var cut = lower.length
        for (marker in listOf(" with ", " phone", " number")) {
            val index = lower.indexOf(marker)
            if (index in 0 until cut) cut = index
        }
        var head = afterContact.substring(0, cut).trim().trimEnd(',', '.')
        for (prefix in listOf("named ", "name ", "called ", "for ")) {
            if (head.startsWith(prefix, ignoreCase = true)) {
                head = head.substring(prefix.length).trim()
                break
            }
        }
        head = head.trim('"', '\'', ' ').trim()
        if (head.isEmpty() || head.length > 40) return null
        if (!head.matches(Regex("[A-Za-z][A-Za-z .'\\-]{0,39}"))) return null
        if (head.any { it.isDigit() }) return null
        return head.split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * `search for pizza on google` -> ACTION_VIEW on a Google query URL. Deliberately does not
     * touch web-research prompts ("search the web …"): those belong to the answer path.
     */
    private fun parseWebSearch(text: String): ResolvedIntentShortcut? {
        if (AniobTaskIntentClassifier.isWebResearch(text)) return null
        val cue = BROWSER_CUES.firstOrNull { text.contains(it) } ?: return null
        val raw = when {
            text.contains("search") -> text.substringAfterLast("search").trim()
            text.startsWith("google ") -> text.removePrefix("google ").trim()
            text.startsWith("look up ") -> text.removePrefix("look up ").trim()
            text.startsWith("open $cue and look up ") -> text.removePrefix("open $cue and look up ").trim()
            else -> return null
        }
        val query = raw
            .removePrefix("for ")
            .removePrefix("google for ")
            .substringBefore(" on google")
            .substringBefore(" in google")
            .substringBefore(" on chrome")
            .substringBefore(" in chrome")
            .substringBefore(" on the web")
            .removePrefix("on google ")
            .removePrefix("in chrome ")
            .trim()
            .trimEnd('?', '.', '!')
        if (query.length < 2) return null
        if (query.contains("settings", ignoreCase = true)) return null
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return ResolvedIntentShortcut(
            action = ACTION_VIEW,
            uriString = GOOGLE_SEARCH_URL + encoded,
            label = "Search \"$query\""
        )
    }

    /** Kept public: URL validation is a security-relevant pure function, and it is unit-tested. */
    fun sanitizeAndValidateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("content:") || lower.startsWith("data:")
        ) {
            return null
        }

        val targetUrl = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val schemeEnd = targetUrl.indexOf("://")
        if (schemeEnd == -1) return null
        val afterScheme = targetUrl.substring(schemeEnd + 3)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.contains('@') || hostPart.isBlank()) {
            return null
        }

        return targetUrl
    }
}
