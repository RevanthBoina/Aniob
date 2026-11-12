package com.aniob.core.perception

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-observe scroll contract: one scroll per screen, a merged observation that keeps the
 * fresh viewport actionable, and nothing carried across a package change.
 */
class PreObserveScrollTest {

    private fun node(
        id: Int,
        text: String,
        clickable: Boolean = true,
        scrollable: Boolean = false,
        viewId: String = "res/$id"
    ) = AniobNode(
        id = id,
        className = "android.widget.TextView",
        text = text,
        viewId = viewId,
        bounds = AniobRect(0, id * 100, 400, id * 100 + 80),
        isClickable = clickable,
        isScrollable = scrollable
    )

    private fun screen(pkg: String, hash: String, vararg nodes: AniobNode) = AniobScreenState(
        packageName = pkg,
        nodes = nodes.toList(),
        treeHash = hash
    )

    @Test
    fun scrollsWhenTheScreenHasAContainerAndNothingWasTried() {
        val s = screen("com.android.settings", "h1", node(1, "Settings", scrollable = true), node(2, "Network"))
        assertTrue(PreObserveScroll.shouldScroll(s, emptyMap()))
        assertTrue(PreObserveScroll.scrollableContainer(s)?.isScrollable == true)
    }

    @Test
    fun stopsAfterEightScrollsOfTheSameScreenUnlessATextTargetIsPending() {
        val s = screen("com.android.settings", "h1", node(1, "Settings", scrollable = true))
        assertEquals(8, PreObserveScroll.MAX_SCROLLS_PER_SCREEN)
        assertTrue(PreObserveScroll.shouldScroll(s, mapOf("h1" to 7)))
        assertFalse(PreObserveScroll.shouldScroll(s, mapOf("h1" to 8)))
        assertTrue(PreObserveScroll.shouldScroll(s, mapOf("h1" to 8), textTargetPending = true))
        assertTrue(PreObserveScroll.shouldScroll(s, mapOf("other" to 8)))
    }

    @Test
    fun doesNothingWhenNothingScrolls() {
        val s = screen("com.android.settings", "h1", node(1, "Settings"))
        assertFalse(PreObserveScroll.shouldScroll(s, emptyMap()))
    }

    @Test
    fun mergeKeepsTheNewViewportActionableAndCarriesTheOldLabels() {
        val before = screen(
            "com.android.settings", "h1",
            node(1, "Network", scrollable = true),
            node(2, "Display")
        )
        val after = screen(
            "com.android.settings", "h2",
            node(1, "Battery", scrollable = true),
            node(2, "Storage")
        )

        val merged = PreObserveScroll.merge(before, after)

        assertEquals(4, merged.nodes.size)
        // The post-scroll nodes keep their ids: a dispatch grounds against exactly this viewport.
        assertTrue(merged.findNodeById(1)?.text == "Battery")
        assertTrue(merged.findNodeById(2)?.text == "Storage")
        assertTrue(merged.findNodeById(1)?.isScrollable == true)
        // Carried nodes are context only.
        val carried = merged.nodes.filter { it.text == "Network" || it.text == "Display" }
        assertEquals(2, carried.size)
        assertTrue(carried.all { !it.isClickable && !it.isScrollable && !it.isEditable })
        assertTrue(merged.nodes.map { it.id }.distinct().size == merged.nodes.size)
    }

    @Test
    fun mergeDropsNodesThatAreStillVisible() {
        val before = screen("com.android.settings", "h1", node(1, "Network"), node(2, "Display"))
        val after = screen("com.android.settings", "h2", node(1, "Network"), node(2, "Battery"))
        val merged = PreObserveScroll.merge(before, after)
        assertEquals(3, merged.nodes.size)
        assertEquals(1, merged.nodes.count { it.text == "Network" })
    }

    @Test
    fun mergeRefusesToJoinTwoDifferentApps() {
        val before = screen("com.android.settings", "h1", node(1, "Network"))
        val after = screen("com.android.chrome", "h2", node(1, "Search"))
        val merged = PreObserveScroll.merge(before, after)
        assertEquals(1, merged.nodes.size)
        assertEquals("Search", merged.nodes.first().text)
    }

    @Test
    fun mergeSkipsUnlabelledNoise() {
        val before = screen(
            "com.android.settings", "h1",
            AniobNode(id = 9, className = "android.view.View", bounds = AniobRect(0, 0, 10, 10))
        )
        val after = screen("com.android.settings", "h2", node(1, "Battery"))
        assertEquals(1, PreObserveScroll.merge(before, after).nodes.size)
    }
}
