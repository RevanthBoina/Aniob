package com.aniob.core.understander

import com.aniob.core.text.AniobTextFold

/**
 * STEP 0 — how the app understands what the user typed, before any model is called.
 *
 * Replaces substring keyword guessing (`"vocabulary".contains("cab")` is a taxi booking) with whole-word
 * matching on locale-proof folded tokens, and produces a short "task contract" the UI can echo back so the
 * user can see — and correct — what was understood before the phone starts moving.
 */
object AniobPromptUnderstanding {

    enum class SpeechAct { QUESTION, COMMAND, MIXED, UNCLEAR }
    enum class Verb { OPEN, CALL, SEND, SET, TOGGLE, MEDIA, PACKAGE, SEARCH, TRANSACT, NAVIGATE, INFO }

    data class Goal(val verb: Verb, val verbWord: String, val rest: String)

    data class Understanding(
        val speechAct: SpeechAct,
        val goals: List<Goal>,
        val risks: Set<String>,
        val confidence: Double,
        /** One line for the chat: what Aniob thinks it was asked. */
        val echo: String,
        /** Show the echo before acting: several goals, a risk, or low confidence. */
        val needsEcho: Boolean
    )

    private val VERBS: Map<String, Verb> = buildMap {
        listOf("open", "launch", "start", "run").forEach { put(it, Verb.OPEN) }
        listOf("call", "dial", "phone", "ring").forEach { put(it, Verb.CALL) }
        listOf("send", "text", "message", "whatsapp", "email", "reply").forEach { put(it, Verb.SEND) }
        listOf("set", "create", "schedule", "add").forEach { put(it, Verb.SET) }
        listOf("turn", "switch", "enable", "disable", "toggle").forEach { put(it, Verb.TOGGLE) }
        listOf("play", "pause", "resume", "skip", "mute", "unmute").forEach { put(it, Verb.MEDIA) }
        listOf("install", "uninstall", "delete", "remove", "download", "update").forEach { put(it, Verb.PACKAGE) }
        listOf("search", "find", "google", "lookup").forEach { put(it, Verb.SEARCH) }
        listOf("book", "order", "buy", "pay", "purchase", "checkout", "transfer").forEach { put(it, Verb.TRANSACT) }
        listOf("go", "navigate", "take", "directions").forEach { put(it, Verb.NAVIGATE) }
        listOf("read", "show", "tell", "check", "list").forEach { put(it, Verb.INFO) }
    }
    private val CONNECTORS = setOf("and", "then", "also", "after", "next", "plus")
    private val POLITE_LEAD = listOf(listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("will", "you"), listOf("please"), listOf("hey"), listOf("aniob"), listOf("pls"))
    private val WH = setOf("what", "whats", "why", "how", "who", "when", "where", "which", "is", "are", "does", "do", "explain", "define")
    private val DESTRUCTIVE = setOf("uninstall", "delete", "remove", "wipe", "erase", "reset", "format")
    private val SWEEPING = setOf("all", "everything", "every")

    fun understand(prompt: String): Understanding {
        var tokens = AniobTextFold.tokens(prompt)
        var stripped = true
        while (stripped) {
            stripped = false
            for (lead in POLITE_LEAD) if (tokens.size > lead.size && tokens.take(lead.size) == lead) { tokens = tokens.drop(lead.size); stripped = true }
        }
        if (tokens.isEmpty()) return Understanding(SpeechAct.UNCLEAR, emptyList(), emptySet(), 0.0, "I did not catch a request.", false)

        // Goal starts: a verb at the start, or a verb right after a connector ("... and then turn on ...").
        val starts = tokens.indices.filter { i -> tokens[i] in VERBS && (i == 0 || tokens[i - 1] in CONNECTORS) }
        val goals = starts.mapIndexed { n, s ->
            val end = starts.getOrNull(n + 1) ?: tokens.size
            val body = tokens.subList(s + 1, end).toMutableList()
            while (body.isNotEmpty() && body.last() in CONNECTORS) body.removeAt(body.lastIndex)
            Goal(VERBS.getValue(tokens[s]), tokens[s], body.joinToString(" "))
        }

        val asksQuestion = tokens.first() in WH || prompt.trimEnd().endsWith("?")
        val speech = when {
            goals.isEmpty() -> if (asksQuestion) SpeechAct.QUESTION else SpeechAct.UNCLEAR
            asksQuestion && starts.first() != 0 -> SpeechAct.MIXED
            else -> SpeechAct.COMMAND
        }

        val risks = buildSet {
            if (goals.any { it.verb == Verb.TRANSACT }) add("spends money")
            if (tokens.any { it in DESTRUCTIVE } && goals.any { it.verb == Verb.PACKAGE || it.verbWord in DESTRUCTIVE }) add("removes something")
            if (tokens.any { it in DESTRUCTIVE } && tokens.any { it in SWEEPING }) add("affects many items")
            if (goals.any { it.verb == Verb.SEND }) add("sends a message")
        }
        var confidence = when {
            goals.isEmpty() -> 0.5
            else -> 0.92
        }
        if (goals.any { it.rest.isBlank() && it.verb != Verb.MEDIA }) confidence -= 0.25
        if (speech == SpeechAct.MIXED) confidence -= 0.2

        val echo = when {
            goals.isEmpty() -> if (speech == SpeechAct.QUESTION) "You asked a question — I will answer, not touch the phone." else "I'm not sure what you want done yet."
            else -> "I understood: " + goals.withIndex().joinToString("  then  ") { (i, g) ->
                (if (goals.size > 1) "${i + 1}) " else "") + (g.verbWord + " " + g.rest).trim()
            } + if (risks.isNotEmpty()) "  (${risks.joinToString(", ")})" else ""
        }
        return Understanding(speech, goals, risks, confidence.coerceIn(0.0, 1.0), echo, needsEcho = goals.size > 1 || risks.isNotEmpty() || confidence < 0.7)
    }
}
