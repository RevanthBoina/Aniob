package com.aniob.core.ladder

import com.aniob.core.domain.ExecutionOutcomeStatus
import com.aniob.core.domain.TaskContext

data class TaskProgress(
    val completed: List<String>,
    val pending: List<String>,
    val summary: String,
    val failed: List<String> = emptyList(),
    val uncertain: List<String> = emptyList(),
    val notExecuted: List<String> = emptyList()
)

/**
 * Tracks real task progress strictly from verified post-execution outcomes.
 * Never assumes an attempted operation succeeded without execution evidence.
 */
class AniobPlanningAgent {
    fun recordExecution(
        userInstruction: String,
        actionDescription: String?,
        verifiedSuccess: Boolean,
        detail: String?,
        previousProgress: TaskProgress?,
        additionalInstructions: List<String> = emptyList()
    ): TaskProgress {
        val completed = previousProgress?.completed?.toMutableList() ?: mutableListOf()
        val failed = previousProgress?.failed?.toMutableList() ?: mutableListOf()
        val uncertain = previousProgress?.uncertain?.toMutableList() ?: mutableListOf()
        if (!actionDescription.isNullOrBlank()) {
            val detailSuffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            if (verifiedSuccess) {
                completed.add("$actionDescription$detailSuffix")
            } else {
                failed.add("$actionDescription$detailSuffix")
            }
        }
        val pending = buildList {
            add(userInstruction)
            additionalInstructions.filter { it.isNotBlank() }.forEach { add(it) }
        }
        val summary = buildString {
            append("Goal: $userInstruction")
            if (completed.isNotEmpty()) {
                append(" | Verified(${completed.size}): ${completed.takeLast(3).joinToString("; ")}")
            }
            if (failed.isNotEmpty()) {
                append(" | Failed(${failed.size}): ${failed.takeLast(2).joinToString("; ")}")
            }
            if (uncertain.isNotEmpty()) {
                append(" | Uncertain(${uncertain.size}): ${uncertain.takeLast(2).joinToString("; ")}")
            }
        }
        return TaskProgress(
            completed = completed,
            pending = pending,
            summary = summary,
            failed = failed,
            uncertain = uncertain
        )
    }

    fun summarizeExecution(taskContext: TaskContext): TaskProgress {
        val completed = taskContext.executionResults
            .filter { it.status == ExecutionOutcomeStatus.VERIFIED }
            .map { "${it.actionSummary}: ${it.detail}" }
        val failed = taskContext.executionResults
            .filter { it.status == ExecutionOutcomeStatus.FAILED }
            .map { "${it.actionSummary}: ${it.detail}" }
        val uncertain = taskContext.executionResults
            .filter {
                it.status == ExecutionOutcomeStatus.UNVERIFIED ||
                    it.status == ExecutionOutcomeStatus.UNCERTAIN
            }
            .map { "${it.actionSummary}: ${it.detail}" }
        val notExecuted = taskContext.executionResults
            .filter { it.status == ExecutionOutcomeStatus.NOT_EXECUTED }
            .map { "${it.actionSummary}: ${it.detail}" }
        val pending = buildList {
            add(taskContext.instruction)
            taskContext.additionalInstructions.filter { it.isNotBlank() }.forEach { add(it) }
        }
        val summary = buildString {
            append("Goal: ${taskContext.instruction}")
            if (completed.isNotEmpty()) {
                append(" | Verified(${completed.size}): ${completed.takeLast(3).joinToString("; ")}")
            }
            if (failed.isNotEmpty()) {
                append(" | Failed(${failed.size}): ${failed.takeLast(2).joinToString("; ")}")
            }
            if (uncertain.isNotEmpty()) {
                append(" | Uncertain(${uncertain.size}): ${uncertain.takeLast(2).joinToString("; ")}")
            }
            if (notExecuted.isNotEmpty()) {
                append(" | NotExecuted(${notExecuted.size}): ${notExecuted.takeLast(2).joinToString("; ")}")
            }
        }
        return TaskProgress(
            completed = completed,
            pending = pending,
            summary = summary,
            failed = failed,
            uncertain = uncertain,
            notExecuted = notExecuted
        )
    }
}
