package com.aniob.core.grillme

import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskPolicy

/**
 * The model's way of saying "I am missing a value": it FAILs with
 * `ASK_USER: <question> | <option> | <option>`.
 *
 * Questions are asked ONCE, before the task starts (see [AniobUpfrontPlanner]). If the model still
 * emits this mid-run, the app must not stop the task and wait for a reply: [resolveWithoutUser]
 * decides whether the run can safely continue on a stated assumption or must end with a clear,
 * actionable message.
 */
object AniobAskUser {
    const val PREFIX = "ASK_USER:"
    const val QUESTION_ID = "model_ask"
    private const val MAX_OPTIONS = 3

    fun parse(failReason: String?): GrillQuestion? {
        val text = failReason?.trim().orEmpty()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val parts = text.substring(PREFIX.length).split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val question = parts.firstOrNull()?.take(160) ?: return null
        val options = parts.drop(1).distinct().take(MAX_OPTIONS)
        return GrillQuestion(id = QUESTION_ID, question = question, options = options, allowFreeText = true, defaultOption = null)
    }

    /** What to do with a mid-run question when the user is not going to be interrupted. */
    sealed interface MidRunDecision {
        /** Continue, telling the model which option was assumed. Shown to the user as a note. */
        data class Assume(val question: GrillQuestion, val answer: String) : MidRunDecision

        /** Stop honestly. [message] says what was missing so the user can re-run with it. */
        data class CannotContinue(val message: String) : MidRunDecision
    }

    private val unsafeToAssume = setOf(AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE)

    /**
     * The model lists its recommended option first, so a question with offered options continues on
     * the first one. Never assumed: a second question in the same run, anything about payments,
     * secrets or deletion, and free-text values (recipient, destination, message) that only the
     * user can supply.
     */
    fun resolveWithoutUser(question: GrillQuestion, alreadyAssumed: Boolean): MidRunDecision {
        val missing = "I need one more detail: ${question.question} Add it to your request and run it again."
        if (alreadyAssumed) return MidRunDecision.CannotContinue(missing)
        val scope = question.question + " " + question.options.joinToString(" ")
        if (AskPolicy.predictCategories(scope).any { it in unsafeToAssume }) {
            return MidRunDecision.CannotContinue(missing)
        }
        val choice = question.options.firstOrNull() ?: return MidRunDecision.CannotContinue(missing)
        return MidRunDecision.Assume(question, choice)
    }
}
