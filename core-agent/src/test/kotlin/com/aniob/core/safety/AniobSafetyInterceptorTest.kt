package com.aniob.core.safety

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class AniobSafetyInterceptorTest {

    @Before
    fun resetPolicy() {
        AniobSafetyInterceptor.askLevel = AskLevel.BALANCED
    }

    @After
    fun restorePolicy() {
        AniobSafetyInterceptor.askLevel = AskLevel.BALANCED
    }

    private fun screen(pkg: String = "com.aniob.test", hash: String = "H1"): AniobScreenState =
        AniobScreenState(packageName = pkg, treeHash = hash)

    @Test
    fun paymentAlwaysRequiresApprovalEvenWhenOrdinaryPromptsAreOff() {
        AniobSafetyInterceptor.askLevel = AskLevel.OFF
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "complete the payment now"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_pay"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        assertEquals("HIGH", result.riskTier)
        AniobSafetyInterceptor.askLevel = AskLevel.BALANCED
    }

    @Test
    fun destructiveActionRequiresApprovalRatherThanBypassingThePipeline() {
        AniobSafetyInterceptor.askLevel = AskLevel.OFF
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "confirm factory reset now"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_wipe"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        assertEquals("HIGH", result.riskTier)
        assertTrue(result.reason.contains("delete", ignoreCase = true))
        AniobSafetyInterceptor.askLevel = AskLevel.BALANCED
    }

    @Test
    fun testActionWithPayInsideWordIsNotBlocked() {
        // "display" contains the letters p-a-y but is not a payment op.
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.InputText(SemanticTarget.SomIndex(3), text = "display the results"),
            targetNode = AniobNode(id = 3, className = "EditText", bounds = AniobRect(0, 0, 10, 10), isEditable = true),
            screenState = screen(),
            screenFingerprint = "fp_display"
        )
        assertTrue(result.isAllowed)
    }

    @Test
    fun secretEntryAlwaysRequiresApproval() {
        AniobSafetyInterceptor.askLevel = AskLevel.OFF
        val editable = AniobNode(id = 2, className = "EditText", bounds = AniobRect(0, 0, 10, 10), isEditable = true)
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.InputText(SemanticTarget.SomIndex(2), text = "my otp is 1234"),
            targetNode = editable,
            screenState = screen(),
            screenFingerprint = "fp_otp"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        assertTrue(result.reason.contains("secret", ignoreCase = true))
        AniobSafetyInterceptor.askLevel = AskLevel.BALANCED
    }

    @Test
    fun testConfirmWithUserUnknownContactRequiresConfirmation() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.ConfirmWithUser(message = "Send message to ???"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_confirm"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        assertEquals("MEDIUM", result.riskTier)
    }

    @Test
    fun testConfirmWithUserKnownContactStillRequiresConfirmation() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.ConfirmWithUser(message = "Send message to Mom"),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_confirm2"
        )
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun testNormalActionAllowedLowRisk() {
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.Text("Wi-Fi")),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_normal"
        )
        assertTrue(result.isAllowed)
        assertEquals("LOW", result.riskTier)
        assertFalse(result.requiresConfirmation)
    }

    @Test
    fun testReadingPaymentHistoryNotBlocked() {
        val historyNode = AniobNode(
            id = 5,
            className = "TextView",
            text = "Payment History",
            bounds = AniobRect(0, 0, 100, 50),
            isClickable = true
        )
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(5), thought = "view past payments"),
            targetNode = historyNode,
            screenState = screen(),
            screenFingerprint = "fp_history"
        )
        assertTrue("Reading payment history must be allowed as a read-only navigation", result.isAllowed)
        assertEquals("LOW", result.riskTier)
    }

    @Test
    fun visiblePaymentControlRequiresApprovalDespiteInnocuousThought() {
        // Model explanation is innocent ("view next screen"), but target node is a payment button ("Pay $50.00")
        val payNode = AniobNode(
            id = 10,
            className = "Button",
            text = "Pay $50.00",
            bounds = AniobRect(0, 0, 100, 50),
            isClickable = true
        )
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(10), thought = "proceed to the next screen"),
            targetNode = payNode,
            screenState = screen(),
            screenFingerprint = "fp_pay_control"
        )
        assertTrue("A grounded payment must reach the approval gate", result.isAllowed)
        assertTrue("An innocuous thought cannot remove the payment approval", result.requiresConfirmation)
        assertEquals("HIGH", result.riskTier)
    }

    @Test
    fun testSkillRiskHighRequiresConfirmationInCarefulMode() {
        AniobSafetyInterceptor.askLevel = AskLevel.CAREFUL
        val result = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.Text("Export Data")),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_skill",
            skillRisk = "HIGH"
        )
        assertTrue(result.isAllowed)
        assertTrue("High-risk skill must require confirmation", result.requiresConfirmation)
        assertEquals("HIGH", result.riskTier)
    }

    @Test
    fun testConsequenceClassifierRaisesConfirmationForSendAndDeleteControls() {
        val sendNode = AniobNode(
            id = 7,
            className = "Button",
            text = "Send",
            bounds = AniobRect(0, 0, 100, 50),
            isClickable = true
        )
        val sendRes = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(7)),
            targetNode = sendNode,
            screenState = screen(),
            screenFingerprint = "fp_send"
        )
        assertTrue(sendRes.isAllowed)
        assertTrue("Tapping Send must require confirmation via consequence classifier", sendRes.requiresConfirmation)
        assertEquals("MEDIUM", sendRes.riskTier)

        val deleteNode = sendNode.copy(id = 8, text = "Delete")
        val deleteRes = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(8)),
            targetNode = deleteNode,
            screenState = screen(),
            screenFingerprint = "fp_delete"
        )
        assertTrue(deleteRes.isAllowed)
        assertTrue("Tapping Delete must require confirmation via consequence classifier", deleteRes.requiresConfirmation)
        assertEquals("HIGH", deleteRes.riskTier)
    }
}