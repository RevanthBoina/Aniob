package com.aniob.core.safety

import com.aniob.core.safety.MidRunApproval.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobTaskConsentTest {

    private val allowAll = TaskConsent(setOf(AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE, AskCategory.SEND), allowOther = true)

    @Test
    fun midRunTimingKeepsTheLegacyPrompt() {
        val r = MidRunApproval.resolve(AskTiming.MID_RUN, AskLevel.BALANCED, AskCategory.MONEY, TaskConsent.NONE)
        assertEquals(Resolution.Prompt, r)
    }

    @Test
    fun unapprovedConsequentialFamiliesAreDeclinedNotAsked() {
        for (c in listOf(AskCategory.MONEY, AskCategory.SECRET, AskCategory.DELETE, AskCategory.SEND)) {
            val r = MidRunApproval.resolve(AskTiming.AT_START, AskLevel.BALANCED, c, TaskConsent.NONE)
            assertTrue("$c must fail closed", r is Resolution.Decline)
            assertEquals(c, (r as Resolution.Decline).needed)
        }
    }

    @Test
    fun approvedFamiliesPassWithoutAPrompt() {
        for (c in AskCategory.values()) {
            val r = MidRunApproval.resolve(AskTiming.AT_START, AskLevel.CAREFUL, c, allowAll)
            assertTrue("$c", r is Resolution.Approve)
        }
    }

    @Test
    fun approvingPaymentsDoesNotApproveDeletion() {
        val onlyMoney = TaskConsent(setOf(AskCategory.MONEY))
        assertTrue(MidRunApproval.resolve(AskTiming.AT_START, AskLevel.BALANCED, AskCategory.MONEY, onlyMoney) is Resolution.Approve)
        assertTrue(MidRunApproval.resolve(AskTiming.AT_START, AskLevel.BALANCED, AskCategory.DELETE, onlyMoney) is Resolution.Decline)
    }

    @Test
    fun ordinaryConfirmationsFollowTheStrictness() {
        assertTrue(MidRunApproval.resolve(AskTiming.AT_START, AskLevel.BALANCED, AskCategory.NONE, TaskConsent.NONE) is Resolution.Approve)
        assertTrue(MidRunApproval.resolve(AskTiming.AT_START, AskLevel.CAREFUL, AskCategory.NONE, TaskConsent.NONE) is Resolution.Decline)
        assertTrue(MidRunApproval.resolve(AskTiming.AT_START, AskLevel.CAREFUL, AskCategory.NONE, TaskConsent(allowOther = true)) is Resolution.Approve)
    }

    @Test
    fun answersBecomeConsentAndAreSplitFromClarifications() {
        val answers = mapOf(
            "destination" to "Airport",
            "consent.money" to "Allow",
            "consent.delete" to "Don't allow",
            "consent.other" to "Allow"
        )
        val (clarifications, consentAnswers) = TaskConsent.split(answers)
        assertEquals(mapOf("destination" to "Airport"), clarifications)
        val consent = TaskConsent.fromAnswers(consentAnswers, implicit = setOf(AskCategory.SEND))
        assertEquals(setOf(AskCategory.MONEY, AskCategory.SEND), consent.granted)
        assertTrue(consent.allowOther)
        assertFalse(consent.allows(AskCategory.DELETE))
    }

    @Test
    fun predictionIsReadOnlyAndIgnoresHistoryViews() {
        assertEquals(setOf(AskCategory.MONEY), AskPolicy.predictCategories("buy headphones"))
        assertTrue(AskPolicy.predictCategories("view payment history").isEmpty())
        assertTrue(AskPolicy.predictCategories("open youtube").isEmpty())
    }
}
