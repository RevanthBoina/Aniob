package com.aniob.core.ladder

import com.aniob.core.domain.DeviceCommand
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.narration.DeviceNarration
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Zero-token, zero-UI resolver for the everyday phone chores that have a documented Android API:
 * media keys, volume, torch, screenshot, panels, DND, OTP copy, notification housekeeping, file and
 * storage queries, reminders and calendar entries, messaging deep links, turn-by-turn navigation.
 *
 * It sits in [AniobIntentResolver.resolve] after the older reflexes and before the app-launch lookup,
 * and it is deliberately **conservative**:
 *
 *  - A sentence with a second goal ("pause music and open Instagram") is not a reflex; it goes to the
 *    model, which now knows the same commands as `DEVICE_CONTROL`, and may chain them.
 *  - A command that is hard to undo ([com.aniob.core.domain.DeviceRisk.CONFIRM]: call, inline reply,
 *    paste a code, share a location) is never resolved here. Those only run from the model path,
 *    where the safety interceptor asks the user first.
 *  - Phrases must match whole, so "play chess" never becomes a music search.
 *
 * The result is an ordinary [ResolvedIntentShortcut] whose `extras` carry the command name and its
 * arguments; `AniobViewModel.dispatchSystemIntent` hands them to the Android implementation.
 *
 * Pure Kotlin JVM: no `android.*` imports.
 */
object AniobReflexResolver {

    /** Pseudo-action for shortcuts that are executed by the device-control layer, never by startActivity. */
    const val ACTION_DEVICE_CONTROL = "com.aniob.action.DEVICE_CONTROL"

    /** Prefix that keeps command arguments apart from the intent extras already in the map. */
    const val ARG_PREFIX = "aniob_arg_"

    /** The command a shortcut carries, or null for an ordinary intent shortcut. */
    fun commandOf(shortcut: ResolvedIntentShortcut): DeviceCommand? =
        if (shortcut.action != ACTION_DEVICE_CONTROL) null
        else DeviceCommand.fromWire(shortcut.extras[AniobIntentResolver.EXTRA_SYSTEM_ACTION])

    fun argsOf(shortcut: ResolvedIntentShortcut): Map<String, String> =
        shortcut.extras.filterKeys { it.startsWith(ARG_PREFIX) }.mapKeys { it.key.removePrefix(ARG_PREFIX) }

    private fun shortcut(command: DeviceCommand, args: Map<String, String> = emptyMap()): ResolvedIntentShortcut =
        ResolvedIntentShortcut(
            action = ACTION_DEVICE_CONTROL,
            extras = buildMap {
                put(AniobIntentResolver.EXTRA_SYSTEM_ACTION, command.wire)
                args.forEach { (k, v) -> if (v.isNotBlank()) put(ARG_PREFIX + k, v) }
            },
            requiresObservation = !command.completesOnDispatch,
            label = DeviceNarration.phrase(command)
        )

    private val MULTI_GOAL = Regex(
        """\b(?:then|and then|after that|afterwards|also|and (?:open|set|turn|start|send|call|play|text|message|navigate|check|add|create|search|pause|skip|mute|take|copy|paste|reply))\b"""
    )

    private val MUSIC_APPS: Map<String, String> = mapOf(
        "spotify" to "com.spotify.music",
        "youtube music" to "com.google.android.apps.youtube.music",
        "yt music" to "com.google.android.apps.youtube.music",
        "amazon music" to "com.amazon.mp3",
        "apple music" to "com.apple.android.music",
        "jiosaavn" to "com.jio.media.jiobeats",
        "saavn" to "com.jio.media.jiobeats",
        "gaana" to "com.gaana",
        "wynk" to "com.bsbportal.music",
        "wynk music" to "com.bsbportal.music",
        "soundcloud" to "com.soundcloud.android",
        "deezer" to "deezer.android.app"
    )

    private val MUSIC_CUES = Regex("""\b(?:song|songs|music|playlist|album|track|tune|podcast)\b""")
    private val PLAY_REGEX = Regex("""^play\s+(.+?)(?:\s+(?:on|in|using|with|via)\s+([a-z ]+))?$""")
    private val VOLUME_SET_REGEX = Regex(
        """^(?:(?:set|change|put|make|adjust)\s+)?(?:the\s+)?(?:(media|music|ring|ringer|ringtone|alarm|notification|call)\s+)?volume\s+(?:to|at)\s+(\d{1,3})\s*(?:%|percent)?$"""
    )
    private val VOLUME_WORD_REGEX = Regex(
        """^(?:(?:set|put|turn|make)\s+)?(?:the\s+)?(?:(media|music|ring|ringer|alarm)\s+)?volume\s+(?:to\s+)?(max|maximum|full|min|minimum|zero)$"""
    )
    private val WHATSAPP_REGEX = Regex(
        """^(?:send\s+(?:a\s+)?)?whats\s?app(?:\s+message)?\s+to\s+(.+?)\s*(?:[:,]|\s+(?:saying|that says|with message|message))\s*['"“]?(.+?)['"”]?$""",
        RegexOption.IGNORE_CASE
    )
    private val WHATSAPP_OPEN_REGEX = Regex(
        """^(?:open\s+(?:a\s+)?(?:chat|conversation)\s+with\s+(.+?)\s+(?:in|on)\s+whats\s?app|whats\s?app\s+(.+))$""",
        RegexOption.IGNORE_CASE
    )
    private val TELEGRAM_OPEN_REGEX = Regex(
        """^open\s+(?:a\s+)?(?:chat|conversation)\s+with\s+(.+?)\s+(?:in|on)\s+telegram$""",
        RegexOption.IGNORE_CASE
    )
    private val TELEGRAM_SEND_REGEX = Regex(
        """^(?:send\s+(?:a\s+)?)?telegram(?:\s+message)?\s+to\s+(.+?)\s*(?:[:,]|\s+(?:saying|that says|with message|message))\s*['"“]?(.+?)['"”]?$""",
        RegexOption.IGNORE_CASE
    )
    private val SMS_REGEX = Regex(
        """^(?:send\s+(?:a\s+)?)?(?:sms|text(?:\s+message)?)\s+to\s+(.+?)\s*(?:[:,]|\s+(?:saying|that says|with message|message))\s*['"“]?(.+?)['"”]?$""",
        RegexOption.IGNORE_CASE
    )
    private val NAVIGATE_REGEX = Regex(
        """^(?:(?:start\s+)?navigation\s+to|directions?\s+to|get\s+directions?\s+to|drive\s+to|take\s+me\s+(?:to|home)|navigate\s+home)\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val FIND_FILE_REGEX = Regex(
        """^(?:find|show|list|search(?:\s+for)?|look\s+for)\s+(?:me\s+)?(?:the\s+|my\s+|all\s+|any\s+)?(?:(?:latest|recent|new|newest)\s+)?""" +
            """(pdfs?|images?|photos?|pictures?|screenshots?|downloads?|documents?|videos?|songs?|audio)(?:\s+files?)?""" +
            """(?:\s+(?:i\s+)?(?:downloaded|saved|took|received|got))?(?:\s+(today|yesterday|this week|this month))?$"""
    )
    private val OPEN_LATEST_REGEX = Regex(
        """^open\s+(?:the\s+|my\s+)?(?:latest|newest|last|most recent|recent)\s+(pdf|download|screenshot|photo|image|picture|file|document|bill|invoice|receipt|video)s?$"""
    )
    private val DISMISS_NOTIFICATION_REGEX = Regex("""^(?:dismiss|clear|remove)\s+(?:the\s+)?(.+?)\s+notifications?$""")
    private val REMIND_DAY_FIRST = Regex(
        """^remind\s+me\s+(?:(today|tonight|tomorrow)\s+)?(?:at\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\s+)?(?:to|that|about)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val REMIND_TASK_FIRST = Regex(
        """^remind\s+me\s+to\s+(.+?)\s+(?:(today|tonight|tomorrow)\s+)?at\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)$""",
        RegexOption.IGNORE_CASE
    )
    private val CALENDAR_REGEX = Regex(
        """^(?:add|create|schedule|put)\s+(?:a\s+|an\s+)?(?:meeting|event|appointment)?\s*['"“]?(.+?)['"”]?\s+(?:to|in|on)\s+(?:my\s+|the\s+)?calendar(?:\s+(?:for|at|on)\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )
    private val CLOCK_TIME = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE)

    /**
     * @param raw the user's sentence with its own capitalisation (message text, titles).
     * @param lower [raw] lower-cased with trailing punctuation removed.
     */
    fun resolve(raw: String, lower: String, clock: Clock = Clock.systemDefaultZone()): ResolvedIntentShortcut? {
        val text = AniobTaskIntentClassifier.stripPreamble(lower)
        if (text.isEmpty()) return null
        val rawText = raw.trim().trimEnd('?', '.', '!', ' ', ',', ';')

        // Reminders and messages legitimately contain "and"/"then" inside their payload, so they are
        // matched before the multi-goal guard; everything else must be a single sentence of intent.
        reminder(rawText, clock)?.let { return it }
        calendar(rawText, clock)?.let { return it }
        messaging(rawText)?.let { return it }
        if (MULTI_GOAL.containsMatchIn(text)) return null

        return media(text)
            ?: volume(text)
            ?: hardware(text)
            ?: notifications(text)
            ?: files(text)
            ?: navigation(rawText)
            ?: alarmDismiss(text)
    }

    // ------------------------------------------------------------------ media ------------------

    private fun media(text: String): ResolvedIntentShortcut? {
        val cleaned = text.removePrefix("the ").removePrefix("my ").trim()
        when (cleaned) {
            "pause", "pause music", "pause the music", "pause song", "pause the song", "pause playback",
            "pause audio", "pause podcast", "pause the podcast", "pause this" ->
                return shortcut(DeviceCommand.MEDIA_PAUSE)
            "play", "resume", "resume music", "resume playback", "play music", "continue music",
            "unpause", "unpause music", "resume the music", "resume song" ->
                return shortcut(DeviceCommand.MEDIA_PLAY)
            "play pause", "toggle music", "toggle playback" -> return shortcut(DeviceCommand.MEDIA_PLAY_PAUSE)
            "next song", "next track", "skip", "skip song", "skip track", "skip this", "skip this song",
            "skip this track", "skip the song", "skip the track", "play next song", "play next track",
            "next music" -> return shortcut(DeviceCommand.MEDIA_NEXT)
            "previous song", "previous track", "last song", "play previous song", "play previous track",
            "go back a track", "play the previous song" -> return shortcut(DeviceCommand.MEDIA_PREVIOUS)
            "stop music", "stop the music", "stop playing", "stop playback", "stop the song", "stop audio" ->
                return shortcut(DeviceCommand.MEDIA_STOP)
            "what's playing", "whats playing", "what is playing", "what song is this", "what song is playing",
            "what's this song", "what is this song" -> return shortcut(DeviceCommand.MEDIA_NOW_PLAYING)
        }
        val match = PLAY_REGEX.find(text) ?: return null
        var query = match.groupValues[1].trim()
        val appName = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
        val appPackage = appName?.let { MUSIC_APPS[it] }
        // "play X on <something we do not know as a music app>" is a video/game request: leave it.
        if (appName != null && appPackage == null) return null
        if (appPackage == null && !MUSIC_CUES.containsMatchIn(text)) return null
        query = query.removePrefix("the song ").removePrefix("some ").removePrefix("song ").trim()
        if (query.length < 2) return null
        return shortcut(
            DeviceCommand.MEDIA_PLAY_SEARCH,
            mapOf("query" to query, "app" to appPackage.orEmpty())
        )
    }

    // ------------------------------------------------------------------ volume -----------------

    private fun volume(text: String): ResolvedIntentShortcut? {
        VOLUME_SET_REGEX.find(text)?.let { m ->
            val percent = m.groupValues[2].toIntOrNull()?.coerceIn(0, 100) ?: return null
            return shortcut(DeviceCommand.VOLUME_SET, mapOf("percent" to percent.toString(), "stream" to streamOf(m.groupValues[1])))
        }
        VOLUME_WORD_REGEX.find(text)?.let { m ->
            val percent = if (m.groupValues[2] in setOf("max", "maximum", "full")) 100 else 0
            return shortcut(DeviceCommand.VOLUME_SET, mapOf("percent" to percent.toString(), "stream" to streamOf(m.groupValues[1])))
        }
        return when (text) {
            "volume up", "turn up the volume", "turn the volume up", "increase volume", "increase the volume",
            "raise the volume", "raise volume", "louder", "make it louder", "turn it up", "turn up volume" ->
                shortcut(DeviceCommand.VOLUME_UP)
            "volume down", "turn down the volume", "turn the volume down", "decrease volume", "decrease the volume",
            "lower the volume", "lower volume", "quieter", "make it quieter", "turn it down", "turn down volume" ->
                shortcut(DeviceCommand.VOLUME_DOWN)
            "mute", "mute volume", "mute the volume", "mute sound", "mute media", "mute music" ->
                shortcut(DeviceCommand.VOLUME_MUTE, mapOf("stream" to "music"))
            "mute phone", "mute my phone", "mute the phone", "silence phone", "silence my phone", "silent mode",
            "mute ringer", "put phone on silent", "put the phone on silent" ->
                shortcut(DeviceCommand.VOLUME_MUTE, mapOf("stream" to "ring"))
            "unmute", "unmute volume", "unmute sound", "unmute media", "unmute music" ->
                shortcut(DeviceCommand.VOLUME_UNMUTE, mapOf("stream" to "music"))
            "unmute phone", "unmute my phone", "unmute the phone", "unmute ringer", "ring mode on" ->
                shortcut(DeviceCommand.VOLUME_UNMUTE, mapOf("stream" to "ring"))
            "what's the volume", "whats the volume", "what is the volume", "check volume", "check the volume",
            "how loud is it" -> shortcut(DeviceCommand.VOLUME_GET)
            else -> null
        }
    }

    private fun streamOf(word: String): String = when (word) {
        "ring", "ringer", "ringtone" -> "ring"
        "alarm" -> "alarm"
        "notification" -> "notification"
        "call" -> "call"
        else -> "music"
    }

    // ------------------------------------------------------------------ hardware ---------------

    private fun hardware(text: String): ResolvedIntentShortcut? = when (text) {
        "turn off flashlight", "turn off the flashlight", "flashlight off", "switch off flashlight",
        "switch off the flashlight", "turn off torch", "turn off the torch", "torch off", "disable flashlight" ->
            shortcut(DeviceCommand.TORCH_OFF)
        "turn on torch", "turn on the torch", "torch on", "torch", "switch on flashlight",
        "switch on the flashlight", "enable flashlight" -> shortcut(DeviceCommand.TORCH_ON)
        "take a screenshot", "take screenshot", "screenshot", "capture screen", "capture the screen",
        "take a screen shot", "screenshot this", "screenshot the screen" -> shortcut(DeviceCommand.SCREENSHOT)
        // "open notifications" / "show notifications" are deliberately absent: the older settings-panel
        // resolver owns those two phrases (they open the Notifications settings screen).
        "pull down notifications", "pull down the notification shade", "open notification shade",
        "open the notification shade", "open notification panel", "open the notification panel",
        "swipe down the notification shade", "show the notification shade" -> shortcut(DeviceCommand.OPEN_NOTIFICATIONS)
        "open quick settings", "show quick settings", "pull down quick settings", "open quick panel" ->
            shortcut(DeviceCommand.OPEN_QUICK_SETTINGS)
        "lock the phone", "lock my phone", "lock the device", "lock phone", "lock my screen", "lock now" ->
            shortcut(DeviceCommand.LOCK_SCREEN)
        "hide keyboard", "hide the keyboard", "close keyboard", "close the keyboard", "dismiss keyboard",
        "dismiss the keyboard" -> shortcut(DeviceCommand.HIDE_KEYBOARD)
        "turn on hotspot", "turn on the hotspot", "enable hotspot", "turn on mobile hotspot",
        "open hotspot", "open hotspot settings", "turn on wifi hotspot", "start hotspot", "switch on hotspot" ->
            shortcut(DeviceCommand.HOTSPOT_SETTINGS)
        "connect to wifi", "connect to wi-fi", "connect to a wifi network", "connect to home wifi",
        "connect to home wi-fi", "connect to the wifi", "connect to the wi-fi", "join wifi", "join a wifi network" ->
            shortcut(DeviceCommand.OPEN_PANEL, mapOf("name" to "internet"))
        "turn on do not disturb", "turn on dnd", "enable do not disturb", "enable dnd", "do not disturb on",
        "dnd on", "switch on do not disturb" -> shortcut(DeviceCommand.DND_ON)
        "turn off do not disturb", "turn off dnd", "disable do not disturb", "disable dnd", "do not disturb off",
        "dnd off", "switch off do not disturb" -> shortcut(DeviceCommand.DND_OFF)
        else -> null
    }

    // ------------------------------------------------------------------ notifications / OTP ----

    private fun notifications(text: String): ResolvedIntentShortcut? {
        when (text) {
            "copy the otp", "copy otp", "copy the verification code", "copy verification code", "copy the code",
            "copy the otp from sms", "copy otp from sms", "copy the otp from messages", "copy the code from sms",
            "copy the otp from the notification", "copy the latest otp", "copy my otp" ->
                return shortcut(DeviceCommand.OTP_COPY)
            "is there an otp", "did i get an otp", "check for otp", "check for an otp", "any otp" ->
                return shortcut(DeviceCommand.OTP_STATUS)
            "read my notifications", "read notifications", "what are my notifications", "check my notifications",
            "show recent notifications", "what notifications do i have", "read my latest notifications" ->
                return shortcut(DeviceCommand.NOTIFICATION_LIST)
            "clear notifications", "clear all notifications", "dismiss all notifications", "clear my notifications",
            "dismiss notifications", "remove all notifications" ->
                return shortcut(DeviceCommand.NOTIFICATION_DISMISS, mapOf("all" to "true"))
            "read clipboard", "read my clipboard", "what's on my clipboard", "whats on my clipboard",
            "what is on my clipboard", "show clipboard", "show my clipboard" ->
                return shortcut(DeviceCommand.CLIPBOARD_GET)
        }
        val dismiss = DISMISS_NOTIFICATION_REGEX.find(text) ?: return null
        val what = dismiss.groupValues[1].trim()
        if (what in setOf("all", "my", "the", "every", "all my", "all the") || what.endsWith(" settings")) return null
        return shortcut(DeviceCommand.NOTIFICATION_DISMISS, mapOf("match" to what))
    }

    // ------------------------------------------------------------------ files / storage --------

    private fun files(text: String): ResolvedIntentShortcut? {
        if (Regex("""^(?:check\s+)?(?:how much\s+)?(?:internal\s+)?(?:storage|space)(?:\s+is)?(?:\s+(?:left|free|available|remaining))?$""").matches(text) &&
            text != "storage" && text != "space"
        ) return shortcut(DeviceCommand.STORAGE_INFO)
        if (text in setOf("how much storage do i have left", "how much space do i have left", "how much free space do i have",
                "check storage space", "check my storage", "check free space", "check phone storage")
        ) return shortcut(DeviceCommand.STORAGE_INFO)

        FIND_FILE_REGEX.find(text)?.let { m ->
            val kind = kindOf(m.groupValues[1])
            val within = when (m.groupValues[2]) {
                "today" -> "24"
                "yesterday" -> "48"
                "this week" -> "168"
                "this month" -> "720"
                else -> ""
            }
            return shortcut(DeviceCommand.FILE_FIND, mapOf("kind" to kind, "within_hours" to within))
        }
        OPEN_LATEST_REGEX.find(text)?.let { m ->
            val noun = m.groupValues[1]
            val query = if (noun in setOf("bill", "invoice", "receipt")) noun else ""
            val kind = if (query.isNotEmpty()) "pdf" else kindOf(noun)
            return shortcut(DeviceCommand.FILE_OPEN, mapOf("kind" to kind, "query" to query))
        }
        return null
    }

    private fun kindOf(noun: String): String = when {
        noun.startsWith("pdf") -> "pdf"
        noun.startsWith("screenshot") -> "screenshot"
        noun.startsWith("image") || noun.startsWith("photo") || noun.startsWith("picture") -> "image"
        noun.startsWith("download") -> "download"
        noun.startsWith("video") -> "video"
        noun.startsWith("song") || noun == "audio" -> "audio"
        noun.startsWith("document") -> "document"
        else -> "download"
    }

    // ------------------------------------------------------------------ navigation -------------

    private fun navigation(rawText: String): ResolvedIntentShortcut? {
        val m = NAVIGATE_REGEX.find(rawText) ?: return null
        val lowerAll = rawText.lowercase()
        val destination = when {
            lowerAll.endsWith(" home") || lowerAll == "navigate home" || lowerAll == "take me home" -> "home"
            else -> m.groupValues[1].trim().trim('"', '\'')
        }
        if (destination.isBlank()) return null
        val mode = when {
            Regex("""\b(?:walk|walking|on foot)\b""", RegexOption.IGNORE_CASE).containsMatchIn(destination) -> "w"
            Regex("""\b(?:bike|cycling|bicycle)\b""", RegexOption.IGNORE_CASE).containsMatchIn(destination) -> "b"
            else -> "d"
        }
        val cleaned = destination
            .replace(Regex("""\s+(?:by|on)\s+(?:foot|bike|bicycle|car)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:walking|cycling)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return shortcut(DeviceCommand.NAVIGATE, mapOf("destination" to cleaned, "mode" to mode))
    }

    // ------------------------------------------------------------------ alarms -----------------

    private fun alarmDismiss(text: String): ResolvedIntentShortcut? = when (text) {
        "dismiss alarm", "dismiss the alarm", "stop the alarm", "stop alarm", "turn off the alarm that is ringing",
        "silence the alarm", "dismiss the ringing alarm" -> shortcut(DeviceCommand.ALARM_DISMISS, mapOf("mode" to "next"))
        else -> null
    }

    // ------------------------------------------------------------------ messaging --------------

    private fun messaging(rawText: String): ResolvedIntentShortcut? {
        WHATSAPP_REGEX.find(rawText)?.let { m ->
            val who = m.groupValues[1].trim()
            return shortcut(DeviceCommand.MESSAGE_WHATSAPP, whoArgs(who) + ("text" to m.groupValues[2].trim()))
        }
        WHATSAPP_OPEN_REGEX.find(rawText)?.let { m ->
            val who = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (who.isNotEmpty()) return shortcut(DeviceCommand.MESSAGE_WHATSAPP, whoArgs(who))
        }
        TELEGRAM_SEND_REGEX.find(rawText)?.let { m ->
            return shortcut(DeviceCommand.MESSAGE_TELEGRAM, mapOf("to" to m.groupValues[1].trim(), "text" to m.groupValues[2].trim()))
        }
        TELEGRAM_OPEN_REGEX.find(rawText)?.let { m ->
            return shortcut(DeviceCommand.MESSAGE_TELEGRAM, mapOf("to" to m.groupValues[1].trim()))
        }
        SMS_REGEX.find(rawText)?.let { m ->
            return shortcut(DeviceCommand.MESSAGE_SMS, whoArgs(m.groupValues[1].trim()) + ("text" to m.groupValues[2].trim()))
        }
        return null
    }

    /** A phone number goes in `phone`, anything else is a saved contact name for the Android side to resolve. */
    private fun whoArgs(who: String): Map<String, String> {
        val digits = who.filter { it.isDigit() || it == '+' }
        return if (digits.count { it.isDigit() } >= 6 && who.none { it.isLetter() }) mapOf("phone" to digits)
        else mapOf("contact" to who.trim('"', '\''))
    }

    // ------------------------------------------------------------------ reminders / calendar ---

    private fun reminder(rawText: String, clock: Clock): ResolvedIntentShortcut? {
        // Task-first is tried first: "remind me to call mom tomorrow at 9" would otherwise be read as
        // day-first with the time swallowed into the task text.
        REMIND_TASK_FIRST.find(rawText)?.let { m ->
            val begin = beginOf(m.groupValues[2], m.groupValues[3], clock)
            return calendarShortcut(m.groupValues[1].trim(), begin)
        }
        REMIND_DAY_FIRST.find(rawText)?.let { m ->
            val begin = beginOf(m.groupValues[1], m.groupValues[2], clock)
            return calendarShortcut(m.groupValues[3].trim(), begin)
        }
        return null
    }

    private fun calendar(rawText: String, clock: Clock): ResolvedIntentShortcut? {
        val m = CALENDAR_REGEX.find(rawText) ?: return null
        val title = m.groupValues[1].trim().trim('"', '\'')
        if (title.isBlank()) return null
        val whenText = m.groupValues[2].lowercase()
        val day = Regex("""\b(today|tonight|tomorrow)\b""").find(whenText)?.value.orEmpty()
        val time = Regex("""\bat\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)""").find(whenText)?.groupValues?.get(1).orEmpty()
        return calendarShortcut(title, beginOf(day, time, clock))
    }

    private fun calendarShortcut(title: String, begin: String?): ResolvedIntentShortcut? {
        if (title.isBlank()) return null
        return shortcut(DeviceCommand.CALENDAR_INSERT, mapOf("title" to title, "begin" to begin.orEmpty()))
    }

    /**
     * ISO local date-time for "[today|tonight|tomorrow] [time]", or null when no time was named.
     * A time that has already passed today rolls to tomorrow, which is what "remind me at 9" means at 10pm.
     */
    internal fun beginOf(day: String, time: String, clock: Clock): String? {
        var parsed = parseClockTime(time) ?: return null
        // "tonight at 9" means 21:00 unless the speaker said am/pm.
        if (day.equals("tonight", ignoreCase = true) && parsed.hour in 1..11 &&
            !time.contains("am", ignoreCase = true) && !time.contains("pm", ignoreCase = true)
        ) parsed = parsed.plusHours(12)
        val now = LocalDateTime.now(clock)
        var date: LocalDate = now.toLocalDate()
        when (day.lowercase()) {
            "tomorrow" -> date = date.plusDays(1)
            "today", "tonight" -> Unit
            else -> if (!LocalDateTime.of(date, parsed).isAfter(now)) date = date.plusDays(1)
        }
        return LocalDateTime.of(date, parsed).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    private fun parseClockTime(text: String): LocalTime? {
        if (text.isBlank()) return null
        val m = CLOCK_TIME.matchEntire(text.trim()) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val meridiem = m.groupValues[3].lowercase()
        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }
}
