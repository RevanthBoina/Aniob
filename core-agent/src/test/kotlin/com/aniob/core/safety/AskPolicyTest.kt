package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskPolicyTest {

    private fun evaluateAt(
        level: AskLevel,
        action: AniobAction,
        target: AniobNode? = null
    ): InterceptResult {
        val previous = AniobSafetyInterceptor.askLevel
        return try {
            AniobSafetyInterceptor.askLevel = level
            AniobSafetyInterceptor.evaluateAction(
                action = action,
                targetNode = target,
                screenState = AniobScreenState(packageName = "com.example"),
                screenFingerprint = "ask-policy-test"
            )
        } finally {
            AniobSafetyInterceptor.askLevel = previous
        }
    }

    private fun button(label: String) = AniobNode(
        id = 1,
        className = "android.widget.Button",
        text = label,
        bounds = AniobRect(0, 0, 100, 48),
        isClickable = true
    )

    @Test
    fun moneySecretsAndDeletionAlwaysRequireApprovalAtEveryLevel() {
        val sensitiveActions = listOf(
            AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "complete the payment"),
            AniobAction.InputText(SemanticTarget.SomIndex(1), text = "my OTP is 482913"),
            AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "delete the account")
        )

        AskLevel.entries.forEach { level ->
            sensitiveActions.forEach { action ->
                val result = evaluateAt(level, action)
                assertTrue("$level must preserve approval for ${action.describeAction()}", result.isAllowed)
                assertTrue("$level must require approval for ${action.describeAction()}", result.requiresConfirmation)
                assertEquals("HIGH", result.riskTier)
            }
        }
    }

    @Test
    fun offRemovesOrdinarySendPromptsButBalancedAndCarefulKeepThem() {
        val action = AniobAction.Tap(SemanticTarget.SomIndex(1))
        val target = button("Send message")

        assertFalse(evaluateAt(AskLevel.OFF, action, target).requiresConfirmation)
        assertTrue(evaluateAt(AskLevel.BALANCED, action, target).requiresConfirmation)
        assertTrue(evaluateAt(AskLevel.CAREFUL, action, target).requiresConfirmation)
    }

    @Test
    fun hardPaymentProtocolBlockSurvivesOff() {
        val result = evaluateAt(
            AskLevel.OFF,
            AniobAction.DeviceControl(
                command = DeviceCommand.OPEN_URL,
                args = mapOf("url" to "upi://pay?pa=recipient@bank")
            )
        )

        assertFalse(result.isAllowed)
        assertFalse(result.requiresConfirmation)
        assertEquals("HIGH", result.riskTier)
    }

    @Test
    fun billingPermissionBlockSurvivesOff() {
        val result = evaluateAt(
            AskLevel.OFF,
            AniobAction.DeviceControl(
                command = DeviceCommand.OPEN_URL,
                args = mapOf("permission" to "com.android.vending" + ".BILLING")
            )
        )

        assertFalse(result.isAllowed)
        assertEquals("HIGH", result.riskTier)
    }

    @Test
    fun `task start produces no chat narration`() {
        // Decisions stay; narration is gone. AskPolicy must not expose any API whose
        // purpose is building a task-start "heads-up" transcript string.
        val narrationApis = AskPolicy::class.java.declaredMethods
            .map { it.name }
            .filter { name ->
                name.contains("taskStartPreview", ignoreCase = true) ||
                    name.contains("headsUp", ignoreCase = true)
            }
        assertTrue("AskPolicy must not build task-start narration: $narrationApis", narrationApis.isEmpty())
    }
}
