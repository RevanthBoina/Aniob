package com.aniob.app.research

import java.util.Locale

/**
 * One completed task, exactly as written to `research_runs.csv`.
 *
 * Pure data with pure CSV encoding so it can be unit-tested without an Android runtime. The file is
 * append-only: a manual pass/fail override rewrites the last row instead of appending a second one,
 * so every row is one task and the summary never double-counts.
 */
data class ResearchRun(
    val timestampMs: Long,
    val mode: AblationMode,
    val goal: String,
    val latencySeconds: Double,
    val llmCalls: Int,
    val localInferences: Int,
    val steps: Int,
    val success: Boolean,
    val failureReason: String,
    val intentShortcutUsed: Boolean,
    val rememberCacheHit: Boolean,
    /** True once the researcher replaced the automatic verdict with a manual one. */
    val manuallyOverridden: Boolean = false,
    val screenshots: Int = 0,
    val promptTokens: Int = 0,
    val studyPass: StudyPass = StudyPass.MEASURED,
    val treeReads: Int = 0,
    val bitmaps: Int = 0,
    val burstsPlanned: Int = 0,
    val burstMembersRun: Int = 0,
    val burstsSuspended: Boolean = false,
    val shortcutAttempted: Boolean = false,
    val shortcutDegraded: Boolean = false,
    val shortcutFailReason: String = "",
    val skillId: String = "",
    val rememberReplayActions: Int = 0,
    val fastpathSteps: Int = 0,
    val replayValidated: Boolean = false,
    val replayDiverged: Boolean = false,
    val setupLatencyMs: Long = 0L
) {

    fun toCsvRow(): String = listOf(
        timestampMs.toString(),
        mode.id,
        CsvCodec.escape(CsvCodec.singleLine(goal)),
        formatLatency(latencySeconds),
        llmCalls.toString(),
        localInferences.toString(),
        steps.toString(),
        success.toString(),
        CsvCodec.escape(CsvCodec.singleLine(failureReason)),
        intentShortcutUsed.toString(),
        rememberCacheHit.toString(),
        manuallyOverridden.toString(),
        screenshots.toString(),
        promptTokens.toString(),
        studyPass.id,
        treeReads.toString(),
        bitmaps.toString(),
        burstsPlanned.toString(),
        burstMembersRun.toString(),
        burstsSuspended.toString(),
        shortcutAttempted.toString(),
        shortcutDegraded.toString(),
        CsvCodec.escape(CsvCodec.singleLine(shortcutFailReason)),
        CsvCodec.escape(CsvCodec.singleLine(skillId)),
        rememberReplayActions.toString(),
        fastpathSteps.toString(),
        replayValidated.toString(),
        replayDiverged.toString(),
        setupLatencyMs.toString()
    ).joinToString(CsvCodec.DELIMITER.toString())

    companion object {
        const val HEADER =
            "timestamp_ms,mode,goal,latency_s,llm_calls,local_inferences,steps,success," +
                "failure_reason,intent_shortcut_used,remember_cache_hit,manually_overridden," +
                "screenshots,prompt_tokens,study_pass,tree_reads,bitmaps,bursts_planned," +
                "burst_members_run,bursts_suspended,shortcut_attempted,shortcut_degraded," +
                "shortcut_fail_reason,skill_id,remember_replay_actions,fastpath_steps," +
                "replay_validated,replay_diverged,setup_latency_ms"

        const val COLUMN_COUNT = 29
        const val PREV_COLUMN_COUNT = 15
        const val LEGACY_COLUMN_COUNT = 12

        /** Milliseconds precision in the raw file; the summary table and HUD round to 2 decimals. */
        fun formatLatency(seconds: Double): String =
            String.format(Locale.US, "%.3f", if (seconds.isFinite()) seconds else 0.0)

        /** Parses one CSV row; returns `null` for the header, blank lines or malformed rows. */
        fun fromCsvRow(row: String): ResearchRun? {
            val cells = CsvCodec.split(row)
            if (cells.size != COLUMN_COUNT && cells.size != PREV_COLUMN_COUNT && cells.size != LEGACY_COLUMN_COUNT) return null
            val timestamp = cells[0].toLongOrNull() ?: return null
            val latency = cells[3].toDoubleOrNull() ?: return null
            val llmCalls = cells[4].toIntOrNull() ?: return null
            val localInferences = cells[5].toIntOrNull() ?: return null
            val steps = cells[6].toIntOrNull() ?: return null
            val success = cells[7].toBooleanStrictOrNull() ?: return null
            val intentShortcutUsed = cells[9].toBooleanStrictOrNull() ?: return null
            val rememberCacheHit = cells[10].toBooleanStrictOrNull() ?: return null
            val manuallyOverridden = cells[11].toBooleanStrictOrNull() ?: false

            val screenshots = if (cells.size >= 13) cells[12].toIntOrNull() ?: return null else 0
            val promptTokens = if (cells.size >= 14) cells[13].toIntOrNull() ?: return null else 0
            val studyPass = if (cells.size >= 15) StudyPass.fromId(cells[14]) else StudyPass.MEASURED

            val treeReads = if (cells.size >= 16) cells[15].toIntOrNull() ?: return null else 0
            val bitmaps = if (cells.size >= 17) cells[16].toIntOrNull() ?: return null else 0
            val burstsPlanned = if (cells.size >= 18) cells[17].toIntOrNull() ?: return null else 0
            val burstMembersRun = if (cells.size >= 19) cells[18].toIntOrNull() ?: return null else 0
            val burstsSuspended = if (cells.size >= 20) cells[19].toBooleanStrictOrNull() ?: return null else false
            val shortcutAttempted = if (cells.size >= 21) cells[20].toBooleanStrictOrNull() ?: return null else false
            val shortcutDegraded = if (cells.size >= 22) cells[21].toBooleanStrictOrNull() ?: return null else false
            val shortcutFailReason = if (cells.size >= 23) cells[22] else ""
            val skillId = if (cells.size >= 24) cells[23] else ""
            val rememberReplayActions = if (cells.size >= 25) cells[24].toIntOrNull() ?: return null else 0
            val fastpathSteps = if (cells.size >= 26) cells[25].toIntOrNull() ?: return null else 0
            val replayValidated = if (cells.size >= 27) cells[26].toBooleanStrictOrNull() ?: return null else false
            val replayDiverged = if (cells.size >= 28) cells[27].toBooleanStrictOrNull() ?: return null else false
            val setupLatencyMs = if (cells.size >= 29) cells[28].toLongOrNull() ?: return null else 0L

            return ResearchRun(
                timestampMs = timestamp,
                mode = AblationMode.fromId(cells[1]),
                goal = cells[2],
                latencySeconds = latency,
                llmCalls = llmCalls,
                localInferences = localInferences,
                steps = steps,
                success = success,
                failureReason = cells[8],
                intentShortcutUsed = intentShortcutUsed,
                rememberCacheHit = rememberCacheHit,
                manuallyOverridden = manuallyOverridden,
                screenshots = screenshots,
                promptTokens = promptTokens,
                studyPass = studyPass,
                treeReads = treeReads,
                bitmaps = bitmaps,
                burstsPlanned = burstsPlanned,
                burstMembersRun = burstMembersRun,
                burstsSuspended = burstsSuspended,
                shortcutAttempted = shortcutAttempted,
                shortcutDegraded = shortcutDegraded,
                shortcutFailReason = shortcutFailReason,
                skillId = skillId,
                rememberReplayActions = rememberReplayActions,
                fastpathSteps = fastpathSteps,
                replayValidated = replayValidated,
                replayDiverged = replayDiverged,
                setupLatencyMs = setupLatencyMs
            )
        }
    }
}

/**
 * Minimal RFC 4180 quoting. Goals and failure reasons are free text, so a comma in
 * "message Ana, about dinner" must not shift every later column.
 */
object CsvCodec {
    const val DELIMITER = ','

    fun escape(value: String): String {
        val needsQuotes = value.contains(DELIMITER) || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** Collapses line breaks into a single space so one task never spills onto multiple CSV rows. */
    fun singleLine(value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()

    /** Splits an RFC-4180 row; handles quotes, escaped quotes and empty cells. */
    fun split(row: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < row.length && row[i + 1] == '"') {
                        cur.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == DELIMITER && !inQuotes -> {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
