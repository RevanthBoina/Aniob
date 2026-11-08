package com.aniob.app.research

import java.util.Locale

/** Running averages for one [AblationMode] — one row of `research_summary_table.csv`. */
data class ResearchSummaryRow(
    val mode: AblationMode,
    val runs: Int,
    val successes: Int,
    val meanLatencySeconds: Double,
    val meanLlmCallsPerTask: Double,
    val successRatePercent: Double,
    val stdLatencySeconds: Double = 0.0,
    val meanLatencyPerStepSeconds: Double = 0.0,
    val meanUiStepsPerTask: Double = 0.0,
    val meanScreenshotsPerTask: Double = 0.0,
    val meanPromptTokensPerCall: Double = 0.0,
    val speedupVsBaseline: String = ""
) {
    /**
     * The line shown in the end-of-task toast and in `Log.i("RESEARCH_METRICS", ...)`:
     * `[BASELINE] Latency: 12.34s | LLM Calls: 3 | Success Rate: 66.7%`
     *
     * The success rate is the running rate for the mode, so it moves with every finished task
     * rather than reporting only the latest one.
     */
    fun hudLine(): String = String.format(
        Locale.US,
        "[%s] Latency: %.2fs | LLM Calls: %d | Success Rate: %.1f%%",
        mode.id,
        meanLatencySeconds,
        meanLlmCallsPerTask.toInt(),
        successRatePercent
    )

    fun toCsvRow(): String = listOf(
        "${AblationMode.entries.indexOf(mode) + 1}. ${mode.paperLabel}",
        mode.id,
        runs.toString(),
        successes.toString(),
        String.format(Locale.US, "%.2f", successRatePercent),
        String.format(Locale.US, "%.2f", meanLatencySeconds),
        String.format(Locale.US, "%.2f", stdLatencySeconds),
        String.format(Locale.US, "%.2f", meanLatencyPerStepSeconds),
        String.format(Locale.US, "%.2f", meanLlmCallsPerTask),
        String.format(Locale.US, "%.2f", meanUiStepsPerTask),
        String.format(Locale.US, "%.2f", meanScreenshotsPerTask),
        String.format(Locale.US, "%.2f", meanPromptTokensPerCall),
        speedupVsBaseline
    ).joinToString(CsvCodec.DELIMITER.toString())
}

/**
 * Aggregation for the paper's metrics, grouped by ablation mode.
 *
 * Deliberately pure: no files, no Android, no clock. `ResearchTelemetryManager` owns the IO and
 * feeds the parsed runs here, which is what makes the numbers unit-testable.
 */
object ResearchSummaryTable {

    const val HEADER =
        "Ablation_Mode,Mode_Code,Tasks_Evaluated,Tasks_Succeeded,Task_Success_Rate_Pct," +
            "Mean_End_to_End_Latency_Sec,Std_Latency_Sec,Mean_Latency_Per_Step_Sec," +
            "Mean_LLM_API_Calls_Per_Task,Mean_UI_Steps_Per_Task,Mean_Screenshots_Per_Task," +
            "Mean_Prompt_Tokens_Per_Call,Speedup_vs_Baseline"

    /** Always one row per [AblationMode], in enum order; a mode with no measured run reports zeros instead of vanishing. */
    fun buildAllModes(runs: List<ResearchRun>): List<ResearchSummaryRow> {
        val built = build(runs).associateBy { it.mode }
        return AblationMode.entries.map { built[it] ?: ResearchSummaryRow(it, 0, 0, 0.0, 0.0, 0.0) }
    }

    /**
     * One row per mode that has at least one measured run, in enum declaration order so the CSV is
     * stable between rewrites. Warm-up runs are excluded from this summary.
     */
    fun build(runs: List<ResearchRun>): List<ResearchSummaryRow> {
        val measuredRuns = runs.filter { it.studyPass == StudyPass.MEASURED }
        val baselineRuns = measuredRuns.filter { it.mode == AblationMode.BASELINE }
        val baselineMeanLatency = if (baselineRuns.isNotEmpty()) {
            baselineRuns.sumOf { it.latencySeconds } / baselineRuns.size
        } else {
            null
        }

        return AblationMode.entries.mapNotNull { mode ->
            val modeRuns = measuredRuns.filter { it.mode == mode }
            if (modeRuns.isEmpty()) return@mapNotNull null
            val n = modeRuns.size
            val successes = modeRuns.count { it.success }
            val successRatePercent = successes.toDouble() / n * 100.0
            val meanLatencySeconds = modeRuns.sumOf { it.latencySeconds } / n

            val stdLatencySeconds = if (n <= 1) {
                0.0
            } else {
                val sumSquaredDiffs = modeRuns.sumOf {
                    val diff = it.latencySeconds - meanLatencySeconds
                    diff * diff
                }
                Math.sqrt(sumSquaredDiffs / (n - 1))
            }

            val runsWithSteps = modeRuns.filter { it.steps > 0 }
            val meanLatencyPerStepSeconds = if (runsWithSteps.isEmpty()) {
                0.0
            } else {
                runsWithSteps.sumOf { it.latencySeconds / it.steps } / runsWithSteps.size
            }

            val meanLlmCallsPerTask = modeRuns.sumOf { it.llmCalls.toDouble() } / n
            val meanUiStepsPerTask = modeRuns.sumOf { it.steps.toDouble() } / n
            val meanScreenshotsPerTask = modeRuns.sumOf { it.screenshots.toDouble() } / n

            val totalLlmCalls = modeRuns.sumOf { it.llmCalls }
            val totalPromptTokens = modeRuns.sumOf { it.promptTokens }
            val meanPromptTokensPerCall = if (totalLlmCalls == 0) {
                0.0
            } else {
                totalPromptTokens.toDouble() / totalLlmCalls.toDouble()
            }

            val speedupVsBaseline = if (baselineMeanLatency == null) {
                ""
            } else if (mode == AblationMode.BASELINE) {
                "1.00x"
            } else if (meanLatencySeconds <= 0.0) {
                "0.00x"
            } else {
                String.format(Locale.US, "%.2fx", baselineMeanLatency / meanLatencySeconds)
            }

            ResearchSummaryRow(
                mode = mode,
                runs = n,
                successes = successes,
                meanLatencySeconds = meanLatencySeconds,
                meanLlmCallsPerTask = meanLlmCallsPerTask,
                successRatePercent = successRatePercent,
                stdLatencySeconds = stdLatencySeconds,
                meanLatencyPerStepSeconds = meanLatencyPerStepSeconds,
                meanUiStepsPerTask = meanUiStepsPerTask,
                meanScreenshotsPerTask = meanScreenshotsPerTask,
                meanPromptTokensPerCall = meanPromptTokensPerCall,
                speedupVsBaseline = speedupVsBaseline
            )
        }
    }

    /** Full file contents, header included, newline-terminated. */
    fun toCsv(rows: List<ResearchSummaryRow>): String = buildString {
        append(HEADER).append('\n')
        rows.forEach { append(it.toCsvRow()).append('\n') }
    }

    /**
     * Rebuilds the table without dropping a mode that was already measured.
     *
     * `research_summary_table.csv` is the paper's artifact and a batch is run mode by mode, so
     * switching the ablation radio button must never cost the earlier rows. The table is normally
     * recomputed from `research_runs.csv`, which is append-only — but if that file is reset or
     * pruned between modes, a mode that has no runs left would silently vanish and the final table
     * would report fewer conditions than were actually evaluated. Any mode already present in
     * [existingCsv] therefore keeps its row verbatim.
     */
    fun mergeWithExisting(existingCsv: String?, rows: List<ResearchSummaryRow>): String {
        val previous = previousRowsByMode(existingCsv)
        val current = rows.associateBy { it.mode }
        val merged = AblationMode.entries.mapNotNull { mode ->
            current[mode]?.toCsvRow() ?: previous[mode]
        }
        return buildString {
            append(HEADER).append('\n')
            merged.forEach { append(it).append('\n') }
        }
    }

    /** `Mode_Code` -> row line, from a previously written table. Damaged lines are ignored. */
    private fun previousRowsByMode(existingCsv: String?): Map<AblationMode, String> {
        if (existingCsv.isNullOrBlank()) return emptyMap()
        val byId = AblationMode.entries.associateBy { it.id }
        return existingCsv.lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val code = line.split(CsvCodec.DELIMITER).getOrNull(1) ?: return@mapNotNull null
                byId[code]?.let { it to line }
            }
            .toMap()
    }

    /** Convenience for the HUD: the running row for one mode, or `null` when it has no runs. */
    fun rowFor(runs: List<ResearchRun>, mode: AblationMode): ResearchSummaryRow? =
        build(runs).firstOrNull { it.mode == mode }
}
