package com.aniob.core

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.ExecutorRequest
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.perception.BurstTargetGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The burst contract: the compact reply the model is asked for, and the guard that stops a burst
 * when the screen moves under it.
 */
class ActionBurstTest {

    @Test
    fun `malformed or ungrounded burst becomes a bounded wait`() {
        val malformed = """{"actions":[{"type":"tap","id":1},{"type":"bogus"}]}"""
        val missing = """{"actions":[{"type":"tap","id":999}]}"""
        for (raw in listOf(malformed, missing)) {
            val result = com.aniob.core.providers.AniobStructuredOutput.parseBurstOrRepair(raw, screen)
            assertNotNull(result)
            assertEquals(1, result!!.actions.size)
            assertTrue(result.actions.single() is AniobAction.Wait)
        }
    }

    private val screen = AniobScreenState(
        packageName = "com.example.app",
        nodes = listOf(
            AniobNode(
                id = 1,
                className = "android.widget.EditText",
                text = "Search",
                viewId = "com.example.app:id/search",
                bounds = AniobRect(0, 100, 400, 180),
                isEditable = true
            ),
            AniobNode(
                id = 2,
                className = "android.widget.Button",
                text = "Go",
                viewId = "com.example.app:id/go",
                bounds = AniobRect(420, 100, 520, 180),
                isClickable = true
            )
        )
    )

    private val burstPromptJson = """
        {"thought":"type then submit","is_complete":false,
         "actions":[{"type":"tap","id":1},{"type":"input","id":1,"text":"pizza"},{"type":"tap","id":2}]}
    """.trimIndent()

    // ---------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun parsesTheDocumentedBurstShape() {
        val burst = AniobActionSchema.parseActionBurstOrNull(burstPromptJson)
        assertNotNull(burst)
        burst!!
        assertEquals("type then submit", burst.thought)
        assertFalse(burst.isComplete)
        assertEquals(3, burst.actions.size)
        assertEquals(AniobAction.Tap(SemanticTarget.SomIndex(1), "type then submit").target, (burst.actions[0] as AniobAction.Tap).target)
        assertEquals("pizza", (burst.actions[1] as AniobAction.InputText).text)
        assertEquals(SemanticTarget.SomIndex(2), (burst.actions[2] as AniobAction.Tap).target)
    }

    @Test
    fun parsesGesturesAndBack() {
        val burst = AniobActionSchema.parseActionBurstOrNull(
            """{"thought":"scroll and leave","actions":[{"type":"scroll"},{"type":"back"}]}"""
        )
        assertNotNull(burst)
        assertTrue(burst!!.actions[0] is AniobAction.Swipe)
        assertEquals(KeyType.BACK, (burst.actions[1] as AniobAction.PressKey).key)
    }

    @Test
    fun rejectsBurstsOverThreeActions() {
        val burst = AniobActionSchema.parseActionBurstOrNull(
            """{"thought":"too much","actions":[
                {"type":"tap","id":1},{"type":"tap","id":2},{"type":"tap","id":1},
                {"type":"tap","id":2},{"type":"tap","id":1}]}"""
        )
        assertNull(burst)
    }

    @Test
    fun completionFlagWithoutActionsStillParses() {
        val burst = AniobActionSchema.parseActionBurstOrNull("""{"thought":"already done","is_complete":true,"actions":[]}""")
        assertNotNull(burst)
        assertTrue(burst!!.isComplete)
        assertTrue(burst.actions.isEmpty())
        assertNull(burst.tail)
    }

    @Test
    fun singleActionReplyIsRejectedSoTheCallerCanFallBack() {
        // The model ignored the burst instruction: the canonical single-action parser handles it.
        val single = """{"thought":"tap","tool":"TAP","target":{"kind":"som_index","value":1}}"""
        assertNull(AniobActionSchema.parseActionBurstOrNull(single))
        assertNotNull(AniobActionSchema.parseActionJsonOrNull(single))
    }

    @Test
    fun unparseablePayloadsReturnNullRatherThanThrowing() {
        assertNull(AniobActionSchema.parseActionBurstOrNull("not json at all"))
        assertNull(AniobActionSchema.parseActionBurstOrNull("""{"actions":[{"type":"tap"}]}"""))
        assertNull(AniobActionSchema.parseActionBurstOrNull("""{"actions":[{"id":1}]}"""))
    }

    @Test
    fun headAndTailSplitDependsOnCompletion() {
        val open = AniobActionSchema.parseActionBurstOrNull(burstPromptJson)!!
        assertEquals(2, open.head.size)
        assertNotNull(open.tail)

        val complete = AniobActionSchema.parseActionBurstOrNull(burstPromptJson.replace("false", "true"))!!
        assertEquals(3, complete.head.size)
        assertNull(complete.tail)
    }

    // ---------------------------------------------------------------------------------------------
    // Prompt
    // ---------------------------------------------------------------------------------------------

    @Test
    fun burstPromptCarriesTheCompactSchema() {
        val prompt = ExecutorRequest(
            goal = "search for pizza",
            packageName = "com.example.app",
            observationId = "hash",
            actionableNodes = screen.nodes,
            burstMode = true
        ).toPromptString()

        assertTrue(prompt.contains("\"actions\""))
        assertTrue(prompt.contains("\"is_complete\""))
        assertTrue(prompt.contains("tap|input|scroll|back"))
        assertTrue(prompt.contains("1 to 3"))
        assertTrue(prompt.contains("under 60 tokens"))
        assertFalse(prompt.contains("\"tool\": \"TAP\""))
    }

    @Test
    fun normalPromptKeepsTheSingleActionContract() {
        val prompt = ExecutorRequest(
            goal = "search for pizza",
            packageName = "com.example.app",
            observationId = "hash",
            actionableNodes = screen.nodes
        ).toPromptString()

        assertTrue(prompt.contains("\"tool\": \"TAP\""))
        assertFalse(prompt.contains("\"is_complete\""))
        assertTrue(prompt.contains("strictly ONE JSON action object"))
    }

    // ---------------------------------------------------------------------------------------------
    // Guard
    // ---------------------------------------------------------------------------------------------

    @Test
    fun burstContinuesWhileTheTargetSurvivesANewObservation() {
        // Same widget, different id: the guard matches by viewId and re-targets.
        val later = screen.copy(
            nodes = screen.nodes.map { it.copy(id = it.id + 10) }
        )
        val action = AniobAction.Tap(SemanticTarget.SomIndex(2))
        assertTrue(BurstTargetGuard.check(action, screen, later).allowed)
        val node = BurstTargetGuard.findTarget(action, screen, later)
        assertEquals("com.example.app:id/go", node?.viewId)
        val retargeted = BurstTargetGuard.retarget(action, node!!) as AniobAction.Tap
        assertEquals(SemanticTarget.ResourceId("com.example.app:id/go"), retargeted.target)
    }

    @Test
    fun burstStopsWhenTheScreenNavigatesAway() {
        val elsewhere = screen.copy(packageName = "com.other.app")
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(1)), screen, elsewhere)
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason.contains("navigated"))
    }

    @Test
    fun burstStopsWhenTheTargetIsGone() {
        val replaced = screen.copy(
            nodes = screen.nodes.filter { it.id == 1 }
        )
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(2)), screen, replaced)
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason.contains("no longer on screen"))
    }

    @Test
    fun burstStopsWhenTheTargetIsDisabled() {
        val disabled = screen.copy(nodes = screen.nodes.map { if (it.id == 2) it.copy(isEnabled = false) else it })
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(2)), screen, disabled)
        assertFalse(verdict.allowed)
        assertTrue(verdict.reason.contains("disabled"))
    }

    @Test
    fun retargetFallsBackToTextThenBounds() {
        val textOnly = AniobNode(id = 5, className = "android.widget.TextView", text = "Continue", bounds = AniobRect(0, 0, 100, 50))
        val byText = BurstTargetGuard.retarget(AniobAction.Tap(SemanticTarget.SomIndex(5)), textOnly) as AniobAction.Tap
        assertEquals(SemanticTarget.Text("Continue"), byText.target)

        val bare = AniobNode(id = 6, className = "android.view.View", bounds = AniobRect(10, 20, 30, 40))
        val byBounds = BurstTargetGuard.retarget(AniobAction.Tap(SemanticTarget.SomIndex(6)), bare) as AniobAction.Tap
        assertTrue(byBounds.target is SemanticTarget.BoundingBox)
    }

    @Test
    fun `input text carries an optional submit flag`() {
        val parsed = AniobActionSchema.parseActionJson(
            """{"thought":"search","tool":"INPUT_TEXT","target":{"kind":"som_index","value":2},"text":"pizza","submit":true}"""
        ) as AniobAction.InputText
        assertTrue(parsed.submit)

        val plain = AniobActionSchema.parseActionJson(
            """{"thought":"type","tool":"INPUT_TEXT","target":{"kind":"som_index","value":2},"text":"pizza"}"""
        ) as AniobAction.InputText
        assertFalse(plain.submit)

        val serialized = AniobActionSchema.toActionJson(parsed)
        assertTrue(serialized.contains("\"submit\":true"))
        assertEquals(parsed, AniobActionSchema.parseActionJson(serialized))
    }

    @Test
    fun `burst elements accept submit too`() {
        val burst = AniobActionSchema.parseActionBurstOrNull(
            """{"thought":"search","is_complete":true,"actions":[{"type":"input","id":3,"text":"pizza","submit":true}]}"""
        )
        val action = burst?.actions?.first() as AniobAction.InputText
        assertTrue(action.submit)
    }
}
