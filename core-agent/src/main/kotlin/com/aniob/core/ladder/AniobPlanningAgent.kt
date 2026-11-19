package com.aniob.core.ladder

import com.aniob.core.domain.TaskContext

data class TaskProgress(
    val completed: List<String>,
    val pending: List<String>,
    val summary: String,
    val failed: List<String> = emptyList()
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
        }
        return TaskProgress(
            completed = completed,
            pending = pending,
            summary = summary,
            failed = failed
        )
    }

    fun summarizeExecution(taskContext: TaskContext): TaskProgress {
        val completed = taskContext.executionResults
            .filter { it.success }
            .map { "${it.actionSummary}: ${it.detail}" }
        val failed = taskContext.executionResults
            .filter { !it.success }
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
        }
        return TaskProgress(
            completed = completed,
            pending = pending,
            summary = summary,
            failed = failed
        )
    }
}
