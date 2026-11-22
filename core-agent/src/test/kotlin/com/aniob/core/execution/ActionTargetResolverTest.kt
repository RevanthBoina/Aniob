package com.aniob.core.execution

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.perception.ActionTargetResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one action-level target-resolution contract: the unnamed-input sentinel resolves only for an
 * input, only when exactly one usable editable field exists; a stale resource id resolves only via
 * its declared semantic fallback, and only to a unique control.
 */
class ActionTargetResolverTest {

    private fun node(
        id: Int,
        cls: String,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        click: Boolean = false,
        edit: Boolean = false,
        password: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true
    ) = AniobNode(
        id = id,
        className = cls,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        isClickable = click,
        isEditable = edit,
        isPassword = password,
        isEnabled = enabled,
        isVisibleToUser = visible,
        bounds = AniobRect(0, id * 100, 500, id * 100 + 80)
    )

    private fun screen(nodes: List<AniobNode>) = AniobScreenState(packageName = "com.example", nodes = nodes)

    private fun unnamedInput(text: String = "7:30") =
        AniobAction.InputText(SemanticTarget.SomIndex(ActionBurst.UNNAMED_TARGET), text)

    // ---------------------------------------------------------------- unnamed input sentinel ----

    @Test
    fun `unnamed input resolves to the single visible editable field`() {
        val single = screen(listOf(node(7, "android.widget.EditText", edit = true)))
        val resolved = ActionTargetResolver.resolve(unnamedInput(), single)
        assertNotNull(resolved)
        assertEquals(7, resolved!!.node.id)
    }

    @Test
    fun `unnamed input fails closed when there is no editable field`() {
        val none = screen(listOf(node(1, "android.widget.Button", text = "Save", click = true)))
        assertNull(ActionTargetResolver.resolve(unnamedInput(), none))
    }

    @Test
    fun `unnamed input fails closed when there are two editable fields`() {
        val two = screen(listOf(
            node(1, "android.widget.EditText", edit = true),
            node(2, "android.widget.EditText", edit = true)
        ))
        assertNull(ActionTargetResolver.resolve(unnamedInput(), two))
    }

    @Test
    fun `unnamed input fails closed when the only field is disabled`() {
        val disabled = screen(listOf(node(1, "android.widget.EditText", edit = true, enabled = false)))
        assertNull(ActionTargetResolver.resolve(unnamedInput(), disabled))
    }

    @Test
    fun `unnamed input never types into a password field`() {
        val secure = screen(listOf(node(1, "android.widget.EditText", edit = true, password = true)))
        assertNull(ActionTargetResolver.resolve(unnamedInput(), secure))
    }

    @Test
    fun `the sentinel is meaningless for a tap`() {
        val single = screen(listOf(node(7, "android.widget.EditText", edit = true)))
        val tap = AniobAction.Tap(SemanticTarget.SomIndex(ActionBurst.UNNAMED_TARGET))
        assertNull(ActionTargetResolver.resolve(tap, single))
    }

    @Test
    fun `executor turns the sentinel on a tap into grounding_miss, never a coordinate`() {
        val single = screen(listOf(node(7, "android.widget.EditText", edit = true)))
        val command = AniobActionExecutor.planDispatch(AniobAction.Tap(SemanticTarget.SomIndex(ActionBurst.UNNAMED_TARGET)), single)
        assertTrue(command is DispatchCommand.NoOp)
        assertTrue(command.targetDescription.startsWith("noop:grounding_miss:"))
    }

    @Test
    fun `named input into a non-editable node is a miss`() {
        val button = screen(listOf(node(4, "android.widget.Button", text = "Save", click = true)))
        val input = AniobAction.InputText(SemanticTarget.Text("Save"), "hello")
        assertNull(ActionTargetResolver.resolve(input, button))
    }

    // ------------------------------------------------------------ resource-id semantic fallback ----

    @Test
    fun `exact resource id still wins over the label fallback`() {
        val exact = screen(listOf(
            node(1, "android.widget.Button", text = "Other", viewId = "pkg:id/confirm", click = true),
            node(2, "android.widget.Button", text = "Save", viewId = "pkg:id/legacy_save", click = true)
        ))
        val action = AniobAction.Tap(SemanticTarget.ResourceId("pkg:id/legacy_save", label = "Save"))
        val resolved = ActionTargetResolver.resolve(action, exact)
        assertNotNull(resolved)
        assertEquals(2, resolved!!.node.id)
    }

    @Test
    fun `stale resource id falls back to a unique label match`() {
        val renamed = screen(listOf(
            node(9, "android.widget.Button", text = "Save", viewId = "pkg:id/new_save", click = true)
        ))
        val action = AniobAction.Tap(SemanticTarget.ResourceId("pkg:id/old_save", label = "Save"))
        val resolved = ActionTargetResolver.resolve(action, renamed)
        assertNotNull(resolved)
        assertEquals(9, resolved!!.node.id)
    }

    @Test
    fun `stale resource id with ambiguous label matches nothing`() {
        val twice = screen(listOf(
            node(1, "android.widget.Button", text = "Save", viewId = "pkg:id/a", click = true),
            node(2, "android.widget.Button", text = "Save", viewId = "pkg:id/b", click = true)
        ))
        val action = AniobAction.Tap(SemanticTarget.ResourceId("pkg:id/gone", label = "Save"))
        assertNull(ActionTargetResolver.resolve(action, twice))
    }

    @Test
    fun `stale resource id falls back to the accessibility label`() {
        val renamed = screen(listOf(
            node(3, "android.widget.ImageButton", desc = "Send", viewId = "pkg:id/send_v2", click = true)
        ))
        val action = AniobAction.Tap(SemanticTarget.ResourceId("pkg:id/send_v1", desc = "Send"))
        val resolved = ActionTargetResolver.resolve(action, renamed)
        assertNotNull(resolved)
        assertEquals(3, resolved!!.node.id)
    }

    @Test
    fun `a contradicting expected class refuses the fallback instead of guessing`() {
        val wrongClass = screen(listOf(
            node(5, "android.widget.TextView", text = "Save", viewId = "pkg:id/x")
        ))
        val action = AniobAction.Tap(
            SemanticTarget.ResourceId("pkg:id/gone", label = "Save", expectedClass = "Button")
        )
        assertNull(ActionTargetResolver.resolve(action, wrongClass))
    }

    @Test
    fun `no label and no desc means no fallback at all`() {
        val renamed = screen(listOf(
            node(9, "android.widget.Button", text = "Save", viewId = "pkg:id/new_save", click = true)
        ))
        val action = AniobAction.Tap(SemanticTarget.ResourceId("pkg:id/old_save"))
        assertNull(ActionTargetResolver.resolve(action, renamed))
    }
}
