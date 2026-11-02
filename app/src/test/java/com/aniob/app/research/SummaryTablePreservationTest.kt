package com.aniob.app.research

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `research_summary_table.csv` is the paper's table: one row per evaluated ablation condition.
 *
 * A batch is run mode by mode, so the table is rewritten several times over a session. These cases
 * pin that a later rewrite can never shrink the table — not when a mode has no runs yet, and not
 * when `research_runs.csv` was reset between batches.
 */
class SummaryTablePreservationTest {

    private fun row(mode: AblationMode, runs: Int, successes: Int, latency: Double) = ResearchSummaryRow(
        mode = mode,
        runs = runs,
        successes = successes,
        meanLatencySeconds = latency,
        meanLlmCallsPerTask = 1.0,
        successRatePercent = successes.toDouble() / runs * 100.0
    )

    private fun run(mode: AblationMode) = ResearchRun(
        timestampMs = 1_700_000_000_000,
        mode = mode,
        goal = "open settings",
        latencySeconds = 5.0,
        llmCalls = 1,
        localInferences = 0,
        steps = 2,
        success = true,
        failureReason = "",
        intentShortcutUsed = false,
        rememberCacheHit = false
    )

    @Test
    fun `a mode that has no runs keeps its previous row`() {
        val baseline = row(AblationMode.BASELINE, 15, 10, 18.42).toCsvRow()
        val existing = ResearchSummaryTable.HEADER + "\n" + baseline + "\n"

        val merged = ResearchSummaryTable.mergeWithExisting(
            existing,
            listOf(row(AblationMode.FULL_HUMAN_SPEED, 15, 14, 0.68))
        )

        val lines = merged.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertEquals(ResearchSummaryTable.HEADER, lines[0])
        assertEquals("the baseline row is kept verbatim", baseline, lines[1])
        assertTrue(lines[2].contains(AblationMode.FULL_HUMAN_SPEED.id))
    }

    @Test
    fun `rows stay in ablation order whatever arrives`() {
        val existing = ResearchSummaryTable.toCsv(listOf(row(AblationMode.INTENT_SHORTCUT_ONLY, 15, 12, 7.85)))

        val merged = ResearchSummaryTable.mergeWithExisting(
            existing,
            listOf(
                row(AblationMode.FULL_HUMAN_SPEED, 15, 14, 0.68),
                row(AblationMode.BASELINE, 15, 10, 18.42)
            )
        )

        val codes = merged.lines().drop(1).filter { it.isNotBlank() }.map { it.split(',')[1] }
        assertEquals(
            listOf(
                AblationMode.BASELINE.id,
                AblationMode.INTENT_SHORTCUT_ONLY.id,
                AblationMode.FULL_HUMAN_SPEED.id
            ),
            codes
        )
    }

    @Test
    fun `a fresh measurement replaces the stale row for its own mode`() {
        val existing = ResearchSummaryTable.toCsv(listOf(row(AblationMode.BASELINE, 5, 1, 30.0)))

        val merged = ResearchSummaryTable.mergeWithExisting(
            existing,
            listOf(row(AblationMode.BASELINE, 15, 10, 18.42))
        )

        val baseline = merged.lines().first { it.contains(AblationMode.BASELINE.id) }
        assertTrue("the newer measurement wins", baseline.contains(",15,10,"))
    }

    @Test
    fun `a table without history is just the current rows`() {
        val merged = ResearchSummaryTable.mergeWithExisting(
            null,
            listOf(row(AblationMode.BASELINE, 1, 1, 2.0))
        )
        assertEquals(2, merged.lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `damaged history lines are ignored instead of crashing the rewrite`() {
        val existing = ResearchSummaryTable.HEADER + "\nnot,a,row\n,,\n"

        val merged = ResearchSummaryTable.mergeWithExisting(
            existing,
            listOf(row(AblationMode.BASELINE, 1, 1, 2.0))
        )
        assertEquals(2, merged.lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `the store keeps every mode even after the runs file is reset`() {
        val directory = Files.createTempDirectory("research-summary").toFile()
        val store = ResearchRunStore(directory)

        store.append(run(AblationMode.BASELINE))
        store.rewriteSummary()
        // A second batch starts from a clean runs file, as the study protocol does between modes.
        store.runsFile().delete()
        store.append(run(AblationMode.FULL_HUMAN_SPEED))
        store.rewriteSummary()

        val lines = store.summaryFile().readLines().filter { it.isNotBlank() }
        val codes = lines.drop(1).map { it.split(',')[1] }
        assertEquals(
            "the earlier condition must survive the reset",
            listOf(AblationMode.BASELINE.id, AblationMode.FULL_HUMAN_SPEED.id),
            codes
        )
        directory.deleteRecursively()
    }
}
