package com.aniob.app.research

import java.util.Locale

/**
 * The value of each speed mechanism taken alone: the baseline (every mechanism off) against five
 * conditions with exactly one mechanism on.
 *
 * `research_summary_table.csv` already has one row per condition; this is the same data cut the
 * way the question is asked — how many tasks each mode gets right, how far that is from the
 * baseline, and what a task costs in steps, time and prompt tokens. It reads the runs that are
 * already recorded and adds no counters of its own.
 *
 * A run belongs to a condition by its feature mask, so a run made with two mechanisms on is not
 * counted under either of them. Runs recorded before feature masks existed are placed by their
 * legacy mode. Warm-up runs are left out, as in the summary table.
 *
 * Pure: no files, no Android, no clock.
 */
object ValueModeReport {

    /** A condition of the comparison: [feature] is null for the baseline. */
    data class Row(
        val label: String,
        val feature: ExecutionFeature?,
        val runs: Int,
        val correct: Int,
        val accuracyPercent: Double,
        /** Percentage points against the baseline; null when either side has no runs. */
        val deltaVsBaseline: Double?,
        val meanSteps: Double,
        val meanLatencyMs: Double,
        val meanPromptTokens: Double
    )

    const val BASELINE_LABEL = "Baseline (all off)"

    /** Baseline first, then one row per [ExecutionFeature] in its declared order. Always six rows. */
    fun build(runs: List<ResearchRun>): List<Row> {
        val measured = runs.filter { it.studyPass == StudyPass.MEASURED }
        val byMask = measured.groupBy { maskOf(it) }
        val baseline = row(BASELINE_LABEL, null, byMask[0].orEmpty(), null)
        val baselineAccuracy = baseline.accuracyPercent.takeIf { baseline.runs > 0 }
        return listOf(baseline) + ExecutionFeature.entries.map { feature ->
            row(feature.label, feature, byMask[feature.bit].orEmpty(), baselineAccuracy)
        }
    }

    private fun maskOf(run: ResearchRun): Int = run.featureMask ?: FeatureSelection.fromLegacy(run.mode).mask

    private fun row(label: String, feature: ExecutionFeature?, runs: List<ResearchRun>, baselineAccuracy: Double?): Row {
        val n = runs.size
        // Same definition of "correct" as the summary table: verified success the user did not overturn.
        val correct = runs.count { it.success && !it.userCorrected }
        val accuracy = if (n == 0) 0.0 else correct * 100.0 / n
        return Row(
            label = label,
            feature = feature,
            runs = n,
            correct = correct,
            accuracyPercent = accuracy,
            deltaVsBaseline = when {
                n == 0 || baselineAccuracy == null -> null
                feature == null -> 0.0
                else -> accuracy - baselineAccuracy
            },
            meanSteps = if (n == 0) 0.0 else runs.sumOf { it.steps.toDouble() } / n,
            meanLatencyMs = if (n == 0) 0.0 else runs.sumOf { it.latencySeconds * 1000.0 } / n,
            meanPromptTokens = if (n == 0) 0.0 else runs.sumOf { it.promptTokens.toDouble() } / n
        )
    }

    /** Markdown table; a condition with no runs is shown as not run instead of as 0%. */
    fun toMarkdown(rows: List<Row>): String = buildString {
        append("| Mode | Correct | Accuracy | Δ baseline | Avg steps | Avg ms | Prompt tokens |\n")
        append("|---|---|---|---|---|---|---|\n")
        rows.forEach { r ->
            if (r.runs == 0) {
                append("| ${r.label} | not run | – | – | – | – | – |\n")
            } else {
                val delta = when {
                    r.feature == null -> "–"
                    r.deltaVsBaseline == null -> "n/a"
                    else -> String.format(Locale.US, "%+.1f pp", r.deltaVsBaseline)
                }
                append(
                    String.format(
                        Locale.US,
                        "| %s | %d/%d | %.1f%% | %s | %.1f | %.0f | %.0f |\n",
                        r.label, r.correct, r.runs, r.accuracyPercent, delta,
                        r.meanSteps, r.meanLatencyMs, r.meanPromptTokens
                    )
                )
            }
        }
    }
}
