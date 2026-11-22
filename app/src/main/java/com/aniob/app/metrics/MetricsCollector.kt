package com.aniob.app.metrics

import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.SessionScoreEntity
import kotlinx.coroutines.flow.Flow

data class AggregateMetrics(
    val totalTasks: Int = 0,
    val successRatePct: Float = 0.0f,
    val totalTokensSavedFastPath: Int = 0,
    val avgLatencyMs: Long = 0L,
    val cloudCallsCount: Int = 0,
    val localSlmCallsCount: Int = 0,
    val fastPathHitsCount: Int = 0,
    val intentHitsCount: Int = 0
)

class MetricsCollector(
    private val database: AniobDatabase,
    /**
     * Task ids the user attested with a thumbs-up while the run was still finishing. When the
     * session row lands after the thumbs-up (the thumbs-up UPDATE would hit 0 rows), the collector
     * flips the freshly inserted row instead of losing the attestation.
     */
    val pendingAttestations: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
) {

    fun getSessionScores(): Flow<List<SessionScoreEntity>> {
        return database.sessionScoreDao().getAllScores()
    }

    /** After the row lands, honour a thumbs-up that arrived before the insert. */
    private suspend fun flipIfAttested(taskId: String) {
        if (pendingAttestations.remove(taskId)) {
            runCatching { database.sessionScoreDao().markUserAttestedSuccess(taskId) }
        }
    }

    suspend fun recordSession(
        taskId: String,
        prompt: String,
        isSuccess: Boolean,
        steps: Int,
        durationMs: Long,
        tokensUsed: Int,
        providerUsed: String,
        decisionReason: String
    ): Long {
        val entity = SessionScoreEntity(
            taskId = taskId,
            userPrompt = prompt,
            status = if (isSuccess) "SUCCESS" else "FAILED",
            totalSteps = steps,
            durationMs = durationMs,
            tokensUsed = tokensUsed,
            providerUsed = providerUsed,
            decisionReason = decisionReason
        )
        val id = database.sessionScoreDao().insertScore(entity)
        flipIfAttested(taskId)
        return id
    }

    suspend fun recordWorkflowOutcome(
        taskId: String,
        prompt: String,
        result: com.aniob.core.workflow.WorkflowResult,
        durationMs: Long = 0L
    ): Long {
        val (status, decisionReason) = when (result) {
            is com.aniob.core.workflow.WorkflowResult.CompletedWithEvidence -> "SUCCESS" to "Completed: ${result.evidenceKind}"
            is com.aniob.core.workflow.WorkflowResult.UserReportedCompletion -> "SUCCESS" to "User marked complete"
            is com.aniob.core.workflow.WorkflowResult.Failed -> "FAILED" to result.errorReason
            is com.aniob.core.workflow.WorkflowResult.Cancelled -> "CANCELLED" to result.reason
            is com.aniob.core.workflow.WorkflowResult.LaunchAccepted -> "WAITING" to "Launch accepted"
            is com.aniob.core.workflow.WorkflowResult.WaitingForUser -> "WAITING" to result.reason
            is com.aniob.core.workflow.WorkflowResult.EffectUnknown -> "UNKNOWN" to result.details
        }

        if (status == "WAITING" || status == "UNKNOWN") {
            return -1L
        }

        val entity = SessionScoreEntity(
            taskId = taskId,
            userPrompt = prompt,
            status = status,
            totalSteps = 1,
            durationMs = durationMs,
            tokensUsed = 0,
            providerUsed = "NATIVE_WORKFLOW",
            decisionReason = decisionReason
        )
        val id = database.sessionScoreDao().insertScore(entity)
        flipIfAttested(taskId)
        return id
    }
}
