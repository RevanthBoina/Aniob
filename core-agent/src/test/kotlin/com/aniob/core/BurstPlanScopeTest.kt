package com.aniob.core

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.perception.BurstPlanScope
import com.aniob.core.providers.AniobStructuredOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled-plan contract as a model actually produces it.
 *
 * The earlier contract assumed a model would answer a "plan 2-3 actions for this screen" request
 * with a list whose every element is visible right now. A model that understands the task answers
 * with the whole task — `tap(Add alarm) → input(7:30) → tap(OK)` — and two thirds of that reply
 * describes a screen that does not exist yet. Those replies were rejected outright, which is why
 * the multi-action mode could end up doing *more* round trips than one action per call.
 *
 * These tests pin the reduction instead of the rejection: the part of the plan this screen can
 * ground runs, the rest is deferred, and a plan that cannot be grounded at all still falls back.
 */
class BurstPlanScopeTest {

    private fun node(
        id: Int,
        cls: String,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        click: Boolean = false,
        edit: Boolean = false,
        password: Boolean = false
    ) = AniobNode(
        id = id,
        className = cls,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        isClickable = click,
        isEditable = edit,
        isPassword = password,
        bounds = AniobRect(0, id * 100, 500, id * 100 + 80)
    )

    /** Clock app, alarm list: the only targets are the add button and an existing alarm. */
    private val alarmList = AniobScreenState(
        packageName = "com.google.android.deskclock",
        nodes = listOf(
            node(1, "android.widget.ImageButton", desc = "Add alarm", click = true),
            node(2, "android.widget.TextView", text = "Alarm"),
            node(3, "android.widget.TextView", text = "6:15 PM", click = true)
        )
    )

    /** The editor the first tap opens; ids here collide with the list's ids, which is the point. */
    private val alarmEditor = AniobScreenState(
        packageName = "com.google.android.deskclock",
        nodes = listOf(
            node(1, "android.widget.EditText", text = "7:30", edit = true),
            node(2, "android.widget.Button", text = "OK", click = true)
        )
    )

    private val goal = "Open Clock and set an alarm for 7:30 AM"

    private fun plan(raw: String): ActionBurst = AniobActionSchema.parseActionBurstOrNull(raw)!!

    // ---------------------------------------------------------------------------------------------
    // Parsing: the spellings models actually emit
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a quoted id is the same id`() {
        val burst = plan("""{"thought":"p","actions":[{"type":"tap","id":"1"}]}""")
        assertEquals(SemanticTargetOf.som(1), (burst.actions.single() as AniobAction.Tap).target)
    }

    @Test
    fun `the canonical target object is accepted inside a plan`() {
        val burst = plan("""{"thought":"p","actions":[{"type":"tap","target":{"kind":"som_index","value":2}}]}""")
        assertEquals(SemanticTargetOf.som(2), (burst.actions.single() as AniobAction.Tap).target)
    }

    @Test
    fun `index and node_id are aliases for id`() {
        assertEquals(SemanticTargetOf.som(3), (plan("""{"actions":[{"type":"tap","index":3}]}""").actions.single() as AniobAction.Tap).target)
        assertEquals(SemanticTargetOf.som(4), (plan("""{"actions":[{"type":"tap","node_id":4}]}""").actions.single() as AniobAction.Tap).target)
    }

    @Test
    fun `a plan may start by opening an app or issuing a device command`() {
        val open = plan("""{"actions":[{"type":"open_app","package_name":"com.google.android.deskclock"}]}""")
        assertEquals("com.google.android.deskclock", (open.actions.single() as AniobAction.OpenApp).packageName)

        val command = plan("""{"actions":[{"type":"device_control","command":"volume_set","args":{"percent":"70"}}]}""")
        val device = command.actions.single() as AniobAction.DeviceControl
        assertEquals("volume_set", device.command.wire)
        assertEquals("70", device.args["percent"])
    }

    @Test
    fun `long press, back, home and scroll are all expressible in a plan`() {
        val burst = plan(
            """{"actions":[{"type":"long_press","id":1},{"type":"back"},{"type":"scroll","direction":"down"}]}"""
        )
        assertTrue(burst.actions[0] is AniobAction.LongPress)
        assertEquals(KeyType.BACK, (burst.actions[1] as AniobAction.PressKey).key)
        assertEquals(SwipeDirection.DOWN, (burst.actions[2] as AniobAction.Swipe).direction)
    }

    @Test
    fun `prose and a markdown fence around the object are tolerated`() {
        val burst = AniobActionSchema.parseActionBurstOrNull(
            "Here is my plan:\n```json\n{\"thought\":\"p\",\"actions\":[{\"type\":\"tap\",\"id\":2}]}\n```\nDone."
        )
        assertNotNull(burst)
        assertEquals(1, burst!!.actions.size)
    }

    @Test
    fun `a plan longer than the cap is refused whole, never truncated`() {
        assertNull(
            AniobActionSchema.parseActionBurstOrNull(
                """{"actions":[{"type":"tap","id":1},{"type":"tap","id":2},{"type":"tap","id":3},{"type":"tap","id":1}]}"""
            )
        )
    }

    @Test
    fun `an unknown action type refuses the plan instead of guessing`() {
        assertNull(AniobActionSchema.parseActionBurstOrNull("""{"actions":[{"type":"teleport","id":1}]}"""))
    }

    // ---------------------------------------------------------------------------------------------
    // Scoping: the plan crosses the screen boundary
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the part of the plan this screen can ground runs, the rest is deferred`() {
        val scoped = BurstPlanScope.scope(
            plan("""{"thought":"p","is_complete":true,"actions":[
                {"type":"tap","id":1},{"type":"input","id":1,"text":"7:30"},{"type":"tap","id":2}]}"""),
            alarmList,
            goal
        )
        assertEquals(listOf("tap"), scoped.executable.map { it.toolName })
        assertEquals(listOf("input_text", "tap"), scoped.deferred.map { it.toolName })
        assertTrue(scoped.groundedOnScreen)
        assertTrue(scoped.deferredBecause.contains("not on the observed screen"))
    }

    @Test
    fun `typing into an id that names a non-editable widget is deferred, not dispatched`() {
        // Id 2 on the list is a plain label. Grounding would happily "resolve" it, and the tap that
        // followed would land on the wrong screen's button. Only a field can be typed into.
        val scoped = BurstPlanScope.scope(
            plan("""{"thought":"p","actions":[{"type":"tap","id":1},{"type":"input","id":2,"text":"7:30"}]}"""),
            alarmList,
            goal
        )
        assertEquals(1, scoped.executable.size)
        assertEquals(1, scoped.deferred.size)
    }

    @Test
    fun `a plan whose first action is not on the screen is refused so the caller can fall back`() {
        val scoped = BurstPlanScope.scope(
            plan("""{"thought":"p","actions":[{"type":"tap","id":42},{"type":"tap","id":1}]}"""),
            alarmList,
            goal
        )
        assertFalse(scoped.groundedOnScreen)
        assertTrue(scoped.executable.isEmpty())
        assertNull(AniobStructuredOutput.parseBurstOrRepair(
            """{"thought":"p","actions":[{"type":"tap","id":42}]}""", alarmList, goal
        ))
    }

    @Test
    fun `a truncated plan never claims the goal is complete`() {
        val scoped = BurstPlanScope.scope(
            plan("""{"thought":"p","is_complete":true,"actions":[
                {"type":"tap","id":1},{"type":"input","id":1,"text":"7:30"},{"type":"tap","id":2}]}"""),
            alarmList,
            goal
        )
        assertFalse(
            "closing the run after the first of three planned actions would report a task the screen does not show",
            scoped.actionable.isComplete
        )
    }

    @Test
    fun `a fully grounded plan keeps its completion`() {
        val burst = plan("""{"thought":"p","is_complete":true,"actions":[{"type":"tap","id":1},{"type":"tap","id":3}]}""")
        val scoped = BurstPlanScope.scope(burst, alarmList, goal)
        assertTrue(scoped.actionable.isComplete)
        assertEquals(2, scoped.executable.size)
        assertTrue(scoped.deferred.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Typing without an id: the one field on screen
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `typing without an id is allowed when the screen has exactly one field and the text is the user's`() {
        val burst = plan("""{"thought":"p","is_complete":true,"actions":[{"type":"input","text":"7:30"}]}""")
        val input = burst.actions.single() as AniobAction.InputText
        assertEquals(ActionBurst.UNNAMED_TARGET, (input.target as com.aniob.core.domain.SemanticTarget.SomIndex).index)

        val scoped = BurstPlanScope.scope(burst, alarmEditor, goal)
        assertTrue(scoped.groundedOnScreen)
        assertEquals(1, scoped.executable.size)
    }

    @Test
    fun `typing without an id is refused when the value is not in the request`() {
        // An injected screen must not be able to steer arbitrary text into the UI.
        val burst = plan("""{"thought":"p","actions":[{"type":"input","text":"9999"}]}""")
        assertFalse(BurstPlanScope.scope(burst, alarmEditor, goal).groundedOnScreen)
    }

    @Test
    fun `typing without an id is refused on a password field`() {
        val secure = AniobScreenState(
            packageName = "com.example",
            nodes = listOf(node(1, "android.widget.EditText", edit = true, password = true))
        )
        val burst = plan("""{"actions":[{"type":"input","text":"7:30"}]}""")
        assertFalse(BurstPlanScope.scope(burst, secure, goal).groundedOnScreen)
    }

    @Test
    fun `typing without an id is refused when the screen has several fields`() {
        val twoFields = AniobScreenState(
            packageName = "com.example",
            nodes = listOf(
                node(1, "android.widget.EditText", edit = true),
                node(2, "android.widget.EditText", edit = true)
            )
        )
        val burst = plan("""{"actions":[{"type":"input","text":"7:30"}]}""")
        assertFalse(BurstPlanScope.scope(burst, twoFields, goal).groundedOnScreen)
    }

    // ---------------------------------------------------------------------------------------------
    // The deferred tail, and the boundary the caller relies on
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the deferred tail is a neutral planning hint, not an instruction`() {
        val scoped = BurstPlanScope.scope(
            plan("""{"thought":"p","actions":[{"type":"tap","id":1},{"type":"input","id":1,"text":"7:30"}]}"""),
            alarmList,
            goal
        )
        val hint = BurstPlanScope.renderDeferredHint(scoped.deferred)
        assertNotNull(hint)
        assertTrue(hint!!.startsWith("PLAN ALREADY CHOSEN"))
        assertTrue(hint.contains("input"))
        assertTrue(hint.contains("7:30"))
        assertNull(BurstPlanScope.renderDeferredHint(emptyList()))
    }

    @Test
    fun `a single-action reply stays with the single-action contract`() {
        assertNull(
            AniobStructuredOutput.parseBurstOrRepair(
                """{"thought":"tap","tool":"TAP","target":{"kind":"som_index","value":1}}""",
                alarmList,
                goal
            )
        )
    }

    @Test
    fun `a completion with no actions is returned as such`() {
        val burst = AniobStructuredOutput.parseBurstOrRepair(
            """{"thought":"already done","is_complete":true,"actions":[]}""",
            alarmList,
            goal
        )
        assertNotNull(burst)
        assertTrue(burst!!.isComplete)
        assertTrue(burst.actions.isEmpty())
    }

    @Test
    fun `a plan with no executable action returns null so the step can repair`() {
        assertNull(
            AniobStructuredOutput.parseBurstOrRepair(
                """{"thought":"p","actions":[{"type":"tap","id":99},{"type":"tap","id":98}]}""",
                alarmList,
                goal
            )
        )
    }

    private object SemanticTargetOf {
        fun som(index: Int) = com.aniob.core.domain.SemanticTarget.SomIndex(index)
    }
}
