package com.aniob.core.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Field report: tasks that have to be done in an app were answered as chat text with 0 steps
 * ("Write an mail to …" was routed to the cloud answer path).
 *
 * Second report (this suite's later half): reading data **inside** an app had the same fate for the
 * opposite reason — "What are my two newest emails?" is question-shaped, so the classifier answered
 * `KNOWLEDGE_QA` and no device step ever ran. Both directions are pinned here: read requests must
 * reach the device path, and questions *about* an app must not.
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

    // -----------------------------------------------------------------------------------------
    // Reading data inside an app
    // -----------------------------------------------------------------------------------------

    @Test
    fun `app data retrieval requests are device automation`() {
        for (prompt in listOf(
            "Check my two newest emails.",
            "What are my two newest emails?",
            "Summarize the newest emails in Gmail.",
            "Read the latest messages in an installed application.",
            "Read my unread emails",
            "Show me my inbox",
            "Summarize my latest WhatsApp messages",
            "What did my latest emails say?"
        )) {
            assertEquals(prompt, AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify(prompt))
        }
    }

    @Test
    fun `questions about an app stay conversational`() {
        for (prompt in listOf(
            "What is Gmail?",
            "How does Gmail work?",
            "Write an email template.",
            "Explain how to organize an inbox.",
            "What is the capital of France?",
            "Explain how email works",
            "What are the differences between email and chat?",
            "Give me email etiquette tips",
            "Summarize this chat",
            "What is a mailing list?"
        )) {
            assertNotEquals(
                "$prompt must not become device automation",
                AniobIntent.DEVICE_AUTOMATION,
                AniobTaskIntentClassifier.classify(prompt)
            )
        }
        // "What is Gmail?" is a definition question, not merely "not device": it must be answered.
        assertEquals(AniobIntent.KNOWLEDGE_QA, AniobTaskIntentClassifier.classify("What is Gmail?"))
    }

    @Test
    fun `app data read rule reads the request it recognised`() {
        val request = AniobAppDataRead.detect("Check my two newest emails.")
        assertNotNull("a request to read app data must be recognised", request)
        assertEquals(AppDataKind.EMAIL, request!!.kind)
        assertEquals(2, request.requestedCount)

        val inGmail = AniobAppDataRead.detect("Summarize the newest emails in Gmail.")
        assertNotNull(inGmail)
        assertEquals("gmail", inGmail!!.appHint)

        val messages = AniobAppDataRead.detect("Read the latest messages in an installed application.")
        assertNotNull(messages)
        assertEquals(AppDataKind.MESSAGES, messages!!.kind)
        assertNull("no app is named, so no app may be guessed", messages.appHint)
    }

    @Test
    fun `definition and compose forms are not app data reads`() {
        for (prompt in listOf(
            "What is Gmail?",
            "How does Gmail work?",
            "Write an email template.",
            "Explain how to organize an inbox.",
            "Compose a message to Priya",
            "Email Sam saying hi",
            "Summarize this chat"
        )) {
            assertNull("'$prompt' is not a request to read app data", AniobAppDataRead.detect(prompt))
        }
    }

    @Test
    fun `read criteria require the named app to be in the foreground`() {
        val gmailPackage = "com.google.android.gm"
        val criteria = AniobTaskIntentClassifier.extractCriteria(
            prompt = "Summarize the newest emails in Gmail.",
            appCatalog = mapOf("gmail" to gmailPackage)
        )
        assertEquals(listOf(gmailPackage), criteria.mustShowPackage)
    }
}
