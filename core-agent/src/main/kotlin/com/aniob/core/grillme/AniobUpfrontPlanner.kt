package com.aniob.core.grillme

import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskLevel
import com.aniob.core.safety.AskPolicy
import com.aniob.core.safety.TaskConsent

/**
 * Collects EVERYTHING the agent may need to ask, once, before the task starts.
 *
 *  1. Clarifications (missing values such as recipient, time, app). These need the model, so they
 *     are skipped completely for direct-intent tasks: "open settings" never pays a model call.
 *  2. Consents (payments, secrets, deletion; sending and "other" only in strict mode). These are
 *     deterministic, so they cost nothing even for intent tasks.
 *
 * The result is one ordered queue the UI shows a single question at a time. After the queue is
 * answered the run never asks again (see [com.aniob.core.safety.MidRunApproval]).
 */
object AniobUpfrontPlanner {

    const val MAX_CLARIFICATIONS = 3

    data class Plan(
        val clarifications: List<GrillQuestion>,
        val consents: List<GrillQuestion>,
        /** Families the request itself authorises (for example "send hi to Asha"), no question needed. */
        val implicitConsent: Set<AskCategory>,
        /** True when the model was not consulted because the task is a direct intent. */
        val skippedThinking: Boolean
    ) {
        val questions: List<GrillQuestion> get() = clarifications + consents
        val needsAsk: Boolean get() = questions.isNotEmpty()
        fun toResult(): AniobGrillMeResult = AniobGrillMeResult(needsClarification = needsAsk, questions = questions)
    }

    private val communicative = Regex(
        "(?i)\\b(message|text|sms|whatsapp|telegram|email|e-mail|mail|dm|tweet|reply|respond|ring|phone|invite|comment)\\b"
    )
    private val permanence = Regex(
        "(?i)\\b(permanent(?:ly)?|cannot\\s+undo|can'?t\\s+undo|irreversibl[ey]|forever|for\\s+good)\\b"
    )

    /** What the request is likely to touch. Messaging verbs count as sending even without "send". */
    fun predictedCategories(prompt: String): Set<AskCategory> {
        val base = AskPolicy.predictCategories(prompt)
        return if (communicative.containsMatchIn(prompt)) base + AskCategory.SEND else base
    }

    /**
     * @param intentBased the task is carried by a direct intent shortcut: no thinking, no clarification.
     * @param clarify the (possibly model-backed) clarification generator; only called when thinking is needed.
     */
    suspend fun plan(
        prompt: String,
        level: AskLevel,
        rememberedAnswers: Map<String, String>,
        intentBased: Boolean,
        clarify: suspend (String, Map<String, String>) -> AniobGrillMeResult
    ): Plan {
        val predicted = predictedCategories(prompt)
        // Typing "send hi to Asha" is the authorisation to send. Strict mode still asks.
        val implicit = if (level != AskLevel.CAREFUL && AskCategory.SEND in predicted) {
            setOf(AskCategory.SEND)
        } else {
            emptySet()
        }
        val clarifications = if (intentBased) {
            emptyList()
        } else {
            val result = clarify(prompt, rememberedAnswers)
            if (result.needsClarification) {
                result.questions
                    .filterNot { TaskConsent.isConsentId(it.id) }
                    .distinctBy { it.id }
                    .take(MAX_CLARIFICATIONS)
            } else {
                emptyList()
            }
        }
        return Plan(
            clarifications = clarifications,
            consents = consentQuestions(prompt, level, predicted),
            implicitConsent = implicit,
            skippedThinking = intentBased
        )
    }

    fun consentQuestions(
        prompt: String,
        level: AskLevel,
        predicted: Set<AskCategory> = predictedCategories(prompt)
    ): List<GrillQuestion> {
        val out = mutableListOf<GrillQuestion>()
        if (AskCategory.MONEY in predicted) {
            out += consent(AskCategory.MONEY, "This may involve a payment. Allow Aniob to pay or place an order for this task?")
        }
        if (AskCategory.SECRET in predicted) {
            out += consent(AskCategory.SECRET, "This may need a password, PIN or code. Allow Aniob to enter one for this task?")
        }
        if (AskCategory.DELETE in predicted) {
            out += consent(AskCategory.DELETE, "This may delete something. Allow Aniob to delete or remove items for this task?")
        }
        if (level == AskLevel.CAREFUL) {
            if (AskCategory.SEND in predicted) {
                out += consent(AskCategory.SEND, "This may send, post, share or call someone. Allow it for this task?")
            }
            if (predicted.isNotEmpty() || permanence.containsMatchIn(prompt)) {
                out += GrillQuestion(
                    id = TaskConsent.ID_OTHER,
                    question = "This task may take other hard-to-undo steps. Allow them?",
                    options = listOf(TaskConsent.OPT_ALLOW, TaskConsent.OPT_DENY),
                    allowFreeText = false,
                    defaultOption = null
                )
            }
        }
        return out
    }

    private fun consent(category: AskCategory, text: String) = GrillQuestion(
        id = TaskConsent.questionId(category),
        question = text,
        options = listOf(TaskConsent.OPT_ALLOW, TaskConsent.OPT_DENY),
        allowFreeText = false,
        defaultOption = null
    )
}
