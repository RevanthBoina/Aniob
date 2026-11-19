package com.aniob.core.run

import com.aniob.core.domain.TaskContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-task continuation: one task id, one ordered input queue, and rules that keep a continuation
 * from restarting (or silently ending) the work it was added to.
 *
 * These are the pure rules the ViewModel relies on, so they are pinned without a device: the app
 * layer only decides *when* to call them.
 */
class TaskInputChannelTest {

    private val ctx = TaskContext(
        instruction = "Read my two newest emails",
        condensedHistory = listOf("APP_LAUNCH: Open Gmail -> the expected change was observed"),
        trajectory = emptyList(),
        stepIndex = 3,
        taskId = "task_1"
    )

    @Test
    fun `instructions are ordered and identified`() {
        val channel = TaskInputChannel("task_1")
        val first = channel.post(TaskInputKind.INSTRUCTION, "only the unread ones")!!
        val second = channel.post(TaskInputKind.INSTRUCTION, "and summarize them")!!

        assertTrue(second.seq > first.seq)
        assertEquals(listOf("only the unread ones", "and summarize them"), channel.pendingInstructions().map { it.text })
    }

    @Test
    fun `an instruction is appended with its original text and the goal is preserved`() {
        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.INSTRUCTION, "Only the unread ones, please")

        val updated = TaskContinuation.applyToContext(ctx, channel.takeInstructions())

        assertEquals("the task goal must not be rewritten", ctx.instruction, updated.instruction)
        assertEquals(listOf("Only the unread ones, please"), updated.additionalInstructions)
        assertEquals("verified progress is preserved", ctx.condensedHistory + "the user added an instruction: \"Only the unread ones, please\"", updated.condensedHistory)
        assertEquals(ctx.stepIndex, updated.stepIndex)
    }

    @Test
    fun `an instruction is consumed exactly once`() {
        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.INSTRUCTION, "use the other account")

        val first = channel.takeInstructions()
        val second = channel.takeInstructions()

        assertEquals(1, first.size)
        assertTrue("a continuation must not replay what it already appended", second.isEmpty())
        assertFalse(channel.hasPendingInstruction())
    }

    @Test
    fun `a pending instruction blocks completion until it is consumed`() {
        val channel = TaskInputChannel("task_1")
        assertTrue(TaskContinuation.mayClose(channel))

        channel.post(TaskInputKind.INSTRUCTION, "also read the third one")
        assertFalse("a final answer may not close the task over the user's new instruction", TaskContinuation.mayClose(channel))

        channel.takeInstructions()
        assertTrue(TaskContinuation.mayClose(channel))
    }

    @Test
    fun `an answer does not block completion the way an instruction does`() {
        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.CLARIFICATION_ANSWER, "the work account")

        assertTrue(TaskContinuation.mayClose(channel))
        val answer = channel.takeLatestOf(TaskInputKind.CLARIFICATION_ANSWER)
        assertNotNull(answer)
        assertNull("an answer is consumed by the question that asked it", channel.takeLatestOf(TaskInputKind.CLARIFICATION_ANSWER))
    }

    @Test
    fun `cancellation is distinguished from an instruction`() {
        for (phrase in listOf("stop", "Cancel", "never mind", "abort", "STOP IT.")) {
            assertTrue("'$phrase' cancels", TaskContinuation.isCancellation(phrase))
        }
        for (phrase in listOf("stop after this one, then read the third", "cancel my subscription", "one more thing")) {
            assertFalse("'$phrase' is an instruction, not a cancellation", TaskContinuation.isCancellation(phrase))
        }

        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.CANCELLATION, "stop")
        channel.post(TaskInputKind.INSTRUCTION, "read one more")
        assertTrue(channel.cancellationRequested())
        assertTrue(channel.hasPendingInstruction())
    }

    @Test
    fun `a model reply requested before a newer instruction is stale`() {
        val channel = TaskInputChannel("task_1")
        val requestSeq = channel.latestSeq()

        assertFalse(channel.isStaleResponse(requestSeq))

        channel.post(TaskInputKind.INSTRUCTION, "actually, the oldest one")
        assertTrue("a late reply must not overwrite newer user words", channel.isStaleResponse(requestSeq))
        assertFalse("a reply requested after the instruction is current", channel.isStaleResponse(channel.latestSeq()))
    }

    @Test
    fun `a clarification answer joins the task context without replacing its goal`() {
        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.CLARIFICATION_ANSWER, "the work account")

        val updated = TaskContinuation.applyAnswer(
            ctx,
            channel.takeLatestOf(TaskInputKind.CLARIFICATION_ANSWER)!!,
            question = "Which account?"
        )

        assertEquals(ctx.instruction, updated.instruction)
        assertEquals(mapOf("Which account?" to "the work account"), updated.grillAnswers)
        assertTrue(updated.condensedHistory.last().contains("the work account"))
    }

    @Test
    fun `appending instructions is idempotent for repeated text`() {
        val once = ctx.withInstructions(listOf("only unread"))
        val twice = once.withInstructions(listOf("only unread"))

        assertEquals(listOf("only unread"), twice.additionalInstructions)
    }

    @Test
    fun `the continuation notice quotes the user's own words`() {
        val channel = TaskInputChannel("task_1")
        channel.post(TaskInputKind.INSTRUCTION, "only the unread ones")

        val notice = TaskContinuation.continuationNotice(channel.takeInstructions())
        assertTrue(notice.contains("only the unread ones"))
        assertTrue(notice.contains("Continuing the same task"))
    }

    @Test
    fun `a whole answered sheet joins the same context and keeps the goal`() {
        val ctx = TaskContext(instruction = "Read my two newest emails", taskId = "t9")
        val updated = TaskContinuation.applyAnswers(
            ctx,
            mapOf("app_read.account" to "work@example.com", "ignored" to "   ")
        )

        assertEquals("Read my two newest emails", updated.instruction)
        assertEquals(mapOf("app_read.account" to "work@example.com"), updated.grillAnswers)
        assertTrue(
            "the answered exchange is remembered",
            updated.condensedHistory.any { it.contains("app_read.account") }
        )
        assertEquals("a blank answer is not an answer", ctx, TaskContinuation.applyAnswers(ctx, mapOf("x" to "")))
    }

    @Test
    fun `continuation commands are recognized and resume context without replacing goal`() {
        assertTrue(TaskContinuation.isContinuationCommand("Proceed"))
        assertTrue(TaskContinuation.isContinuationCommand("Continue."))
        assertTrue(TaskContinuation.isContinuationCommand("Yes, proceed"))
        assertFalse(TaskContinuation.isContinuationCommand("Open Settings"))
        assertFalse(TaskContinuation.isContinuationCommand("Stop"))

        val updated = TaskContinuation.applyToContext(
            ctx,
            listOf(TaskInput(seq = 1L, kind = TaskInputKind.INSTRUCTION, text = "Proceed", receivedAt = 1L))
        )
        assertEquals("Read my two newest emails", updated.instruction)
        assertTrue("pure Proceed does not become an extra requirement", updated.additionalInstructions.isEmpty())
        assertTrue(updated.conversationHistory.any { it.content == "Proceed" })
    }

    @Test
    fun `task context preserves execution results and structured conversation turns`() {
        val updated = ctx
            .withConversationTurn(com.aniob.core.external.ChatTurn.USER, "Read my two newest emails")
            .withConversationTurn(com.aniob.core.external.ChatTurn.ASSISTANT, "open_app(com.google.android.gm)")
            .withExecutionResult(
                com.aniob.core.domain.ActionExecutionRecord(
                    stepIndex = 0,
                    actionSummary = "open_app(com.google.android.gm)",
                    success = true,
                    detail = "Opened Gmail inbox",
                    screenPackage = "com.google.android.gm"
                )
            )
            .withConversationTurn(
                com.aniob.core.external.ChatTurn.USER,
                com.aniob.core.external.ChatTurn.formatExecutionResult(
                    stepIndex = 0,
                    actionSummary = "open_app(com.google.android.gm)",
                    success = true,
                    detail = "Opened Gmail inbox"
                )
            )

        val turns = updated.priorConversationTurns()
        assertTrue(turns.any { it.role == com.aniob.core.external.ChatTurn.ASSISTANT && it.content.contains("open_app") })
        assertTrue(turns.any { it.role == com.aniob.core.external.ChatTurn.USER && it.content.contains("SUCCEEDED") })

        val req = updated.buildExecutorRequest(
            com.aniob.core.domain.AniobScreenState(packageName = "com.google.android.gm")
        )
        assertTrue(req.lastResult?.contains("Opened Gmail inbox") == true)
    }
}
