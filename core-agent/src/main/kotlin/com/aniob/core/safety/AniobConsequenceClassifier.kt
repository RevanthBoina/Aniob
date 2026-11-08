package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode

/**
 * Plan-7.8 — confirmations must fire for everything hard to reverse.
 *
 * Today `AniobSafetyInterceptor` only raises a confirmation when the MODEL volunteers one
 * (`AniobAction.ConfirmWithUser`), so a tap on a button labelled "Send" is LOW risk and dispatches
 * silently. Whether the user is asked therefore depends on whether the model felt like asking,
 * which is exactly backwards.
 *
 * Classification here is by CONSEQUENCE (does this send, post, delete, pay, share, call?) and by
 * REVERSIBILITY (easily undone / hard to undo / irreversible), computed from the TARGET LABEL —
 * the thing that is actually true about the button — rather than from the model's narration.
 */
object AniobConsequenceClassifier {

    enum class Consequence { NONE, SENDS, POSTS, DELETES, PAYS, SHARES, CALLS }

    enum class Reversibility(val userWord: String) {
        EASILY_UNDONE("You can undo this"),
        HARD_TO_UNDO("Hard to undo"),
        IRREVERSIBLE("Cannot be undone")
    }

    data class Assessment(
        val consequence: Consequence,
        val reversibility: Reversibility,
        val requiresConfirmation: Boolean,
        /** Shown in the timeline next to the step. */
        val timelineTag: String,
        /** The confirm sheet body, in words a person actually uses. */
        val confirmQuestion: String?
    )

    // Matched against the visible label of the element being tapped, not against a thought.
    private val PATTERNS: List<Triple<Regex, Consequence, Reversibility>> = listOf(
        Triple(Regex("(?i)\\b(pay|pay now|place order|buy|purchase|checkout|confirm payment|subscribe)\\b"),
            Consequence.PAYS, Reversibility.IRREVERSIBLE),
        Triple(Regex("(?i)\\b(delete|remove|erase|discard|clear all|unsend|deactivate|close account)\\b"),
            Consequence.DELETES, Reversibility.IRREVERSIBLE),
        Triple(Regex("(?i)\\b(send|send now|reply|reply all|submit)\\b"),
            Consequence.SENDS, Reversibility.HARD_TO_UNDO),
        Triple(Regex("(?i)\\b(post|publish|tweet|share now|go live|comment)\\b"),
            Consequence.POSTS, Reversibility.HARD_TO_UNDO),
        Triple(Regex("(?i)\\b(share|send to|forward|invite)\\b"),
            Consequence.SHARES, Reversibility.HARD_TO_UNDO),
        Triple(Regex("(?i)\\b(call|dial|video call|ring)\\b"),
            Consequence.CALLS, Reversibility.HARD_TO_UNDO)
    )

    fun classify(action: AniobAction, targetNode: AniobNode?): Assessment {
        val label = listOfNotNull(
            targetNode?.text?.takeIf { it.isNotBlank() },
            targetNode?.contentDescription?.takeIf { it.isNotBlank() },
            (action as? AniobAction.Tap)?.describeTarget(),
            (action as? AniobAction.LongPress)?.describeTarget(),
            (action as? AniobAction.ConfirmWithUser)?.message,
            (action as? AniobAction.DeviceControl)?.let { device ->
                when (device.command) {
                    com.aniob.core.domain.DeviceCommand.CALL -> "place a call"
                    com.aniob.core.domain.DeviceCommand.NOTIFICATION_REPLY -> "send a reply"
                    else -> null
                }
            }
        ).joinToString(" ")

        // Typing and composing a message are not the commit itself; the grounded Send button or
        // notification-reply command is classified separately.
        if (action is AniobAction.InputText || action is AniobAction.Swipe ||
            action is AniobAction.GetScreenInfo || action is AniobAction.TakeScreenshot ||
            (action is AniobAction.DeviceControl && action.command in setOf(
                com.aniob.core.domain.DeviceCommand.MESSAGE_SMS,
                com.aniob.core.domain.DeviceCommand.MESSAGE_TELEGRAM,
                com.aniob.core.domain.DeviceCommand.MESSAGE_WHATSAPP
            ))
        ) {
            return benign()
        }

        val hit = PATTERNS.firstOrNull { it.first.containsMatchIn(label) } ?: return benign()
        val (_, consequence, reversibility) = hit

        return Assessment(
            consequence = consequence,
            reversibility = reversibility,
            // Anything not easily undone is confirmed. That is the whole point.
            requiresConfirmation = reversibility != Reversibility.EASILY_UNDONE,
            timelineTag = reversibility.userWord,
            confirmQuestion = question(consequence, label.trim())
        )
    }

    private fun benign() = Assessment(
        Consequence.NONE, Reversibility.EASILY_UNDONE,
        requiresConfirmation = false,
        timelineTag = Reversibility.EASILY_UNDONE.userWord,
        confirmQuestion = null
    )

    /**
     * Plan-7.8 also says: fix the sheet's copy — "Payload" is not a word users know.
     * These strings are the replacement, and they name the actual consequence.
     */
    private fun question(c: Consequence, label: String): String = when (c) {
        Consequence.PAYS -> "This looks like it will take a payment. Go ahead?"
        Consequence.DELETES -> "This will delete something and I cannot undo it. Go ahead?"
        Consequence.SENDS -> "This will send it. Once it is sent I cannot take it back. Go ahead?"
        Consequence.POSTS -> "This will post it publicly. Go ahead?"
        Consequence.SHARES -> "This will share it with someone else. Go ahead?"
        Consequence.CALLS -> "This will start a call. Go ahead?"
        Consequence.NONE -> "Tap \u201C$label\u201D?"
    }

    /**
     * Bridge to the existing interceptor: the model may still volunteer a confirmation, but it can
     * no longer prevent one. Consequence-based confirmation wins.
     */
    fun mergeWithInterceptor(existing: InterceptResult, assessment: Assessment): InterceptResult =
        if (!assessment.requiresConfirmation) existing
        else existing.copy(
            requiresConfirmation = true,
            riskTier = if (assessment.reversibility == Reversibility.IRREVERSIBLE) "HIGH" else "MEDIUM",
            reason = assessment.confirmQuestion ?: existing.reason
        )
}
