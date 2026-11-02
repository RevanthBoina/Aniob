package com.aniob.core.perception

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Element ids are reassigned on every capture, so an action planned before the pre-observe scroll
 * must be re-expressed against the revealed screen before it is retried. The widget is identified
 * by what survives re-indexing; when nothing does, there is no retry at all.
 */
class RetargetForRetryTest {

    private fun node(
        id: Int,
        text: String = "",
        contentDescription: String = "",
        viewId: String = ""
    ) = AniobNode(
        id = id,
        className = "android.widget.TextView",
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        bounds = AniobRect(0, id * 100, 400, id * 100 + 80)
    )

    private fun screen(vararg nodes: AniobNode) = AniobScreenState(
        packageName = "com.android.settings",
        nodes = nodes.toList(),
        treeHash = "planning"
    )

    @Test
    fun `a view id is preferred over every label`() {
        val s = screen(node(3, text = "Battery", viewId = "com.android.settings:id/battery"))
        val retried = PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(3)), s)

        assertEquals(SemanticTarget.ResourceId("com.android.settings:id/battery"), retried?.semanticTarget())
    }

    @Test
    fun `text is the second choice and is matched exactly`() {
        val s = screen(node(4, text = "About phone"))
        val retried = PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(4)), s)

        assertEquals(SemanticTarget.Text("About phone", exact = true), retried?.semanticTarget())
    }

    @Test
    fun `content description is the last stable identity`() {
        val s = screen(node(5, contentDescription = "More options"))
        val retried = PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(5)), s)

        assertEquals(SemanticTarget.ContentDesc("More options", exact = true), retried?.semanticTarget())
    }

    @Test
    fun `a node with no stable identity is not retried`() {
        val s = screen(node(6))
        assertNull(PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(6)), s))
    }

    @Test
    fun `a target that is already stable is left alone`() {
        val s = screen(node(7, text = "Battery"))
        assertNull(
            "only an index needs re-expressing",
            PreObserveScroll.retargetForRetry(
                AniobAction.Tap(SemanticTarget.ResourceId("com.android.settings:id/battery")),
                s
            )
        )
    }

    @Test
    fun `an index that is not on the planning screen is not retried`() {
        val s = screen(node(8, text = "Battery"))
        assertNull(PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(99)), s))
    }

    @Test
    fun `texting keeps the text and the submit flag`() {
        val s = screen(node(9, viewId = "com.android.settings:id/search"))
        val action = AniobAction.InputText(
            target = SemanticTarget.SomIndex(9),
            text = "battery",
            clearFirst = true,
            submit = true
        )

        val retried = PreObserveScroll.retargetForRetry(action, s)
        assertTrue(retried is AniobAction.InputText)
        retried as AniobAction.InputText
        assertEquals("battery", retried.text)
        assertTrue(retried.clearFirst)
        assertTrue(retried.submit)
        assertEquals(SemanticTarget.ResourceId("com.android.settings:id/search"), retried.target)
    }

    @Test
    fun `an ambiguous label is not guessed`() {
        val s = screen(
            node(10, text = "Battery"),
            node(11, text = "Battery")
        )

        assertNull(
            "two widgets answer to the same label; a wrong tap is worse than a retry miss",
            PreObserveScroll.retargetForRetry(AniobAction.Tap(SemanticTarget.SomIndex(10)), s)
        )
        assertFalse(s.nodes.isEmpty())
    }
}
