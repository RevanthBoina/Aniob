package com.aniob.core.device

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.safety.AniobOtpExtractor
import com.aniob.core.safety.AniobSafetyInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobOtpAndSafetyTest {

    @After fun tearDown() {
        AniobSafetyInterceptor.askLevel = com.aniob.core.safety.AskLevel.BALANCED
    }

    @Test
    fun extractsCodesNextToAKeyword() {
        assertEquals("483920", AniobOtpExtractor.extract("Your OTP is 483920. Do not share it."))
        assertEquals("123456", AniobOtpExtractor.extract("123456 is your verification code"))
        assertEquals("482913", AniobOtpExtractor.extract("G-482913 is your Google verification code"))
        assertEquals("4829", AniobOtpExtractor.extract("Use code 4829 to log in"))
        assertEquals("483920", AniobOtpExtractor.extract("verification code: 483 920"))
    }

    @Test
    fun ignoresAmountsAndPlainNumbers() {
        assertNull(AniobOtpExtractor.extract("Rs 5000 debited from your account"))
        assertNull(AniobOtpExtractor.extract("Your order 20481 has shipped"))
        assertNull(AniobOtpExtractor.extract(null))
    }

    @Test
    fun maskNeverLeavesTheDigits() {
        val masked = AniobOtpExtractor.mask("Your OTP is 483920. Valid 10 min.")
        assertFalse(masked.contains("483920"))
        assertTrue(masked.contains("Valid 10 min"))
        assertEquals("Lunch at 1?", AniobOtpExtractor.mask("Lunch at 1?"))
    }

    private fun evaluate(action: AniobAction) =
        AniobSafetyInterceptor.evaluateAction(action, null, AniobScreenState(packageName = "x"), "fp-${action.describeAction()}")

    @Test
    fun otpCopyAlwaysRequiresApprovalAsSecretHandling() {
        AniobSafetyInterceptor.askLevel = com.aniob.core.safety.AskLevel.OFF
        val result = evaluate(AniobAction.DeviceControl(DeviceCommand.OTP_COPY, thought = "Copy the OTP to the clipboard"))
        assertTrue(result.isAllowed)
        assertTrue(result.requiresConfirmation)
        AniobSafetyInterceptor.askLevel = com.aniob.core.safety.AskLevel.BALANCED
    }

    @Test
    fun callsAndRepliesAreConfirmed() {
        val call = evaluate(AniobAction.DeviceControl(DeviceCommand.CALL, mapOf("contact" to "Mom")))
        assertTrue(call.isAllowed)
        assertTrue(call.requiresConfirmation)
        val reply = evaluate(AniobAction.DeviceControl(DeviceCommand.NOTIFICATION_REPLY, mapOf("text" to "On my way")))
        assertTrue(reply.requiresConfirmation)
        assertTrue(evaluate(AniobAction.DeviceControl(DeviceCommand.OTP_PASTE)).requiresConfirmation)
    }

    @Test
    fun paymentSchemesInArgumentsAreBlocked() {
        val result = evaluate(AniobAction.DeviceControl(DeviceCommand.OPEN_URL, mapOf("url" to "upi://pay?pa=x@bank")))
        assertFalse(result.isAllowed)
    }

    @Test
    fun harmlessCommandsRunWithoutAsking() {
        val result = evaluate(AniobAction.DeviceControl(DeviceCommand.VOLUME_SET, mapOf("percent" to "70")))
        assertTrue(result.isAllowed)
        assertFalse(result.requiresConfirmation)
    }
}
