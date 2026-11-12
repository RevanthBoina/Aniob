package com.aniob.core.tools

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers scroll-until-visible: when a valid element is further along a list or picker, the agent
 * scrolls the specific container and retries instead of reporting element-not-found.
 */
class AniobScrollHelperTest {

    private val list = AniobNode(
        id = 99, className = "androidx.recyclerview.widget.RecyclerView", isScrollable = true,
        bounds = AniobRect(0, 0, 1080, 2000)
    )

    private fun row(id: Int, text: String) = AniobNode(
        id = id, className = "android.widget.TextView", text = text,
        bounds = AniobRect(0, id * 60, 300, id * 60 + 50)
    )

    private fun screenWith(visibleTexts: List<String>, scrollable: Boolean = true) = AniobScreenState(
        packageName = "com.example.app",
        nodes = buildList {
            addAll(visibleTexts.mapIndexed { i, t -> row(i + 1, t) })
            if (scrollable) add(list)
        }
    )

    @Test
    fun `returns immediately when target already visible`() = runBlocking {
        val screen = screenWith(listOf("Search", "Submit"))
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screen,
            targetPredicate = { it.text == "Submit" },
            container = list,
            capture = { error("must not capture when already found") },
            scrollNode = { _, _, _ -> error("must not scroll when already found") }
        )
        assertTrue(result.found)
        assertEquals(0, result.swipes)
        assertEquals("Submit", result.node?.text)
    }

    @Test
    fun `scrolls forward and finds element that was below the fold`() = runBlocking {
        val afterScroll = screenWith(listOf("Profile", "Submit Button"))
        val directions = mutableListOf<Boolean>()
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screenWith(listOf("Search", "Profile")),
            targetPredicate = { it.text.contains("Submit") },
            container = list,
            capture = { afterScroll },
            scrollNode = { node, forward, dy ->
                assertEquals(99, node.id)
                assertEquals((2000 * AniobScrollHelper.LIST_STEP_FRACTION).toInt(), dy)
                directions += forward
                true
            }
        )
        assertTrue(result.found)
        assertEquals("Submit Button", result.node?.text)
        assertEquals(listOf(true), directions)
        assertEquals(afterScroll, result.screen)
    }

    @Test
    fun `returns not found when there is no container`() = runBlocking {
        val screen = screenWith(listOf("Search"), scrollable = false)
        assertNull(AniobScrollHelper.chooseContainer(screen, "Submit"))
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screen,
            targetPredicate = { it.text == "Submit" },
            container = null,
            capture = { screen },
            scrollNode = { _, _, _ -> error("nothing to scroll") }
        )
        assertFalse(result.found)
        assertEquals(0, result.swipes)
    }

    @Test
    fun `stops at the swipe ceiling with failure when content keeps changing`() = runBlocking {
        var page = 0
        var swipes = 0
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screenWith(listOf("item 0")),
            targetPredicate = { it.text == "NeverThere" },
            container = list,
            capture = { screenWith(listOf("item ${++page}")) },
            scrollNode = { _, _, _ -> swipes++; true }
        )
        assertFalse(result.found)
        assertEquals(24, AniobScrollHelper.MAX_SWIPES)
        assertEquals(AniobScrollHelper.MAX_SWIPES, swipes)
        assertEquals(AniobScrollHelper.MAX_SWIPES, result.swipes)
        assertFalse(result.reversed)
    }

    @Test
    fun `reverses once after three unchanged reads then fails at the other end`() = runBlocking {
        val screen = screenWith(listOf("Search"))
        val directions = mutableListOf<Boolean>()
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screen,
            targetPredicate = { it.text == "NeverThere" },
            container = list,
            capture = { screen },
            scrollNode = { _, forward, _ -> directions += forward; true }
        )
        assertFalse(result.found)
        assertTrue(result.reversed)
        assertEquals(listOf(true, true, true, false, false, false), directions)
        assertEquals(AniobScrollHelper.STALL_LIMIT * 2, result.swipes)
    }

    @Test
    fun `finds a target that is behind the start after reversing`() = runBlocking {
        val bottom = screenWith(listOf("Zulu"))
        val top = screenWith(listOf("Alpha"))
        val directions = mutableListOf<Boolean>()
        var showTop = false
        val result = AniobScrollHelper.scrollUntilFound(
            screen = bottom,
            targetPredicate = { it.text == "Alpha" },
            container = list,
            capture = { if (showTop) top else bottom },
            scrollNode = { _, forward, _ -> directions += forward; showTop = !forward; true }
        )
        assertTrue(result.found)
        assertTrue(result.reversed)
        assertEquals(listOf(true, true, true, false), directions)
    }

    @Test
    fun `a refused scroll counts as not moving`() = runBlocking {
        val screen = screenWith(listOf("Search"))
        var attempts = 0
        val result = AniobScrollHelper.scrollUntilFound(
            screen = screen,
            targetPredicate = { it.text == "NeverThere" },
            container = list,
            capture = { error("nothing moved, nothing to read") },
            scrollNode = { _, _, _ -> attempts++; false }
        )
        assertFalse(result.found)
        assertEquals(AniobScrollHelper.STALL_LIMIT * 2, attempts)
    }

    @Test
    fun `start direction can be backward`() = runBlocking {
        val screen = screenWith(listOf("Search"))
        var first: Boolean? = null
        AniobScrollHelper.scrollUntilFound(
            screen = screen,
            targetPredicate = { false },
            container = list,
            capture = { screen },
            scrollNode = { _, forward, _ -> if (first == null) first = forward; true },
            startForward = false
        )
        assertEquals(false, first)
        assertEquals(SwipeDirection.UP, AniobScrollHelper.getSemanticDirection("scroll up to the top"))
        assertEquals(SwipeDirection.DOWN, AniobScrollHelper.getSemanticDirection("find battery"))
    }

    // ---- container choice -------------------------------------------------------------------

    private val page = AniobNode(
        id = 10, className = "android.widget.ScrollView", isScrollable = true, bounds = AniobRect(0, 0, 1080, 2200)
    )
    private val hourWheel = AniobNode(
        id = 11, className = "android.widget.NumberPicker", contentDescription = "Hour",
        isScrollable = true, bounds = AniobRect(100, 800, 400, 1200)
    )
    private val minuteWheel = AniobNode(
        id = 12, className = "android.widget.NumberPicker", contentDescription = "Minute",
        isScrollable = true, bounds = AniobRect(500, 700, 900, 1300)
    )

    @Test
    fun `smallest container is chosen when nothing mentions the target`() {
        val screen = AniobScreenState(packageName = "p", nodes = listOf(page, minuteWheel, hourWheel))
        assertEquals(11, AniobScrollHelper.chooseContainer(screen, "")?.id)
        assertEquals(11, AniobScrollHelper.chooseContainer(screen, "zzz unrelated")?.id)
    }

    @Test
    fun `container that mentions the target wins over a smaller one`() {
        val screen = AniobScreenState(packageName = "p", nodes = listOf(page, minuteWheel, hourWheel))
        assertEquals(12, AniobScrollHelper.chooseContainer(screen, "minute 45")?.id)
    }

    @Test
    fun `mention can come from a row inside the container`() {
        val countries = AniobNode(
            id = 20, className = "android.widget.ListView", isScrollable = true, bounds = AniobRect(0, 1300, 1080, 2100)
        )
        val label = AniobNode(id = 21, className = "android.widget.TextView", text = "Country: Albania", bounds = AniobRect(20, 1320, 600, 1380))
        val screen = AniobScreenState(packageName = "p", nodes = listOf(page, hourWheel, countries, label))
        // The page also contains the label, but the inner list is the smaller container mentioning it.
        assertEquals(20, AniobScrollHelper.chooseContainer(screen, "country India")?.id)
    }

    @Test
    fun `pickers step one row and lists step most of a page`() {
        assertEquals(140, AniobScrollHelper.stepPx(hourWheel))
        assertEquals((2200 * 0.6).toInt(), AniobScrollHelper.stepPx(page))
    }

    @Test
    fun `textual targets are matched exactly or by containment`() {
        val node = row(1, "Battery saver")
        assertTrue(AniobScrollHelper.matches(node, SemanticTarget.Text("battery")))
        assertFalse(AniobScrollHelper.matches(node, SemanticTarget.Text("battery", exact = true)))
        assertTrue(AniobScrollHelper.matches(node, SemanticTarget.Text("battery saver", exact = true)))
        assertFalse(AniobScrollHelper.matches(node, SemanticTarget.SomIndex(1)))
        assertEquals("Battery", AniobScrollHelper.textOf(SemanticTarget.ContentDesc(" Battery ")))
        assertNull(AniobScrollHelper.textOf(SemanticTarget.SomIndex(3)))
    }
}
