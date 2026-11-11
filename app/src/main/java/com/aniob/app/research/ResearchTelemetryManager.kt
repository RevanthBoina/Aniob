package com.aniob.app.research

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Self-contained measurement harness for the paper's three metrics, scoped to one task at a time.
 *
 * **Metrics**
 * 1. End-to-end task latency in seconds, measured with [SystemClock.elapsedRealtime] so a wall-clock
 *    change mid-batch cannot produce a negative or inflated run.
 * 2. LLM API calls per task: the number of outbound generation requests (one per
 *    `AniobOmniRouteProvider` HTTP call, including the JSON repair retry). Local on-device
 *    generations are counted separately and never inflate this number.
 * 3. Task success rate (%): tasks whose final deterministic verification passed, per ablation mode,
 *    with a manual pass/fail override for runs the verifier cannot judge.
 *
 * **Outputs** (both under `getExternalFilesDir(null)`, i.e. `Android/data/<pkg>/files/`)
 * - `research_runs.csv` — append-only, one row per finished task ([ResearchRun.HEADER]).
 * - `research_summary_table.csv` — rewritten after every run with the running means per
 *   [AblationMode] ([ResearchSummaryTable.HEADER]).
 * - Every completion also logs `Log.i("RESEARCH_METRICS", ...)` with the HUD line.
 *
 * **Design notes**
 * - The clock and the logger are injectable so the CSV/summary behaviour is unit-testable without
 *   sleeping or reading logcat.
 * - [startTask] is the only place counters reset, and [endTask] is a no-op when no run is active, so
 *   a cancellation/exception that skips [endTask] can never leak counts into the next task.
 * - File writes are synchronous and guarded by a single lock. Callers invoke this from a background
 *   dispatcher (the task pipeline already runs on `Dispatchers.Default`; the UI override wraps the
 *   call in `Dispatchers.IO`).
 * - `getExternalFilesDir` can be null on a device with no adopted storage; the internal `filesDir` is
 *   used as a fallback so a run is never lost.
 */
class ResearchTelemetryManager(
    context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val logger: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val combinationDao: () -> com.aniob.app.db.FeatureCombinationDao = {
        com.aniob.app.db.AniobDatabase.getDatabase(context.applicationContext).featureCombinationDao()
    }
) {

    private data class ActiveRun(
        val goal: String,
        val mode: AblationMode,
        val pass: StudyPass,
        val startElapsedMs: Long,
        val startedAtWallMs: Long,
        var llmCalls: Int = 0,
        var localInferences: Int = 0,
        var steps: Int = 0,
        var screenshots: Int = 0,
        var promptTokens: Int = 0,
        var intentShortcutUsed: Boolean = false,
        var rememberCacheHit: Boolean = false,
        var treeReads: Int = 0,
        var bitmaps: Int = 0,
        var burstsPlanned: Int = 0,
        var burstMembersRun: Int = 0,
        var burstsSuspended: Boolean = false,
        var shortcutAttempted: Boolean = false,
        var shortcutDegraded: Boolean = false,
        var shortcutFailReason: String = "",
        var skillId: String = "",
        var rememberReplayActions: Int = 0,
        var fastpathSteps: Int = 0,
        var replayValidated: Boolean = false,
        var replayDiverged: Boolean = false,
        var setupLatencyMs: Long = 0L,
        val runId: String = "",
        val featureMask: Int? = null,
        var lastActionCompletedMs: Long? = null,
        var messageDelayMs: Long = 0L,
        var questionsBeforeStart: Int = 0,
        var fastSkippedSteps: Int = 0,
        var userCorrected: Boolean = false,
        var endedElapsedMs: Long? = null
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = ResearchRunStore(appContext.getExternalFilesDir(null) ?: appContext.filesDir, logger)
    private val combinations by lazy(combinationDao)
    private val lock = Any()
    private val runCounter = AtomicLong(0)

    private var active: ActiveRun? = null
    private val closing = mutableMapOf<String, ActiveRun>()

    @Volatile
    private var lastCompleted: ResearchRun? = null

    /**
     * Active ablation mode, persisted across process death so a batch stays in one condition even if
     * Android kills the activity between tasks.
     */
    var mode: AblationMode
        get() = AblationMode.fromId(prefs.getString(KEY_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_MODE, value.id).apply()
        }

    var featureSelection: FeatureSelection
        get() = if (prefs.contains("execution_features_v1")) FeatureSelection(
            prefs.getInt("execution_features_v1", 31).coerceIn(0, 31)) else FeatureSelection.fromLegacy(mode)
        set(value) { prefs.edit().putInt("execution_features_v1", value.mask).apply() }

    private fun saveCombination(run: ResearchRun) {
        val mask = requireNotNull(run.featureMask)
        combinations.upsert(com.aniob.app.db.FeatureCombinationEntity(run.runId, mask,
            FeatureSelection(mask).key, run.timestampMs, run.toCsvRow()))
    }

    /**
     * Active study pass (warm-up vs measured), persisted across process death.
     */
    var studyPass: StudyPass
        get() = StudyPass.fromId(prefs.getString(KEY_STUDY_PASS, null))
        set(value) {
            prefs.edit().putString(KEY_STUDY_PASS, value.id).apply()
        }

    /** True between [startTask] and [endTask]; lets the UI gate the manual override honestly. */
    val isTaskActive: Boolean
        get() = synchronized(lock) { active != null }

    /** Number of completed runs seen by this process; useful as a Compose key. */
    val completedInProcess: Long get() = runCounter.get()

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Resets the per-task counters and starts the latency clock.
     *
     * An unfinished previous run is discarded with a warning rather than written with invented
     * values: a crash must not fabricate a data point.
     */
    fun startTask(
        goal: String,
        mode: AblationMode = this.mode,
        pass: StudyPass = this.studyPass,
        runId: String = "",
        featureMask: Int? = null
    ) {
        synchronized(lock) {
            active?.let {
                logger("RESEARCH_WARN discarding unfinished run for '${it.goal.take(60)}'")
            }
            active = ActiveRun(
                goal = goal.trim(),
                runId = runId,
                featureMask = featureMask,
                mode = mode,
                pass = pass,
                startElapsedMs = clock(),
                startedAtWallMs = System.currentTimeMillis()
            )
        }
        logger("RESEARCH_START mode=${mode.id} pass=${pass.id} goal=\"${goal.trim().take(120)}\"")
    }

    /** Increments the LLM HTTP request counter. Call once per outbound generation request. */
    fun recordLlmCall() {
        synchronized(lock) { active?.let { it.llmCalls++ } }
    }

    /** Increments the on-device generation counter (reported separately from [recordLlmCall]). */
    fun recordLocalInference() {
        synchronized(lock) { active?.let { it.localInferences++ } }
    }

    /** Increments the executed-step counter. Call once per dispatched step. */
    fun recordStep() {
        synchronized(lock) { active?.let { it.steps++ } }
    }

    /** Increments the tree read counter (M-1). */
    fun recordTreeRead() {
        synchronized(lock) {
            active?.let {
                it.treeReads++
            }
        }
    }

    /** Increments the screenshot bitmap capture counter (M-1). */
    fun recordBitmapCapture() {
        synchronized(lock) {
            active?.let {
                it.bitmaps++
                it.screenshots++
            }
        }
    }

    /** Increments the screenshot capture counter. Kept for backwards compatibility. */
    @Deprecated("Use recordTreeRead or recordBitmapCapture explicitly")
    fun recordScreenCapture() {
        recordTreeRead()
    }

    fun recordActionCompleted() = synchronized(lock) { active?.let { it.lastActionCompletedMs = clock() } }

    fun recordCompletionMessageShown(runId: String = "") = synchronized(lock) {
        val run = if (runId.isBlank()) active else closing[runId] ?: active?.takeIf { it.runId == runId }
        run?.lastActionCompletedMs?.let { run.messageDelayMs = (clock() - it).coerceAtLeast(0L) }
    }

    fun recordQuestionsBeforeStart(count: Int) = synchronized(lock) { active?.let { it.questionsBeforeStart = count.coerceAtLeast(0) } }

    fun recordFastSkippedStep() = synchronized(lock) { active?.let { it.fastSkippedSteps++ } }

    fun recordBurst(planned: Int, run: Int, suspended: Boolean) {
        synchronized(lock) {
            active?.let {
                it.burstsPlanned += planned
                it.burstMembersRun += run
                if (suspended) it.burstsSuspended = true
            }
        }
    }

    fun recordBurstPlanned() {
        synchronized(lock) { active?.let { it.burstsPlanned++ } }
    }

    fun recordBurstMemberRun() {
        synchronized(lock) { active?.let { it.burstMembersRun++ } }
    }

    fun recordBurstSuspended() {
        synchronized(lock) { active?.let { it.burstsSuspended = true } }
    }

    fun recordShortcutAttempt(attempted: Boolean, degraded: Boolean = false, reason: String = "") {
        synchronized(lock) {
            active?.let {
                it.shortcutAttempted = attempted
                if (degraded) it.shortcutDegraded = true
                if (reason.isNotBlank()) it.shortcutFailReason = reason
            }
        }
    }

    fun recordSkillUsed(name: String) {
        synchronized(lock) { active?.let { it.skillId = name } }
    }

    fun recordRememberReplay(actionCount: Int, validated: Boolean = false, diverged: Boolean = false) {
        synchronized(lock) {
            active?.let {
                it.rememberCacheHit = true
                it.rememberReplayActions += actionCount
                if (validated) it.replayValidated = true
                if (diverged) it.replayDiverged = true
            }
        }
    }

    fun recordReplayValidated() {
        synchronized(lock) { active?.let { it.replayValidated = true } }
    }

    fun recordReplayDiverged() {
        synchronized(lock) { active?.let { it.replayDiverged = true } }
    }

    fun recordFastpathStep() {
        synchronized(lock) { active?.let { it.fastpathSteps++ } }
    }

    fun setSetupLatency(ms: Long) {
        synchronized(lock) { active?.let { it.setupLatencyMs = ms } }
    }

    /** Adds prompt tokens to the current task. Call at each cloud/local generation site. */
    fun recordPromptTokens(n: Int) {
        synchronized(lock) { active?.let { it.promptTokens += n.coerceAtLeast(0) } }
    }

    /** Marks that the run was resolved by a direct intent shortcut rather than a model call. */
    fun markIntentShortcutUsed() {
        synchronized(lock) { active?.let { it.intentShortcutUsed = true } }
    }

    /** Marks that at least one step came from a remembered (FastPath) trajectory. */
    fun markRememberCacheHit() {
        synchronized(lock) { active?.let { it.rememberCacheHit = true } }
    }

    /**
     * Closes the run, appends it to `research_runs.csv`, rewrites `research_summary_table.csv` and
     * logs the summary. Returns the row that was written, or `null` when no task was active.
     */
    fun endTask(isSuccess: Boolean, failureReason: String = ""): ResearchRun? =
        endTask(if (isSuccess) ResearchOutcome.SUCCESS else ResearchOutcome.FAILED, failureReason)

    fun endTask(outcomeCategory: ResearchOutcome, failureReason: String = "", runId: String = ""): ResearchRun? {
        val isSuccess = outcomeCategory == ResearchOutcome.SUCCESS
        val run: ResearchRun
        val summaryRow: ResearchSummaryRow?
        synchronized(lock) {
            val current = if (runId.isNotBlank()) closing.remove(runId) ?: active?.takeIf { it.runId == runId } else active
            if (current == null) return null
            if (current === active) active = null
            val elapsedMs = ((current.endedElapsedMs ?: clock()) - current.startElapsedMs).coerceAtLeast(0L)
            run = ResearchRun(
                timestampMs = current.startedAtWallMs,
                mode = current.mode,
                goal = current.goal,
                latencySeconds = elapsedMs / 1000.0,
                llmCalls = current.llmCalls,
                localInferences = current.localInferences,
                steps = current.steps,
                success = isSuccess,
                failureReason = if (isSuccess) "" else failureReason.trim(),
                intentShortcutUsed = current.intentShortcutUsed,
                rememberCacheHit = current.rememberCacheHit,
                screenshots = current.screenshots,
                promptTokens = current.promptTokens,
                studyPass = current.pass,
                treeReads = current.treeReads,
                bitmaps = current.bitmaps,
                burstsPlanned = current.burstsPlanned,
                burstMembersRun = current.burstMembersRun,
                burstsSuspended = current.burstsSuspended,
                shortcutAttempted = current.shortcutAttempted,
                shortcutDegraded = current.shortcutDegraded,
                shortcutFailReason = current.shortcutFailReason,
                skillId = current.skillId,
                rememberReplayActions = current.rememberReplayActions,
                fastpathSteps = current.fastpathSteps,
                replayValidated = current.replayValidated,
                replayDiverged = current.replayDiverged,
                setupLatencyMs = current.setupLatencyMs,
                runId = current.runId,
                originalOutcomeCategory = outcomeCategory,
                outcomeCategory = if (current.userCorrected && outcomeCategory in setOf(ResearchOutcome.FAILED, ResearchOutcome.UNVERIFIED)) ResearchOutcome.SUCCESS else outcomeCategory,
                userCorrected = current.userCorrected && outcomeCategory in setOf(ResearchOutcome.FAILED, ResearchOutcome.UNVERIFIED),
                messageDelayMs = current.messageDelayMs,
                questionsBeforeStart = current.questionsBeforeStart,
                fastSkippedSteps = current.fastSkippedSteps,
                featureMask = current.featureMask
            )
            if (run.isCombination) saveCombination(run) else {
                store.append(run)
                store.rewriteSummary()
            }
            lastCompleted = run
            runCounter.incrementAndGet()
            summaryRow = ResearchSummaryTable.rowFor(store.read(), run.mode)
        }
        val line = if (run.isCombination) hudLine(run) else summaryRow?.hudLine() ?: fallbackHudLine(run)
        logger(line)
        logger(
            "TASK_END mode=${run.mode.id} pass=${run.studyPass.id} latency_s=${ResearchRun.formatLatency(run.latencySeconds)} " +
                "llm_calls=${run.llmCalls} local_inferences=${run.localInferences} steps=${run.steps} " +
                "screenshots=${run.screenshots} prompt_tokens=${run.promptTokens} " +
                "success=${run.success} intent_shortcut=${run.intentShortcutUsed} " +
                "feature_mask=${run.featureMask} remember_cache_hit=${run.rememberCacheHit} failure_reason=\"${run.failureReason.take(120)}\""
        )
        return run
    }

    /**
     * Safety net for abnormal exits (cancellation, crash, a thrown exception).
     *
     * A cancelled or stopped task has no verified outcome, so it is recorded as a failure with the
     * given reason instead of vanishing from the denominator. No-op when the run was already closed
     * by [endTask] on the normal path.
     */
    fun endTaskIfActive(failureReason: String, runId: String = ""): ResearchRun? {
        val stillActive = synchronized(lock) { active != null && (runId.isBlank() || active?.runId == runId) }
        if (!stillActive) return null
        return endTask(outcomeCategory = when (failureReason) {
            "stopped" -> ResearchOutcome.STOPPED
            "interrupted", "superseded" -> ResearchOutcome.INTERRUPTED
            else -> ResearchOutcome.FAILED
        }, failureReason = failureReason, runId = runId)
    }

    /**
     * Replaces the automatic verdict of the most recent run with a manual one.
     *
     * The row is rewritten in place (not appended) and flagged [ResearchRun.manuallyOverridden], so
     * the summary stays one-row-per-task and the paper can report how many verdicts were manual.
     * Returns `null` when a task is in flight or no runs exist.
     */
    fun overrideLastOutcome(isSuccess: Boolean): ResearchRun? {
        val updated: ResearchRun
        val summaryRow: ResearchSummaryRow?
        synchronized(lock) {
            if (active != null) {
                logger("RESEARCH_WARN override rejected while a task is running")
                return null
            }
            val combination = lastCompleted?.takeIf { it.isCombination }
            if (combination != null) {
                val changed = combination.copy(success = isSuccess, manuallyOverridden = true,
                    failureReason = if (isSuccess) "" else combination.failureReason.ifBlank { "Marked failed by researcher" })
                saveCombination(changed)
                lastCompleted = changed
                runCounter.incrementAndGet()
                return changed
            }
            val runs = store.read()
            if (runs.isEmpty()) return null
            val previous = runs.last()
            updated = previous.copy(
                success = isSuccess,
                manuallyOverridden = true,
                failureReason = if (isSuccess) {
                    ""
                } else {
                    previous.failureReason.ifBlank { "Marked failed by researcher" }
                }
            )
            store.rewrite(runs.dropLast(1) + updated)
            store.rewriteSummary()
            lastCompleted = updated
            runCounter.incrementAndGet()
            summaryRow = ResearchSummaryTable.rowFor(store.read(), updated.mode)
        }
        val line = summaryRow?.hudLine() ?: fallbackHudLine(updated)
        logger("RESEARCH_OVERRIDE success=${updated.success} mode=${updated.mode.id}")
        logger(line)
        return updated
    }

    /** Detach counters before publishing so a new task cannot steal background closure. No IO. */
    fun prepareBackgroundClosure(runId: String) = synchronized(lock) {
        active?.takeIf { it.runId == runId }?.let {
            it.endedElapsedMs = clock()
            closing[runId] = it
            active = null
        }
    }

    /** A user attestation targets a task ID, never the most recent row. */
    fun markUserCorrected(runId: String): ResearchRun? = synchronized(lock) {
        if (runId.isBlank()) return null
        active?.takeIf { it.runId == runId }?.let { it.userCorrected = true; return null }
        closing[runId]?.let { it.userCorrected = true; return null }
        val runs = store.read()
        val index = runs.indexOfFirst { it.runId == runId }
        if (index < 0) {
            val previous = combinations.find(runId)?.runCsv?.let(ResearchRun::fromCsvRow) ?: return null
            if (previous.originalOutcomeCategory !in setOf(ResearchOutcome.FAILED, ResearchOutcome.UNVERIFIED)) return null
            val updated = previous.copy(success = false, outcomeCategory = ResearchOutcome.SUCCESS, userCorrected = true)
            saveCombination(updated)
            if (lastCompleted?.runId == runId) lastCompleted = updated
            return updated
        }
        val previous = runs[index]
        if (previous.originalOutcomeCategory !in setOf(ResearchOutcome.FAILED, ResearchOutcome.UNVERIFIED)) return null
        val updated = previous.copy(success = false, outcomeCategory = ResearchOutcome.SUCCESS,
            userCorrected = true, manuallyOverridden = false)
        store.rewrite(runs.toMutableList().apply { this[index] = updated })
        store.rewriteSummary()
        if (lastCompleted?.runId == runId) lastCompleted = updated
        updated
    }

    /** Deletes both CSV files. Intended for "start a clean batch". */
    fun clearAll() {
        synchronized(lock) {
            active = null
            closing.clear()
            lastCompleted = null
            store.clear()
        }
        logger("RESEARCH_RESET all stored runs deleted")
    }

    // ---------------------------------------------------------------------------------------------
    // Read-side helpers (used by the HUD, the Stats card and tests)
    // ---------------------------------------------------------------------------------------------

    /** Last run of this process, or the last persisted row after a cold start. */
    fun lastRunOrNull(): ResearchRun? {
        lastCompleted?.let { return it }
        return synchronized(lock) { store.read().lastOrNull() }
    }

    /** Running averages for one mode, read from disk so a restart keeps the table. */
    fun summaryFor(mode: AblationMode): ResearchSummaryRow? =
        synchronized(lock) { ResearchSummaryTable.rowFor(store.read(), mode) }

    /** All running rows, in enum order. */
    fun summaryRows(): List<ResearchSummaryRow> =
        synchronized(lock) { ResearchSummaryTable.build(store.read()) }

    fun allRuns(): List<ResearchRun> = synchronized(lock) { store.read() }

    /** The exact research status line; always available even if the CSV write failed. */
    fun hudLine(run: ResearchRun): String = if (run.isCombination)
        "[${FeatureSelection(requireNotNull(run.featureMask)).key}] Saved separately to combination database" else
        summaryFor(run.mode)?.hudLine() ?: fallbackHudLine(run)

    fun runsFile(): File = store.runsFile()

    fun summaryFile(): File = store.summaryFile()

    private fun fallbackHudLine(run: ResearchRun): String = String.format(
        java.util.Locale.US,
        "[%s] Latency: %.2fs | LLM Calls: %d | Success Rate: %.1f%%",
        run.mode.id,
        run.latencySeconds,
        run.llmCalls,
        if (run.success) 100.0 else 0.0
    )

    companion object {
        const val TAG = "RESEARCH_METRICS"
        const val RUNS_FILE = ResearchRunStore.RUNS_FILE
        const val SUMMARY_FILE = ResearchRunStore.SUMMARY_FILE
        private const val PREFS_NAME = "aniob_research"
        private const val KEY_MODE = "ablation_mode"
        private const val KEY_STUDY_PASS = "study_pass"
    }
}
