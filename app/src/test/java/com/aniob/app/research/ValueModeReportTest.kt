package com.aniob.app.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueModeReportTest {

    private fun run(
        mask: Int,
        success: Boolean,
        steps: Int = 4,
        seconds: Double = 2.0,
        tokens: Int = 1000,
        pass: StudyPass = StudyPass.MEASURED,
        corrected: Boolean = false
    ) = ResearchRun(
        timestampMs = 0L,
        mode = FeatureSelection(mask).mode,
        goal = "task",
        latencySeconds = seconds,
        llmCalls = 1,
        localInferences = 0,
        steps = steps,
        success = success,
        failureReason = "",
        intentShortcutUsed = false,
        rememberCacheHit = false,
        promptTokens = tokens,
        studyPass = pass,
        userCorrected = corrected,
        featureMask = mask
    )

    @Test
    fun `baseline and one row per mechanism in a fixed order`() {
        val rows = ValueModeReport.build(emptyList())
        assertEquals(6, rows.size)
        assertNull(rows[0].feature)
        assertEquals(ExecutionFeature.entries.toList(), rows.drop(1).map { it.feature })
        assertTrue(rows.all { it.runs == 0 && it.deltaVsBaseline == null })
    }

    @Test
    fun `accuracy delta and means are computed per isolated mode`() {
        val runs = listOf(
            run(0, success = true, steps = 6, seconds = 4.0, tokens = 2000),
            run(0, success = false, steps = 8, seconds = 6.0, tokens = 3000),
            run(ExecutionFeature.INTENT_SHORTCUTS.bit, success = true, steps = 1, seconds = 0.5, tokens = 0),
            run(ExecutionFeature.INTENT_SHORTCUTS.bit, success = true, steps = 3, seconds = 1.5, tokens = 400),
            // Two mechanisms on, a warm-up, and a full-speed run belong to no isolated mode.
            run(ExecutionFeature.INTENT_SHORTCUTS.bit or ExecutionFeature.REMEMBER.bit, success = false),
            run(ExecutionFeature.INTENT_SHORTCUTS.bit, success = false, pass = StudyPass.entries.first { it != StudyPass.MEASURED }),
            run(FeatureSelection.ALL_MASK, success = false),
            // A success the user overturned is not correct.
            run(ExecutionFeature.REMEMBER.bit, success = true, corrected = true)
        )
        val rows = ValueModeReport.build(runs)

        val baseline = rows[0]
        assertEquals(2, baseline.runs)
        assertEquals(1, baseline.correct)
        assertEquals(50.0, baseline.accuracyPercent, 0.001)
        assertEquals(7.0, baseline.meanSteps, 0.001)
        assertEquals(5000.0, baseline.meanLatencyMs, 0.001)
        assertEquals(2500.0, baseline.meanPromptTokens, 0.001)

        val intent = rows.first { it.feature == ExecutionFeature.INTENT_SHORTCUTS }
        assertEquals(2, intent.runs)
        assertEquals(100.0, intent.accuracyPercent, 0.001)
        assertEquals(50.0, intent.deltaVsBaseline!!, 0.001)
        assertEquals(2.0, intent.meanSteps, 0.001)
        assertEquals(1000.0, intent.meanLatencyMs, 0.001)
        assertEquals(200.0, intent.meanPromptTokens, 0.001)

        val remember = rows.first { it.feature == ExecutionFeature.REMEMBER }
        assertEquals(0, remember.correct)
        assertEquals(-50.0, remember.deltaVsBaseline!!, 0.001)

        val markdown = ValueModeReport.toMarkdown(rows)
        assertTrue(markdown, markdown.contains("| Intent shortcuts | 2/2 | 100.0% | +50.0 pp | 2.0 | 1000 | 200 |"))
        assertTrue(markdown, markdown.contains("| Learned skills | not run |"))
    }
}
