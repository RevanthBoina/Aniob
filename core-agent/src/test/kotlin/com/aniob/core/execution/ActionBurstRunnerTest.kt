package com.aniob.core.execution

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.TaskContext
import com.aniob.core.domain.SwipeDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executing a bundled plan, one member at a time, on a screen that keeps changing.
 *
 * The failure this pins is the one that made the multi-action mode slower than the baseline: a plan
 * is written against one observation, the first member opens a new screen, and every later member
 * is then dispatched against a screen it was never written for. The runner's job is to notice that
 * and close the plan, not to keep firing.
 */
class ActionBurstRunnerTest {

    private fun row(id: Int, text: String, click: Boolean = true, editable: Boolean = false) = AniobNode(
        id = id,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        text = text,
        isClickable = click,
        isEditable = editable,
        bounds = AniobRect(0, id * 100, 500, id * 100 + 80)
    )

    private val list = AniobScreenState(packageName = "com.clock", nodes = listOf(row(1, "Add alarm"), row(2, "6:15 PM")))
    private val editor = AniobScreenState(packageName = "com.clock", nodes = listOf(row(1, "7:30", click = false, editable = true), row(2, "OK")))

    private fun tap(id: Int) = AniobAction.Tap(SemanticTarget.SomIndex(id))

    private fun ctx() = TaskContext(instruction = "set an alarm")

    private fun stepResult(
        action: AniobAction,
        screen: AniobScreenState,
        terminal: StepResult.TerminalState = StepResult.TerminalState.CONTINUE,
        verified: Boolean = true,
        blockedBySafety: Boolean = false,
        ctx: TaskContext = ctx()
    ) = StepResult(
        action = action,
        dispatch = DispatchCommand.NoOp("test"),
        verification = com.aniob.core.verifier.DeterministicVerifier.VerificationResult(
            isExpected = verified, durationMs = 0, reason = if (verified) "changed" else "no change"
        ),
        nextCtx = ctx.withStep(ctx.stepIndex + 1, screen, "did ${action.toolName}").withAction(action),
        terminalState = terminal,
        blockedBySafety = blockedBySafety
    )

    // ---------------------------------------------------------------------------------------------
    // Happy path
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a plan on one screen runs every member`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ ->
                ran += member
                stepResult(member, screen, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(thought = "tap two rows", isComplete = true, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(listOf(tap(1), tap(2)), ran)
        assertEquals(2, outcome.ran.size)
        assertEquals(ActionBurstRunner.Stop.NONE, outcome.stop)
        assertTrue(outcome.completed)
        assertFalse(outcome.shouldReplan)
    }

    @Test fun `a recycled index never replaces the identity that the guard approved`() = runBlocking {
        val fresh = list.copy(nodes = listOf(row(2, "Wrong row"), row(20, "6:15 PM")))
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(capture = { fresh }, executeMember = { member, c, screen, target ->
            ran += member
            if (ran.size == 2) {
                assertEquals(20, target?.id)
                assertEquals(tap(20), member)
            }
            stepResult(member, screen, ctx = c)
        })
        val outcome = runner.run(ActionBurst("p", true, listOf(tap(1), tap(2))), list, ctx())
        assertEquals(listOf(tap(1), tap(20)), ran)
        assertTrue(outcome.completed)
    }

    @Test
    fun `the screen is re-read before every member after the first`() = runBlocking {
        var captures = 0
        val runner = ActionBurstRunner(
            capture = { captures++; list },
            executeMember = { member, c, screen, _ -> stepResult(member, screen, ctx = c) }
        )
        runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2), tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals("one capture between members", 2, captures)
    }

    // ---------------------------------------------------------------------------------------------
    // The plan crosses a screen boundary
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a member whose target vanished closes the plan instead of failing the task`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        // The first member opens the editor, so id 2 of the list no longer exists.
        val runner = ActionBurstRunner(
            capture = { editor },
            executeMember = { member, c, screen, _ ->
                ran += member
                stepResult(member, screen, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = true, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(listOf(tap(1)), ran)
        assertEquals(ActionBurstRunner.Stop.TARGET_GONE, outcome.stop)
        assertTrue(outcome.shouldReplan)
        assertFalse(outcome.isHardStop)
        assertTrue(outcome.detail.contains("dropped"))
        // The screen the plan revealed is handed back so the next step plans from it.
        assertEquals(editor, outcome.screen)
        assertFalse("a truncated plan is not a completed one", outcome.completed)
    }

    @Test
    fun `navigating to another package is a replan, not a failure`() = runBlocking {
        val elsewhere = editor.copy(packageName = "com.android.settings")
        val runner = ActionBurstRunner(
            capture = { elsewhere },
            executeMember = { member, c, screen, _ -> stepResult(member, screen, ctx = c) }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(ActionBurstRunner.Stop.NAVIGATED, outcome.stop)
        assertTrue(outcome.shouldReplan)
    }

    @Test
    fun `a member is never fired at a lookalike id on the screen the plan opened`() = runBlocking {
        // The plan's second member was written as "id 1", which on the list is a label and on the
        // editor is a text field. The guard matches the element the plan *meant* (class + text), so
        // the collision is detected and the plan closes instead of typing into whatever id 1 became.
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { editor },
            executeMember = { member, c, screen, _ -> ran += member; stepResult(member, screen, ctx = c) }
        )
        val outcome = runner.run(
            ActionBurst(
                thought = "p",
                isComplete = true,
                actions = listOf(tap(1), AniobAction.InputText(SemanticTarget.SomIndex(1), "7:30"))
            ),
            origin = list,
            ctx = ctx()
        )
        assertEquals(1, ran.size)
        assertEquals(ActionBurstRunner.Stop.TARGET_GONE, outcome.stop)
        assertTrue(outcome.shouldReplan)
    }

    @Test
    fun `a plan made on the editor's own screen runs both members`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { editor },
            executeMember = { member, c, screen, target ->
                ran += member
                assertNotNull("every member must be resolved on the live screen", target)
                stepResult(member, screen, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(
                thought = "type then confirm",
                isComplete = true,
                actions = listOf(
                    AniobAction.InputText(SemanticTarget.SomIndex(1), "7:30"),
                    tap(2)
                )
            ),
            origin = editor,
            ctx = ctx()
        )
        assertEquals(2, ran.size)
        assertEquals(ActionBurstRunner.Stop.NONE, outcome.stop)
    }

    // ---------------------------------------------------------------------------------------------
    // Stops that are not about the screen
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a safety block stops the plan and is not retried`() = runBlocking {
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ ->
                if (member == tap(2)) {
                    stepResult(member, screen, StepResult.TerminalState.FAIL, blockedBySafety = true, ctx = c)
                } else stepResult(member, screen, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2), tap(1))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(ActionBurstRunner.Stop.BLOCKED, outcome.stop)
        assertTrue(outcome.isHardStop)
        assertEquals(2, outcome.ran.size)
    }

    @Test
    fun `a soft failure closes the plan for a replan instead of ending the task`() = runBlocking {
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ ->
                if (member == tap(2)) stepResult(member, screen, StepResult.TerminalState.FAIL, ctx = c)
                else stepResult(member, screen, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2), tap(1))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(ActionBurstRunner.Stop.TARGET_GONE, outcome.stop)
        assertTrue(outcome.shouldReplan)
        assertFalse(outcome.isHardStop)
        assertEquals(2, outcome.ran.size)
    }

    @Test
    fun `a finished member ends the plan as a success`() = runBlocking {
        val finish = AniobAction.Finish("done")
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ ->
                stepResult(member, screen, StepResult.TerminalState.SUCCESS, ctx = c)
            }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = true, actions = listOf(tap(1), finish, tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(false, outcome.shouldReplan)
        assertTrue(outcome.completed)
        assertEquals(1, outcome.ran.size)
    }

    @Test
    fun `a stopped run aborts between members`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ -> ran += member; stepResult(member, screen, ctx = c) }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = ctx(),
            shouldContinue = { ran.isEmpty() }
        )
        assertEquals(1, ran.size)
        assertEquals(ActionBurstRunner.Stop.ABORTED, outcome.stop)
        assertTrue(outcome.isHardStop)
    }

    @Test
    fun `a failed capture is never dispatched through`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { null },
            executeMember = { member, c, screen, _ -> ran += member; stepResult(member, screen, ctx = c) }
        )
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = ctx()
        )
        assertEquals(1, ran.size)
        assertEquals(ActionBurstRunner.Stop.NO_CAPTURE, outcome.stop)
    }

    @Test
    fun `the context of the last executed member is handed back`() = runBlocking {
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ -> stepResult(member, screen, ctx = c) }
        )
        val start = ctx()
        val outcome = runner.run(
            ActionBurst(thought = "p", isComplete = false, actions = listOf(tap(1), tap(2))),
            origin = list,
            ctx = start
        )
        assertEquals(2, outcome.ctx.stepIndex)
        assertEquals(2, outcome.ctx.trajectory.size)
        assertNotNull(outcome.lastResult)
    }

    // ---------------------------------------------------------------------------------------------
    // Members that need no target
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `screen-free members never depend on the capture`() = runBlocking {
        val ran = mutableListOf<AniobAction>()
        val runner = ActionBurstRunner(
            capture = { list },
            executeMember = { member, c, screen, _ -> ran += member; stepResult(member, screen, ctx = c) }
        )
        val outcome = runner.run(
            ActionBurst(
                thought = "scroll and go back",
                isComplete = true,
                actions = listOf(AniobAction.Swipe(SwipeDirection.DOWN), AniobAction.PressKey(KeyType.BACK))
            ),
            origin = list,
            ctx = ctx()
        )
        assertEquals(2, ran.size)
        assertEquals(ActionBurstRunner.Stop.NONE, outcome.stop)
    }
}
