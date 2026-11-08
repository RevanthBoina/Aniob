package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState

/** A hard safety verdict and the ordinary approval decision are deliberately separate. */
data class InterceptResult(
    val isAllowed: Boolean,
    val reason: String = "Allowed",
    val riskTier: String = "LOW",
    val requiresConfirmation: Boolean = false,
    val recommendedAction: AniobAction? = null
)

typealias InterceptionResult = InterceptResult

/**
 * Pure-core safety boundary shared by every action rung.
 *
 * The UPI protocol and billing permission are hard stops. Other consequential actions are allowed
 * to reach [AskPolicy], which is the sole owner of whether execution pauses for user approval.
 */
object AniobSafetyInterceptor {

    private const val DENIED_BILLING_PERMISSION = "com.android.vending.BILLING"
    private val hardPaymentSchemes = Regex("(?i)\\bupi://")
    private val hardBillingPermission = Regex(Regex.escape(DENIED_BILLING_PERMISSION), RegexOption.IGNORE_CASE)

    @Volatile
    var askLevel: AskLevel = AskLevel.BALANCED

    fun evaluateAction(
        action: AniobAction,
        targetNode: AniobNode?,
        screenState: AniobScreenState,
        screenFingerprint: String,
        skillRisk: String? = null,
        skillRequiresConfirmation: Boolean = false
    ): InterceptResult {
        @Suppress("UNUSED_VARIABLE")
        val currentScreenPackage = screenState.packageName
        @Suppress("UNUSED_VARIABLE")
        val observedFingerprint = screenFingerprint

        val groundedText = listOfNotNull(
            targetNode?.text,
            targetNode?.contentDescription,
            targetNode?.viewId
        ).joinToString(" ")
        val actionText = "$action ${action.thought} $groundedText"

        // Only these protocol/permission cases remain hard stops. Do not record them in the retry
        // ledger: the block is a safety verdict, not a process-wide target blacklist.
        if (hardPaymentSchemes.containsMatchIn(actionText)) {
            return InterceptResult(isAllowed = false, reason = "UPI payment protocol blocked", riskTier = "HIGH")
        }
        if (hardBillingPermission.containsMatchIn(actionText)) {
            return InterceptResult(isAllowed = false, reason = "Billing permission blocked", riskTier = "HIGH")
        }

        val baseResult = when {
            action is AniobAction.DeviceControl -> evaluateDeviceControl(action)
            skillRisk.equals("HIGH", ignoreCase = true) || skillRequiresConfirmation -> InterceptResult(
                isAllowed = true,
                reason = "This saved action may be consequential. Continue?",
                riskTier = skillRisk ?: "HIGH",
                requiresConfirmation = true
            )
            else -> InterceptResult(isAllowed = true, reason = "Allowed", riskTier = "LOW")
        }
        val consequence = AniobConsequenceClassifier.classify(action, targetNode)
        val merged = AniobConsequenceClassifier.mergeWithInterceptor(baseResult, consequence)
        return AskPolicy.decide(askLevel, action, targetNode, merged)
    }

    private fun evaluateDeviceControl(action: AniobAction.DeviceControl): InterceptResult {
        return if (action.command.risk == com.aniob.core.domain.DeviceRisk.CONFIRM) {
            InterceptResult(
                isAllowed = true,
                reason = "This device command may be consequential. Continue?",
                riskTier = "MEDIUM",
                requiresConfirmation = true
            )
        } else {
            InterceptResult(isAllowed = true, reason = "Allowed", riskTier = "LOW")
        }
    }
}
