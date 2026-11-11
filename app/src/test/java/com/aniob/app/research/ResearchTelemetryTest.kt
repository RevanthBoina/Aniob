package com.aniob.app.research

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the research metrics: CSV fidelity, per-mode running averages, the HUD line format and
 * the manual override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ResearchTelemetryTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    // ---------------------------------------------------------------------------------------------
    // Pure: CSV + summary
    // ---------------------------------------------------------------------------------------------

    @Test
    fun csvRoundTripPreservesCommasQuotesAndNewlines() {
        val run = ResearchRun(
            timestampMs = 1_700_000_000_000,
            mode = AblationMode.BURST_AND_FAST_OBSERVE,
            goal = "Send \"hello, Ana\"\nto the group",
            latencySeconds = 12.345, // CSV keeps millisecond precision
            llmCalls = 4,
            localInferences = 2,
            steps = 7,
            success = true,
            failureReason = "",
            intentShortcutUsed = true,
            rememberCacheHit = false,
            manuallyOverridden = false,
            screenshots = 3,
            promptTokens = 450,
            studyPass = StudyPass.MEASURED
        )
        val parsed = ResearchRun.fromCsvRow(run.toCsvRow())
        requireNotNull(parsed)
        // The newline is collapsed so one task stays one physical row; everything else is exact.
        assertEquals("Send \"hello, Ana\" to the group", parsed.goal)
        assertEquals(run.copy(goal = parsed.goal), parsed)
    }

    @Test fun ablationModesDoNotLeakLearnedSkillsIntoControlConditions() {
        for (mode in AblationMode.entries) {
            assertEquals(mode != AblationMode.BASELINE, mode.skillsEnabled)
        }
    }

    @Test fun detachedClosureDoesNotCloseTheNextTaskAndAcceptsImmediateCorrection() {
        val manager = ResearchTelemetryManager(context, clock = { 100L }, logger = {})
        manager.clearAll()
        manager.startTask("first", runId = "first")
        manager.prepareBackgroundClosure("first")
        manager.startTask("second", runId = "second")
        assertNull(manager.endTaskIfActive("interrupted", runId = "first"))
        manager.markUserCorrected("first")
        val first = requireNotNull(manager.endTask(ResearchOutcome.UNVERIFIED, runId = "first"))
        assertTrue(manager.isTaskActive)
        assertTrue(first.userCorrected)
        assertFalse(first.success)
        assertEquals("second", manager.endTask(ResearchOutcome.STOPPED, runId = "second")?.runId)
    }

    @Test fun categorizedRowsAndAllFourModesRemainIndependent() {
        val runs = AblationMode.entries.map { mode ->
            ResearchRun(1, mode, "goal", 2.0, 0, 0, 1, false, "", false, false,
                runId = mode.id, outcomeCategory = ResearchOutcome.SUCCESS,
                originalOutcomeCategory = ResearchOutcome.UNVERIFIED, userCorrected = true, messageDelayMs = 40)
        }
        val rows = ResearchSummaryTable.build(runs)
        assertEquals(AblationMode.entries.toList(), rows.map { it.mode })
        rows.forEach {
            assertEquals(0, it.successes)
            assertEquals(2.0, it.medianLatencySeconds, 0.001)
            assertEquals(100.0, it.unverifiedRatePercent, 0.001)
            assertEquals(40.0, it.meanMessageDelayMs, 0.001)
        }
        runs.forEach { assertEquals(it, ResearchRun.fromCsvRow(it.toCsvRow())) }
    }

    @Test fun correctionTargetsRunIdAndPreservesMachineVerdict() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})
        manager.clearAll()
        manager.startTask("first", runId = "first")
        manager.recordTreeRead()
        manager.recordBitmapCapture()
        manager.recordActionCompleted()
        now = 75
        manager.recordCompletionMessageShown()
        manager.endTask(ResearchOutcome.UNVERIFIED)
        manager.startTask("second", runId = "second")
        manager.endTask(ResearchOutcome.FAILED)
        val corrected = requireNotNull(manager.markUserCorrected("first"))
        assertFalse(corrected.success)
        assertTrue(corrected.userCorrected)
        assertEquals(ResearchOutcome.UNVERIFIED, corrected.originalOutcomeCategory)
        assertEquals(ResearchOutcome.SUCCESS, corrected.outcomeCategory)
        assertEquals(75L, corrected.messageDelayMs)
        assertEquals(1, corrected.treeReads)
        assertEquals(1, corrected.bitmaps)
        assertEquals(1, corrected.screenshots)
        assertEquals(ResearchOutcome.FAILED, manager.allRuns().last().outcomeCategory)
    }

    @Test fun extendedMeasurementsRoundTripWithoutConflatingPlansMembersAndBitmaps() {
        val run = ResearchRun(1L, AblationMode.FULL_HUMAN_SPEED, "goal", 1.0, 2, 0, 3, true, "", false, true,
            treeReads = 4, bitmaps = 1, burstsPlanned = 1, burstMembersRun = 3, burstsSuspended = true,
            shortcutAttempted = true, shortcutDegraded = true, shortcutFailReason = "not landed",
            skillId = "skill", rememberReplayActions = 2, fastpathSteps = 1,
            replayValidated = true, replayDiverged = true, setupLatencyMs = 125L)
        assertEquals(run, ResearchRun.fromCsvRow(run.toCsvRow()))
        val cells = CsvCodec.split(run.toCsvRow()).toMutableList()
        cells[15] = "not-a-measurement"
        assertNull(ResearchRun.fromCsvRow(cells.joinToString(",")))
    }

    @Test
    fun legacyTwelveColumnRowsParseWithDefaults() {
        val legacyRow = "1700000000000,BASELINE,Open Settings,10.500,2,0,3,true,,false,false,false"
        val parsed = ResearchRun.fromCsvRow(legacyRow)
        requireNotNull(parsed)
        assertEquals(1700000000000L, parsed.timestampMs)
        assertEquals(AblationMode.BASELINE, parsed.mode)
        assertEquals("Open Settings", parsed.goal)
        assertEquals(10.5, parsed.latencySeconds, 0.0001)
        assertEquals(2, parsed.llmCalls)
        assertEquals(0, parsed.localInferences)
        assertEquals(3, parsed.steps)
        assertTrue(parsed.success)
        assertEquals("", parsed.failureReason)
        assertFalse(parsed.intentShortcutUsed)
        assertFalse(parsed.rememberCacheHit)
        assertFalse(parsed.manuallyOverridden)
        // Defaults for legacy rows:
        assertEquals(0, parsed.screenshots)
        assertEquals(0, parsed.promptTokens)
        assertEquals(StudyPass.MEASURED, parsed.studyPass)
    }

    @Test
    fun headerAndMalformedRowsAreIgnored() {
        assertNull(ResearchRun.fromCsvRow(ResearchRun.HEADER))
        assertNull(ResearchRun.fromCsvRow(""))
        assertNull(ResearchRun.fromCsvRow("1000,BASELINE,only,three"))
        assertNull(ResearchRun.fromCsvRow("not-a-number,BASELINE,goal,1.000,0,0,1,true,,false,false,false,0,0,MEASURED"))
    }

    @Test
    fun summaryExcludesWarmupRunsAndComputesPaperMetrics() {
        val runs = listOf(
            // Baseline measured runs: mean latency = 15.0, sample std = 7.071
            run(mode = AblationMode.BASELINE, latency = 10.0, calls = 2, steps = 2, screenshots = 2, tokens = 400, success = true, pass = StudyPass.MEASURED),
            run(mode = AblationMode.BASELINE, latency = 20.0, calls = 4, steps = 4, screenshots = 4, tokens = 800, success = false, pass = StudyPass.MEASURED),
            // Warm-up run for FULL_HUMAN_SPEED (must be excluded from summary)
            run(mode = AblationMode.FULL_HUMAN_SPEED, latency = 12.0, calls = 3, steps = 5, screenshots = 5, tokens = 600, success = true, pass = StudyPass.WARMUP),
            // Measured run for FULL_HUMAN_SPEED: mean latency = 3.0
            run(mode = AblationMode.FULL_HUMAN_SPEED, latency = 3.0, calls = 0, steps = 3, screenshots = 1, tokens = 0, success = true, pass = StudyPass.MEASURED)
        )
        val rows = ResearchSummaryTable.build(runs)
        assertEquals(
            listOf(AblationMode.BASELINE, AblationMode.FULL_HUMAN_SPEED),
            rows.map { it.mode }
        )

        val baseline = rows.first()
        assertEquals(2, baseline.runs)
        assertEquals(1, baseline.successes)
        assertEquals(50.0, baseline.successRatePercent, 0.0001)
        assertEquals(15.0, baseline.meanLatencySeconds, 0.0001)
        // Sample std-dev: sqrt(((10 - 15)^2 + (20 - 15)^2) / (2 - 1)) = sqrt(50) = 7.0710678
        assertEquals(Math.sqrt(50.0), baseline.stdLatencySeconds, 0.0001)
        // Mean latency per step: (10.0 / 2 + 20.0 / 4) / 2 = (5.0 + 5.0) / 2 = 5.0
        assertEquals(5.0, baseline.meanLatencyPerStepSeconds, 0.0001)
        assertEquals(3.0, baseline.meanLlmCallsPerTask, 0.0001)
        assertEquals(3.0, baseline.meanUiStepsPerTask, 0.0001)
        assertEquals(3.0, baseline.meanScreenshotsPerTask, 0.0001)
        // Tokens per call: (400 + 800) / (2 + 4) = 1200 / 6 = 200.0
        assertEquals(200.0, baseline.meanPromptTokensPerCall, 0.0001)
        assertEquals("1.00x", baseline.speedupVsBaseline)

        val full = rows.last()
        assertEquals(1, full.runs)
        assertEquals(1, full.successes)
        assertEquals(100.0, full.successRatePercent, 0.0001)
        assertEquals(3.0, full.meanLatencySeconds, 0.0001)
        assertEquals(0.0, full.stdLatencySeconds, 0.0001) // n <= 1 -> 0.00
        assertEquals(1.0, full.meanLatencyPerStepSeconds, 0.0001) // 3.0 / 3
        assertEquals(0.0, full.meanLlmCallsPerTask, 0.0001)
        assertEquals(3.0, full.meanUiStepsPerTask, 0.0001)
        assertEquals(1.0, full.meanScreenshotsPerTask, 0.0001)
        assertEquals(0.0, full.meanPromptTokensPerCall, 0.0001) // 0 calls -> 0.00
        // Speedup vs baseline: baseline mean (15.0) / this mode mean (3.0) = 5.00x
        assertEquals("5.00x", full.speedupVsBaseline)
    }

    @Test
    fun speedupWithoutBaselineIsEmptyString() {
        val runs = listOf(
            run(mode = AblationMode.INTENT_SHORTCUT_ONLY, latency = 5.0, calls = 1, steps = 2, screenshots = 2, tokens = 200, success = true)
        )
        val rows = ResearchSummaryTable.build(runs)
        assertEquals(1, rows.size)
        assertEquals("", rows.first().speedupVsBaseline)
    }

    @Test
    fun summaryOmitsModesWithoutRunsAndWritesExactHeader() {
        val csv = ResearchSummaryTable.toCsv(
            ResearchSummaryTable.build(listOf(run(AblationMode.INTENT_SHORTCUT_ONLY, 5.0, 1, 2, 1, 300, true)))
        )
        val lines = csv.trim().split("\n")
        assertEquals(ResearchSummaryTable.HEADER, lines[0])
        assertEquals(
            "Ablation_Mode,Mode_Code,Tasks_Evaluated,Tasks_Succeeded,Task_Success_Rate_Pct," +
                "Mean_End_to_End_Latency_Sec,Std_Latency_Sec,Mean_Latency_Per_Step_Sec," +
                "Mean_LLM_API_Calls_Per_Task,Mean_UI_Steps_Per_Task,Mean_Screenshots_Per_Task," +
                "Mean_Prompt_Tokens_Per_Call,Speedup_vs_Baseline," +
                "Median_Latency_Sec,Unverified_Rate_Pct,Failed_Rate_Pct,Stopped_Rate_Pct," +
                "User_Corrected_Rate_Pct,Mean_Message_Delay_Ms",
            lines[0]
        )
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("2. Intent shortcuts only (+ OS intent teleportation layer),INTENT_SHORTCUT_ONLY,1,1,100.00,5.00,0.00,2.50,1.00,2.00,1.00,300.00,"))
    }

    @Test
    fun hudLineMatchesTheRequiredFormat() {
        val row = ResearchSummaryRow(
            mode = AblationMode.BASELINE,
            runs = 3,
            successes = 2,
            meanLatencySeconds = 12.345,
            meanLlmCallsPerTask = 2.5,
            successRatePercent = 66.666
        )
        assertEquals("[BASELINE] Latency: 12.35s | LLM Calls: 2 | Success Rate: 66.7%", row.hudLine())
    }

    @Test
    fun ablationFlagsAndPaperLabelsMatchSpecification() {
        assertFalse(AblationMode.BASELINE.intentShortcuts)
        assertFalse(AblationMode.BASELINE.actionBursts)
        assertFalse(AblationMode.BASELINE.fastDeltaVerify)
        assertFalse(AblationMode.BASELINE.fastObservation)
        assertFalse(AblationMode.BASELINE.rememberReplay)
        assertEquals("Baseline (full observation; one action per turn)", AblationMode.BASELINE.paperLabel)

        assertTrue(AblationMode.INTENT_SHORTCUT_ONLY.intentShortcuts)
        assertFalse(AblationMode.INTENT_SHORTCUT_ONLY.actionBursts)
        assertFalse(AblationMode.INTENT_SHORTCUT_ONLY.rememberReplay)
        assertEquals("Intent shortcuts only (+ OS intent teleportation layer)", AblationMode.INTENT_SHORTCUT_ONLY.paperLabel)

        assertTrue(AblationMode.BURST_AND_FAST_OBSERVE.intentShortcuts)
        assertTrue(AblationMode.BURST_AND_FAST_OBSERVE.actionBursts)
        assertTrue(AblationMode.BURST_AND_FAST_OBSERVE.fastDeltaVerify)
        assertTrue(AblationMode.BURST_AND_FAST_OBSERVE.fastObservation)
        assertFalse(AblationMode.BURST_AND_FAST_OBSERVE.rememberReplay)
        assertEquals("Burst and fast observe (+ compact tree folding + action bursts + event-driven verify)", AblationMode.BURST_AND_FAST_OBSERVE.paperLabel)

        assertTrue(AblationMode.FULL_HUMAN_SPEED.intentShortcuts)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.actionBursts)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.fastObservation)
        assertTrue(AblationMode.FULL_HUMAN_SPEED.rememberReplay)
        assertEquals("Full human speed (+ online verified replay cache)", AblationMode.FULL_HUMAN_SPEED.paperLabel)

        // Ensure zero commas in any paper label:
        AblationMode.entries.forEach { mode ->
            assertFalse("Paper label must not have commas: ${mode.paperLabel}", mode.paperLabel.contains(","))
        }

        assertEquals(AblationMode.FULL_HUMAN_SPEED, AblationMode.DEFAULT)
        assertEquals(AblationMode.BASELINE, AblationMode.fromId("baseline"))
        assertEquals(AblationMode.FULL_HUMAN_SPEED, AblationMode.fromId("nonsense"))
    }

    // ---------------------------------------------------------------------------------------------
    // Store: file behaviour shared by the manager
    // ---------------------------------------------------------------------------------------------

    @Test
    fun storeAppendsHeaderOnceRewritesSummaryAndToleratesDamage() {
        val dir = java.io.File(context.cacheDir, "research-store-${System.nanoTime()}")
        val store = ResearchRunStore(dir)

        store.append(run(AblationMode.BASELINE, 10.0, 2, 2, 1, 100, true))
        store.append(run(AblationMode.BASELINE, 20.0, 4, 4, 2, 200, false))

        val runsFile = store.runsFile()
        val lines = runsFile.readLines()
        assertEquals(2 + 1, lines.size)
        assertEquals(ResearchRun.HEADER, lines.first())
        assertEquals(2, store.read().size)

        store.rewriteSummary()
        val summaryLines = store.summaryFile().readLines()
        assertEquals(ResearchSummaryTable.HEADER, summaryLines.first())
        assertTrue(summaryLines[1].startsWith("1. Baseline (full observation; one action per turn),BASELINE,2,1,50.00,15.00,"))

        runsFile.appendText("garbage,not,a,run\n")
        assertEquals("a damaged line is skipped, not fatal", 2, store.read().size)

        store.clear()
        assertFalse(store.runsFile().exists())
        assertFalse(store.summaryFile().exists())
    }

    // ---------------------------------------------------------------------------------------------
    // Manager: counters, files, logging, override
    // ---------------------------------------------------------------------------------------------

    @Test
    fun managerWritesRunAndSummaryAndLogsTheHudLine() {
        var now = 100_000L
        val logs = mutableListOf<String>()
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = { logs.add(it) })

        manager.startTask("Open Settings", AblationMode.BASELINE, StudyPass.MEASURED)
        now += 12_500
        manager.recordLlmCall()
        manager.recordStep()
        manager.recordBitmapCapture()
        manager.recordPromptTokens(250)
        manager.markIntentShortcutUsed()
        val run = manager.endTask(isSuccess = true)

        requireNotNull(run)
        assertEquals(12.5, run.latencySeconds, 0.0001)
        assertEquals(1, run.llmCalls)
        assertEquals(1, run.steps)
        assertEquals(1, run.screenshots)
        assertEquals(250, run.promptTokens)
        assertEquals(StudyPass.MEASURED, run.studyPass)
        assertTrue(run.intentShortcutUsed)
        assertTrue(run.success)

        val runsText = manager.runsFile().readText()
        assertTrue(runsText.startsWith(ResearchRun.HEADER))
        assertEquals(2, runsText.trim().split("\n").size)

        val summaryText = manager.summaryFile().readText()
        assertTrue(summaryText.startsWith(ResearchSummaryTable.HEADER))
        assertTrue(summaryText.contains("1. Baseline (full observation; one action per turn),BASELINE,1,1,100.00,12.50,0.00,12.50,1.00,1.00,1.00,250.00,1.00x"))

        assertTrue(
            "HUD line must be logged verbatim: $logs",
            logs.contains("[BASELINE] Latency: 12.50s | LLM Calls: 1 | Success Rate: 100.0%")
        )
        assertTrue(logs.any { it.startsWith("TASK_END mode=BASELINE pass=MEASURED latency_s=12.500") })
    }

    @Test
    fun overrideRewritesTheLastRowPreservingAllFields() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})

        manager.startTask("first", AblationMode.BASELINE, StudyPass.MEASURED)
        now += 10_000
        manager.endTask(isSuccess = true)

        manager.startTask("second", AblationMode.BASELINE, StudyPass.WARMUP)
        now += 20_000
        manager.recordLlmCall()
        manager.recordStep()
        manager.recordBitmapCapture()
        manager.recordPromptTokens(350)
        manager.endTask(isSuccess = true)

        val updated = manager.overrideLastOutcome(false)
        requireNotNull(updated)
        assertFalse(updated.success)
        assertTrue(updated.manuallyOverridden)
        assertEquals("second", updated.goal)
        assertEquals(AblationMode.BASELINE, updated.mode)
        assertEquals(StudyPass.WARMUP, updated.studyPass)
        assertEquals(1, updated.screenshots)
        assertEquals(350, updated.promptTokens)
        assertEquals(1, updated.llmCalls)
        assertEquals(1, updated.steps)

        val rows = manager.allRuns()
        assertEquals(2, rows.size)
        val lastOnDisk = rows.last()
        assertTrue(lastOnDisk.manuallyOverridden)
        assertFalse(lastOnDisk.success)
        assertEquals(StudyPass.WARMUP, lastOnDisk.studyPass)
        assertEquals(1, lastOnDisk.screenshots)
        assertEquals(350, lastOnDisk.promptTokens)
    }

    @Test
    fun modeAndStudyPassArePersistedAcrossManagerInstances() {
        val first = ResearchTelemetryManager(context, logger = {})
        first.mode = AblationMode.BURST_AND_FAST_OBSERVE
        first.studyPass = StudyPass.WARMUP
        val second = ResearchTelemetryManager(context, logger = {})
        assertEquals(AblationMode.BURST_AND_FAST_OBSERVE, second.mode)
        assertEquals(StudyPass.WARMUP, second.studyPass)
    }

    @Test
    fun countersOutsideATaskAreIgnoredAndEndTaskReturnsNull() {
        val manager = ResearchTelemetryManager(context, logger = {})
        manager.recordLlmCall()
        manager.recordStep()
        manager.recordBitmapCapture()
        manager.recordPromptTokens(100)
        manager.markRememberCacheHit()
        assertNull(manager.endTask(isSuccess = true))
        assertFalse(manager.isTaskActive)
        assertTrue(manager.allRuns().isEmpty())
    }

    @Test
    fun eachTaskStartsFromZeroCounters() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})
        manager.startTask("first", AblationMode.BASELINE)
        repeat(5) { manager.recordLlmCall() }
        repeat(3) { manager.recordBitmapCapture() }
        manager.recordPromptTokens(500)
        now += 1_000
        manager.endTask(isSuccess = false, failureReason = "unmet_criteria")

        manager.startTask("second", AblationMode.BASELINE)
        manager.recordLlmCall()
        now += 1_000
        val second = manager.endTask(isSuccess = true)

        requireNotNull(second)
        assertEquals(1, second.llmCalls)
        assertEquals(0, second.steps)
        assertEquals(0, second.screenshots)
        assertEquals(0, second.promptTokens)
        assertEquals("unmet_criteria", manager.allRuns().first().failureReason)
    }

    @Test
    fun endTaskIfActiveRecordsCancelledRunsAsFailuresOnlyOnce() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})

        manager.startTask("cancelled task", AblationMode.BASELINE)
        now += 7_000
        val aborted = manager.endTaskIfActive("stopped")
        requireNotNull(aborted)
        assertFalse(aborted.success)
        assertEquals("stopped", aborted.failureReason)
        assertEquals(7.0, aborted.latencySeconds, 0.0001)
        assertFalse(manager.isTaskActive)

        // A normal end already closed the run, so the safety net must not append a second row.
        assertNull(manager.endTaskIfActive("stopped"))
        assertEquals(1, manager.allRuns().size)
    }

    @Test
    fun clearAllRemovesStoredFiles() {
        var now = 0L
        val manager = ResearchTelemetryManager(context, clock = { now }, logger = {})
        manager.startTask("task", AblationMode.FULL_HUMAN_SPEED)
        now += 2_000
        manager.endTask(isSuccess = true)
        assertTrue(manager.runsFile().exists())

        manager.clearAll()
        assertFalse(manager.runsFile().exists())
        assertFalse(manager.summaryFile().exists())
        assertNull(manager.lastRunOrNull())
    }

    private fun run(
        mode: AblationMode,
        latency: Double,
        calls: Int,
        steps: Int = 1,
        screenshots: Int = 0,
        tokens: Int = 0,
        success: Boolean,
        pass: StudyPass = StudyPass.MEASURED
    ) = ResearchRun(
        timestampMs = 1L,
        mode = mode,
        goal = "goal",
        latencySeconds = latency,
        llmCalls = calls,
        localInferences = 0,
        steps = steps,
        success = success,
        failureReason = if (success) "" else "unmet_criteria",
        intentShortcutUsed = false,
        rememberCacheHit = false,
        screenshots = screenshots,
        promptTokens = tokens,
        studyPass = pass
    )
}
