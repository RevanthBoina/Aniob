package com.aniob.core.ladder

import com.aniob.core.intent.AniobTaskIntentClassifier

/**
 * A resolved Android Intent the app layer can fire directly.
 *
 * [completesTask] is the difference this layer exists for: when the intent already carries every
 * parameter the task needs (an alarm with a time and `SKIP_UI`, a Wi-Fi settings panel, a URL),
 * a successful `startActivity` *is* the task's completion and no screen work is needed. When it
 * only opens the right surface (an empty alarm editor, a prefilled contact card), the agent lands
 * there and continues from the next step instead of starting from the launcher.
 */
data class ResolvedIntentShortcut(
    val action: String,
    val targetPackage: String? = null,
    val uriString: String? = null,
    val category: String? = null,
    /** `Intent.setType` — the contact insert intent is addressed by its MIME type. */
    val dataType: String? = null,
    val extras: Map<String, String> = emptyMap(),
    /** First installed package wins; lets one shortcut serve OEM variants of the same app. */
    val targetPackages: List<String> = emptyList(),
    val completesTask: Boolean = false,
    val completionReason: String = ""
)

/**
 * System Intent Shortcut Resolver.
 * Resolves standard system commands directly to Android Intent targets in <15ms with 0 tokens.
 *
 * Pure JVM: the module deliberately cannot see `android.*`, so the platform constants that matter
 * are mirrored here by value and the app layer forwards them to a real `Intent`.
 */
object AniobIntentResolver {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val ACTION_FLASHLIGHT = "android.intent.action.FLASHLIGHT"

    // Clock (android.provider.AlarmClock)
    const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    const val ACTION_SET_TIMER = "android.intent.action.SET_TIMER"
    const val ACTION_SHOW_ALARMS = "android.intent.action.SHOW_ALARMS"
    const val ACTION_SHOW_TIMERS = "android.intent.action.SHOW_TIMERS"
    const val EXTRA_ALARM_HOUR = "android.intent.extra.alarm.HOUR"
    const val EXTRA_ALARM_MINUTES = "android.intent.extra.alarm.MINUTES"
    const val EXTRA_ALARM_LENGTH = "android.intent.extra.alarm.LENGTH"
    const val EXTRA_ALARM_SKIP_UI = "android.intent.extra.alarm.SKIP_UI"

    // Settings panels (android.provider.Settings)
    const val ACTION_SETTINGS = "android.settings.SETTINGS"
    const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
    const val ACTION_BATTERY_SAVER_SETTINGS = "android.settings.BATTERY_SAVER_SETTINGS"
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

    // Contacts (android.provider.ContactsContract.Intents.Insert)
    const val CONTACT_MIME_TYPE = "vnd.android.cursor.dir/contact"
    const val EXTRA_CONTACT_NAME = "name"
    const val EXTRA_CONTACT_PHONE = "phone"

    /** Web search opened in whatever browser is installed. */
    const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="

    // System actions surfaced through extras so the executor can dispatch
    // them without an explicit Android Intent (pure JVM core-agent).
    const val EXTRA_SYSTEM_ACTION = "aniob_system_action"
    const val SYSTEM_ACTION_FLASHLIGHT = "turn_on_flashlight"
    const val SYSTEM_ACTION_GET_DEVICE_INFO = "get_device_info"

    /** OEM and AOSP calculator packages, tried in order by the launcher. */
    private val CALCULATOR_PACKAGES = listOf(
        "com.google.android.calculator",
        "com.android.calculator2",
        "com.sec.android.app.popupcalculator",
        "com.miui.calculator",
        "com.oneplus.calculator",
        "com.coloros.calculator",
        "com.android.calculator"
    )

    private data class SettingsPanel(val action: String, val label: String, val keys: List<String>)

    /**
     * Long enough to cover what the panel screens are normally called, short enough that the same
     * table does not swallow ordinary sentences. A key matches the *whole* prompt once the opening
     * verb and a trailing "settings" are stripped, never a substring of a longer task.
     */
    private val SETTINGS_PANELS = listOf(
        SettingsPanel(ACTION_WIFI_SETTINGS, "Wi-Fi settings", listOf("wifi", "wi-fi", "wireless", "wlan")),
        SettingsPanel(ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings", listOf("bluetooth")),
        SettingsPanel(
            ACTION_BATTERY_SAVER_SETTINGS,
            "Battery saver settings",
            listOf("battery saver", "battery saver settings", "power saving", "power saving mode", "battery")
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

    private val OPEN_VERBS = listOf(
        "open", "show", "launch", "start", "go to", "navigate to", "take me to", "view"
    )
    private val TOGGLE_VERBS = listOf("turn on", "turn off", "enable", "disable", "switch on", "switch off")

    /** `7:30`, `7.30`, `7:30pm` — a separator or a meridiem is required to be a clock time. */
    private val TIME_WITH_MINUTES = Regex("""\b(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?\b""", RegexOption.IGNORE_CASE)
    private val TIME_WITH_MERIDIEM = Regex("""\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE)
    private val DURATION_PART = Regex("""(\d+)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""", RegexOption.IGNORE_CASE)
    private val PHONE_NUMBER = Regex("""\+?\d[\d\s()\-]{4,}""")
    private val BROWSER_CUES = listOf("google", "chrome", "browser", "the web", "web")

    /** Prompts that demand something visible on screen cannot complete silently. */
    private val WANTS_VISIBLE_RESULT = listOf("leaving", "showing", "on screen", "visible", "display")

    /** A create verb close to the noun; "cancel my alarm" must never set one. */
    private val ALARM_SET_VERB = Regex("""\b(set|create|add|put)\b[^.]{0,25}\balarm\b""")
    private val TIMER_SET_VERB = Regex("""\b(start|set|create|add)\b[^.]{0,25}\btimer\b""")
    private val CANCEL_PREFIXES = listOf("cancel ", "delete ", "remove ", "dismiss ", "stop ", "pause ")

    /**
     * Resolves natural language commands to direct Android Intent actions if possible.
     */
    fun resolve(command: String): ResolvedIntentShortcut? {
        val lower = command.lowercase().trim().trimEnd('?', '.', '!', ' ', ',', ';')

        // Parameterised shortcuts run first: "set an alarm for 7:30" must not fall into the bare
        // "set alarm" branch that only opens the editor.
        if (lower.contains("alarm")) parseAlarm(lower)?.let { return it }
        if (lower.contains("timer")) parseTimer(lower)?.let { return it }
        parseSettingsPanel(lower)?.let { return it }
        parseContactInsert(command, lower)?.let { return it }
        parseWebSearch(command, lower)?.let { return it }

        return when {
            lower == "turn on flashlight" || lower == "flashlight on" || lower == "open flashlight" || lower == "turn on the flashlight" || lower == "open the flashlight" -> {
                ResolvedIntentShortcut(
                    action = ACTION_FLASHLIGHT,
                    extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_FLASHLIGHT),
                    completesTask = true,
                    completionReason = "Flashlight turned on"
                )
            }
            lower == "check battery" || lower == "battery status" || lower == "what is my battery" || lower == "check battery level" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_GET_DEVICE_INFO),
                    completesTask = true,
                    completionReason = "Battery status read"
                )
            }
            lower == "open settings" || lower == "settings" || lower == "launch settings" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.android.settings",
                    category = CATEGORY_LAUNCHER,
                    completesTask = true,
                    completionReason = "Settings opened"
                )
            }
            lower == "open system settings" || lower == "system settings" || lower == "device settings" ->
                ResolvedIntentShortcut(
                    action = ACTION_SETTINGS,
                    completesTask = true,
                    completionReason = "Settings opened"
                )
            lower == "open calculator" || lower == "calculator" || lower == "launch calculator" || lower == "open the calculator" ->
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    category = CATEGORY_LAUNCHER,
                    targetPackages = CALCULATOR_PACKAGES,
                    completesTask = true,
                    completionReason = "Calculator opened"
                )
            lower == "open clock" || lower == "clock" || lower == "launch clock" || lower == "show clock" -> {
                ResolvedIntentShortcut(
                    action = ACTION_SHOW_ALARMS,
                    completesTask = true,
                    completionReason = "Clock opened"
                )
            }
            lower == "set alarm" || lower == "alarm" || lower == "set an alarm" -> {
                ResolvedIntentShortcut(
                    action = ACTION_SET_ALARM
                )
            }
            lower == "open alarms" || lower == "show alarms" || lower == "show my alarms" || lower == "my alarms" ->
                ResolvedIntentShortcut(
                    action = ACTION_SHOW_ALARMS,
                    completesTask = true,
                    completionReason = "Alarms shown"
                )
            lower == "show timers" || lower == "open timers" || lower == "my timers" ->
                ResolvedIntentShortcut(
                    action = ACTION_SHOW_TIMERS,
                    completesTask = true,
                    completionReason = "Timers shown"
                )
            lower == "open spotify" || lower == "spotify" || lower == "launch spotify" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.spotify.music",
                    category = CATEGORY_LAUNCHER,
                    completesTask = true,
                    completionReason = "Spotify opened"
                )
            }
            lower == "open maps" || lower == "maps" || lower == "launch maps" -> {
                ResolvedIntentShortcut(
                    action = ACTION_VIEW,
                    uriString = "geo:0,0",
                    completesTask = true,
                    completionReason = "Maps opened"
                )
            }
            lower == "open calendar" || lower == "calendar" || lower == "launch calendar" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    category = "android.intent.category.APP_CALENDAR",
                    completesTask = true,
                    completionReason = "Calendar opened"
                )
            }
            lower == "open browser" || lower == "browser" || lower == "launch browser" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    category = "android.intent.category.APP_BROWSER",
                    completesTask = true,
                    completionReason = "Browser opened"
                )
            }
            lower == "open files" || lower == "files" || lower == "launch files" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    category = "android.intent.category.APP_FILES",
                    completesTask = true,
                    completionReason = "Files opened"
                )
            }
            lower == "open camera" || lower == "launch camera" -> {
                ResolvedIntentShortcut(
                    action = "android.media.action.IMAGE_CAPTURE",
                    completesTask = true,
                    completionReason = "Camera opened"
                )
            }
            lower.startsWith("call ") -> {
                val number = lower.removePrefix("call ").filter { it.isDigit() || it == '+' }
                if (number.isNotBlank()) {
                    // ACTION_DIAL only pre-fills the dialer; the call itself is still one tap away,
                    // so the run continues on the dialer rather than claiming a placed call.
                    ResolvedIntentShortcut(
                        action = ACTION_DIAL,
                        uriString = "tel:$number",
                        completionReason = ""
                    )
                } else null
            }
            lower.startsWith("dial ") -> {
                val number = lower.removePrefix("dial ").filter { it.isDigit() || it == '+' }
                if (number.isNotBlank()) {
                    ResolvedIntentShortcut(
                        action = ACTION_DIAL,
                        uriString = "tel:$number",
                        completesTask = true,
                        completionReason = "Number dialed: $number"
                    )
                } else null
            }
            lower.startsWith("open website ") -> {
                val prefixLen = command.indexOf("open website ", ignoreCase = true) + "open website ".length
                val raw = command.substring(prefixLen).trim()
                val validated = sanitizeAndValidateUrl(raw)
                if (validated != null) {
                    ResolvedIntentShortcut(
                        action = ACTION_VIEW,
                        uriString = validated,
                        completesTask = true,
                        completionReason = "Opened $validated"
                    )
                } else null
            }
            lower.startsWith("browse ") -> {
                val prefixLen = command.indexOf("browse ", ignoreCase = true) + "browse ".length
                val raw = command.substring(prefixLen).trim()
                val validated = sanitizeAndValidateUrl(raw)
                if (validated != null) {
                    ResolvedIntentShortcut(
                        action = ACTION_VIEW,
                        uriString = validated,
                        completesTask = true,
                        completionReason = "Opened $validated"
                    )
                } else null
            }
            lower == "open whatsapp" || lower == "whatsapp" || lower == "launch whatsapp" || lower == "whats app" || lower == "open whats app" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.whatsapp",
                    category = CATEGORY_LAUNCHER,
                    completesTask = true,
                    completionReason = "WhatsApp opened"
                )
            }
            lower == "open youtube" || lower == "youtube" || lower == "launch youtube" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.google.android.youtube",
                    category = CATEGORY_LAUNCHER,
                    completesTask = true,
                    completionReason = "YouTube opened"
                )
            }
            else -> {
                val appCandidate = when {
                    lower.startsWith("open ") -> lower.removePrefix("open ").trim()
                    lower.startsWith("launch ") -> lower.removePrefix("launch ").trim()
                    lower.startsWith("start ") -> lower.removePrefix("start ").trim()
                    lower.startsWith("go to ") -> lower.removePrefix("go to ").trim()
                    else -> null
                }?.removePrefix("the ")?.trim()

                // "open calculator and compute 12 times 8" is not an app-open task, even though a
                // fuzzy catalog lookup would happily match "calculator" inside it. Only a bare
                // open command may end the run; anything with a clause falls through to the
                // step-0 app launch, which opens the app and lets the rest be planned.
                val bareOpen = appCandidate != null &&
                    !appCandidate.contains(" and ") && !appCandidate.contains(',') &&
                    appCandidate.split(' ').size <= 3
                if (bareOpen && appCandidate != null && appCandidate.isNotBlank()) {
                    val known = when (appCandidate) {
                        "whatsapp", "whats app" -> "com.whatsapp"
                        "youtube" -> "com.google.android.youtube"
                        "telegram" -> "org.telegram.messenger"
                        "chrome" -> "com.android.chrome"
                        "gmail", "email", "mail" -> "com.google.android.gm"
                        "instagram" -> "com.instagram.android"
                        "play store", "google play" -> "com.android.vending"
                        else -> null
                    } ?: com.aniob.core.knowledge.AniobAppCatalog.getInstance().findPackageForName(appCandidate)
                    if (known != null) {
                        ResolvedIntentShortcut(
                            action = ACTION_MAIN,
                            targetPackage = known,
                            category = CATEGORY_LAUNCHER,
                            completesTask = true,
                            completionReason = "$appCandidate opened"
                        )
                    } else null
                } else null
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Parameterised shortcuts
    // ---------------------------------------------------------------------------------------------

    /**
     * `set an alarm for 7:30 am` → SET_ALARM with hour/minute and `SKIP_UI`.
     *
     * Without a recognisable time this returns null on purpose: opening an empty alarm editor is
     * still the right first step, but it is not the finished task.
     */
    private fun parseAlarm(text: String): ResolvedIntentShortcut? {
        if (CANCEL_PREFIXES.any { text.startsWith(it) }) return null
        if (!ALARM_SET_VERB.containsMatchIn(text)) return null
        val time = parseClockTime(text) ?: return null
        val (hour, minute) = time
        // "leaving the alarm list showing 7:30 AM" asks for a visible end state: the silent
        // SKIP_UI path would set the alarm on a screen that cannot show it.
        val wantsVisible = WANTS_VISIBLE_RESULT.any { text.contains(it) }
        return ResolvedIntentShortcut(
            action = ACTION_SET_ALARM,
            extras = buildMap {
                put(EXTRA_ALARM_HOUR, hour.toString())
                put(EXTRA_ALARM_MINUTES, minute.toString())
                if (!wantsVisible) put(EXTRA_ALARM_SKIP_UI, "true")
            },
            completesTask = !wantsVisible,
            completionReason = if (wantsVisible) "" else "Alarm set for ${"%02d:%02d".format(hour, minute)}"
        )
    }

    /** `set a timer for 10 minutes` → SET_TIMER with the length in seconds and `SKIP_UI`. */
    private fun parseTimer(text: String): ResolvedIntentShortcut? {
        if (CANCEL_PREFIXES.any { text.startsWith(it) }) return null
        if (!TIMER_SET_VERB.containsMatchIn(text)) return null
        val seconds = parseDurationSeconds(text) ?: return null
        val wantsVisible = WANTS_VISIBLE_RESULT.any { text.contains(it) }
        return ResolvedIntentShortcut(
            action = ACTION_SET_TIMER,
            extras = buildMap {
                put(EXTRA_ALARM_LENGTH, seconds.toString())
                if (!wantsVisible) put(EXTRA_ALARM_SKIP_UI, "true")
            },
            completesTask = !wantsVisible,
            completionReason = if (wantsVisible) "" else "Timer started for ${seconds}s"
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
     * Settings panels have stable, documented actions (`Settings.ACTION_WIFI_SETTINGS` and
     * friends), so the shortcut opens the exact screen instead of making the model find it.
     * A toggle verb keeps the run alive: opening the Wi-Fi panel is not the same as turning Wi-Fi
     * on, and the agent still has to press the switch.
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
                return ResolvedIntentShortcut(
                    action = panel.action,
                    completesTask = !toggling,
                    completionReason = if (toggling) "" else "${panel.label} opened"
                )
            }
        }

        // "…and open Wi-Fi settings, leaving the screen showing Wi-Fi" names the panel in the
        // middle of a longer task. Opening it directly is still the right first move, but the run
        // continues so the rest of the instruction is carried out.
        val embedded = SETTINGS_PANELS.firstOrNull { panel ->
            panel.keys.any { key ->
                text.contains("$key settings") || text.contains("$key screen") || text.contains("$key panel")
            }
        }
        if (embedded != null) {
            return ResolvedIntentShortcut(
                action = embedded.action,
                completesTask = false,
                completionReason = ""
            )
        }
        return null
    }

    /**
     * `add contact John with number 5551234` → INSERT with NAME/PHONE, on the contact editor.
     *
     * The editor still needs a Save tap, so this is a partial shortcut: it removes the form filling
     * (the part the model usually fumbles) and leaves one visible, verified step.
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
            completionReason = ""
        )
    }

    /** Best-effort display name from "add contact John …", title-cased; empty when unsure. */
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
     * `search for pizza on google` → ACTION_VIEW on a Google query URL, opened by the default
     * browser. Deliberately does not touch web-research prompts ("search the web …"): those are
     * answer-path tasks, and diverting them would change the research battery's routing.
     */
    private fun parseWebSearch(command: String, text: String): ResolvedIntentShortcut? {
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
            completesTask = true,
            completionReason = "Search opened for \"$query\""
        )
    }

    fun sanitizeAndValidateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
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
