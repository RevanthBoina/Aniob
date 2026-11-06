package com.aniob.app.metrics

import android.util.Log
import com.aniob.app.db.AniobDatabase
import com.aniob.app.db.LogEventEntity
import com.aniob.core.domain.AniobAction
import com.aniob.core.logging.AniobXLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Append-only log/audit wrapper plus a BrowserUse-style structured trajectory stream.
 * PRIVACY: trajectory [TrajectoryStep] fields carry categorical tokens / action summaries
 * only — never the raw user utterance (mirrors AniobXLog's mandate).
 */
class EventLogger(private val database: AniobDatabase) {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** BrowserUse-style append-only trajectory for the current task (in-memory, privacy-safe). */
    data class TrajectoryStep(
        val stepIndex: Int,
        val observation: String,
        val thought: String,
        val action: AniobAction,
        val latencyMs: Long,
        val screenHash: String,
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val textEvidence: String? = null,
        val targetBounds: com.aniob.core.domain.AniobRect? = null
    )

    companion object {
        const val MAX_TRAJECTORY_CAPACITY = 50
        private const val PRUNE_INTERVAL = 100
        private const val MAX_PERSISTED_LOGS = 1000
    }

    private val trajectory = ArrayDeque<TrajectoryStep>(MAX_TRAJECTORY_CAPACITY + 1)
    private val insertCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)
    fun debug(tag: String, message: String) = log("DEBUG", tag, message)

    @Synchronized
    fun logStep(step: TrajectoryStep) {
        if (trajectory.size >= MAX_TRAJECTORY_CAPACITY && trajectory.isNotEmpty()) {
            trajectory.removeFirst()
        }
        trajectory.addLast(step)
        // Privacy: never store raw user text. Sensitive gates are referenced only via
        // categorical audit entries (stepIndex + targetPackage), not the raw message.
        if (step.action is AniobAction.ConfirmWithUser) {
            AniobXLog.recordEvent(AniobXLog.SecurityEvent.CONFIRMATION_GATE_TRIGGERED, stepIndex = step.stepIndex)
        }
    }

    @Synchronized
    fun snapshotTrajectory(): List<TrajectoryStep> = ArrayList(trajectory)

    @Synchronized
    fun clearTrajectory() {
        trajectory.clear()
    }

    /**
     * Appends a terminal session-counter summary through the existing persistence path so it
     * lands in `event_logs` (categorical counters only — no user text).
     */
    @Synchronized
    fun recordSessionCounters(
        screenReads: Int,
        actions: Int,
        escalations: Int,
        elapsedMs: Long,
        stateTrace: List<String>,
        providerDecisionReason: String,
        lastRoutingReason: String
    ) {
        info(
            "AniobMetrics",
            "SESSION_COUNTERS{" +
                "screenReads=$screenReads," +
                "actions=$actions," +
                "escalations=$escalations," +
                "elapsedMs=$elapsedMs," +
                "stateTrace=${stateTrace.takeLast(12).joinToString("->")}," +
                "decisionReason=$providerDecisionReason," +
                "lastRoutingReason=$lastRoutingReason" +
                "}"
        )
    }

    private fun log(level: String, tag: String, message: String) {
        val sanitized = sanitize(message)
        when (level) {
            "WARN" -> Log.w(tag, sanitized)
            "ERROR" -> Log.e(tag, sanitized)
            "DEBUG" -> Log.d(tag, sanitized)
            else -> Log.i(tag, sanitized)
        }
        scope.launch {
            try {
                database.logEventDao().insertLog(
                    LogEventEntity(
                        level = level,
                        tag = tag,
                        message = sanitized
                    )
                )
                if (insertCount.incrementAndGet() % PRUNE_INTERVAL == 0) {
                    database.logEventDao().pruneOldLogs(MAX_PERSISTED_LOGS)
                }
            } catch (e: Exception) {
                Log.e("EventLogger", "Failed to persist log: ${e.message}")
            }
        }
    }

    internal fun sanitize(raw: String): String {
        var s = raw
        // Redact Authorization headers and Bearer tokens
        s = BEARER_REGEX.replace(s, "Bearer [REDACTED]")
        // Redact standard API key patterns (sk-..., or key=...)
        s = API_KEY_REGEX.replace(s, "$1[REDACTED]")
        // Redact common secret key assignments
        s = SECRET_ASSIGN_REGEX.replace(s, "$1=[REDACTED]")
        // Sign-in credentials: JWT-shaped strings, code=/refresh_token= parameters, token JSON fields.
        s = com.aniob.core.auth.AniobSecretRedactor.redact(s)
        return s
    }

    private val BEARER_REGEX = Regex("(?i)bearer\\s+[a-zA-Z0-9_\\-\\.]+")
    private val API_KEY_REGEX = Regex("(?i)(sk-[a-zA-Z0-9_\\-]{10,})")
    private val SECRET_ASSIGN_REGEX = Regex("(?i)(api_?key|auth_?token|secret|password|otp|pin)=[^\\s,;]+")
}
