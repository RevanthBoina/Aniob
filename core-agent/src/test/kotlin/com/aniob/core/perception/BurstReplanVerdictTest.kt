package com.aniob.core.perception

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A target that vanished mid-burst is a reason to re-plan, not to fail the task. */
class BurstReplanVerdictTest {

    private fun screen(vararg nodes: AniobNode, pkg: String = "com.google.android.documentsui") =
        AniobScreenState(packageName = pkg, nodes = nodes.toList())

    private val resultsRow = AniobNode(id = 5, className = "android.widget.TextView", text = "resume.pdf", isClickable = true)
    private val searchButton = AniobNode(id = 2, className = "android.widget.ImageButton", contentDescription = "Search", isClickable = true)

    @Test
    fun `a hidden target asks for a replan`() {
        val origin = screen(searchButton, resultsRow)
        val afterSearchTap = screen(searchButton)
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(5)), origin, afterSearchTap)
        assertFalse(verdict.allowed)
        assertTrue(verdict.shouldReplan)
    }

    @Test
    fun `a screen that moved to another app asks for a replan`() {
        val origin = screen(searchButton, resultsRow)
        val elsewhere = screen(searchButton, pkg = "com.android.launcher3")
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(5)), origin, elsewhere)
        assertTrue(verdict.shouldReplan)
    }

    @Test
    fun `an allowed action never asks for a replan`() {
        val origin = screen(searchButton, resultsRow)
        val verdict = BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.SomIndex(5)), origin, origin)
        assertTrue(verdict.allowed)
        assertFalse(verdict.shouldReplan)
    }
}
