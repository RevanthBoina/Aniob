package com.aniob.core.grillme

import com.aniob.core.safety.TaskConsent

/**
 * Pure state machine behind the single-question card above the chat bar.
 *
 * One reducer owns the whole interaction (answer, skip, back, finish), so the UI keeps no parallel
 * maps of "which question is current / what was answered", which is where stale and duplicate
 * answers came from. Every transition returns a new immutable state.
 */
data class AniobAskQueue(
    val questions: List<GrillQuestion>,
    val index: Int = 0,
    val answers: Map<String, String> = emptyMap(),
    /** Questions the user chose to remember in the vault. Never contains a consent question. */
    val remember: Set<String> = emptySet()
) {
    val total: Int get() = questions.size
    val isComplete: Boolean get() = index >= questions.size
    val current: GrillQuestion? get() = questions.getOrNull(index)
    /** 1-based position for "1 of 3". */
    val position: Int get() = (index + 1).coerceAtMost(total)
    val canGoBack: Boolean get() = index > 0

    /** Records [value] for the current question. Rejects blank text and choices the question did not offer. */
    fun answer(value: String, rememberChoice: Boolean = false): AniobAskQueue {
        val q = current ?: return this
        val clean = value.trim()
        if (clean.isEmpty()) return this
        val offered = clean in q.options
        if (!offered && !q.allowFreeText) return this
        val keep = rememberChoice && !TaskConsent.isConsentId(q.id)
        return copy(
            index = index + 1,
            answers = answers + (q.id to clean),
            remember = if (keep) remember + q.id else remember - q.id
        )
    }

    /**
     * Moves on without an answer. A clarification falls back to its default (or is dropped so the
     * agent decides); a consent question falls back to NOT allowing, never to allowing.
     */
    fun skip(): AniobAskQueue {
        val q = current ?: return this
        val fallback = if (TaskConsent.isConsentId(q.id)) TaskConsent.OPT_DENY else q.defaultOption
        return copy(
            index = index + 1,
            answers = if (fallback != null) answers + (q.id to fallback) else answers - q.id,
            remember = remember - q.id
        )
    }

    fun back(): AniobAskQueue = if (canGoBack) copy(index = index - 1) else this

    companion object {
        fun start(questions: List<GrillQuestion>): AniobAskQueue = AniobAskQueue(questions.distinctBy { it.id })
    }
}
