package com.aniob.core.dialogue

import com.aniob.core.lexicon.TaskLexicon

/**
 * Session key-value state: [Action: Verb] + [Target: Entity] + [App], kept across turns.
 *
 * The routing layer used to treat every incoming message as a fresh, context-free prompt. That is the
 * single root cause behind a family of reports: an answer to a clarification question was routed as a
 * new task, "subscribe it" had nothing to bind "it" to, "yes" produced "What would you like me to do?",
 * and a named app in turn one was forgotten by turn two.
 *
 * Contract (all deterministic, no model call, pure JVM):
 *  1. Every concrete command is remembered as a fact ([observe]) with the turn it appeared on.
 *  2. While a question is pending ([expectAnswer]), a short reply — or a reply that matches one of the
 *     options offered — is the ANSWER: it is merged into the pending command, never restarted.
 *  3. A confirmation re-issues the pending command; a negation cancels it ([Resolution.cancelled]).
 *  4. Pronouns and a bare verb bind to the newest entity within [lookback] turns, including its app.
 */
class ConversationState(private val lookback: Int = 6) {

    data class Fact(val verb: String, val target: String?, val app: String?, val turn: Int)

    data class Pending(
        val originalPrompt: String,
        val verb: String?,
        val target: String?,
        val question: String,
        val options: List<String> = emptyList(),
        val turn: Int = 0
    )

    data class Resolution(
        /** The prompt the rest of the pipeline should run. */
        val prompt: String,
        val rewritten: Boolean,
        val reason: String = "",
        /** True when the user rejected the pending proposal: nothing should run. */
        val cancelled: Boolean = false
    )

    private val facts = ArrayDeque<Fact>()
    private var pending: Pending? = null
    private var turn = 0

    @Synchronized fun reset() { facts.clear(); pending = null; turn = 0 }

    @Synchronized fun pendingQuestion(): Pending? = pending

    @Synchronized fun lastFact(): Fact? = facts.lastOrNull()

    /** Call whenever the app asks the user something (clarification sheet, bare toggle, chooser). */
    @Synchronized
    fun expectAnswer(originalPrompt: String, question: String, options: List<String> = emptyList()) {
        val f = extract(originalPrompt)
        pending = Pending(
            originalPrompt = originalPrompt,
            verb = f?.verb ?: TaskLexicon.splitToggle(originalPrompt)?.let { if (it.first == "on") "turn on" else "turn off" },
            target = f?.target,
            question = question,
            options = options,
            turn = turn
        )
    }

    @Synchronized
    fun resolve(rawPrompt: String): Resolution {
        turn++
        val text = rawPrompt.trim()
        val lower = TaskLexicon.stripPoliteness(text).lowercase().trimEnd('.', '!', '?')
        val p = pending

        if (p != null) {
            pending = null
            if (lower in TaskLexicon.NEGATIONS) {
                return Resolution(p.originalPrompt, false, "user rejected '${p.originalPrompt}'", cancelled = true)
            }
            if (lower in TaskLexicon.CONFIRMATIONS) {
                observe(p.originalPrompt)
                return Resolution(p.originalPrompt, true, "confirmation re-issued the pending action")
            }
            val picksAnOption = p.options.any { it.equals(text, ignoreCase = true) || lower == it.lowercase() }
            if (picksAnOption || (lower.split(' ').size <= 6 && !TaskLexicon.isActionVerb(lower))) {
                val merged = merge(p, text)
                observe(merged)
                return Resolution(merged, true, "answer merged into pending '${p.originalPrompt}'")
            }
            // Not an answer to our question: fall through and treat it as a new command.
            observe(p.originalPrompt)
        }

        PRONOUN.matchEntire(lower)?.let { m ->
            val verb = m.groupValues[1]
            latestEntity()?.let { e ->
                val merged = compose(verb, e.target, e.app)
                observe(merged)
                return Resolution(merged, true, "pronoun bound to '${e.target}'")
            }
        }
        // A bare verb with no object continues the previous target ("subscribe" after naming a channel).
        if (lower in TaskLexicon.ACTION_VERBS) latestEntity()?.let { e ->
            val merged = compose(lower, e.target, e.app)
            observe(merged)
            return Resolution(merged, true, "verb bound to the previous entity")
        }

        observe(text)
        return Resolution(text, false)
    }

    private fun compose(verb: String, target: String?, app: String?): String =
        (verb + (target?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + (app?.let { " on $it" } ?: "")).trim()

    private fun merge(p: Pending, answer: String): String {
        val verb = p.verb
        val target = answer.trim().removeSuffix(".").trim()
        return when {
            verb != null && p.target.isNullOrBlank() -> "$verb $target"
            verb != null -> "$verb ${p.target} $target".trim()
            else -> "${p.originalPrompt} $target".trim()
        }
    }

    @Synchronized
    fun observe(prompt: String) {
        val f = extract(prompt) ?: return
        facts.addLast(f.copy(turn = turn))
        while (facts.size > 12) facts.removeFirst()
    }

    private fun latestEntity(): Fact? = facts.lastOrNull {
        !it.target.isNullOrBlank() && turn - it.turn <= lookback && !isPronoun(it.target!!)
    }

    private fun isPronoun(s: String) = s.lowercase() in TaskLexicon.PRONOUNS

    /** Verb + object (+ "on <app>") from one sentence; null when the sentence carries no task verb. */
    internal fun extract(prompt: String): Fact? {
        val lower = TaskLexicon.stripPoliteness(prompt).lowercase()
        val m = ACTION.find(lower) ?: return null
        val verb = m.groupValues[1]
        var rest = m.groupValues[2].trim()
        var app: String? = null
        APP_SUFFIX.find(rest)?.let { am ->
            app = am.groupValues[1].trim()
            rest = rest.removeRange(am.range).trim()
        }
        rest = rest.removeSuffix(" channel").removeSuffix(" app").trim()
        if (rest.split(' ').all { it in TaskLexicon.PRONOUNS }) rest = ""
        return Fact(verb, rest.ifBlank { null }, app, turn)
    }

    companion object {
        /** Built from the shared lexicon so one new verb extends binding everywhere. */
        private val VERB_ALTERNATION = TaskLexicon.ACTION_VERBS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        private val PRONOUN_TAIL_ALTERNATION = TaskLexicon.PRONOUN_TAILS.joinToString("|") { Regex.escape(it) }
        private val PRONOUN_ALTERNATION = TaskLexicon.PRONOUNS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        private val PRONOUN = Regex("^($VERB_ALTERNATION)\\s+(?:to\\s+|with\\s+)?(?:the\\s+)?($PRONOUN_ALTERNATION)(?:\\s+(?:$PRONOUN_TAIL_ALTERNATION))?$")
        private val ACTION = Regex("^($VERB_ALTERNATION)\\s+(.+)$")
        private val APP_SUFFIX = Regex("\\b(?:on|in|using|via|through)\\s+([a-z0-9 ]{2,24})$")
    }
}
