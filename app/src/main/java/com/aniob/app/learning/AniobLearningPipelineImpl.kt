package com.aniob.app.learning

import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.AppKnowledgeBaseEntity
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext
import com.aniob.core.learning.LearningPipeline
import com.aniob.core.tools.AniobFingerprint
import com.aniob.core.tools.AniobReplayEngine
import com.aniob.core.tools.AniobTrajectoryCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single learning pipeline for the APK (finding #8, track D core).
 *
 * Five learning stores used to accumulate knowledge independently. This implementation is
 * stage-owned and reuses the Engine + Room that already exist:
 *
 * ```
 * verified trajectory -> fastpath entry (now)
 *                     -> distill candidacy (signature match count, >= 3)
 * ```
 *
 * Only verified outcomes are recorded; a failure can never become a fastpath entry.
 */
class AniobLearningPipelineImpl(
    private val database: AniobDatabase,
    private val replayEngine: AniobReplayEngine,
    /** Called when a signature reaches the distillation threshold; PROMPT D promotes the draft. */
    private val onDistillCandidate: (taskSignature: String, trajectory: List<AniobAction>) -> Unit = { _, _ -> }
) : LearningPipeline {

    /** Per-task rehearsal buffer: the screen fingerprint observed *before* each verified step. */
    private val rehearsal = ConcurrentHashMap<String, CopyOnWriteArrayList<ReplayPoint>>()
    private val signatureCounts = ConcurrentHashMap<String, Int>()
    private val hydrationMutex = Mutex()
    @Volatile private var isHydrated: Boolean = false
    private val hydrationDeferred = CompletableDeferred<Int>()

    private data class ReplayPoint(
        val action: AniobAction,
        val screenFingerprint: String
    )

    override fun onVerifiedStep(ctx: TaskContext, action: AniobAction, screen: AniobScreenState) {
        val key = ctx.taskId.ifBlank { ctx.instruction.lowercase() }
        rehearsal.computeIfAbsent(key) { CopyOnWriteArrayList() }
            .add(ReplayPoint(action, AniobFingerprint.computeScreenFingerprint(screen)))
    }

    override fun onTaskSuccess(ctx: TaskContext, trajectory: List<AniobAction>) {
        val key = ctx.taskId.ifBlank { ctx.instruction.lowercase() }
        val signature = ctx.instruction.lowercase()
        val points = rehearsal.remove(key)?.toList().orEmpty()
        rehearsal.remove(key.lowercase())
        if (points.isEmpty()) return

        // Invariant: Unverified outcomes cannot enter success-only learning.
        if (ctx.successCriteria == null || !ctx.successCriteria.hasObservableCheck) {
            return
        }

        // 1. FastPath: persist an executable, fingerprinted trajectory so a cold start replays it
        //    with zero model tokens.
        val replayTrajectory = AniobReplayEngine.ReplayTrajectory(
            taskSignature = signature,
            packageName = ctx.lastScreenState?.packageName ?: "",
            steps = points.map { AniobReplayEngine.ReplayStep(it.screenFingerprint, it.action) }
        )
        replayEngine.registerTrajectory(replayTrajectory)

        val inMemCount = signatureCounts.merge(signature, 1, Int::plus) ?: 1
        val persistedCount = runCatching {
            kotlinx.coroutines.runBlocking {
                val existing = database.appKnowledgeBaseDao().findBySignature(signature)
                val nextCount = maxOf(inMemCount, (existing?.successCount ?: 0) + 1)
                database.appKnowledgeBaseDao().saveMacro(
                    AppKnowledgeBaseEntity(
                        taskSignature = signature,
                        packageName = replayTrajectory.packageName,
                        macroStepsJson = AniobTrajectoryCodec.encode(replayTrajectory),
                        successCount = nextCount
                    )
                )
                nextCount
            }
        }.getOrDefault(inMemCount)

        val count = maxOf(inMemCount, persistedCount)
        signatureCounts[signature] = count

        // 2. Distill candidacy: three matching verified runs are the threshold for a skill draft.
        if (count >= DISTILL_THRESHOLD) {
            onDistillCandidate(signature, points.map { it.action })
        }
    }

    override fun onUserAttestedTask(ctx: TaskContext, trajectory: List<AniobAction>) {
        if (trajectory.isEmpty()) return
        // Retain the corrected example without inventing pre-action fingerprints or replacing
        // an existing machine-verified macro. It remains reviewable user-attested knowledge.
        kotlinx.coroutines.runBlocking {
            database.appKnowledgeBaseDao().saveMacro(AppKnowledgeBaseEntity(
                taskSignature = ctx.instruction.lowercase() + "#user_attested",
                packageName = ctx.lastScreenState?.packageName.orEmpty(), macroStepsJson = "",
                successCount = 0, kind = "USER_ATTESTED", reviewState = "USER_ATTESTATION",
                artifactJson = com.aniob.core.auth.AniobAuthJson.toJson(linkedMapOf(
                    "provenance" to "USER_ATTESTATION", "taskId" to ctx.taskId,
                    "actions" to trajectory.map { com.aniob.core.domain.AniobActionSchema.toActionJson(it) }
                ))
            ))
        }
    }

    override fun onTaskEnd(taskId: String) {
        endTask(taskId)
    }

    /** Rehearsal is per-task working memory; it is cleared once the task has been persisted or terminated. */
    fun endTask(key: String) {
        rehearsal.remove(key)
        rehearsal.remove(key.lowercase())
    }

    /** Loads every persisted trajectory back into the replay engine. Returns how many loaded. */
    suspend fun hydrate(): Int = hydrationMutex.withLock {
        if (isHydrated) return lastHydratedCount
        val entries = try {
            database.appKnowledgeBaseDao().getAllKnowledgeOnce()
        } catch (_: Exception) {
            emptyList()
        }
        var loaded = 0
        entries.forEach { entry ->
            AniobTrajectoryCodec.decode(entry.macroStepsJson).forEach {
                replayEngine.registerTrajectory(it)
                loaded++
            }
        }
        lastHydratedCount = loaded
        isHydrated = true
        if (!hydrationDeferred.isCompleted) {
            hydrationDeferred.complete(loaded)
        }
        return loaded
    }

    /** Suspend barrier ensuring Room trajectories are loaded before FastPath lookup. */
    suspend fun awaitHydration(timeoutMs: Long = 2000L): Int {
        if (isHydrated) return lastHydratedCount
        return withTimeoutOrNull(timeoutMs) {
            hydrationDeferred.await()
        } ?: lastHydratedCount
    }

    /** Number of trajectories restored by the most recent [hydrate]. */
    var lastHydratedCount: Int = 0
        private set

    companion object {
        const val DISTILL_THRESHOLD = 3
    }
}