package com.aniob.core.intent

import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.capability.pattern.PatternCriteriaBridge
import com.aniob.core.capability.pattern.UniversalPlanner
import com.aniob.core.knowledge.AniobStoreCatalog

/**
 * Pure JVM Intent Gate Classifier.
 * Classifies tasks BEFORE entering the UI automation loop to avoid hallucinated taps
 * for knowledge questions, web research, or personal memory queries.
 */
enum class AniobIntent {
    DEVICE_AUTOMATION,    // open, tap, send, book, etc. -> UI automation ladder
    KNOWLEDGE_QA,         // what is, who is, explain, summarize -> external AI
    VAULT_QUERY,          // my preference, remember, what did I -> SharedKnowledgeStore
    WEB_RESEARCH,         // search the web, latest news, find information -> external AI with web
    EXTERNAL_AI_QUERY     // general generation, writing, coding -> external AI
}

object AniobTaskIntentClassifier {

    /** Common phonetic spellings collapsed so every downstream matcher sees canonical words. */
    private val lexicalFixes: List<Pair<Regex, String>> = listOf(
        Regex("""\bflash\s*light\b""") to "flashlight",
        Regex("""\b(?:flaslight|flashligh|flashlite)\b""") to "flashlight",
        Regex("""\b(?:tourch|troch)\b""") to "torch",
        Regex("""\bwifi\b""") to "wi-fi"
    )

    /** Lexical repair pass: "flash light" -> "flashlight", "tourch" -> "torch", ... */
    fun normalizeLexical(text: String): String {
        var out = text
        for ((rx, replacement) in lexicalFixes) out = rx.replace(out, replacement)
        return out.trim()
    }

    /** Hardware nouns that unambiguously belong to the device domain, regardless of verb position. */
    private val deviceNouns = Regex(
        """\b(?:flashlight|torch|torchlight|wi-fi|bluetooth|hotspot|dnd|do\s+not\s+disturb|""" +
            """volume|brightness|airplane|nfc|screenshot|screen\s+recording|battery\s+saver|dark\s+mode|location|gps|mobile\s+data)\b"""
    )

    /**
     * Toggle cues in ANY position: "on flash light", "flashlight on", "turn on the torch" are the
     * same command. Together with [deviceNouns] this keeps telegraphic speech from falling into
     * the conversational default — the first link of the false-success cascade.
     */
    private val toggleCues = Regex(
        """\b(?:on|off)\b|\b(?:turn|switch|enable|disable|toggle|activate|deactivate)\b"""
    )

    private val torchNounRx = Regex("""\b(?:flashlight|torch|torchlight)\b""")
    private val installRx = Regex("""^(?:install|download)\s+""")

    /**
     * True when the prompt is a hardware toggle in any word order ("on flash light", "torch on").
     * Public: the app layer uses it as the last line of defence keeping device commands off the
     * "answer text and claim success" path.
     */
    fun looksLikeDeviceToggle(prompt: String): Boolean {
        val lower = normalizeLexical(stripPreamble(prompt.lowercase().trim()))
        return deviceNouns.containsMatchIn(lower) && toggleCues.containsMatchIn(lower)
    }

    /**
     * A toggle cue with NO device noun ("on and off after 5 seconds"): ambiguous alone, but the
     * noun is usually the one the conversation was just about — see [impliedDeviceNoun].
     */
    fun looksLikeBareToggle(prompt: String): Boolean {
        val lower = normalizeLexical(stripPreamble(prompt.lowercase().trim()))
        return !deviceNouns.containsMatchIn(lower) && toggleCues.containsMatchIn(lower)
    }

    /** The device noun a sentence refers to, if any — used to resolve bare toggles from context. */
    fun impliedDeviceNoun(sentence: String): String? {
        val lower = normalizeLexical(sentence.lowercase())
        return when {
            torchNounRx.containsMatchIn(lower) -> "flashlight"
            Regex("""\bwi-fi\b""").containsMatchIn(lower) -> "wi-fi"
            Regex("""\bbluetooth\b""").containsMatchIn(lower) -> "bluetooth"
            Regex("""\bhotspot\b""").containsMatchIn(lower) -> "hotspot"
            else -> null
        }
    }

    private val deviceVerbs = listOf(
        "open", "launch", "start", "run", "go to", "navigate", "tap", "click", "press", "swipe",
        "scroll", "drag", "long press", "type", "input", "fill", "enter", "select", "send",
        "reply", "call", "text", "message", "book", "order", "buy", "checkout", "pay", "transfer",
        "turn on", "turn off", "enable", "disable", "toggle", "switch", "connect", "disconnect",
        "pair", "play", "pause", "resume", "skip", "stop", "search", "search in", "find",
        "set", "take", "record", "screenshot", "increase", "decrease", "check", "remind",
        "add", "remove", "delete", "rename", "share", "save", "copy", "paste", "download",
        "upload", "install", "uninstall", "create", "close", "restart"
    )

    /**
     * "can you", "please", "I need to" … say how the request is phrased, not what it asks for.
     * They are stripped before the imperative test so a politely worded command still automates
     * instead of being answered as a question.
     */
    private val preamblePrefixes = listOf(
        "please", "kindly", "can you", "could you", "would you", "will you",
        "i want to", "i need to", "i would like to", "i'd like to", "help me",
        "let's", "lets", "go ahead and", "just", "now"
    )

    /** Removes stacked openers, e.g. "please can you just open settings". */
    internal fun stripPreamble(prompt: String): String {
        var text = prompt.trim()
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in preamblePrefixes) {
                if (text.startsWith("$prefix ")) {
                    text = text.removePrefix("$prefix ").trim()
                    stripped = true
                }
            }
        }
        return text
    }

    private const val COMPOSE_NOUN = """(?:e-?mails?|mails?|messages?|sms|dm|whatsapp)"""
    private val EMAIL_ADDRESS = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""")

    /**
     * Requests that are carried out in an app but do not open with a verb from [deviceVerbs]:
     *
     *  - "write an mail to sam@example.com", "compose a message to Priya" — a composing verb, a
     *    mail/message noun and a recipient;
     *  - "email Sam saying hi", "mail sam@example.com" — the noun used as the verb;
     *  - "follow Sam on Instagram", "connect with Priya on LinkedIn" — a social action in a named app.
     *
     * Each form needs its recipient or app, so plain writing requests ("write a poem about spring",
     * "write an email template", "draft a mail to my landlord") keep going to the answer path.
     */
    private val composeToRecipient = Regex(
        """^(?:write|compose|type)\s+(?:(?:a|an|the|new|quick|short)\s+){0,3}""" + COMPOSE_NOUN + """\s+to\s+\S"""
    )
    private val mailAsVerb = Regex(
        """^(?:e-?mail|mail|dm|whatsapp)\s+\S.*\b(?:saying|that|about|asking|telling)\b"""
    )
    private val socialActionInApp = Regex(
        """^(?:follow|unfollow|subscribe\s+to|connect\s+with)\s+(?!(?:up|the|these|this|that|my|along)\b)\S.*""" +
            """\s(?:on|in)\s+[a-z][a-z0-9.]{1,20}(?:\s+[a-z0-9.]{1,15})?$"""
    )

    /** True when the prompt asks for something to be composed or done in an app for a recipient. */
    fun looksLikeInAppCompose(prompt: String): Boolean {
        val lower = normalizeLexical(stripPreamble(prompt.lowercase().trim())).trimEnd('.', '!', ' ')
        if (composeToRecipient.containsMatchIn(lower)) return true
        if (mailAsVerb.containsMatchIn(lower)) return true
        if (Regex("""^(?:e-?mail|mail)\s""").containsMatchIn(lower) && EMAIL_ADDRESS.containsMatchIn(lower)) return true
        return socialActionInApp.containsMatchIn(lower)
    }

    private val knowledgePrefixes = listOf(
        "what is", "who is", "when is", "where is", "why", "how to", "how does", "explain",
        "summarize", "capital of", "who invented", "what are", "define", "meaning of",
        "translate", "tell me about"
    )

    private val vaultKeywords = listOf(
        "my preference", "remember", "what did i", "my favorite", "personal", "my name",
        "my birthday", "do you remember", "what do you know about me", "save my"
    )

    private val webKeywords = listOf(
        "search the web", "research", "latest news", "find information", "google this",
        "web search", "what's happening", "current events", "today's news"
    )

    /**
     * True when the prompt is a web-research question. The intent shortcut layer consults this so
     * "search the web for X" keeps going to the answer path instead of being turned into a
     * browser tab.
     */
    fun isWebResearch(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return webKeywords.any { lower.contains(it) }
    }

    fun classify(prompt: String): AniobIntent {
        val lower = normalizeLexical(stripPreamble(prompt.lowercase().trim()))
        if (lower.isBlank()) return AniobIntent.EXTERNAL_AI_QUERY

        val hasQuestionWord = lower.startsWith("what") || lower.startsWith("who") ||
                lower.startsWith("when") || lower.startsWith("where") ||
                lower.startsWith("why") || lower.startsWith("how") ||
                lower.contains("?")

        // 1. Vault query check first - personal memory
        if (vaultKeywords.any { lower.contains(it) }) {
            return AniobIntent.VAULT_QUERY
        }

        // 2. Web research
        if (webKeywords.any { lower.contains(it) }) {
            return AniobIntent.WEB_RESEARCH
        }

        // 3. Knowledge QA - what is, who is, explain, etc.
        // e.g., "What is open settings?" is KNOWLEDGE_QA, not DEVICE_AUTOMATION
        if (knowledgePrefixes.any { lower.startsWith(it) } ||
            (hasQuestionWord && !deviceVerbs.any { lower.startsWith(it) || lower.startsWith("please $it") })
        ) {
            return AniobIntent.KNOWLEDGE_QA
        }

        // 3.5 Hardware toggles are device intent in ANY word order ("on flash light", "torch on").
        // Telegraphic forms used to fall into the EXTERNAL_AI_QUERY default, where a chat answer
        // was recorded as an executed command — the false-success cascade of the 2026-11-04 report.
        if (looksLikeDeviceToggle(prompt)) {
            return AniobIntent.DEVICE_AUTOMATION
        }

        // Pattern gate: if the capability planner produced a device-domain plan for ANY slot value,
        // it is automation. This generalises beyond the verb whitelist: "install <any app>" and
        // every future registry capability route correctly without touching this classifier.
        val patternPlan = UniversalPlanner.plan(prompt)
        if (patternPlan.isExecutable && patternPlan.atoms.any { it.capability.deviceDomain }) {
            return AniobIntent.DEVICE_AUTOMATION
        }

        // 4. Device automation requires imperative command at start, not inside question
        // "Open Settings" -> DEVICE, "What is capital?" -> KNOWLEDGE_QA
        val hasImperativeDeviceVerb = deviceVerbs.any { verb ->
            lower.startsWith(verb) || lower.startsWith("please $verb")
        }

        if (hasImperativeDeviceVerb) {
            return AniobIntent.DEVICE_AUTOMATION
        }

        // 5. Compose-to-a-recipient and social actions ("write an mail to …", "email Sam saying hi",
        // "follow Sam on Instagram") happen in an app. They used to fall through to the default
        // below and were answered as chat text with zero device steps.
        if (looksLikeInAppCompose(prompt)) {
            return AniobIntent.DEVICE_AUTOMATION
        }

        // Default to external AI query for general generation
        return AniobIntent.EXTERNAL_AI_QUERY
    }

    /**
     * Deterministically extracts [com.aniob.core.domain.SuccessCriteria] from a raw prompt plus any Grill-Me answers.
     *
     * This is what makes an honest `Finish` possible: without criteria the verifier can never
     * disprove a completion, so the agent could declare success on an unchanged screen. Quoted
     * strings become text assertions and an "open X" imperative becomes a foreground-package
     * assertion when [appCatalog] can resolve X. Grill answers are merged in as extra text
     * assertions so a clarified task is checked against what the user actually asked for.
     */
    fun extractCriteria(
        prompt: String,
        grillAnswers: Map<String, String> = emptyMap(),
        appCatalog: Map<String, String> = emptyMap()
    ): SuccessCriteria {
        val mustContain = mutableListOf<String>()
        val mustShowPackage = mutableListOf<String>()
        val minSteps = if (classify(prompt) == AniobIntent.DEVICE_AUTOMATION) 1 else 0

        // Quoted strings are the user's explicit "this must appear" assertions.
        Regex("[\"'\u201c\u2018]([^\"'\u201d\u2019]{2,40})[\"'\u201d\u2019]")
            .findAll(prompt)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .forEach { if (it !in mustContain) mustContain += it }

        // "open X" / "launch X" -> the named app's package must be foreground at the end.
        val lower = prompt.lowercase()
        val openMatch = Regex("(?:open|launch|start|go to)\\s+([a-z0-9 .&_-]{2,30})").find(lower)
        if (openMatch != null) {
            val target = openMatch.groupValues[1].trim().substringBefore(" and ").trim()
            appCatalog[target]?.let { pkg -> if (pkg !in mustShowPackage) mustShowPackage += pkg }
        }

        grillAnswers.values
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .forEach { if (it !in mustContain) mustContain += it }

        // Hardware toggles assert a WORLD state the screen cannot show (BUG-06): probe the
        // device, never the pixels. The LAST toggle cue wins, so "on and off flash light" ends OFF.
        val deviceAsserts = mutableListOf<DeviceAssertion>()
        val mustBeInstalled = mutableListOf<String>()
        val normalized = normalizeLexical(lower)
        if (looksLikeDeviceToggle(prompt) && torchNounRx.containsMatchIn(normalized)) {
            val lastOn = normalized.lastIndexOf(" on")
            val lastOff = normalized.lastIndexOf("off")
            val finalOff = lastOff > lastOn
            deviceAsserts += if (finalOff) DeviceAssertion.TORCH_OFF else DeviceAssertion.TORCH_ON
        }

        // "install X" is honestly claimed only when Play Store is foreground AND PackageManager
        // actually reports the package installed. Opening the listing is not installing it.
        if (installRx.containsMatchIn(normalized) && "com.android.vending" !in mustShowPackage) {
            mustShowPackage += "com.android.vending"
        }
        val isInstall = AniobStoreCatalog.isInstallPrompt(prompt) || installRx.containsMatchIn(normalized)
        val installPkg = AniobStoreCatalog.packageForPrompt(prompt)
        if (installPkg != null) {
            mustBeInstalled += installPkg
        }

        // Pattern-derived criteria: any registry capability contributes its OWN postconditions.
        // No new capability needs an edit here for its evidence to be enforced.
        val pattern = PatternCriteriaBridge.derive(prompt)
        if (!pattern.isEmpty) {
            pattern.mustShowPackage.forEach { if (it !in mustShowPackage) mustShowPackage += it }
            pattern.mustBeInstalled.forEach { if (it !in mustBeInstalled) mustBeInstalled += it }
            pattern.deviceAsserts.forEach { if (it !in deviceAsserts) deviceAsserts += it }
        }

        return SuccessCriteria(
            mustContainText = mustContain,
            mustShowPackage = mustShowPackage,
            minSteps = minSteps,
            deviceAsserts = deviceAsserts,
            mustBeInstalled = mustBeInstalled,
            requireInstallConfirmation = isInstall || pattern.requireInstall
        )
    }
}
