package com.aniob.core.narration

/**
 * "Is the model thinking, or is the app stuck?" — one honest, always-visible answer.
 * Pure JVM; the Compose banner only renders what [ThinkingStatus] returns.
 */
enum class AgentPhase { IDLE, UNDERSTANDING, ASKING, PLANNING, STREAMING, ACTING, VERIFYING, DELEGATED, RATE_LIMITED }

/** RULES means no model was called for this step: say so instead of showing a fake "thinking". */
enum class ThinkSource { MODEL_CLOUD, MODEL_LOCAL, RULES }

data class PhaseSnapshot(
    val phase: AgentPhase = AgentPhase.IDLE,
    val source: ThinkSource = ThinkSource.RULES,
    val sinceMs: Long = 0L,
    /** Latest streamed `thought` text, already redacted by the caller. */
    val thoughtPreview: String = "",
    /** Wall-clock time a rate-limited request will be retried, when we really will retry. */
    val retryAtMs: Long? = null,
    val providerLabel: String = ""
)

object ThinkingStatus {
    const val STALL_AFTER_MS = 8_000L

    fun isModelBusy(s: PhaseSnapshot) =
        s.source != ThinkSource.RULES && (s.phase == AgentPhase.PLANNING || s.phase == AgentPhase.STREAMING)

    /** Waiting on a model with no first token yet, for too long. */
    fun isStalled(s: PhaseSnapshot, nowMs: Long): Boolean =
        s.phase == AgentPhase.PLANNING && s.source != ThinkSource.RULES && nowMs - s.sinceMs >= STALL_AFTER_MS

    fun badge(s: PhaseSnapshot): String = when (s.source) {
        ThinkSource.MODEL_CLOUD -> "Cloud model" + s.providerLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        ThinkSource.MODEL_LOCAL -> "On-device model"
        ThinkSource.RULES -> "Rules only — no model used"
    }

    fun headline(s: PhaseSnapshot, nowMs: Long): String {
        val secs = ((nowMs - s.sinceMs).coerceAtLeast(0L)) / 1000
        return when (s.phase) {
            AgentPhase.IDLE -> "Ready"
            AgentPhase.UNDERSTANDING -> "Reading your request…"
            AgentPhase.ASKING -> "Waiting for your answer"
            AgentPhase.PLANNING -> when {
                s.source == ThinkSource.RULES -> "Working it out with rules (no model)…"
                isStalled(s, nowMs) -> "Still waiting on the model… ${secs}s"
                else -> "Thinking… ${secs}s"
            }
            AgentPhase.STREAMING -> "Model is answering…"
            AgentPhase.ACTING -> "Doing it on your phone…"
            AgentPhase.VERIFYING -> "Checking that it worked…"
            AgentPhase.DELEGATED -> "Over to you — I can't do the last step"
            AgentPhase.RATE_LIMITED -> {
                val left = s.retryAtMs?.let { ((it - nowMs).coerceAtLeast(0L) + 999) / 1000 }
                if (left != null) "Rate limited — retrying in ${left}s" else "Rate limited — switching to another route"
            }
        }
    }
}
