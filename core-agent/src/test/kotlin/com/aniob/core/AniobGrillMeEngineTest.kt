package com.aniob.core

import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.grillme.GrillMeLlmClient
import com.aniob.core.grillme.GrillQuestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobGrillMeEngineTest {

    private val engine = AniobGrillMeEngine()

    /** A model that answers the same thing for every prompt, and counts how often it was asked. */
    private class FixedModel(private val answer: AniobGrillMeResult) : GrillMeLlmClient {
        var calls = 0
        override suspend fun generateClarificationQuestions(taskPrompt: String): AniobGrillMeResult {
            calls++
            return answer
        }
    }

    private fun asking(id: String, question: String) = FixedModel(
        AniobGrillMeResult(needsClarification = true, questions = listOf(GrillQuestion(id, question, emptyList())))
    )

    private val nothingMissing get() = FixedModel(AniobGrillMeResult(needsClarification = false))

    @Test
    fun testSelfContainedTaskNeedsNoClarification() = runBlocking {
        val result = engine.evaluateTask("Open Settings")
        assertFalse(result.needsClarification)
        assertTrue(result.questions.isEmpty())
    }

    @Test
    fun keywordsAloneNeverAsk() = runBlocking {
        // No model: the keyword ladder used to ask on "send", "message", "cab", "alarm".
        for (prompt in listOf(
            "Send an connection request to krishna vamsi vemula in linkedin",
            "Send connection request to Priya",
            "Follow Sam on Instagram",
            "Email Sam saying hi",
            "Send a message to Sam",
            "Book a cab",
            "Set an alarm"
        )) {
            val result = engine.evaluateTask(prompt)
            assertFalse(prompt, result.needsClarification)
            assertTrue(prompt, result.questions.isEmpty())
        }
    }

    @Test
    fun modelThatSeesNothingMissingMeansNoQuestion() = runBlocking {
        for (prompt in listOf(
            "Send an connection request to krishna vamsi vemula in linkedin",
            "Send connection request to Priya",
            "Email Sam saying hi",
            "Follow Sam on Instagram"
        )) {
            assertFalse(prompt, AniobGrillMeEngine(llmClient = nothingMissing).evaluateTask(prompt).needsClarification)
        }
    }

    @Test
    fun modelRequestIsTheOnlyWayToAsk() = runBlocking {
        val model = asking("message_content", "What should I say?")
        val result = AniobGrillMeEngine(llmClient = model).evaluateTask("Send a message to Sam")
        assertTrue(result.needsClarification)
        assertEquals(1, result.questions.size)
        assertEquals("What should I say?", result.questions.single().question)
        assertEquals(1, model.calls)
    }

    @Test
    fun cabBookingAsksWhatTheModelAsks() = runBlocking {
        val result = AniobGrillMeEngine(llmClient = asking("destination", "What is your destination?"))
            .evaluateTask("Book a cab")
        assertTrue(result.needsClarification)
        assertEquals("Only one unresolved question", 1, result.questions.size)
        val destinationQuestion = result.questions.first { it.id == "destination" }
        assertTrue("Destination remains a free-text field", destinationQuestion.allowFreeText)
        assertTrue("Do not fabricate candidate destinations", destinationQuestion.options.isEmpty())
    }

    @Test
    fun rememberedAnswerIsNotAskedAgain() = runBlocking {
        val result = AniobGrillMeEngine(llmClient = asking("destination", "What is your destination?"))
            .evaluateTask("Book a cab", rememberedAnswers = mapOf("grill.destination" to "Airport"))
        assertFalse(result.needsClarification)
    }

    @Test
    fun failingModelDoesNotFallBackToKeywordQuestions() = runBlocking {
        val broken = object : GrillMeLlmClient {
            override suspend fun generateClarificationQuestions(taskPrompt: String): AniobGrillMeResult =
                throw IllegalStateException("gateway down")
        }
        assertFalse(AniobGrillMeEngine(llmClient = broken).evaluateTask("Send a message to Sam").needsClarification)
    }

    @Test fun completeInstructionsAskNothing() = runBlocking {
        val model = asking("q1", "Anything else?")
        for (prompt in listOf("Send 'I am here' to John", "Message John saying 'I am here'",
            "Set an alarm at 7 am", "Set timer in 5 minutes", "Call John")) {
            assertFalse(prompt, engine.evaluateTask(prompt).needsClarification)
            assertFalse(prompt, AniobGrillMeEngine(llmClient = model).evaluateTask(prompt).needsClarification)
        }
        assertEquals("a complete instruction never reaches the model", 0, model.calls)
    }
}
