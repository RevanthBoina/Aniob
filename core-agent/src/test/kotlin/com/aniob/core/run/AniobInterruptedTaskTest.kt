package com.aniob.core.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 6 — a persisted running marker is evidence, never a checkpoint.
 *
 * These tests pin the words the user sees after a process death: what was verified, what may already
 * have happened, and that nothing is executed again without the user asking.
 */
class AniobInterruptedTaskTest {

    @Test
    fun `verified and uncertain work are reported separately`() {
        val report = AniobInterruptedTask.parse(
            taskId = "task_1",
            prompt = "Read my two newest emails",
            step = 4,
            lastKnownEffect = "verified: Tap Gmail | Tap Inbox; may have happened: Tap first message"
        )

        assertEquals(listOf("Tap Gmail", "Tap Inbox"), report.verified)
        assertEquals(listOf("Tap first message"), report.uncertain)
        assertFalse(report.wasWaitingForUser)

        val reason = report.reason()
        assertTrue("verified work must be named: $reason", reason.contains("Tap Inbox"))
        assertTrue("the unsure step must be named: $reason", reason.contains("Tap first message"))
        assertTrue("nothing was retried and it must say so: $reason", reason.contains("nothing was retried"))
    }

    @Test
    fun `a marker with no recorded effect is honest about it`() {
        val report = AniobInterruptedTask.parse("task_2", "Send the draft", 2, "")

        assertTrue(report.verified.isEmpty())
        assertTrue(report.uncertain.isEmpty())
        val reason = report.reason()
        assertTrue(reason.contains("Interrupted at step 2"))
        assertTrue("a blank record may not be presented as success: $reason", reason.contains("may have moved on"))
    }

    @Test
    fun `a task interrupted while waiting for the user says it was waiting`() {
        val report = AniobInterruptedTask.parse(
            taskId = "task_3",
            prompt = "Read the newest messages",
            step = 3,
            lastKnownEffect = "verified: Tap Messages; waiting for you: Which account should I read?"
        )

        assertTrue(report.wasWaitingForUser)
        assertFalse("a parked task had nothing in flight", report.reason().contains("Effect unknown"))
        assertTrue(report.reason().contains("waiting for your answer"))
    }

    @Test
    fun `an unlabelled record is treated as unknown rather than verified`() {
        val report = AniobInterruptedTask.parse("task_4", "Reply to mom", 5, "Tap Send")

        assertTrue(report.verified.isEmpty())
        assertEquals(listOf("Tap Send"), report.uncertain)
        assertTrue(report.reason().contains("Tap Send"))
    }

    @Test
    fun `the continuation prompt forbids repeating the uncertain action`() {
        val report = AniobInterruptedTask.parse(
            taskId = "task_5",
            prompt = "Read my two newest emails",
            step = 4,
            lastKnownEffect = "verified: Tap Gmail; may have happened: Tap the first message"
        )

        val prompt = report.continuationPrompt()
        assertTrue("the original goal must survive: $prompt", prompt.contains("Read my two newest emails"))
        assertTrue("it must start by observing: $prompt", prompt.contains("look at the current screen"))
        assertTrue("it must not repeat the unsure step: $prompt", prompt.contains("do not repeat it"))
        assertTrue("it must ask instead of guessing: $prompt", prompt.contains("ask me once"))
    }

    @Test
    fun `the continuation prompt of a waiting task does not claim an action was in flight`() {
        val report = AniobInterruptedTask.parse(
            "task_6", "Read the newest messages", 2, "waiting for you: which account?"
        )

        val prompt = report.continuationPrompt()
        assertTrue(prompt.contains("Read the newest messages"))
        assertFalse("nothing was in flight, so nothing may be called out", prompt.contains("may already have happened"))
    }
}
