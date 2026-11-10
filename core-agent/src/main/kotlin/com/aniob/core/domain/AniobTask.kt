package com.aniob.core.domain

/**
 * Task lifecycle state and history.
 */
data class AniobTask(
    val id: String,
    val rawPrompt: String,
    val clarifiedGoal: String = rawPrompt,
    val grillAnswers: Map<String, String> = emptyMap(),
    val status: TaskStatus = TaskStatus.PENDING,
    val steps: List<AniobStepRecord> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val providerUsed: String = "UNDECIDED",
    /** Populated after Grill-Me, before step 0. Null means "no deterministic finish evidence". */
    val successCriteria: SuccessCriteria? = null
) {
    val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
    val totalTokens: Int get() = steps.sumOf { it.tokensUsed }
}

enum class TaskStatus {
    PENDING,
    GRILLING,
    RUNNING,
    SUCCESS,
    FAILED,
    ABORTED
}

data class AniobStepRecord(
    val stepIndex: Int,
    val screenHash: String,
    val action: AniobAction,
    val provider: String,
    val latencyMs: Long,
    val tokensUsed: Int = 0,
    val verifiedSuccess: Boolean = true,
    val failureReason: String? = null,
    val reflectorInvoked: Boolean = false,
    val textEvidence: String? = null,
    val targetBounds: AniobRect? = null,
    val imageArtifactPath: String? = null
)

data class AniobPlan(
    val taskId: String,
    val goal: String,
    val estimatedSteps: Int,
    val strategySummary: String
)
