package com.aniob.app.outcome

import com.aniob.app.ui.model.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobOutcomeCopyTest {

    @Test
    fun partialFailureUsesTheRequestedHonestRetryCopy() {
        val copy = AniobOutcomeCopy.forTask(
            outcome = Outcome.FAILED,
            reason = "A later step failed",
            taskLabel = "Set up Wi-Fi",
            completedChecks = listOf("Opened Settings"),
            outstandingChecks = listOf("Wi-Fi is on")
        )

        assertEquals("Needs attention", copy.title)
        assertEquals("FAILED 'Set up Wi-Fi'. Done except: Wi-Fi is on. Want me to try again?", copy.message)
    }

    @Test
    fun unverifiedWorkIsNotPresentedAsDone() {
        val copy = AniobOutcomeCopy.forTask(Outcome.UNVERIFIED, "No checkable result")
        assertEquals("Done, not verified", copy.title)
        assertTrue(copy.message.contains("couldn't verify"))
        assertTrue(copy.message.contains("won't mark it done"))
    }

    @Test
    fun failureWithoutEvidenceUsesPlainLanguageAndOffersRetry() {
        val copy = AniobOutcomeCopy.forTask(Outcome.FAILED, "grounding_miss: text:'Save' (exact)")
        assertEquals("Needs attention", copy.title)
        assertTrue(copy.message.contains("Couldn't find 'Save' on screen"))
        assertTrue(copy.message.contains("Want me to try again?"))
    }
}
