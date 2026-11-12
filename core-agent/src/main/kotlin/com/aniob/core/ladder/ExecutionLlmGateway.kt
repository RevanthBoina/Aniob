package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction

/**
 * The one place that decides which model plans an execution step. Built once per task.
 *
 * Order, cheapest first:
 * 1. a deterministic lane (intent, reflex, replay) when the caller has one: no model call;
 * 2. ONE attempt on the on-device model;
 * 3. the cloud model, with exactly the request the on-device model was given.
 *
 * Once a step has had to go to the cloud, the task stays there ([stickyCloud]): a model that
 * could not plan one step of this task is not asked again on every following step, which would
 * double the latency of each of them. If the on-device model fails and no cloud is usable, the
 * step fails with a reason the user can act on. Nothing is invented on the model's behalf.
 *
 * Pure JVM. The actual model calls are supplied by the caller.
 */
class ExecutionLlmGateway(
    /** Whether the cloud may be used right now (configured, online, not local-only). */
    private val cloudAvailable: () -> Boolean
) {
    /** What a model is asked. The cloud receives the same instance the on-device model was given. */
    data class Request(
        val systemPrompt: String,
        val userPrompt: String,
        val screenshotBase64: String? = null
    )

    /** Result of the single on-device attempt. [usedFallback] means it produced no usable action. */
    data class LocalAttempt(val action: AniobAction, val usedFallback: Boolean, val reason: String = "")

    enum class Lane { DETERMINISTIC, LOCAL, CLOUD, NONE }

    data class Decision(
        val action: AniobAction,
        val lane: Lane,
        /** True when this step went to the cloud because the on-device attempt failed. */
        val escalated: Boolean = false
    )

    @Volatile
    var stickyCloud: Boolean = false
        private set

    @Volatile
    var localCalls: Int = 0
        private set

    @Volatile
    var cloudCalls: Int = 0
        private set

    suspend fun plan(
        request: Request,
        deterministic: suspend () -> AniobAction? = { null },
        /** Null when no on-device model can drive actions. */
        local: (suspend (Request) -> LocalAttempt)?,
        cloud: suspend (Request) -> AniobAction
    ): Decision {
        deterministic()?.let { return Decision(it, Lane.DETERMINISTIC) }

        var localFailure: String? = null
        // A task that moved to the cloud only comes back when the cloud is unusable right now.
        if ((!stickyCloud || !cloudAvailable()) && local != null) {
            localCalls++
            val attempt = local(request)
            if (!attempt.usedFallback) return Decision(attempt.action, Lane.LOCAL)
            localFailure = attempt.reason.ifBlank { "no usable action" }
        }

        if (!cloudAvailable()) {
            return Decision(AniobAction.Fail(noProviderReason(localFailure)), Lane.NONE)
        }
        val escalated = localFailure != null
        if (escalated) stickyCloud = true
        cloudCalls++
        return Decision(cloud(request), Lane.CLOUD, escalated = escalated)
    }

    companion object {
        /** Honest failure copy: says what was tried and what the user can change. */
        fun noProviderReason(localFailure: String?): String =
            if (localFailure != null) {
                "The on-device model could not plan this step ($localFailure) and Cloud AI is not available. " +
                    "Add a gateway key in Settings, check your connection, or install a more capable model in Models."
            } else {
                "No model is available to plan this step. Install an on-device model in Models or set up " +
                    "Cloud AI in Settings."
            }
    }
}
