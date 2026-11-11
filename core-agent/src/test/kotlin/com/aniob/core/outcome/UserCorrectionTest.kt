package com.aniob.core.outcome

import org.junit.Assert.*
import org.junit.Test

class UserCorrectionTest {
    @Test fun `ordinary task text is not treated as a correction`() {
        for (text in listOf("it worked", "That was correct!", "mark that correct", "the task was done."))
            assertTrue(text, AniobOutcomeCorrection.matches(text))
        for (text in listOf("Find out why it worked", "Send John 'it worked'", "it worked yesterday", "worked"))
            assertFalse(text, AniobOutcomeCorrection.matches(text))
    }

    @Test fun `failed and unverified corrections are attestation not machine evidence`() {
        for (original in listOf(SealedOutcome.failed("missed"), SealedOutcome.unverified("unknown"))) {
            val corrected = original.withUserDecision(UserDecision.YES, 42)
            assertEquals(RunOutcome.VERIFIED, corrected.outcome)
            assertTrue(corrected.isUserAttested)
            assertFalse(corrected.isMachineVerified)
        }
    }
}
