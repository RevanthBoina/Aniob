package com.aniob.core

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.ExecutorRequest
import com.aniob.core.domain.AniobRect
import com.aniob.core.perception.ObserveCapturePolicy
import com.aniob.core.perception.ObserveDispatchPolicy
import com.aniob.core.perception.ObserveSnapshot
import com.aniob.core.perception.ObserveTreeCompactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact observe pipeline: pruning, label merging, sequential ids, markup and hashing.
 * Pure JVM, so every rule of the fast ablation conditions is pinned here.
 */
class ObservePipelineTest {

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private fun text(
        value: String,
        top: Int,
        left: Int = 0,
        index: Int = -1
    ) = ObserveSnapshot(
        className = "android.widget.TextView",
        text = value,
        bounds = AniobRect(left, top, left + 200, top + 40),
        platformIndex = index
    )

    private fun clickable(
        label: String = "",
        top: Int = 0,
        left: Int = 0,
        viewId: String = "",
        index: Int = -1,
        children: List<ObserveSnapshot> = emptyList()
    ) = ObserveSnapshot(
        className = "android.widget.LinearLayout",
        viewId = viewId,
        text = label,
        bounds = AniobRect(left, top, left + 400, top + 80),
        isClickable = true,
        platformIndex = index,
        children = children
    )

    private fun editable(label: String, top: Int = 0) = ObserveSnapshot(
        className = "android.widget.EditText",
        text = label,
        bounds = AniobRect(0, top, 400, top + 80),
        isEditable = true
    )

    private fun container(children: List<ObserveSnapshot>) = ObserveSnapshot(
        className = "android.widget.FrameLayout",
        bounds = AniobRect(0, 0, 1080, 2400),
        children = children
    )

    // ---------------------------------------------------------------------------------------------
    // Pruning
    // ---------------------------------------------------------------------------------------------

    @Test
    fun invisibleNodeAndItsSubtreeArePruned() {
        val tree = container(
            listOf(
                ObserveSnapshot(
                    className = "android.widget.FrameLayout",
                    bounds = AniobRect(0, 0, 1080, 800),
                    isVisible = false,
                    children = listOf(clickable(label = "Hidden button", top = 10))
                ),
                clickable(label = "Visible button", top = 900)
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals(1, observation.nodes.size)
        assertEquals("Visible button", observation.nodes.single().label)
    }

    @Test
    fun decorativeWrapperIsDroppedButItsChildrenSurvive() {
        val tree = container(
            listOf(
                ObserveSnapshot(
                    className = "android.widget.LinearLayout",
                    bounds = AniobRect(0, 0, 1080, 400),
                    children = listOf(
                        clickable(label = "First", top = 20),
                        ObserveSnapshot(
                            className = "androidx.constraintlayout.widget.ConstraintLayout",
                            bounds = AniobRect(0, 100, 1080, 300),
                            children = listOf(clickable(label = "Second", top = 150))
                        )
                    )
                )
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals(listOf("First", "Second"), observation.nodes.map { it.label })
        // ... and the empty containers themselves never became prompt rows.
        assertTrue(observation.nodes.none { it.role == "text" })
    }

    // ---------------------------------------------------------------------------------------------
    // Merging
    // ---------------------------------------------------------------------------------------------

    @Test
    fun interactiveParentAbsorbsLabeledChildren() {
        val row = clickable(
            top = 100,
            children = listOf(
                text("Settings", top = 110, index = 1),
                text("Wi-Fi, Bluetooth", top = 140, index = 2)
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(container(listOf(row))), "com.example")

        assertEquals(1, observation.nodes.size)
        assertEquals("Settings | Wi-Fi, Bluetooth", observation.nodes.single().label)
        assertEquals(2, observation.mergedNodeCount)
        // The absorbed children must never appear as their own ids.
        assertEquals(1, observation.nodes.single().id)
    }

    @Test
    fun interactiveChildIsNeverAbsorbed() {
        val row = clickable(
            top = 100,
            children = listOf(
                text("Account", top = 110),
                clickable(label = "Sign out", top = 140, viewId = "com.example:id/sign_out")
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(container(listOf(row))), "com.example")

        assertEquals(2, observation.nodes.size)
        assertEquals(listOf("Account", "Sign out"), observation.nodes.map { it.label })
        assertEquals(listOf(1, 2), observation.nodes.map { it.id })
    }

    @Test
    fun idsFollowReadingOrderAndStaySequential() {
        val tree = container(
            listOf(
                clickable(label = "Bottom", top = 800),
                clickable(label = "Right", top = 100, left = 600),
                clickable(label = "Left", top = 100, left = 0)
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals(listOf("Left", "Right", "Bottom"), observation.nodes.map { it.label })
        assertEquals(listOf(1, 2, 3), observation.nodes.map { it.id })
    }

    // ---------------------------------------------------------------------------------------------
    // Markup
    // ---------------------------------------------------------------------------------------------

    @Test
    fun markupUsesCompactHtmlForm() {
        val tree = container(
            listOf(
                clickable(label = "Save", top = 0, viewId = "com.example:id/save"),
                editable("Search", top = 100)
            )
        )

        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals("<button id=1 label='Save'>", observation.nodes[0].markup)
        assertEquals("<input id=2 text='Search'>", observation.nodes[1].markup)
    }

    @Test
    fun markupEscapesQuotesAndAngleBrackets() {
        val tree = container(listOf(clickable(label = "Bob's <b>bold</b>", top = 0)))
        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals("<button id=1 label='Bob&#39;s &lt;b&gt;bold&lt;/b&gt;'>", observation.nodes.single().markup)
    }

    @Test
    fun promptUsesMarkupAndRestampsTheCurrentId() {
        val node = AniobNode(
            id = 7,
            className = "android.widget.Button",
            bounds = AniobRect(0, 0, 100, 50),
            markup = "<button id=1 label='Open'>"
        )
        assertTrue(node.toOptimizedTokenString().startsWith("<button id=7"))
    }

    @Test
    fun compactPromptTargetsTheMarkupId() {
        val node = AniobNode(
            id = 1,
            className = "android.widget.Button",
            text = "Save",
            isClickable = true,
            bounds = AniobRect(0, 0, 100, 50),
            markup = "<button id=1 label='Save'>"
        )
        val prompt = ExecutorRequest(
            goal = "save",
            packageName = "com.example",
            observationId = "hash",
            actionableNodes = listOf(node)
        ).toPromptString()

        assertTrue(prompt.contains("<button id=1 label='Save'>"))
        assertTrue(prompt.contains("using the id= value"))
    }

    @Test
    fun legacyPromptKeepsTheBracketTargeting() {
        val node = AniobNode(
            id = 1,
            className = "android.widget.Button",
            text = "Save",
            isClickable = true,
            bounds = AniobRect(0, 0, 100, 50)
        )
        val prompt = ExecutorRequest(
            goal = "save",
            packageName = "com.example",
            observationId = "hash",
            actionableNodes = listOf(node)
        ).toPromptString()

        assertTrue(prompt.contains("[1] android.widget.Button"))
        assertTrue(prompt.contains("using the index [X]"))
    }

    // ---------------------------------------------------------------------------------------------
    // Structural hash
    // ---------------------------------------------------------------------------------------------

    @Test
    fun screenHashFollowsTheDocumentedFormula() {
        val tree = container(listOf(clickable(label = "Save", top = 0, viewId = "com.example:id/save")))
        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example", "MainActivity")

        val expectedSignature = "android.widget.LinearLayout#com.example:id/save"
        assertEquals(expectedSignature, observation.structuralSignature)
        assertEquals(
            ObserveTreeCompactor.screenHash("com.example", "MainActivity", expectedSignature),
            observation.screenHash
        )
    }

    @Test
    fun screenHashIgnoresTextButTracksStructure() {
        val first = ObserveTreeCompactor.compact(
            listOf(container(listOf(clickable(label = "Save", top = 0, viewId = "com.example:id/save")))),
            "com.example"
        )
        val sameStructure = ObserveTreeCompactor.compact(
            listOf(container(listOf(clickable(label = "Saved", top = 0, viewId = "com.example:id/save")))),
            "com.example"
        )
        val differentStructure = ObserveTreeCompactor.compact(
            listOf(container(listOf(clickable(label = "Save", top = 0, viewId = "com.example:id/other")))),
            "com.example"
        )

        assertEquals(first.screenHash, sameStructure.screenHash)
        assertNotEquals(first.screenHash, differentStructure.screenHash)
    }

    // ---------------------------------------------------------------------------------------------
    // Capture policy
    // ---------------------------------------------------------------------------------------------

    @Test
    fun screenshotPolicyTable() {
        // Baseline pays for pixels on every step.
        assertTrue(ObserveCapturePolicy.shouldCaptureScreenshot(fastObservation = false, labeledInteractiveNodes = 0, previousStepFailed = false))
        assertTrue(ObserveCapturePolicy.shouldCaptureScreenshot(fastObservation = false, labeledInteractiveNodes = 40, previousStepFailed = false))

        // Compact conditions skip it only when the tree is dense AND the last verify passed.
        assertTrue(ObserveCapturePolicy.shouldCaptureScreenshot(fastObservation = true, labeledInteractiveNodes = 2, previousStepFailed = false))
        assertFalse(ObserveCapturePolicy.shouldCaptureScreenshot(fastObservation = true, labeledInteractiveNodes = 3, previousStepFailed = false))
        assertTrue(ObserveCapturePolicy.shouldCaptureScreenshot(fastObservation = true, labeledInteractiveNodes = 3, previousStepFailed = true))

        // A visual goal is always allowed to ask for the bitmap.
        assertTrue(
            ObserveCapturePolicy.shouldCaptureScreenshot(
                fastObservation = true,
                labeledInteractiveNodes = 10,
                previousStepFailed = false,
                visionRequested = true
            )
        )
    }

    @Test
    fun labeledInteractiveCountOnlyCountsNamedTargets() {
        val nodes = listOf(
            AniobNode(id = 1, className = "Button", text = "Save", isClickable = true),
            AniobNode(id = 2, className = "ImageButton", contentDescription = "Close", isClickable = true),
            AniobNode(id = 3, className = "View", isClickable = true),
            AniobNode(id = 4, className = "TextView", text = "Caption")
        )
        assertEquals(2, ObserveCapturePolicy.labeledInteractiveCount(nodes))
    }

    @Test
    fun nodeActionIsPreferedOnlyForALiveUsableHandle() {
        assertTrue(ObserveDispatchPolicy.preferNodeAction(nodeId = 3, capable = true, enabled = true))
        // No compact handle (baseline capture) -> gesture.
        assertFalse(ObserveDispatchPolicy.preferNodeAction(nodeId = null, capable = true, enabled = true))
        // A label that is not itself clickable -> gesture at its centre.
        assertFalse(ObserveDispatchPolicy.preferNodeAction(nodeId = 3, capable = false, enabled = true))
        // A disabled control -> gesture, so the attempt fails visibly instead of silently.
        assertFalse(ObserveDispatchPolicy.preferNodeAction(nodeId = 3, capable = true, enabled = false))
    }

    // ---------------------------------------------------------------------------------------------
    // Ceiling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun compactObservationNeverExceedsSixtyNodes() {
        val tree = container((0 until 90).map { i -> clickable(label = "Button $i", top = i * 20) })
        val observation = ObserveTreeCompactor.compact(listOf(tree), "com.example")
        assertEquals(ObserveTreeCompactor.MAX_ACTIONABLE_NODES, observation.nodes.size)
        assertEquals(1, observation.nodes.first().id)
        assertEquals(60, observation.nodes.last().id)
    }
}
