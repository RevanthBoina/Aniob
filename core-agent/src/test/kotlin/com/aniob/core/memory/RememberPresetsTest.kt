package com.aniob.core.memory

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.study.StudyBattery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seed is a head start, not a result: it records where each benchmark task starts, applies to
 * whatever screen the app opens on, and still has to resolve on the live screen to be replayed.
 */
class RememberPresetsTest {

    private fun calculatorScreen() = AniobScreenState(
        packageName = "com.google.android.calculator",
        treeHash = "calc:home",
        nodes = listOf(
            AniobNode(
                id = 1,
                className = "android.widget.TextView",
                text = "=",
                bounds = AniobRect(0, 1200, 200, 1350)
            )
        )
    )

    @Test
    fun `every study task gets a preset`() {
        assertEquals(StudyBattery.tasks.size, RememberPresets.studyBattery.size)
        assertEquals(
            StudyBattery.tasks.map { it.prompt },
            RememberPresets.studyBattery.map { it.goal }
        )
    }

    @Test
    fun `seeding writes one entry per installed task app`() {
        val cache = RememberCache()
        val written = RememberPresets.seed(cache) { true }
        assertEquals(15, written)
        assertTrue(cache.snapshot().all { it.screenHash == RememberCache.WILDCARD_SCREEN })
        assertTrue(cache.snapshot().all { it.actions.single().kind == RememberAction.OPEN_APP })
    }

    @Test
    fun `a preset whose app is not installed is skipped`() {
        val cache = RememberCache()
        // Everything absent except the calculator.
        val written = RememberPresets.seed(cache) { pkg -> pkg.contains("calculator") }
        assertEquals(3, written)
    }

    @Test
    fun `a seeded task replays its first hop on the live screen`() {
        val cache = RememberCache()
        RememberPresets.seed(cache) { true }

        val goal = StudyBattery.getTaskById("study_10_calculator_multiply_12_8")!!.prompt
        val replay = cache.lookup(goal, calculatorScreen())
        assertEquals(listOf(AniobAction.OpenApp(packageName = "com.google.android.calculator")), replay)
    }

    @Test
    fun `the wildcard does not leak across packages`() {
        val cache = RememberCache()
        RememberPresets.seed(cache) { true }

        val otherApp = AniobScreenState(packageName = "com.example.other", treeHash = "other:home")
        assertNull(cache.lookup("compute 12 times 8", otherApp))
    }

    @Test
    fun `a seeded task still replays when the numbers change`() {
        val cache = RememberCache()
        RememberPresets.seed(cache) { true }

        // Same shape, different values: the template key cannot tell 12 x 9 from 12 x 8, which is
        // exactly the point of parameterised matching.
        assertNotNull(
            cache.lookup(
                "Open Calculator and compute 12 times 9, leaving the result \"108\" on screen.",
                calculatorScreen()
            )
        )
        // An operation the battery never seeded is a different task, and must miss.
        assertNull(
            cache.lookup(
                "Open Calculator and compute 12 divided by 3, leaving the result \"4\" on screen.",
                calculatorScreen()
            )
        )
    }
}
