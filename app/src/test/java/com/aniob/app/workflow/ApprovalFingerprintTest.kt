package com.aniob.app.workflow

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalFingerprintTest {

    @Test
    fun changedTypedPayloadChangesOpaqueFingerprintWithoutReturningPlaintext() {
        val target = SemanticTarget.ResourceId("com.example:id/otp")
        val first = ApprovalFingerprint.payload(AniobAction.InputText(target, "123456"), node())
        val changed = ApprovalFingerprint.payload(AniobAction.InputText(target, "654321"), node())

        assertNotEquals(first, changed)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.contains("123456"))
        assertFalse(changed.contains("654321"))
    }

    @Test
    fun groundedDestinationIdentityDoesNotUseTheRecycledNodeIndex() {
        val action = AniobAction.Tap(SemanticTarget.SomIndex(1))
        val firstNode = node(viewId = "com.example:id/send", bounds = AniobRect(0, 0, 100, 50))
        val reindexedNode = firstNode.copy(id = 99)
        val movedNode = firstNode.copy(bounds = AniobRect(0, 80, 100, 130))

        assertEquals(
            "Accessibility indices are recycled and must not change approval identity",
            ApprovalFingerprint.target(action, firstNode),
            ApprovalFingerprint.target(action, reindexedNode)
        )
        assertNotEquals(
            "A different grounded target location must receive a different identity",
            ApprovalFingerprint.target(action, firstNode),
            ApprovalFingerprint.target(action, movedNode)
        )
        assertTrue(ApprovalFingerprint.target(action, firstNode).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun sensitiveTargetsAreNamedWithoutShowingTheirLabelOrContents() {
        val action = AniobAction.InputText(SemanticTarget.ResourceId("com.example:id/otp"), "123456")
        val node = node(
            text = "123456",
            contentDescription = "One-time password",
            viewId = "com.example:id/otp",
            isPassword = true
        )

        assertEquals("Protected field", ApprovalFingerprint.displayTarget(action, node, sensitive = true))
    }

    private fun node(
        text: String = "",
        contentDescription: String = "",
        viewId: String = "",
        bounds: AniobRect = AniobRect(0, 0, 100, 50),
        isPassword: Boolean = false
    ) = AniobNode(
        id = 1,
        className = "android.widget.EditText",
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        bounds = bounds,
        isEditable = true,
        isPassword = isPassword
    )
}
