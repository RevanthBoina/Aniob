package com.aniob.core.intent

import com.aniob.core.domain.SuccessCriteria

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
        val lower = stripPreamble(prompt.lowercase().trim())
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

        // 4. Device automation requires imperative command at start, not inside question
        // "Open Settings" -> DEVICE, "What is capital?" -> KNOWLEDGE_QA
        val hasImperativeDeviceVerb = deviceVerbs.any { verb ->
            lower.startsWith(verb) || lower.startsWith("please $verb")
        }

        if (hasImperativeDeviceVerb) {
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

        return SuccessCriteria(
            mustContainText = mustContain,
            mustShowPackage = mustShowPackage,
            minSteps = minSteps
        )
    }
}
