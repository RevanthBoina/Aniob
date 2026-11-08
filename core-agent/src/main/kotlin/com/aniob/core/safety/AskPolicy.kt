package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode

/** User-selected interruption posture. This is separate from hard safety verdicts. */
enum class AskLevel {
    OFF,
    BALANCED,
    CAREFUL
}

/** The consequence family that determines the ordinary approval policy. */
enum class AskCategory {
    MONEY,
    SECRET,
    DELETE,
    SEND,
    NONE
}

/**
 * The only policy that decides whether an otherwise-allowed action pauses for user approval.
 *
 * Hard blocks remain hard blocks: [decide] never changes `isAllowed`. OFF removes ordinary-action
 * prompts, but money, secret entry/exposure, and deletion still require an explicit decision.
 */
object AskPolicy {

    private val moneyPattern = Regex(
        "(?i)\\b(pay(?:ment)?|purchase|buy|checkout|place\\s+order|send\\s+money|transfer\\s+(?:funds|money)|upi)\\b"
    )
    private val secretPattern = Regex(
        "(?i)\\b(password|passcode|pin|otp|one[- ]time(?:[- ]password|[- ]code)?|verification[- ]code|security[- ]code|cvv|cvc|secret)\\b"
    )
    private val deletePattern = Regex(
        "(?i)\\b(delete|remove|erase|wipe|factory\\s+reset|reset\\s+(?:the\\s+)?(?:device|phone|account)|format|clear\\s+all|discard|deactivate|close\\s+account|uninstall)\\b"
    )
    private val sendPattern = Regex(
        "(?i)\\b(send(?:\\s+(?:now|message|reply))?|post|publish|share|forward|call|dial|reply\\s+all|place\\s+a\\s+call)\\b"
    )
    private val readOnlyHistoryPattern = Regex(
        "(?i)\\b(?:payment|purchase|transaction|billing|order)\\s+history\\b|\\b(?:view|read|open)\\s+(?:past\\s+)?(?:payments|transactions|receipts|statements)\\b"
    )

    /**
     * Classifies the grounded action, not a free-standing user prompt. Visible node labels and
     * password metadata take precedence; typed text and direct-command arguments are included so
     * secret operations that do not have an accessibility node are still covered.
     */
    fun category(action: AniobAction, targetNode: AniobNode?): AskCategory {
        if (action is AniobAction.DeviceControl) {
            val explicitSecret = action.args.entries.any { (key, value) ->
                secretPattern.containsMatchIn(key) || secretPattern.containsMatchIn(value) ||
                    (key.equals("text", ignoreCase = true) && value.trim().matches(Regex("\\d{4,8}")))
            }
            when (action.command) {
                com.aniob.core.domain.DeviceCommand.OTP_COPY,
                com.aniob.core.domain.DeviceCommand.OTP_PASTE -> return AskCategory.SECRET
                com.aniob.core.domain.DeviceCommand.OTP_STATUS -> return AskCategory.NONE
                com.aniob.core.domain.DeviceCommand.CALL -> return AskCategory.SEND
                com.aniob.core.domain.DeviceCommand.NOTIFICATION_REPLY ->
                    return if (explicitSecret) AskCategory.SECRET else AskCategory.SEND
                com.aniob.core.domain.DeviceCommand.MESSAGE_SMS,
                com.aniob.core.domain.DeviceCommand.MESSAGE_TELEGRAM,
                com.aniob.core.domain.DeviceCommand.MESSAGE_WHATSAPP ->
                    return if (explicitSecret) AskCategory.SECRET else AskCategory.NONE
                else -> Unit
            }
        }

        val visible = buildString {
            append(targetNode?.text.orEmpty()).append(' ')
            append(targetNode?.contentDescription.orEmpty()).append(' ')
            append(targetNode?.viewId.orEmpty()).append(' ')
            append(targetNode?.tooltip.orEmpty()).append(' ')
            action.semanticTarget()?.let { append(it.describe()).append(' ') }
        }
        val operation = when (action) {
            is AniobAction.InputText -> action.text
            is AniobAction.DeviceControl -> buildString {
                append(action.command.wire).append(' ')
                append(action.command.summary).append(' ')
                action.args.values.forEach { append(it).append(' ') }
            }
            is AniobAction.ConfirmWithUser -> action.message
            else -> ""
        }
        // Use the grounded label and the model's action intent. A grounded target can add context,
        // but an empty accessibility label must not hide an explicitly described payment, secret,
        // or deletion operation.
        val text = listOf(visible, operation, action.thought).joinToString(" ").trim()
        val lower = text.lowercase()

        if (targetNode?.isPassword == true || secretPattern.containsMatchIn(lower)) return AskCategory.SECRET
        if (deletePattern.containsMatchIn(lower)) return AskCategory.DELETE
        if (!readOnlyHistoryPattern.containsMatchIn(lower) && moneyPattern.containsMatchIn(lower)) {
            return AskCategory.MONEY
        }

        if (action is AniobAction.DeviceControl) {
            when (action.command) {
                com.aniob.core.domain.DeviceCommand.CALL,
                com.aniob.core.domain.DeviceCommand.NOTIFICATION_REPLY -> return AskCategory.SEND
                com.aniob.core.domain.DeviceCommand.OTP_COPY,
                com.aniob.core.domain.DeviceCommand.OTP_PASTE -> return AskCategory.SECRET
                else -> Unit
            }
        }
        if (sendPattern.containsMatchIn(lower)) return AskCategory.SEND
        return AskCategory.NONE
    }

    /** Applies the selected posture to an existing hard-safety/consequence verdict. */
    fun decide(
        level: AskLevel,
        action: AniobAction,
        targetNode: AniobNode?,
        verdict: InterceptResult
    ): InterceptResult {
        if (!verdict.isAllowed) return verdict

        val category = category(action, targetNode)
        val alwaysAsk = category == AskCategory.MONEY ||
            category == AskCategory.SECRET || category == AskCategory.DELETE
        val ordinarySend = category == AskCategory.SEND && level != AskLevel.OFF
        val carefulConsequence = level == AskLevel.CAREFUL && verdict.requiresConfirmation
        val shouldAsk = alwaysAsk || ordinarySend || carefulConsequence

        if (!shouldAsk) return verdict.copy(requiresConfirmation = false)

        val tier = when (category) {
            AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE -> "HIGH"
            AskCategory.SEND -> "MEDIUM"
            AskCategory.NONE -> verdict.riskTier
        }
        val reason = when (category) {
            AskCategory.MONEY -> "This may make a payment. Continue?"
            AskCategory.SECRET -> "This may enter or expose a secret. Continue?"
            AskCategory.DELETE -> "This may delete data. Continue?"
            AskCategory.SEND -> "This will send, post, share, or start a call. Continue?"
            AskCategory.NONE -> verdict.reason.ifBlank { "This action may be hard to undo. Continue?" }
        }
        return verdict.copy(reason = reason, riskTier = tier, requiresConfirmation = true)
    }

    /** Plain, deterministic heads-up shown before routing or dispatch. */
    fun taskStartPreview(prompt: String, level: AskLevel): String {
        val likely = buildList {
            val lower = prompt.lowercase()
            if (secretPattern.containsMatchIn(lower)) add("secret codes, passwords, or PINs")
            if (moneyPattern.containsMatchIn(lower)) add("payments")
            if (deletePattern.containsMatchIn(lower)) add("deletion")
            if (level != AskLevel.OFF && sendPattern.containsMatchIn(lower)) add("sending, posting, sharing, or calling")
            if (level == AskLevel.CAREFUL && (prompt.contains("permanent", true) || prompt.contains("cannot undo", true))) {
                add("other hard-to-reverse actions")
            }
        }.distinct().take(3)

        val scope = when (level) {
            AskLevel.OFF -> "I'll proceed without ordinary-action prompts, but I will pause before payments, secrets, or deletion."
            AskLevel.BALANCED -> "I'll pause before payments, secrets, deletion, and sending, posting, sharing, or calling."
            AskLevel.CAREFUL -> "I'll pause before payments, secrets, deletion, sending, posting, sharing, calls, and other consequential actions."
        }
        return if (likely.isEmpty()) {
            "Heads-up: $scope"
        } else {
            "Heads-up: For this task I may ask before ${likely.joinToString(", ")}. $scope"
        }
    }
}
