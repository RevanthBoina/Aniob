package com.aniob.core.grillme

/**
 * How the executor model uses Grill-Me. The action schema has no "ask" tool (ConfirmWithUser is only created
 * by the safety interceptor, so the model could never ask anything). Rather than add an AniobAction subtype and
 * touch every exhaustive `when`, the model FAILs with `ASK_USER: <question> | <option> | <option>`; the app turns
 * that into the existing Grill sheet and restarts the run with the answer in `clarifiedGoal`.
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
}
