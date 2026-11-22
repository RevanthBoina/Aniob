package com.aniob.core.tools

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save-class completion capability: discovery of commit-like controls on any editor screen.
 * Generic by contract — no app or package may ever appear here.
 */
class AniobEditorCommitTest {

    private fun node(
        id: Int,
        text: String = "",
        desc: String = "",
        click: Boolean = true,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true
    ) = AniobNode(
        id = id,
        className = if (editable) "android.widget.EditText" else "android.widget.Button",
        text = text,
        contentDescription = desc,
        isClickable = click,
        isEditable = editable,
        isEnabled = enabled,
        isVisibleToUser = visible,
        bounds = AniobRect(0, id * 100, 200, id * 100 + 80)
    )

    @Test
    fun `an editor's save control is discovered`() {
        val controls = AniobEditorCommit.findCommitControls(
            listOf(node(1, "Cancel"), node(2, "Save"), node(3, "Discard"))
        )
        assertEquals(listOf(2), controls.map { it.node.id })
    }

    @Test
    fun `send done post publish apply are commits`() {
        for (label in listOf("Send", "Done", "Post", "Publish", "Apply", "Update", "Create", "OK")) {
            val controls = AniobEditorCommit.findCommitControls(listOf(node(1, label)))
            assertEquals("$label is a commit control", 1, controls.size)
        }
    }

    @Test
    fun `typed text is not a commit control`() {
        val controls = AniobEditorCommit.findCommitControls(
            listOf(node(1, "Save the world", editable = true))
        )
        assertTrue(controls.isEmpty())
    }

    @Test
    fun `invisible or disabled controls are not offered`() {
        val controls = AniobEditorCommit.findCommitControls(
            listOf(node(1, "Save", enabled = false), node(2, "Save", visible = false))
        )
        assertTrue(controls.isEmpty())
    }

    @Test
    fun `accessibility label works when there is no visible text`() {
        val controls = AniobEditorCommit.findCommitControls(listOf(node(1, desc = "Save")))
        assertEquals(1, controls.size)
    }

    @Test
    fun `the prompt hint names the candidates and the rule, or nothing at all`() {
        val hint = AniobEditorCommit.promptHint(listOf(node(4, "Save"), node(6, "Create")))
        assertNotNull(hint)
        assertTrue(hint!!.contains("node 4"))
        assertTrue(hint.contains("node 6"))
        assertTrue(hint.contains("not complete"))
        assertNull(AniobEditorCommit.promptHint(listOf(node(1, "Cancel"), node(2, "Discard"))))
    }
}
