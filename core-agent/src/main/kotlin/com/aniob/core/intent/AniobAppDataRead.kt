package com.aniob.core.intent

/**
 * Data that lives inside an installed application and can only be read there.
 *
 * This is deliberately a *data* catalogue, not a verb catalogue: which app owns the content, and
 * which noun the user used, is what makes "read my newest emails" different from "what is Gmail?".
 * The verbs (check / search / find / open …) already live in
 * [AniobTaskIntentClassifier]'s device-verb table; they are **not** repeated here.
 */
enum class AppDataKind(
    /** How the user names this data. */
    val nouns: Set<String>,
    /** Registry role the platform reports for an app that owns this data, when one exists. */
    val registryRole: String? = null
) {
    EMAIL(
        nouns = setOf(
            "email", "emails", "e-mail", "e-mails", "mail", "mails", "inbox", "mailbox",
            "gmail", "outlook", "yahoo mail", "protonmail"
        ),
        registryRole = "email"
    ),
    MESSAGES(
        nouns = setOf(
            "message", "messages", "chat", "chats", "dm", "dms", "sms", "texts",
            "whatsapp", "telegram", "slack", "signal", "messenger"
        ),
        registryRole = "messages"
    );

    companion object {
        /** Longest noun first, so "e-mails" is not read as "mail" plus noise. */
        internal val byLength: List<Pair<String, AppDataKind>> =
            entries.flatMap { kind -> kind.nouns.map { it to kind } }
                .sortedByDescending { it.first.length }
    }
}

/**
 * A request to **read data out of an app** ("check my two newest emails", "summarize the newest
 * emails in Gmail", "what are my latest messages?").
 *
 * [requestedCount] is the number of items the user asked for, when they named one. `null` means
 * "whatever the list shows" — never "one", because inventing a count would either under-read the
 * request or invite the agent to invent items it never observed.
 *
 * [appHint] is the app the request names ("gmail", "whatsapp"), still unresolved: turning a name
 * into a package is [com.aniob.core.ladder.AniobAppRegistry]'s job, never this file's.
 */
data class AppDataReadRequest(
    val kind: AppDataKind,
    val noun: String,
    val requestedCount: Int?,
    val appHint: String?,
    /** "newest", "latest", "unread", … — kept so a summary can say what was asked for. */
    val qualifier: String?
) {
    /** "the 2 newest emails" / "the latest messages" — used in narration and honest reports. */
    fun describe(): String {
        val count = requestedCount?.let { "$it " }.orEmpty()
        val qualifierPart = qualifier?.let { "$it " }.orEmpty()
        val app = appHint?.let { " in $it" }.orEmpty()
        return "the $count$qualifierPart$noun$app".replace("  ", " ")
    }
}

/**
 * Recognition of "read app data" requests — the narrow rule behind Part 1 of the app-reading work.
 *
 * Why a separate rule exists at all: [AniobTaskIntentClassifier] answers every question-form prompt
 * with `KNOWLEDGE_QA`, so "What are my two newest emails?" was answered with prose and zero device
 * steps, and "Summarize the newest emails in Gmail" never opened Gmail. The opposite mistake
 * matters just as much — "What is Gmail?" and "How does Gmail work?" are ordinary questions and
 * must stay conversational — so the rule is written as three *positive* conditions and an explicit
 * list of exclusions, and is tested from both sides.
 *
 * A request is app-data retrieval only when all of the following hold:
 *
 *  1. it names app-owned **data** (a noun from [AppDataKind]) — naming an app is not enough;
 *  2. it shows the data is the user's own or a *specific slice* of a list: a possessive
 *     ("my", "our") or a quantity/recency word ("newest", "latest", "two", "unread" …);
 *  3. it is a read request: either an explicit read cue ("read", "summarize", "show", "list",
 *     "what are" …), or possession **plus** a slice word with no writing verb anywhere
 *     ("check my two newest emails" uses "check", which is already a device verb and is therefore
 *     not listed again here).
 *
 * Excluded always: definition/explanation/how-to forms and composing forms ("write", "compose",
 * "draft", "send", "reply", "forward", "explain", "how does", "what is", "define", "meaning of",
 * "template", "etiquette", "tips"). Those are answered as conversation, unchanged.
 */
object AniobAppDataRead {

    /** Read cues. "check", "search" and "find" are intentionally absent: they are device verbs. */
    private val readCues = listOf(
        "read", "summarize", "summarise", "sum up", "review", "show", "list", "see", "look at",
        "go through", "catch up on", "catch up with", "any new", "are there", "how many",
        "what are", "what's", "whats", "which are", "tell me my", "open"
    )

    /** Forms that ask *about* email/messages rather than asking to read them. */
    private val explanationCues = listOf(
        "what is", "what are the", "what's the", "how does", "how do", "how to", "how can i",
        "why ", "explain", "define", "definition", "meaning of", "tell me about", "history of",
        "difference between", "tips", "etiquette", "template", "example of", "draft an email about"
    )

    /** Forms that ask Aniob to *produce* a message. Those are compose tasks, handled elsewhere. */
    private val writingVerbs = listOf(
        "write", "compose", "draft", "send", "reply", "forward", "type", "author", "email ", "mail "
    )

    private val possessiveRx = Regex("""\b(?:my|our|mine)\b""")

    /**
     * A *slice* of a list: recency, unread state, or a count. "emails" alone is a topic;
     * "my newest emails" is data the agent has to go and look at.
     */
    private val sliceCues = listOf(
        "newest", "latest", "recent", "unread", "unseen", "last", "first", "top", "today",
        "this morning", "this week", "important", "starred", "oldest"
    )

    private val numberWords = mapOf(
        "one" to 1, "a" to 1, "single" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    /** App names people put after "in"/"on": they are hints for the registry, not data nouns. */
    private val appHints = listOf(
        "gmail", "outlook", "yahoo mail", "protonmail", "whatsapp", "telegram", "slack", "signal",
        "messenger", "instagram", "facebook"
    )

    /**
     * The app-data request in [prompt], or null when the prompt is not one.
     *
     * Pure and side-effect free; the classifier, the intent resolver and the app layer all call
     * this same function so "which prompts are read requests" has exactly one answer.
     */
    fun detect(prompt: String): AppDataReadRequest? {
        val text = AniobTaskIntentClassifier.normalizeLexical(
            AniobTaskIntentClassifier.stripPreamble(
                prompt.lowercase().trim().trimEnd('?', '.', '!', ',', ';', ' ')
            )
        )
        if (text.isBlank()) return null

        // 1. The data the request is about. Longest noun wins ("e-mails" before "mail").
        val nounHit = AppDataKind.byLength.firstOrNull { (noun, _) -> containsWord(text, noun) }
            ?: return null
        val (noun, kind) = nounHit

        // 2. An explanation or a compose/authoring request is not a read request. Checked before
        //    anything else so "explain how to organize an inbox" can never reach the device path.
        if (explanationCues.any { text.startsWith(it) || text.contains(" $it") }) return null
        if (startsWithWritingVerb(text)) return null
        if (text.contains("template") || text.contains("etiquette") || text.contains("tips")) return null

        val hasPossessive = possessiveRx.containsMatchIn(text)
        val slice = sliceCues.firstOrNull { containsWord(text, it) }
        val hasReadCue = readCues.any { cue -> containsWord(text, cue) || text.startsWith("$cue ") }
        val count = requestedCount(text)
        // The app the request names, if any. "in gmail" / "on whatsapp" / a bare app noun.
        val hint = appHints.firstOrNull { containsWord(text, it) }
        val pointsAtOwnData = hasPossessive || slice != null || count != null || hint != null ||
            namedAppClause(text) != null

        // 3a. A read cue alone is a topic ("summarize this chat", "show the messages app"): it
        //     becomes app-data retrieval only when the prompt also points at the user's own data.
        //     Without a read cue, possession **plus** a slice is enough ("check my two newest
        //     emails." — "check" is a device verb already, so it is not repeated in [readCues]).
        val looksLikeRead = if (hasReadCue) pointsAtOwnData else (hasPossessive && (slice != null || count != null))
        if (!looksLikeRead) return null

        // 3b. A noun that is also an app name ("gmail", "whatsapp") is only read intent together
        //     with ownership or a slice: "what is Gmail" / "how does Gmail work" stay questions.
        val nounIsAppName = appHints.any { it == noun }
        if (nounIsAppName && !hasPossessive && slice == null && count == null) return null

        return AppDataReadRequest(
            kind = kind,
            noun = noun,
            requestedCount = count,
            appHint = hint,
            qualifier = slice
        )
    }

    /** True when [prompt] asks Aniob to read content out of an app. */
    fun isReadRequest(prompt: String): Boolean = detect(prompt) != null

    /** "two newest", "3 latest", "a few" → 2 / 3 / 3. Null when no count was named. */
    internal fun requestedCount(text: String): Int? {
        Regex("""\b(\d{1,2})\b""").find(text)?.let { match ->
            // The count only counts when it sits next to the data noun the user asked about,
            // e.g. "two newest emails" — a number in "set an alarm for 7" is not a message count.
            val index = match.range.first
            val window = text.substring((index - 24).coerceAtLeast(0), (index + 24).coerceAtMost(text.length))
            if (sliceCues.any { containsWord(window, it) } || window.contains("email") ||
                window.contains("message") || window.contains("mail") || window.contains("chat")
            ) {
                val value = match.groupValues[1].toIntOrNull() ?: return null
                if (value in 1..20) return value
            }
            return null
        }
        for ((word, value) in numberWords) {
            if (containsWord(text, word) && (sliceCues.any { containsWord(text, it) })) {
                // "a"/"one" only count when the noun is right there: "a" is far too common.
                if (value == 1 && (word == "a" || word == "single") &&
                    !Regex("""\b(?:a|one|single)\s+(?:new\s+|unread\s+|latest\s+|newest\s+)?(?:email|e-mail|mail|message|chat|dm)s?\b""")
                        .containsMatchIn(text)
                ) {
                    continue
                }
                if (word != "a") return value
            }
        }
        val fewRx = Regex("""\b(?:a few|couple of|several)\b""")
        if (fewRx.containsMatchIn(text)) return 3
        return null
    }

    /** "… in gmail" / "… on whatsapp" / "… inside outlook": the app named as a location. */
    private fun namedAppClause(text: String): String? = appHints.firstOrNull { app ->
        Regex("""\b(?:in|on|inside|from|using)\s+(?:the\s+|my\s+)?${Regex.escape(app)}(?![a-z0-9])""")
            .containsMatchIn(text)
    }

    private fun startsWithWritingVerb(text: String): Boolean =
        writingVerbs.any { verb -> text.startsWith(verb.trim() + " ") || text.startsWith(verb.trim() + " a ") }

    /** Word-boundary containment that tolerates the hyphens people put in "e-mail". */
    private fun containsWord(text: String, word: String): Boolean {
        val escaped = Regex.escape(word).replace("\\-", "[- ]?")
        return Regex("""(?<![a-z0-9])$escaped(?![a-z0-9])""").containsMatchIn(text)
    }
}
