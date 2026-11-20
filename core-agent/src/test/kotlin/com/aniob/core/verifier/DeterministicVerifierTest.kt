package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.UiInteractionEvent
import com.aniob.core.verifier.DeterministicVerifier.FailureKind
import com.aniob.core.verifier.DeterministicVerifier.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicVerifierTest {

    private fun screen(nodeCount: Int, treeHash: String, pkg: String = "com.example") =
        AniobScreenState(
            packageName = pkg,
            activityName = "MainActivity",
            nodes = List(nodeCount) { i ->
                AniobNode(id = i, className = "TextView", text = "Item $i", bounds = AniobRect(0, i * 50, 200, i * 50 + 40), isClickable = true)
            },
            treeHash = treeHash
        )

    private val launcher = "com.sec.android.app.launcher"
    private val wrapper = AniobNode(1, "android.widget.FrameLayout",
        viewId = "$launcher:id/app_search_edit_text_wrapper", bounds = AniobRect(0, 0, 300, 60),
        isClickable = true)
    private val field = AniobNode(2, "android.widget.EditText", viewId = "$launcher:id/app_search_edit_text",
        bounds = AniobRect(10, 10, 290, 50), isEditable = true)
    private val searchTap = AniobAction.Tap(SemanticTarget.ResourceId(wrapper.viewId))

    private fun launcherScreen(vararg nodes: AniobNode, ime: Boolean = false) = AniobScreenState(
        packageName = launcher, nodes = nodes.toList(), screenHash = "launcher-structure",
        treeHash = "launcher-tree", hasImeWindow = ime, windowEventSeq = 10, interactionStartUptimeMs = 500)

    @Test fun `launcher wrapper is verified by new editable focus even if SoM IDs shift`() {
        val before = launcherScreen(wrapper, field)
        val after = launcherScreen(wrapper.copy(id = 4), field.copy(id = 1, isFocused = true))
        val result = DeterministicVerifier.verify(searchTap, before, after)
        assertEquals(Status.VERIFIED, result.status)
        assertTrue(result.isExpected)
    }

    @Test fun `keyboard opening and focused child verify the launcher tap without text or hash change`() {
        val before = launcherScreen(wrapper, field)
        val after = launcherScreen(wrapper, field.copy(isFocused = true), ime = true)
        assertEquals(Status.VERIFIED, DeterministicVerifier.verify(searchTap, before, after).status)
    }

    @Test fun `IME opening is evidence for an input target when focus is not exposed`() {
        val before = launcherScreen(wrapper, field)
        val after = launcherScreen(wrapper, field, ime = true)
        assertEquals(Status.VERIFIED, DeterministicVerifier.verify(searchTap, before, after).status)
    }

    @Test fun `accepted tap on focused input with IME already visible verifies action but not Finish`() {
        val before = launcherScreen(wrapper, field.copy(isFocused = true), ime = true)
        assertEquals(Status.VERIFIED, DeterministicVerifier.verify(searchTap, before, before).status)
        assertEquals(Status.FAILED, DeterministicVerifier.verifyFinish(
            com.aniob.core.domain.SuccessCriteria(mustContainText = listOf("Search result")), before, 1).status)
    }

    @Test fun `accepted tap with the target still present and no evidence is unconfirmed`() {
        val before = launcherScreen(wrapper, field)
        val result = DeterministicVerifier.verify(searchTap, before, before)
        assertEquals(Status.UNCONFIRMED, result.status)
        assertFalse(result.isExpected)
        assertFalse(result.isSuccessful)
    }

    @Test fun `unrelated focus and IME do not verify a tap`() {
        val otherField = field.copy(id = 3, viewId = "other", bounds = AniobRect(0, 100, 300, 160))
        val before = launcherScreen(wrapper, field, otherField)
        val after = launcherScreen(wrapper, field, otherField.copy(isFocused = true), ime = true)
        assertEquals(Status.UNCONFIRMED, DeterministicVerifier.verify(searchTap, before, after).status)
    }

    @Test fun `rejected dispatch fails despite apparent interaction evidence`() {
        val before = launcherScreen(wrapper, field)
        val after = launcherScreen(wrapper, field.copy(isFocused = true), ime = true)
        val result = DeterministicVerifier.verify(searchTap, before, after, dispatched = false)
        assertEquals(Status.FAILED, result.status)
        assertEquals(FailureKind.DISPATCH_REJECTED, result.failureKind)
    }

    @Test fun `target missing from the fresh pre-dispatch screen fails even with new focus`() {
        val before = launcherScreen(field)
        val after = launcherScreen(wrapper, field.copy(isFocused = true), ime = true)
        val result = DeterministicVerifier.verify(searchTap, before, after)
        assertEquals(Status.FAILED, result.status)
        assertEquals(FailureKind.TARGET_MISSING, result.failureKind)
    }

    @Test fun `disabled and non-actionable targets fail without being treated as no-op taps`() {
        for (invalid in listOf(wrapper.copy(isEnabled = false), wrapper.copy(isClickable = false))) {
            val before = launcherScreen(invalid, field)
            val result = DeterministicVerifier.verify(searchTap, before, launcherScreen(wrapper, field))
            assertEquals(Status.FAILED, result.status)
            assertEquals(FailureKind.TARGET_UNAVAILABLE, result.failureKind)
        }
    }

    @Test fun `target checked or selected change verifies an ordinary button tap`() {
        val button = screen(1, "same").nodes.single()
        val before = AniobScreenState("com.example.settings", nodes = listOf(button), treeHash = "same")
        val after = before.copy(nodes = listOf(button.copy(id = 3, isSelected = true)))
        assertEquals(Status.VERIFIED,
            DeterministicVerifier.verify(AniobAction.Tap(SemanticTarget.SomIndex(button.id)), before, after).status)
    }

    @Test fun `fresh targeted events count but stale and unrelated events do not`() {
        val before = launcherScreen(wrapper, field)
        for (kind in UiInteractionEvent.Kind.entries) {
            val source = if (kind == UiInteractionEvent.Kind.CLICKED) wrapper else field
            val event = UiInteractionEvent(kind, 11, 501, launcher, source.className, source.viewId, source.bounds)
            fun verdict(e: UiInteractionEvent) = DeterministicVerifier.verify(searchTap, before,
                before.copy(interactionEvents = listOf(e))).status
            assertEquals(Status.VERIFIED, verdict(event))
            assertEquals(Status.UNCONFIRMED, verdict(event.copy(sequence = 10)))
            assertEquals(Status.UNCONFIRMED, verdict(event.copy(uptimeMs = 499)))
            assertEquals(Status.UNCONFIRMED, verdict(event.copy(packageName = "com.other")))
            assertEquals(Status.UNCONFIRMED, verdict(event.copy(bounds = AniobRect(0, 100, 300, 160))))
        }
    }

    @Test
    fun `tap with changed treeHash reports isExpected true`() {
        val before = screen(nodeCount = 2, treeHash = "abc123")
        val after = screen(nodeCount = 2, treeHash = "def456")

        val result = DeterministicVerifier.verify(AniobAction.Tap(SemanticTarget.SomIndex(1)), before, after)

        assertTrue(result.isExpected)
        assertTrue(result.reason.contains("transitioned"))
    }

    @Test
    fun `long press and wait also report no-effect when treeHash unchanged`() {
        val before = screen(nodeCount = 1, treeHash = "same")
        val after = screen(nodeCount = 1, treeHash = "same")

        val press = DeterministicVerifier.verify(AniobAction.LongPress(SemanticTarget.SomIndex(0), durationMs = 800), before, after)
        assertFalse(press.isExpected)
        assertTrue(press.reason.contains("No-effect"))

        val wait = DeterministicVerifier.verify(AniobAction.Wait(durationMs = 500), before, after)
        assertFalse(wait.isExpected)
        assertTrue(wait.reason.contains("No-effect"))
    }

    @Test
    fun `open app transitions to target package is expected even with same hash`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val afterSameHash = screen(nodeCount = 1, treeHash = "x", pkg = "com.android.settings")

        val result = DeterministicVerifier.verify(
            AniobAction.OpenApp(packageName = "com.android.settings"),
            before,
            afterSameHash
        )

        assertTrue("Package change counts as an expected transition", result.isExpected)
    }

    @Test
    fun `wrong app open is not expected`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val wrongAfter = screen(nodeCount = 1, treeHash = "y", pkg = "com.wrong.app")

        val result = DeterministicVerifier.verify(
            AniobAction.OpenApp(packageName = "com.android.settings"),
            before,
            wrongAfter
        )

        assertFalse("Wrong package open must fail verification", result.isExpected)
    }

    @Test
    fun `unrelated text field update is not expected`() {
        val targetNode = AniobNode(id = 1, className = "EditText", text = "Old Text", isEditable = true)
        val otherNode = AniobNode(id = 2, className = "EditText", text = "Hello", isEditable = true)
        val before = AniobScreenState(packageName = "com.app", treeHash = "h1", nodes = listOf(targetNode, otherNode))
        val after = AniobScreenState(packageName = "com.app", treeHash = "h2", nodes = listOf(targetNode, otherNode.copy(text = "Hello World")))

        val result = DeterministicVerifier.verify(
            AniobAction.InputText(target = SemanticTarget.SomIndex(1), text = "Target Input"),
            before,
            after
        )

        assertFalse("Input text not appearing in target node must fail", result.isExpected)
    }

    @Test
    fun `min-step-only finish is not expected without observable evidence`() {
        val criteria = com.aniob.core.domain.SuccessCriteria(minSteps = 2)
        val screen = screen(nodeCount = 2, treeHash = "abc")

        val result = DeterministicVerifier.verifyFinish(criteria, screen, steps = 3)

        assertFalse("minSteps alone must never count as sufficient observable evidence", result.isExpected)
    }

    @Test
    fun `finish with observable criteria satisfied is expected and captures textEvidence`() {
        val criteria = com.aniob.core.domain.SuccessCriteria(
            mustContainText = listOf("Item 0"),
            mustShowPackage = listOf("com.example"),
            minSteps = 1
        )
        val screen = screen(nodeCount = 2, treeHash = "abc", pkg = "com.example")

        val result = DeterministicVerifier.verifyFinish(criteria, screen, steps = 2)

        assertTrue("Observable evidence satisfied must verify", result.isExpected)
        assertNotNull("textEvidence must be populated", result.textEvidence)
        assertTrue("textEvidence must contain matched item", result.textEvidence!!.contains("Item 0"))
        assertTrue("textEvidence must contain matched package", result.textEvidence!!.contains("com.example"))
    }

    @Test
    fun `open app and input text populate textEvidence`() {
        val before = screen(nodeCount = 1, treeHash = "x", pkg = "com.launcher")
        val after = screen(nodeCount = 1, treeHash = "y", pkg = "com.android.settings")

        val openAppRes = DeterministicVerifier.verify(AniobAction.OpenApp("com.android.settings"), before, after)
        assertTrue(openAppRes.isExpected)
        assertEquals("com.android.settings", openAppRes.textEvidence)

        val target = AniobNode(id = 1, className = "EditText", text = "hello world", isEditable = true)
        val screenWithText = AniobScreenState(packageName = "com.android.settings", nodes = listOf(target))
        val inputRes = DeterministicVerifier.verify(
            AniobAction.InputText(SemanticTarget.SomIndex(1), text = "hello"),
            before,
            screenWithText
        )
        assertTrue(inputRes.isExpected)
        assertEquals("hello", inputRes.textEvidence)
    }

    @Test
    fun `input text verifies when som index shifts due to newly inserted sibling`() {
        val searchBoxBefore = AniobNode(
            id = 1,
            className = "android.widget.EditText",
            text = "",
            viewId = "com.app:id/search_input",
            bounds = AniobRect(20, 100, 800, 180),
            isEditable = true
        )
        val before = AniobScreenState(packageName = "com.app", treeHash = "h1", nodes = listOf(searchBoxBefore))

        // After typing, a banner appears at id=1 and the search EditText shifts to id=2
        val bannerNode = AniobNode(id = 1, className = "android.widget.TextView", text = "Suggestions", bounds = AniobRect(0, 0, 800, 80))
        val searchBoxAfter = searchBoxBefore.copy(id = 2, text = "Wi-Fi settings")
        val after = AniobScreenState(packageName = "com.app", treeHash = "h2", nodes = listOf(bannerNode, searchBoxAfter))

        val res = DeterministicVerifier.verify(
            AniobAction.InputText(SemanticTarget.SomIndex(1), text = "Wi-Fi"),
            before,
            after
        )
        assertTrue("Shifted editable node with matching viewId/bounds must verify", res.isExpected)
    }
}