package com.aniob.core.domain

/** Where a [DeviceCommand] sits in the prompt catalogue. */
enum class DeviceCommandGroup(val label: String) {
    MEDIA("Media"),
    VOLUME("Volume"),
    HARDWARE("Hardware & system"),
    COMMS("Calls & messages"),
    NOTIFICATIONS("Notifications, OTP & clipboard"),
    FILES("Files & storage"),
    TIME("Timers, alarms & calendar"),
    MAPS("Maps & location"),
    WEB("Web")
}

/** [CONFIRM] commands are hard to undo (they call, send or share) and always ask the user first. */
enum class DeviceRisk { LOW, CONFIRM }

/**
 * Closed catalogue of **direct device commands**: things a person does with the phone that have a
 * documented Android API and therefore never need a screen walk or a model round trip.
 *
 * This enum is the single source of truth for four consumers, so they cannot drift apart:
 *  - `AniobActionSchema` (what an LLM may emit as `{"tool":"DEVICE_CONTROL","command":"..."}`),
 *  - `AniobCapabilityManifest` (what the prompt tells the model it can do),
 *  - `AniobReflexResolver` (phrases that skip the model entirely), and
 *  - `AniobDeviceControl` in `:app` (the Android implementation, one branch per entry).
 *
 * [required] entries are argument names; `"a|b"` means "at least one of a or b".
 * Pure Kotlin JVM: no `android.*` imports.
 */
enum class DeviceCommand(
    val wire: String,
    val group: DeviceCommandGroup,
    val summary: String,
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
    val risk: DeviceRisk = DeviceRisk.LOW,
    /**
     * True when the dispatch *is* the outcome (volume set, torch on, pause). False when the command
     * only opens something the user or the UI loop still has to finish (a message draft, an editor).
     */
    val completesOnDispatch: Boolean = true,
    val needsAccessibility: Boolean = false,
    val needsNotificationAccess: Boolean = false
) {
    // ---- Media -------------------------------------------------------------------------------
    MEDIA_PLAY_PAUSE("media_play_pause", DeviceCommandGroup.MEDIA, "toggle play/pause of the active player"),
    MEDIA_PLAY("media_play", DeviceCommandGroup.MEDIA, "resume playback"),
    MEDIA_PAUSE("media_pause", DeviceCommandGroup.MEDIA, "pause playback"),
    MEDIA_NEXT("media_next", DeviceCommandGroup.MEDIA, "skip to the next track"),
    MEDIA_PREVIOUS("media_previous", DeviceCommandGroup.MEDIA, "previous track"),
    MEDIA_STOP("media_stop", DeviceCommandGroup.MEDIA, "stop playback"),
    MEDIA_PLAY_SEARCH(
        "media_play_search", DeviceCommandGroup.MEDIA, "search and play a song/artist in a music app",
        required = listOf("query"), optional = listOf("app"), completesOnDispatch = false
    ),
    MEDIA_NOW_PLAYING(
        "media_now_playing", DeviceCommandGroup.MEDIA, "report the current track and player state",
        needsNotificationAccess = true
    ),

    // ---- Volume ------------------------------------------------------------------------------
    VOLUME_SET(
        "volume_set", DeviceCommandGroup.VOLUME, "set a volume stream to a percentage",
        required = listOf("percent"), optional = listOf("stream")
    ),
    VOLUME_UP("volume_up", DeviceCommandGroup.VOLUME, "raise volume", optional = listOf("steps", "stream")),
    VOLUME_DOWN("volume_down", DeviceCommandGroup.VOLUME, "lower volume", optional = listOf("steps", "stream")),
    VOLUME_MUTE("volume_mute", DeviceCommandGroup.VOLUME, "mute a stream", optional = listOf("stream")),
    VOLUME_UNMUTE("volume_unmute", DeviceCommandGroup.VOLUME, "unmute a stream", optional = listOf("stream")),
    VOLUME_GET("volume_get", DeviceCommandGroup.VOLUME, "read current volumes", optional = listOf("stream")),

    // ---- Hardware & system -------------------------------------------------------------------
    TORCH_ON(
        "torch_on", DeviceCommandGroup.HARDWARE, "flashlight on",
        optional = listOf("then_command", "then_delay_ms")
    ),
    TORCH_OFF(
        "torch_off", DeviceCommandGroup.HARDWARE, "flashlight off",
        optional = listOf("then_command", "then_delay_ms")
    ),
    SCREENSHOT("screenshot", DeviceCommandGroup.HARDWARE, "take a screenshot", needsAccessibility = true),
    OPEN_NOTIFICATIONS(
        "open_notifications", DeviceCommandGroup.HARDWARE, "pull down the notification shade",
        needsAccessibility = true, completesOnDispatch = false
    ),
    OPEN_QUICK_SETTINGS(
        "open_quick_settings", DeviceCommandGroup.HARDWARE, "open the quick-settings tiles",
        needsAccessibility = true, completesOnDispatch = false
    ),
    LOCK_SCREEN("lock_screen", DeviceCommandGroup.HARDWARE, "lock the phone", needsAccessibility = true),
    HIDE_KEYBOARD(
        "hide_keyboard", DeviceCommandGroup.HARDWARE, "dismiss the on-screen keyboard if it is covering a button",
        needsAccessibility = true
    ),
    OPEN_PANEL(
        "open_panel", DeviceCommandGroup.HARDWARE, "open a system panel: internet|wifi|volume|nfc",
        required = listOf("name"), completesOnDispatch = false
    ),
    HOTSPOT_SETTINGS(
        "open_hotspot_settings", DeviceCommandGroup.HARDWARE, "open the hotspot/tethering screen to toggle it",
        completesOnDispatch = false
    ),
    DND_ON("dnd_on", DeviceCommandGroup.HARDWARE, "enable Do Not Disturb"),
    DND_OFF("dnd_off", DeviceCommandGroup.HARDWARE, "disable Do Not Disturb"),

    // ---- Calls & messages --------------------------------------------------------------------
    CALL(
        "call", DeviceCommandGroup.COMMS, "place a call (speaker=true for speakerphone)",
        required = listOf("number|contact"), optional = listOf("speaker"), risk = DeviceRisk.CONFIRM
    ),
    MESSAGE_WHATSAPP(
        "whatsapp_message", DeviceCommandGroup.COMMS, "open a WhatsApp chat (number or saved contact name) with the text pre-filled, then tap Send",
        required = listOf("phone|contact"), optional = listOf("text"), completesOnDispatch = false
    ),
    MESSAGE_TELEGRAM(
        "telegram_message", DeviceCommandGroup.COMMS, "open a Telegram chat (phone, @username or contact name) with text pre-filled",
        required = listOf("to"), optional = listOf("text"), completesOnDispatch = false
    ),
    MESSAGE_SMS(
        "sms_message", DeviceCommandGroup.COMMS, "open an SMS draft",
        required = listOf("phone|contact"), optional = listOf("text"), completesOnDispatch = false
    ),

    // ---- Notifications, OTP, clipboard -------------------------------------------------------
    NOTIFICATION_LIST(
        "notification_list", DeviceCommandGroup.NOTIFICATIONS, "read recent notifications (codes are masked)",
        optional = listOf("package", "limit"), needsNotificationAccess = true
    ),
    NOTIFICATION_DISMISS(
        "notification_dismiss", DeviceCommandGroup.NOTIFICATIONS, "dismiss notifications (all=true, or match=<words in it>, package, key)",
        optional = listOf("match", "package", "key", "all"), needsNotificationAccess = true
    ),
    NOTIFICATION_REPLY(
        "notification_reply", DeviceCommandGroup.NOTIFICATIONS, "reply inline to a messaging notification (match=<sender or words>)",
        required = listOf("text"), optional = listOf("match", "package", "key"),
        risk = DeviceRisk.CONFIRM, needsNotificationAccess = true
    ),
    NOTIFICATION_ACTION(
        "notification_action", DeviceCommandGroup.NOTIFICATIONS, "press a notification button by its title",
        required = listOf("title"), optional = listOf("match", "package", "key"), needsNotificationAccess = true
    ),
    OTP_STATUS(
        "otp_status", DeviceCommandGroup.NOTIFICATIONS, "is a fresh verification code waiting (never reveals digits)",
        needsNotificationAccess = true
    ),
    OTP_COPY(
        "otp_copy", DeviceCommandGroup.NOTIFICATIONS, "copy the newest verification code to the clipboard",
        needsNotificationAccess = true
    ),
    OTP_PASTE(
        "otp_paste", DeviceCommandGroup.NOTIFICATIONS, "type the newest verification code into the focused field",
        risk = DeviceRisk.CONFIRM, needsAccessibility = true, needsNotificationAccess = true
    ),
    CLIPBOARD_GET("clipboard_get", DeviceCommandGroup.NOTIFICATIONS, "read the clipboard text"),
    CLIPBOARD_SET(
        "clipboard_set", DeviceCommandGroup.NOTIFICATIONS, "put text on the clipboard",
        required = listOf("text")
    ),
    CLIPBOARD_PASTE(
        "clipboard_paste", DeviceCommandGroup.NOTIFICATIONS, "paste the clipboard into the focused field",
        needsAccessibility = true
    ),

    // ---- Files & storage ---------------------------------------------------------------------
    FILE_FIND(
        "file_find", DeviceCommandGroup.FILES,
        "query MediaStore: kind=pdf|image|screenshot|download|audio|video|document; extension=py|xlsx|... for file type",
        optional = listOf("query", "kind", "within_hours", "older_than_days", "limit", "extension")
    ),
    FILE_OPEN(
        "file_open", DeviceCommandGroup.FILES, "open the newest match (or a uri) in its viewer",
        optional = listOf("query", "kind", "uri", "within_hours"), completesOnDispatch = false
    ),
    FILE_READ(
        "file_read", DeviceCommandGroup.FILES,
        "read the text of a file found by query/uri or picked from a cloud provider (Drive etc); " +
            "pdf/docx/pptx/xlsx are extracted best-effort (pdf reports limits honestly)",
        optional = listOf("query", "kind", "uri", "within_hours", "limit"), completesOnDispatch = false
    ),
    FILE_SHARED(
        "file_shared", DeviceCommandGroup.FILES,
        "list files received into or picked by Aniob (shared via WhatsApp, Drive picker, share sheet)",
        optional = listOf("limit", "query")
    ),
    DOC_PICK(
        "doc_pick", DeviceCommandGroup.FILES,
        "open the system document picker for cloud/local files (Kind: pdf|document|presentation|spreadsheet|image|any)",
        optional = listOf("kind", "mime"), completesOnDispatch = false
    ),
    INSTALLED_APPS(
        "installed_apps", DeviceCommandGroup.HARDWARE,
        "list launchable apps installed on the device (name and package)",
        optional = listOf("limit", "query")
    ),
    STORAGE_INFO("storage_info", DeviceCommandGroup.FILES, "free and total storage"),

    // ---- Timers, alarms, calendar ------------------------------------------------------------
    TIMER_SET(
        "timer_set", DeviceCommandGroup.TIME, "start a countdown timer",
        required = listOf("seconds|minutes"), optional = listOf("label")
    ),
    ALARM_SET(
        "alarm_set", DeviceCommandGroup.TIME, "set an alarm (24h clock)",
        required = listOf("hour", "minute"), optional = listOf("label")
    ),
    ALARM_DISMISS(
        "alarm_dismiss", DeviceCommandGroup.TIME, "dismiss the ringing/next alarm (mode=next|all or label)",
        optional = listOf("mode", "label")
    ),
    ALARMS_SHOW("alarms_show", DeviceCommandGroup.TIME, "show the alarm list", completesOnDispatch = false),
    CALENDAR_INSERT(
        "calendar_insert", DeviceCommandGroup.TIME,
        "open a prefilled calendar event/reminder (begin/end as ISO 2026-03-05T09:00); the Save tap is still yours",
        required = listOf("title"), optional = listOf("begin", "end", "location", "description"),
        completesOnDispatch = false
    ),

    // ---- Maps & location ---------------------------------------------------------------------
    NAVIGATE(
        "navigate", DeviceCommandGroup.MAPS, "turn-by-turn navigation (mode=d|w|b|l)",
        required = listOf("destination"), optional = listOf("mode")
    ),
    MAPS_SEARCH("maps_search", DeviceCommandGroup.MAPS, "search places on the map", required = listOf("query")),
    LOCATION_SHARE(
        "location_share", DeviceCommandGroup.MAPS, "open the share sheet with the current location",
        risk = DeviceRisk.CONFIRM, completesOnDispatch = false
    ),

    // ---- Web ---------------------------------------------------------------------------------
    OPEN_URL(
        "open_url", DeviceCommandGroup.WEB, "open an http(s) URL directly, optionally in a browser package",
        required = listOf("url"), optional = listOf("browser")
    );

    /**
     * True when the command sends something out of the phone or to another person (a call, a message
     * draft, an inline reply, a URL, a shared location, a clipboard write). The step pipeline subjects
     * these to the same prompt-injection canary as a tap on "Send": an argument copied off the screen is
     * never trusted to choose the recipient.
     */
    val reachesOutside: Boolean
        get() = group == DeviceCommandGroup.COMMS || this == NOTIFICATION_REPLY || this == OPEN_URL ||
            this == CLIPBOARD_SET || this == LOCATION_SHARE

    /** Every argument name this command understands. */
    val argNames: List<String> = (required.flatMap { it.split('|') } + optional).distinct()

    /** Human usage line for prompts: `volume_set(percent, [stream])`. */
    fun usage(): String {
        val req = required.map { it }
        val opt = optional.map { "[$it]" }
        return wire + "(" + (req + opt).joinToString(", ") + ")"
    }

    /** First unmet requirement (`"percent"`, `"seconds|minutes"`) or null when [args] are complete. */
    fun missingRequired(args: Map<String, String>): String? =
        required.firstOrNull { spec -> spec.split('|').none { !args[it].isNullOrBlank() } }

    companion object {
        private val BY_WIRE: Map<String, DeviceCommand> = entries.associateBy { it.wire }

        /** Accepted spellings a small model tends to produce, mapped onto the canonical wire name. */
        private val ALIASES: Map<String, String> = mapOf(
            "flashlight_on" to "torch_on", "flashlight_off" to "torch_off",
            "flashlight" to "torch_on", "torch" to "torch_on",
            "play_pause" to "media_play_pause", "next_track" to "media_next", "skip_track" to "media_next",
            "previous_track" to "media_previous", "prev_track" to "media_previous",
            "set_volume" to "volume_set", "mute" to "volume_mute", "unmute" to "volume_unmute",
            "take_screenshot" to "screenshot", "notifications" to "open_notifications",
            "quick_settings" to "open_quick_settings", "set_timer" to "timer_set", "set_alarm" to "alarm_set",
            "dismiss_alarm" to "alarm_dismiss", "show_alarms" to "alarms_show",
            "add_event" to "calendar_insert", "calendar_event" to "calendar_insert",
            "navigate_to" to "navigate", "directions" to "navigate", "search_maps" to "maps_search",
            "whatsapp" to "whatsapp_message", "telegram" to "telegram_message", "sms" to "sms_message",
            "copy_otp" to "otp_copy", "paste_otp" to "otp_paste", "get_clipboard" to "clipboard_get",
            "set_clipboard" to "clipboard_set", "paste" to "clipboard_paste",
            "find_file" to "file_find", "open_file" to "file_open", "storage" to "storage_info",
            "read_file" to "file_read", "summarize_file" to "file_read", "file_summary" to "file_read",
            "list_shared_files" to "file_shared", "shared_files" to "file_shared", "received_files" to "file_shared",
            "pick_file" to "doc_pick", "choose_file" to "doc_pick", "pick_document" to "doc_pick",
            "choose_document" to "doc_pick", "file_picker" to "doc_pick",
            "list_apps" to "installed_apps", "apps_installed" to "installed_apps", "apps" to "installed_apps",
            "phone_call" to "call", "dial" to "call", "reply_notification" to "notification_reply",
            "dismiss_notification" to "notification_dismiss", "read_notifications" to "notification_list",
            "dnd" to "dnd_on", "do_not_disturb" to "dnd_on", "hotspot" to "open_hotspot_settings"
        )

        fun fromWire(name: String?): DeviceCommand? {
            val key = name?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            if (key.isEmpty()) return null
            return BY_WIRE[key] ?: ALIASES[key]?.let { BY_WIRE[it] }
        }

        /** Every canonical name, for error messages. */
        fun wireNames(): List<String> = entries.map { it.wire }
    }
}
