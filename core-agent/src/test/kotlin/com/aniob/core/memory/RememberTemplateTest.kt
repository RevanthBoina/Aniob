package com.aniob.core.memory

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Remember cache keys on the *shape* of a goal, so a verified run teaches the agent every
 * later task of the same shape; the values that differ between them travel as parameters and are
 * injected into the stored trace.
 */
class RememberTemplateTest {

    @Test fun `linkedin replay binds current person and message on same navigation purpose`() {
        val oldGoal = "message Alice Brown on LinkedIn saying Hello Alice"
        val newGoal = "message Ravi Kumar on LinkedIn saying Can we discuss the role?"
        assertEquals(RememberTemplate.keyOf(oldGoal), RememberTemplate.keyOf(newGoal))
        val oldScreen = AniobScreenState(packageName = "com.linkedin.android", treeHash = "old",
            nodes = listOf(AniobNode(1, "EditText", viewId = "search", isEditable = true)))
        val cache = RememberCache()
        assertTrue(cache.record(oldGoal, oldScreen.packageName,
            RememberCache.navigationScreenKey(oldGoal, oldScreen),
            listOf(RememberAction(kind = RememberAction.INPUT, viewId = "search", inputText = "Alice Brown"))))
        val action = cache.lookup(newGoal, oldScreen.copy(treeHash = "new"))!!.single() as AniobAction.InputText
        assertEquals("Ravi Kumar", action.text)
        val draft = RememberAction(kind = RememberAction.INPUT, viewId = "compose", inputText = "Hello Alice")
            .parameterised(RememberTemplate.parse(oldGoal)).injected(RememberTemplate.parse(newGoal).params)!!
        assertEquals("Can we discuss the role?", draft.inputText)
        assertNull(cache.lookup(newGoal, oldScreen.copy(nodes = emptyList())))
    }

    @Test fun `shared profile resource id cannot select the wrong recipient`() {
        val goal = "message Alice on LinkedIn saying hello"
        val cache = RememberCache()
        val screen = AniobScreenState(packageName = "com.linkedin.android", treeHash = "results",
            nodes = listOf(AniobNode(1, "TextView", viewId = "profile", text = "Alice", isClickable = true)))
        cache.record(goal, screen.packageName, RememberCache.navigationScreenKey(goal, screen),
            listOf(RememberAction(kind = RememberAction.TAP, viewId = "profile", text = "Alice")))
        assertNotNull(cache.lookup("message Bob on LinkedIn saying hi", screen.copy(nodes = listOf(
            screen.nodes.single().copy(text = "Bob")))))
        assertNull(cache.lookup("message Bob on LinkedIn saying hi", screen))
    }

    @Test fun `purpose matching keeps unrelated tasks separate`() {
        assertFalse(RememberTemplate.keyOf("search Alice on LinkedIn") ==
            RememberTemplate.keyOf("message Alice on LinkedIn saying hello"))
        val old = RememberTemplate.parse("message Alice on LinkedIn saying hello")
        val descriptor = RememberAction(kind = RememberAction.TAP, contentDescription = "Alice")
        assertEquals("Bob", descriptor.parameterised(old).injected(listOf("Bob", "hi"))!!.contentDescription)
    }

    private fun clockScreen() = AniobScreenState(
        packageName = "com.google.android.deskclock",
        treeHash = "clock:alarm-editor",
        nodes = listOf(
            AniobNode(
                id = 2,
                className = "android.widget.EditText",
                viewId = "com.google.android.deskclock:id/alarm_time",
                bounds = AniobRect(0, 200, 600, 300),
                isClickable = true,
                isEditable = true
            ),
            AniobNode(
                id = 3,
                className = "android.widget.Button",
                text = "OK",
                bounds = AniobRect(0, 900, 600, 1000),
                isClickable = true
            )
        )
    )

    @Test
    fun `a clock time becomes a TIME parameter`() {
        val parsed = RememberTemplate.parse("set alarm for 7:30")
        assertEquals("set alarm for {TIME}", parsed.template)
        assertEquals(listOf("7:30"), parsed.params)
        assertEquals(listOf(RememberTemplate.Kind.TIME), parsed.kinds)
    }

    @Test
    fun `numbers become NUM parameters in order`() {
        val parsed = RememberTemplate.parse("calculate 25 plus 40")
        assertEquals("calculate {num} plus {num}".replace("num", "NUM"), parsed.template)
        assertEquals(listOf("25", "40"), parsed.params)
    }

    @Test
    fun `a quoted name becomes a NAME parameter`() {
        val parsed = RememberTemplate.parse("add contact \"John Study\"")
        assertEquals("add contact {NAME}", parsed.template)
        assertEquals(listOf("John Study"), parsed.params)
    }

    @Test
    fun `two prompts of the same shape share a template`() {
        assertEquals(
            RememberTemplate.parse("set alarm for 7:30").template,
            RememberTemplate.parse("set alarm for 8:15 pm").template
        )
    }

    @Test
    fun `a goal without values keeps the keyword key`() {
        // Backwards compatible with the entries already on disk: no parameters means the old
        // order-insensitive keyword key, not a template.
        assertEquals("pizza-search", RememberTemplate.keyOf("search for pizza"))
        assertFalse(RememberTemplate.parse("search for pizza").hasParams)
    }

    @Test
    fun `placeholders are filled from the new prompt`() {
        assertEquals("8:15", RememberTemplate.inject("{PARAM_1}", listOf("8:15")))
        assertEquals(
            "calculate 30 plus 50",
            RememberTemplate.inject("calculate {PARAM_2} plus {PARAM_1}", listOf("50", "30"))
        )
    }

    @Test
    fun `a placeholder without a value is a miss, never a stale value`() {
        assertNull(RememberTemplate.inject("{PARAM_2}", listOf("only-one")))
    }

    @Test
    fun `a recovered action stores the prompt value as a placeholder`() {
        val parsed = RememberTemplate.parse("set alarm for 7:30")
        val action = RememberAction(
            kind = RememberAction.INPUT,
            viewId = "com.google.android.deskclock:id/alarm_time",
            inputText = "7:30"
        )

        val stored = action.parameterised(parsed)
        assertEquals("{PARAM_1}", stored.inputText)
        assertEquals("8:15", stored.injected(listOf("8:15"))?.inputText)
    }

    @Test
    fun `a label that is not the parameter is left alone`() {
        val parsed = RememberTemplate.parse("set alarm for 7:30")
        val digit = RememberAction(kind = RememberAction.TAP, text = "7")
        assertEquals("7", digit.parameterised(parsed).text)

        val ok = RememberAction(kind = RememberAction.TAP, text = "OK")
        assertEquals("OK", ok.parameterised(parsed).text)
    }

    @Test
    fun `a warm run replays the same trace with the new value`() {
        val cache = RememberCache()
        val screen = clockScreen()
        val typed = RememberAction.fromAction(
            action = AniobAction.InputText(SemanticTarget.ResourceId("com.google.android.deskclock:id/alarm_time"), "7:30"),
            node = screen.nodes.first(),
            screen = screen
        )!!

        assertTrue(
            cache.record(
                goal = "set alarm for 7:30",
                packageName = screen.packageName,
                preActionScreenHash = screen.treeHash,
                actions = listOf(typed)
            )
        )

        val replay = cache.lookup("set alarm for 8:15", screen)
        assertNotNull("the same shape must hit from a different prompt", replay)
        val action = replay!!.single()
        assertTrue(action is AniobAction.InputText)
        assertEquals("8:15", (action as AniobAction.InputText).text)

        val same = cache.lookup("set alarm for 7:30", screen)
        assertEquals("7:30", (same!!.single() as AniobAction.InputText).text)
    }

    @Test
    fun `a template entry does not answer an unrelated goal`() {
        val cache = RememberCache()
        val screen = clockScreen()
        val typed = RememberAction.fromAction(
            action = AniobAction.InputText(SemanticTarget.ResourceId("com.google.android.deskclock:id/alarm_time"), "7:30"),
            node = screen.nodes.first(),
            screen = screen
        )!!
        cache.record("set alarm for 7:30", screen.packageName, screen.treeHash, listOf(typed))

        assertNull(cache.lookup("set a timer for 7:30", screen))
    }
}
