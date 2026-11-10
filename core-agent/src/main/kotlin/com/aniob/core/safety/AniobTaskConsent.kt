package com.aniob.core.safety

/**
 * When the user is asked about consequential actions.
 *
 * [AT_START] (default): every approval is collected once, before the task starts, and the run
 * never interrupts the user again. [MID_RUN] is the previous behaviour (pause on the first risky
 * step and wait) and is kept only as an explicit opt-in.
 */
enum class AskTiming { AT_START, MID_RUN }

/**
 * What the user approved for ONE task, collected up front.
 *
 * This is deliberately separate from the clarification answers: consent never reaches the model
 * prompt or the success-criteria extractor, and it is never written to the durable vault.
 */
data class TaskConsent(
    val granted: Set<AskCategory> = emptySet(),
    /** Strict mode only: hard-to-undo actions outside payments, secrets, deletion and sending. */
    val allowOther: Boolean = false
) {
    fun allows(category: AskCategory): Boolean = category in granted

    companion object {
        val NONE = TaskConsent()

        const val ID_PREFIX = "consent."
        const val ID_OTHER = "consent.other"
        const val OPT_ALLOW = "Allow"
        const val OPT_DENY = "Don't allow"

        private val ASKABLE = listOf(AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE, AskCategory.SEND)

        fun questionId(category: AskCategory): String = ID_PREFIX + category.name.lowercase()

        fun isConsentId(id: String): Boolean = id.startsWith(ID_PREFIX)

        /** Builds the consent from the card's answers plus anything the request itself already authorises. */
        fun fromAnswers(answers: Map<String, String>, implicit: Set<AskCategory> = emptySet()): TaskConsent {
            val explicit = ASKABLE.filter { answers[questionId(it)] == OPT_ALLOW }.toSet()
            return TaskConsent(
                granted = explicit + implicit,
                allowOther = answers[ID_OTHER] == OPT_ALLOW
            )
        }

        /** Splits clarification answers (first) from consent answers (second) so each goes where it belongs. */
        fun split(answers: Map<String, String>): Pair<Map<String, String>, Map<String, String>> {
            val (consent, other) = answers.entries.partition { isConsentId(it.key) }
            return other.associate { it.key to it.value } to consent.associate { it.key to it.value }
        }
    }
}

/**
 * Decides, without a UI, what to do when a step needs approval mid-run.
 *
 * It never invents consent: a family the user did not approve at the start is DECLINED (fail
 * closed) and the step is reported, instead of pausing the task and waiting for a reply.
 * Hard safety blocks are not routed here at all; they stay blocks in [AniobSafetyInterceptor].
 */
object MidRunApproval {

    sealed interface Resolution {
        /** Legacy [AskTiming.MID_RUN]: show the confirmation and wait. */
        data object Prompt : Resolution

        /** Covered by what the user approved at the start. */
        data class Approve(val why: String) : Resolution

        /** Not covered: skip the step and say so. [needed] names what the user was never asked. */
        data class Decline(val why: String, val needed: AskCategory?) : Resolution
    }

    fun resolve(
        timing: AskTiming,
        level: AskLevel,
        category: AskCategory,
        consent: TaskConsent
    ): Resolution {
        if (timing == AskTiming.MID_RUN) return Resolution.Prompt
        return when (category) {
            AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE, AskCategory.SEND ->
                if (consent.allows(category)) {
                    Resolution.Approve("Approved at the start of the task")
                } else {
                    Resolution.Decline(declineCopy(category), category)
                }
            AskCategory.NONE ->
                if (level == AskLevel.CAREFUL && !consent.allowOther) {
                    Resolution.Decline(
                        "Skipped a hard-to-undo step because it was not approved at the start.",
                        null
                    )
                } else {
                    Resolution.Approve("Part of the task you asked for")
                }
        }
    }

    private fun declineCopy(category: AskCategory): String = when (category) {
        AskCategory.MONEY -> "Skipped a payment step because payments were not approved at the start."
        AskCategory.SECRET -> "Skipped a step that needs a password, PIN or code because it was not approved at the start."
        AskCategory.DELETE -> "Skipped a deletion step because deleting was not approved at the start."
        AskCategory.SEND -> "Skipped a send, post, share or call step because it was not approved at the start."
        AskCategory.NONE -> "Skipped a step that was not approved at the start."
    }
}
