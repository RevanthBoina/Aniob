package com.aniob.core.ladder

import com.aniob.core.domain.ActionExecutionRecord
import com.aniob.core.domain.TaskContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobPlanningAgentTest {

    private val agent = AniobPlanningAgent()

    @Test
    fun `initial recordExecution starts with empty completed and failed and real pending goal`() {
        val progress = agent.recordExecution(
            userInstruction = "Open settings",
            actionDescription = null,
            verifiedSuccess = false,
            detail = null,
            previousProgress = null
        )

        assertTrue(progress.completed.isEmpty())
        assertTrue(progress.failed.isEmpty())
        assertEquals(listOf("Open settings"), progress.pending)
        assertFalse(progress.pending.any { it.startsWith("Continue:") })
    }

    @Test
    fun `verified execution adds to completed without artificial Continue pending entry`() {
        val first = agent.recordExecution("Open settings", null, false, null, null)

        val second = agent.recordExecution(
            userInstruction = "Open settings",
            actionDescription = "open_app(com.android.settings)",
            verifiedSuccess = true,
            detail = "Settings foregrounded",
            previousProgress = first,
            additionalInstructions = listOf("turn on dark theme")
        )

        assertEquals(listOf("open_app(com.android.settings) (Settings foregrounded)"), second.completed)
        assertTrue(second.failed.isEmpty())
        assertEquals(listOf("Open settings", "turn on dark theme"), second.pending)
        assertFalse(second.pending.any { it.startsWith("Continue:") })
        assertTrue(second.summary.contains("Verified(1):"))
    }

    @Test
    fun `unverified or failed execution is recorded in failed and never added to completed`() {
        val progress = agent.recordExecution(
            userInstruction = "Send message",
            actionDescription = "tap(Send)",
            verifiedSuccess = false,
            detail = "Target node not found",
            previousProgress = null
        )

        assertTrue("Unverified/failed actions must never be marked completed", progress.completed.isEmpty())
        assertEquals(listOf("tap(Send) (Target node not found)"), progress.failed)
        assertTrue(progress.summary.contains("Failed(1):"))
    }

    @Test
    fun `summarizeExecution derives completed and failed directly from TaskContext executionResults`() {
        val ctx = TaskContext(instruction = "Enable Wi-Fi")
            .withExecutionResult(ActionExecutionRecord(0, "open_app(Settings)", true, "Opened Settings"))
            .withExecutionResult(ActionExecutionRecord(1, "tap(Wi-Fi)", false, "Control missing"))

        val progress = agent.summarizeExecution(ctx)

        assertEquals(listOf("open_app(Settings): Opened Settings"), progress.completed)
        assertEquals(listOf("tap(Wi-Fi): Control missing"), progress.failed)
        assertEquals(listOf("Enable Wi-Fi"), progress.pending)
    }

    @Test
    fun `blank action description is ignored`() {
        val progress = agent.recordExecution("Task", "   ", true, "ok", null)
        assertTrue(progress.completed.isEmpty())
        assertTrue(progress.failed.isEmpty())
    }
}
