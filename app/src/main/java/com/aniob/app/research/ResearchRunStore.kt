package com.aniob.app.research

import java.io.File

/**
 * File layer for the research CSVs: append a run, read them back, rewrite the summary, and replace
 * the last row for a manual override.
 *
 * Kept free of Android types so the exact byte-level behaviour (header creation, quoting, atomic
 * summary rewrite) is testable on the JVM. The manager owns the lock; this class assumes it is
 * called single-threaded.
 *
 * Failures never throw: research instrumentation must not be able to crash a task. Every failure is
 * reported through [logger] and leaves the previous file untouched.
 */
internal class ResearchRunStore(
    private val directory: File,
    private val logger: (String) -> Unit = {}
) {

    fun runsFile(): File = File(directory, RUNS_FILE)

    fun summaryFile(): File = File(directory, SUMMARY_FILE)

    /** Appends one row, writing the header first when the file is new or was truncated. */
    fun append(run: ResearchRun) {
        runCatching {
            val file = runsFile()
            file.parentFile?.mkdirs()
            if (!file.exists() || file.length() == 0L) {
                file.writeText(ResearchRun.HEADER + "\n")
            }
            if (file.useLines { it.firstOrNull() } != ResearchRun.HEADER) rewrite(read())
            file.appendText(run.toCsvRow() + "\n")
        }.onFailure { report("append", it) }
    }

    /** All parseable rows; the header and any damaged line are skipped rather than fatal. */
    fun read(): List<ResearchRun> = runCatching {
        val file = runsFile()
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { ResearchRun.fromCsvRow(it) }
    }.getOrElse {
        report("read", it)
        emptyList()
    }

    /** Rewrites the whole runs file (used by the manual override). */
    fun rewrite(runs: List<ResearchRun>) {
        runCatching {
            atomicWrite(runsFile(), buildString {
                append(ResearchRun.HEADER).append('\n')
                runs.forEach { append(it.toCsvRow()).append('\n') }
            })
        }.onFailure { report("rewrite runs", it) }
    }

    /**
     * Recomputes `research_summary_table.csv` from the current runs file, keeping any mode that was
     * already measured. One row per evaluated ablation condition, never fewer after a mode switch.
     */
    fun rewriteSummary() {
        runCatching {
            val previous = summaryFile().takeIf { it.exists() }
                ?.let { runCatching { it.readText() }.getOrNull() }
            val rows = ResearchSummaryTable.build(read())
            atomicWrite(summaryFile(), ResearchSummaryTable.mergeWithExisting(previous, rows))
        }.onFailure { report("rewrite summary", it) }
    }

    fun clear() {
        runCatching { runsFile().delete() }
        runCatching { summaryFile().delete() }
    }

    /** Write-then-rename so a reader never sees a half-written table. */
    private fun atomicWrite(target: File, contents: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(contents)
        if (!temp.renameTo(target)) {
            target.writeText(contents)
            temp.delete()
        }
    }

    private fun report(operation: String, error: Throwable) {
        logger(
            "RESEARCH_ERROR $operation failed: " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
    }

    companion object {
        const val RUNS_FILE = "research_runs.csv"
        const val SUMMARY_FILE = "research_summary_table.csv"
    }
}
