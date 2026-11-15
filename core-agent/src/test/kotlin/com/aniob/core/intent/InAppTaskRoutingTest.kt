package com.aniob.core.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Field report: tasks that have to be done in an app were answered as chat text with 0 steps
 * ("Write an mail to …" was routed to the cloud answer path).
 */
class InAppTaskRoutingTest {

    @Test
    fun `tasks done in an app are device automation`() {
        for (prompt in listOf(
            "Write an mail to revanthb@example.com",
            "Write an email to Sam saying I will be late",
            "Compose a message to Priya",
            "Please write a mail to sam@example.com about the meeting",
            "Email Sam saying hi",
            "mail sam@example.com",
            "Search for Jensen haung in linkedin",
            "Search in YouTube for naa anveshana",
            "Send a message to Sam",
            "Send an connection request to krishna vamsi vemula in linkedin",
            "Follow Sam on Instagram",
            "Connect with Priya on LinkedIn"
        )) {
            assertEquals(prompt, AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify(prompt))
        }
    }

    @Test
    fun `questions and plain writing requests are still answered`() {
        assertEquals(AniobIntent.KNOWLEDGE_QA, AniobTaskIntentClassifier.classify("What is the capital of France?"))
        for (prompt in listOf(
            "Write a poem about spring",
            "Write an email template for a job application",
            "Compose a haiku",
            "Draft a mail to my landlord about the leak",
            "Email etiquette tips",
            "Follow up on the last answer",
            "How do I write an email to a professor?"
        )) {
            assertNotEquals(prompt, AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify(prompt))
        }
    }
}
