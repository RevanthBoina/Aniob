package com.aniob.core.capability

import com.aniob.core.domain.*
import com.aniob.core.run.TaskContinuation
import com.aniob.core.run.TaskInputChannel
import com.aniob.core.run.TaskInputKind
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TaskEntityContinuationTest {
    @Test fun `selected reference survives parking without becoming goal text`() {
        val first = EntityCandidate(EntityReference("contact", "id-one"), "Alex", "Work")
        val second = EntityCandidate(EntityReference("contact", "id-two"), "Alex", "Home")
        val ctx = TaskContext(instruction = "Call Alex", taskId = "t1", pendingEntityResolution =
            PendingEntityResolution("recipient", EntityResolution.MultipleCandidates(listOf(first, second))))
        val channel = TaskInputChannel("t1")
        assertFalse(TaskContinuation.mayClose(channel, ctx))
        val sequence = channel.latestSeq()
        val input = channel.postSelection(EntitySelection("recipient", second.reference))
        assertTrue(channel.isStaleResponse(sequence))
        assertFalse(TaskContinuation.mayClose(channel))
        assertEquals("", input.text)
        val resumed = TaskContinuation.applySelection(ctx, channel, channel.takeNextSelection()!!)!!
        assertEquals(second.reference, resumed.selectedEntities["recipient"])
        assertEquals("Call Alex", resumed.instruction)
        assertTrue(resumed.additionalInstructions.isEmpty())
        assertNull(resumed.pendingEntityResolution)
        assertTrue(TaskContinuation.mayClose(channel, resumed))
    }

    @Test fun `wrong task binding or non-offered reference cannot resolve`() {
        val a = EntityCandidate(EntityReference("contact", "a"), "Name")
        val b = EntityCandidate(EntityReference("contact", "b"), "Name")
        val ctx = TaskContext(instruction = "Call", taskId = "t1", pendingEntityResolution =
            PendingEntityResolution("recipient", EntityResolution.MultipleCandidates(listOf(a, b))))
        val blankTask = TaskInputChannel("")
        assertNull(TaskContinuation.applySelection(ctx.copy(taskId = ""), blankTask,
            blankTask.postSelection(EntitySelection("recipient", a.reference))))
        val wrongTask = TaskInputChannel("t2")
        assertNull(TaskContinuation.applySelection(ctx, wrongTask, wrongTask.postSelection(EntitySelection("recipient", a.reference))))
        val channel = TaskInputChannel("t1")
        assertNull(TaskContinuation.applySelection(ctx, channel, channel.postSelection(EntitySelection("recipient", EntityReference("contact", "other")))))
        assertNull(TaskContinuation.applySelection(ctx, channel, channel.postSelection(EntitySelection("other", a.reference))))
        assertNull(channel.post(TaskInputKind.ENTITY_SELECTION, "Name"))
    }

    @Test fun `finish cannot close a task with unresolved selection`() = runBlocking {
        val a = EntityCandidate(EntityReference("contact", "a"), "Name")
        val b = EntityCandidate(EntityReference("contact", "b"), "Name")
        val ctx = TaskContext(instruction = "Call", taskId = "t1", pendingEntityResolution =
            PendingEntityResolution("recipient", EntityResolution.MultipleCandidates(listOf(a, b))))
        val screen = AniobScreenState(packageName = "example", treeHash = "screen")
        var dispatched = false
        val result = StepPipeline().executeStep(ctx, screen,
            StepPipeline.Proposal.Model(AniobAction.Finish("done"), "FAKE"),
            capture = { screen }, dispatch = { dispatched = true; StepPipeline.DispatchOutcome(true) })
        assertFalse(dispatched)
        assertEquals(StepResult.TerminalState.CONTINUE, result.terminalState)
        assertNotNull(result.nextCtx.pendingEntityResolution)
    }

    @Test fun `selections and instructions are ordered and kept separate`() {
        val channel = TaskInputChannel("t1")
        val first = channel.post(TaskInputKind.INSTRUCTION, "continue")!!
        val second = channel.postSelection(EntitySelection("recipient", EntityReference("contact", "b")))
        val third = channel.post(TaskInputKind.INSTRUCTION, "then finish")!!
        assertEquals(listOf(first.seq, second.seq, third.seq), channel.pending().map { it.seq })
        assertEquals(listOf("continue", "then finish"), channel.takeInstructions().map { it.text })
        assertEquals(second.selection, channel.takeNextSelection()?.selection)
        assertNull(channel.takeNextSelection())
    }
}
