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
        assertTrue("Expected at least 2 questions", result.questions.size >= 2)
        val destinationQuestion = result.questions.first { it.id == "destination" }
        assertTrue("Expected destination question", destinationQuestion.question.contains("destination", ignoreCase = true))
        assertTrue("Destination remains a free-text field", destinationQuestion.allowFreeText)
        assertTrue("Do not fabricate candidate destinations", destinationQuestion.options.isEmpty())

        val rideTierQuestion = result.questions.first { it.id == "ride_type" }
        assertTrue("Expected known ride-tier choices", rideTierQuestion.options.isNotEmpty())

        val providerQuestion = result.questions.first { it.id == "provider_app" }
        assertTrue("Only installed ride apps may be offered", providerQuestion.options.isEmpty())
        assertTrue(providerQuestion.allowFreeText)
    }
}
