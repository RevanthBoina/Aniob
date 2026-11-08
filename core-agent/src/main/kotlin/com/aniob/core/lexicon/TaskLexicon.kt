package com.aniob.core.lexicon

/**
 * The ONE vocabulary every sentence-reading layer uses: action verbs, toggle verbs, pronouns,
 * confirmations/negations, politeness noise, spoken app roles and phrase→role affinity.
 *
 * Why it exists: the same word list was copied into the intent classifier, the clarification engine,
 * the planner, the replay-cache key and the chat router. A verb added in one place stayed invisible
 * in the other four, which is how "subscribe it", "on voice recording" and "can you install ludo"
 * each fell into a different (wrong) path. Adding an entry here now extends routing, clarification,
 * parameter templates, pronoun binding and capability checks at once.
 *
 * Pure data + pure functions: no android.*, no model call, fully unit-testable.
 */
object TaskLexicon {

    /** Verbs that take an object and therefore start a task ("subscribe <channel>"). */
    val ACTION_VERBS: List<String> = listOf(
        "subscribe to", "unsubscribe from", "subscribe", "unsubscribe", "follow", "unfollow", "like", "dislike",
        "install", "uninstall", "delete", "remove", "clear", "open", "launch", "start", "close", "call", "message",
        "text", "play", "search for", "search", "find", "look up", "connect with", "connect", "block", "unblock",
        "mute", "unmute", "share", "save", "download", "update", "turn on", "turn off", "switch on", "switch off",
        "enable", "disable", "set"
    )

    /**
     * Verbs whose object is a NAME the user supplies (a person, a video, a query). Used by the replay
     * cache to turn that object into a parameter, so one verified trace replays for every other name.
     */
    val NAME_BEARING_VERBS: List<String> = listOf(
        "search for", "search in", "search on", "search", "play", "call", "text", "message", "find",
        "look up", "subscribe to", "subscribe", "follow", "connect with", "add contact", "email", "write to"
    )

    /** Verbs that remove or inspect an installed app; the system dialog stays the confirmation. */
    val REMOVAL_VERBS = listOf("uninstall", "delete", "remove", "get rid of", "drop")

    /** Words that turn a bare on/off command into a device action. */
    val TOGGLE_ON = listOf("turn on", "switch on", "power on", "enable", "activate", "on")
    val TOGGLE_OFF = listOf("turn off", "switch off", "power off", "disable", "deactivate", "off")

    /** Anaphora that must be bound to an entity from an earlier turn. */
    val PRONOUNS = setOf("it", "them", "that", "this", "those", "these", "him", "her", "that one", "the same one")
    val PRONOUN_TAILS = setOf("channel", "app", "one", "page", "account", "profile", "video", "contact", "file")

    val CONFIRMATIONS = setOf(
        "yes", "yeah", "yep", "yup", "ya", "ok", "okay", "sure", "do it", "go ahead", "proceed", "confirm",
        "yes do it", "yes please", "affirmative", "correct", "right", "please do", "go"
    )
    val NEGATIONS = setOf("no", "nope", "nah", "cancel", "never mind", "forget it", "stop", "not that", "wrong one", "abort")

    /** Filler that carries no intent and must not reach a matcher. */
    private val POLITE_PREFIX = Regex("^(?:(?:please|pls|kindly|can you|could you|would you|will you|hey|hi|hello|aniob|now|just|kindly)[, ]+)+")
    private val POLITE_SUFFIX = Regex("(?:[, ]+(?:please|pls|kindly|for me|thanks|thank you))+$")

    fun stripPoliteness(text: String): String =
        POLITE_SUFFIX.replace(POLITE_PREFIX.replace(text.trim(), ""), "").trim()

    fun isActionVerb(word: String): Boolean = ACTION_VERBS.any { word == it || word.startsWith("$it ") }

    /** "on voice recording" / "turn off the recorder" -> ("on", "voice recording"). */
    fun splitToggle(text: String): Pair<String, String>? {
        val lower = stripPoliteness(text).lowercase()
        val all = (TOGGLE_ON.map { it to "on" } + TOGGLE_OFF.map { it to "off" }).sortedByDescending { it.first.length }
        for ((phrase, polarity) in all) {
            val rest = when {
                lower == phrase -> ""
                lower.startsWith("$phrase ") -> lower.removePrefix(phrase).trim()
                else -> continue
            }.removePrefix("the ").removePrefix("my ").trim()
            if (rest.isNotBlank()) return polarity to rest
        }
        return null
    }

    /**
     * Words a user says for an app *role*, matched against the launcher labels of the apps really
     * installed on this device — so the same phrase works on any OEM build.
     */
    val APP_ROLES: Map<String, List<String>> = mapOf(
        "voice recording" to listOf("recorder", "record", "voice"),
        "recorder" to listOf("recorder", "record"),
        "notes" to listOf("notes", "note", "keep", "memo"),
        "browser" to listOf("chrome", "browser", "firefox", "edge", "samsung internet"),
        "gallery" to listOf("gallery", "photos", "pictures"),
        "music" to listOf("music", "spotify", "player", "audio"),
        "files" to listOf("files", "file manager", "my files"),
        "camera" to listOf("camera"),
        "calendar" to listOf("calendar"),
        "clock" to listOf("clock", "alarm"),
        "mail" to listOf("gmail", "mail", "email", "outlook"),
        "maps" to listOf("maps", "navigation"),
        "calculator" to listOf("calculator")
    )

    /** Messaging-capable roles: the clarification options are built from what is installed, never a fixed trio. */
    val MESSAGING_ROLES = listOf("whatsapp", "telegram", "signal", "messenger", "messages", "sms", "chat", "linkedin", "instagram", "mail", "gmail")

    /**
     * Phrase → app role affinity for requests whose app is implied by the verb, not named
     * ("send a connect request" is a professional-network action, not an SMS).
     * Extend by adding a row; resolution always goes through the installed inventory.
     */
    data class Affinity(
        val role: String,
        val labelKeywords: List<String>,
        /** Fallback package ids when no installed label matches (still verified against the inventory). */
        val knownPackages: List<String> = emptyList(),
        /** The request also needs a person/thing inside that app. */
        val asksPerson: Boolean = true
    )

    val AFFINITIES: List<Pair<Regex, Affinity>> = listOf(
        Regex("\\b(connect(ion)? requests?|connect (with|to)|endorse|inmail|recruit)\\b") to
            Affinity("linkedin", listOf("linkedin"), listOf("com.linkedin.android")),
        Regex("\\b(subscribe|unsubscribe|channel|playlist)\\b") to
            Affinity("youtube", listOf("youtube"), listOf("com.google.android.youtube")),
        Regex("\\b(tweet|retweet|repost|thread)\\b") to
            Affinity("x", listOf("x", "twitter"), listOf("com.twitter.android")),
        Regex("\\b(follow request|story|stories|reel)\\b") to
            Affinity("instagram", listOf("instagram"), listOf("com.instagram.android")),
        Regex("\\b(pin|board)\\b") to Affinity("pinterest", listOf("pinterest")),
        Regex("\\b(repo|pull request|commit|issue)\\b") to Affinity("github", listOf("github"))
    )

    fun affinityFor(lowerPrompt: String): Affinity? =
        AFFINITIES.firstOrNull { it.first.containsMatchIn(lowerPrompt) }?.second

    fun roleKeywords(spoken: String): List<String> {
        val key = spoken.lowercase().removeSuffix(" app").removeSuffix(" application").trim()
        return APP_ROLES.entries.firstOrNull { key == it.key || key.contains(it.key) || it.key.contains(key) }?.value
            ?: key.split(' ').filter { it.length > 2 }
    }
}
