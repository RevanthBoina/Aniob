package com.aniob.core

import com.aniob.core.grillme.AniobGrillMeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobGrillMeEngineTest {

    private val engine = AniobGrillMeEngine()

    @Test
    fun testSelfContainedTaskNeedsNoClarification() = runBlocking {
        val result = engine.evaluateTask("Open Settings")
        assertFalse(result.needsClarification)
        assertTrue(result.questions.isEmpty())
    }

    @Test
    fun testCabBookingTriggersClarification() = runBlocking {
        val result = engine.evaluateTask("Book a cab")
        assertTrue(result.needsClarification)
        assertTrue("Only one unresolved question", result.questions.size == 1)
        val destinationQuestion = result.questions.first { it.id == "destination" }
        assertTrue("Expected destination question", destinationQuestion.question.contains("destination", ignoreCase = true))
        assertTrue("Destination remains a free-text field", destinationQuestion.allowFreeText)
        assertTrue("Do not fabricate candidate destinations", destinationQuestion.options.isEmpty())

    }

    @Test fun completeInstructionsAskNothing() = runBlocking {
        for (prompt in listOf("Send 'I am here' to John", "Message John saying 'I am here'",
            "Set an alarm at 7 am", "Set timer in 5 minutes", "Call John")) {
            assertFalse(prompt, engine.evaluateTask(prompt).needsClarification)
        }
    }
}
