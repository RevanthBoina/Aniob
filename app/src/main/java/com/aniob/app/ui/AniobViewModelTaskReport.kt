package com.aniob.app.ui

import com.aniob.core.intent.AniobTaskIntentClassifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic report over Aniob's own task database — the agent answering questions about its
 * own history without a model in the loop, so it can never invent a task that did not happen.
 *
 * Every line is read from `session_scores` (one row per prompt: status, steps, duration, tokens,
 * provider), `task_outcomes` (sealed outcome + the user's thumbs) and `audit_logs` (one row per
 * dispatched action: step index, action type, target package, verification verdict, payload).
 * Per-step latency and per-step token counts are NOT persisted (audit_logs carries no timing and no
 * usage), and the report says so instead of fabricating them.
 */
internal suspend fun AniobViewModel.buildTaskHistoryReport(
    query: AniobTaskIntentClassifier.TaskHistoryQuery
): String {
    val dao = app.database.sessionScoreDao()
    val limit = query.limit.coerceIn(1, 50)
    val scores = if (query.taskId != null) {
        dao.getByTaskId(query.taskId)?.let { listOf(it) } ?: emptyList()
    } else {
        dao.getLatest(limit)
    }
    if (scores.isEmpty()) {
        return if (query.taskId != null) "No task recorded with id ${query.taskId}."
        else "No tasks in Aniob's history yet."
    }

    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val out = StringBuilder()
    out.append("== Aniob task report — ")
        .append(if (query.taskId != null) "task ${query.taskId}" else "last ${scores.size} task(s)")
        .append(" ==\n")
    out.append("Source: Aniob's local database (session_scores + task_outcomes + audit_logs). ")
        .append("Per-step latency and per-step token counts are not persisted; only prompt totals are shown.\n")

    scores.forEachIndexed { index, score ->
        val sealed = runCatching { outcomeStore.read(score.taskId) }.getOrNull()
        val attested = sealed?.isUserAttested == true || score.decisionReason.contains("[user-attested]")
        val outcomeLabel = when {
            score.status == "SUCCESS" && attested -> "SUCCESS (confirmed by you — user-attested)"
            score.status == "SUCCESS" -> "SUCCESS"
            else -> "NOT SUCCESSFUL"
        }
        out.append("\n#${index + 1} ${score.taskId} — $outcomeLabel\n")
        out.append("Prompt: ${score.userPrompt}\n")
        out.append("When: ${fmt.format(Date(score.timestamp))} · Provider: ${score.providerUsed} · ")
        out.append("Steps: ${score.totalSteps} · Latency: ${score.durationMs} ms · ")
        out.append("Tokens (prompt): ${score.tokensUsed}\n")
        sealed?.let { out.append("Outcome: ${it.outcome} — ${it.reason.take(200)}")
            if (it.userDecision != null) out.append(" (your decision: ${it.userDecision})")
            out.append('\n') }
        val steps = runCatching { app.database.auditLogDao().getByTask(score.taskId) }
            .getOrDefault(emptyList())
        if (steps.isNotEmpty()) {
            out.append("Steps (audit log, ${steps.size} audited action(s)):\n")
            steps.forEach { step ->
                val status = step.verificationStatus
                val mark = when {
                    status.contains("VERIF", true) || status.contains("PASS", true) -> "ok"
                    status.contains("FAIL", true) -> "fail"
                    else -> "info"
                }
                out.append("  step ${step.stepIndex}: ${step.actionType}")
                if (step.targetPackage.isNotBlank()) out.append(" @ ${step.targetPackage}")
                out.append(" [$mark, $status] ${step.sanitizedPayload.take(140)}\n")
            }
            val last = steps.last()
            out.append("Last step: step ${last.stepIndex} — ${last.actionType} [${last.verificationStatus}]\n")
        } else {
            out.append("Steps: no audited actions recorded (a 0-step run)\n")
        }
    }
    return out.toString().trim()
}
