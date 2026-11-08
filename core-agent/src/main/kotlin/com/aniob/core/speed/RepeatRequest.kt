package com.aniob.core.speed

import com.aniob.core.domain.AniobAction
import com.aniob.core.text.SpokenNumbers

/**
 * "Tap the bell three times" / "swipe up 5 times" / "press it twice" -> n identical members in ONE
 * turn, instead of n model round trips that each re-observe a screen nothing else is changing.
 *
 * The count is read from the instruction, expanded through the burst policy ([ActionBurst]) and every
 * member still goes through the full pipeline (safety, grounding, dispatch, verification), so a
 * repeated action is never less checked than a single one. Anything the policy rejects runs once and
 * is re-planned, which is the same fallback the loop already uses.
 */
object RepeatRequest {

    private val COUNT = Regex("\\b(\\d{1,2})\\s*(?:times?|x)\\b")
    private val WORDS = listOf("twice" to 2, "thrice" to 3)

    /** How many times the instruction asks for the action to be repeated, or null. */
    fun count(instruction: String): Int? {
        val text = SpokenNumbers.normalize(instruction).lowercase()
        COUNT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        WORDS.firstOrNull { (word, _) -> Regex("\\b$word\\b").containsMatchIn(text) }?.let { return it.second }
        return null
    }

    /** The repeated action list, or empty when the instruction asks for one (or an unsafe number). */
    fun expandActions(action: AniobAction, instruction: String): List<AniobAction> {
        val times = count(instruction) ?: return emptyList()
        if (times < ActionBurst.MIN_SIZE || times > ActionBurst.MAX_REPEAT) return emptyList()
        if (action is AniobAction.Finish || action is AniobAction.Fail) return emptyList()
        return List(times) { action }
    }

    /** Policy members for an expanded repeat, ready for [ActionBurst.validate]. */
    fun members(actions: List<AniobAction>, justification: String): List<ActionBurst.Member> =
        actions.map { ActionBurst.Member(it, justification, homogeneousRepeat = true, verifiedBeforeDispatch = true) }
}
