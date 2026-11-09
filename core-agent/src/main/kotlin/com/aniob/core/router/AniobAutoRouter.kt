package com.aniob.core.router

import com.aniob.core.providers.AniobModelFiles

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.tools.AniobBatteryHealth
import com.aniob.core.tools.DevicePowerState
import java.io.File

enum class RouteTarget {
    FASTPATH,
    INTENT,
    SKILL,
    LOCAL_SLM,
    OMNIROUTE_CLOUD,
    EXTERNAL_AI_QUERY,
    CANNOT_PROCEED
}

data class RouteDecision(
    val target: RouteTarget,
    val reason: String,
    val requiresVision: Boolean = false,
    val modelId: String? = null
)

/**
 * World-Class AutoRouter:
 * - Honors strict user routing choices: local-only forbids cloud; cloud-only forbids local.
 * - Local-First default for on-device SLMs (e.g. Phi-4 Mini 3.8B, Llama 3.2 3B).
 * - Cloud escalation only when truly necessary in auto mode (low battery <15%, repeated local failures, or explicit vision needs).
 * - Validates local model file existence without mock fallbacks.
 */
object AniobAutoRouter {

    const val PROVIDER_OMNIROUTE = "OMNIROUTE_CLOUD"
    const val PROVIDER_LOCAL = "LOCAL_SLM"
    const val PROVIDER_FASTPATH = "FASTPATH"
    const val PROVIDER_SKILL = "SKILL"
    const val PROVIDER_INTENT = "INTENT"
    const val PROVIDER_EXTERNAL_AI = "EXTERNAL_AI_QUERY"
    const val PROVIDER_CANNOT_PROCEED = "CANNOT_PROCEED"

    fun decideRoute(
        taskPrompt: String,
        screenState: AniobScreenState,
        powerState: DevicePowerState,
        hasFastPathHit: Boolean = false,
        isIntentShortcut: Boolean = false,
        installedModelId: String? = null,
        lastLocalFailCount: Int = 0,
        modelsDir: File? = null,
        isModelFileMissing: Boolean = false,
        userRoutingMode: String = "auto",
        /**
         * Per-task provider lock set by the caller once real work has run for the task.
         * Honored only in auto/local-first/balanced, only between [RouteTarget.LOCAL_SLM] and
         * [RouteTarget.OMNIROUTE_CLOUD], and never against strict policy, offline, battery,
         * thermal, missing-model or vision constraints. Kills local<->cloud ping-pong when both
         * per-step heuristics disagree even though one provider is making real progress.
         */
        taskStickyTarget: RouteTarget? = null
    ): RouteDecision {
        // 1. Intent Shortcut (0ms)
        if (isIntentShortcut) {
            return RouteDecision(
                target = RouteTarget.INTENT,
                reason = "Intent Shortcut matched: Zero-latency system dispatch",
                requiresVision = false
            )
        }

        // 2. FastPath Cache Hit (0 tokens)
        if (hasFastPathHit) {
            return RouteDecision(
                target = RouteTarget.FASTPATH,
                reason = "FastPath trajectory hit in AppKnowledgeBase: 0 tokens",
                requiresVision = false
            )
        }

        val mode = userRoutingMode.lowercase()

        // Strict user policy: local-only forbids cloud under ALL circumstances
        if (mode == "local-only") {
            val modelMissing = isModelFileMissing || AniobModelFiles.isMissing(modelsDir, installedModelId)
            if (modelMissing) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Local-only mode active: local model is missing or not installed",
                    requiresVision = false,
                    modelId = installedModelId
                )
            }
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Local-only policy enforced: cloud offload strictly forbidden",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // Strict user policy: cloud-only forbids local SLM
        if (mode == "cloud-only") {
            if (!powerState.isNetworkAvailable) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Cloud-only mode active: network is offline",
                    requiresVision = false
                )
            }
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Cloud-only policy enforced: local SLM strictly bypassed",
                requiresVision = false
            )
        }

        // 3. Offline constraint -> Must use local SLM
        if (!powerState.isNetworkAvailable) {
            val modelMissing = isModelFileMissing || AniobModelFiles.isMissing(modelsDir, installedModelId)
            if (modelMissing) {
                return RouteDecision(
                    target = RouteTarget.CANNOT_PROCEED,
                    reason = "Offline and local model missing: cannot automate without model",
                    requiresVision = false
                )
            }
            return RouteDecision(
                target = RouteTarget.LOCAL_SLM,
                reason = "Offline -> local ${installedModelId ?: "SLM"}",
                requiresVision = false,
                modelId = installedModelId
            )
        }

        // 4. Battery / Thermal Check (spinal reflex: battery low -> offload, no LLM)
        if (AniobBatteryHealth.isLowBattery(powerState)) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Battery low reflex: ${powerState.batteryPercent}% not charging -> cloud",
                requiresVision = false
            )
        }
        if (powerState.isThermalThrottled) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Device thermal throttled -> offload to cloud",
                requiresVision = false
            )
        }

        // 5. Check installed model actually exists
        val modelMissing = isModelFileMissing || AniobModelFiles.isMissing(modelsDir, installedModelId)
        if (modelMissing) {
            return RouteDecision(
                target = RouteTarget.OMNIROUTE_CLOUD,
                reason = "Local model file missing or not installed, using Omniroute",
                requiresVision = false
            )
        }

        // 5b. Per-task provider lock (sticky). Applied only after strict policy, offline, battery,
        // thermal and model-existence checks — those always win. Vision still wins too (checked next).
        if (mode in setOf("auto", "local-first", "balanced") &&
            (taskStickyTarget == RouteTarget.LOCAL_SLM || taskStickyTarget == RouteTarget.OMNIROUTE_CLOUD)
        ) {
            // Vision cannot be served by the local model: a stuck LOCAL lock must not pin a
            // screen that genuinely needs cloud vision. The vision check below still runs for that.
            val unlabelledPeek = screenState.nodes.count { it.isClickable && it.text.isBlank() && it.contentDescription.isBlank() }
            val canvasPeek = screenState.nodes.any { it.className.contains("Canvas") || it.className.contains("SurfaceView") }
            val taskNeedsVisionPeek = taskPrompt.contains("icon", true) || taskPrompt.contains("image", true) ||
                taskPrompt.contains("canvas", true) || taskPrompt.contains("picture", true)
            val stickyNeedsVision = (unlabelledPeek >= 5) || (unlabelledPeek >= 2 && taskNeedsVisionPeek) ||
                (canvasPeek && taskNeedsVisionPeek)
            if (taskStickyTarget == RouteTarget.OMNIROUTE_CLOUD || !stickyNeedsVision) {
                val target = taskStickyTarget!!
                return RouteDecision(
                    target = target,
                    reason = if (target == RouteTarget.LOCAL_SLM) {
                        "Sticky local: task already progressing on-device -> stay local"
                    } else {
                        "Sticky cloud: task already escalated to cloud -> stay on cloud"
                    },
                    requiresVision = false,
                    modelId = if (target == RouteTarget.LOCAL_SLM) installedModelId else null
                )
            }
        }

        // FIXED: Less aggressive vision - was (unlabelled>=2 && taskNeedsVision) || (unlabelled>=5 && taskNeedsVision) redundant
        val unlabelled = screenState.nodes.count { it.isClickable && it.text.isBlank() && it.contentDescription.isBlank() }
        val hasCanvas = screenState.nodes.any { it.className.contains("Canvas") || it.className.contains("SurfaceView") }
        val taskNeedsVision = taskPrompt.contains("icon", true) || taskPrompt.contains("image", true) || taskPrompt.contains("canvas", true) || taskPrompt.contains("picture", true)
        val needsVision = (unlabelled >= 5) || (unlabelled >= 2 && taskNeedsVision) || (hasCanvas && taskNeedsVision)
        if (needsVision) {
            return RouteDecision(RouteTarget.OMNIROUTE_CLOUD, "Vision needed unlabelled $unlabelled canvas $hasCanvas -> cloud vision", true)
        }

        val textRatio = if (screenState.nodes.isNotEmpty()) screenState.nodes.count { it.text.isNotBlank() }.toFloat() / screenState.nodes.size else 0f
        val isSimple = screenState.nodes.isNotEmpty() && screenState.nodes.size <= 40 && textRatio >= 0.6f
        if (isSimple) {
            return RouteDecision(RouteTarget.LOCAL_SLM, "Simple text nav ${screenState.nodes.size} nodes ratio $textRatio -> local ${installedModelId ?: "SLM"}", false, installedModelId)
        }

        if (lastLocalFailCount >= 2) {
            return RouteDecision(RouteTarget.OMNIROUTE_CLOUD, "Local failed $lastLocalFailCount times -> escalate to cloud")
        }

        // Multi-hop / High UI Entropy complexity escalation check
        val screenEntropy = computeScreenEntropy(screenState)
        val isMultiHopCrossApp = isMultiHopCrossAppPrompt(taskPrompt)
        if (isMultiHopCrossApp && screenEntropy >= 0.82f && screenState.nodes.size > 45) {
            return RouteDecision(
                RouteTarget.OMNIROUTE_CLOUD,
                "High complexity multi-hop prompt with dense UI entropy (${"%.2f".format(screenEntropy)}) -> escalate to cloud",
                requiresVision = false
            )
        }

        if (mode == "balanced" && screenState.nodes.size > 40) {
            return RouteDecision(
                RouteTarget.OMNIROUTE_CLOUD,
                "Balanced policy: complex screen (${screenState.nodes.size} nodes) -> escalate to Omniroute cloud",
                requiresVision = false
            )
        }

        return RouteDecision(RouteTarget.LOCAL_SLM, "Default local-first for 8GB RAM phones, model ${installedModelId ?: "SLM"}", false, installedModelId)
    }

    /**
     * Normalized Shannon class-diversity & density entropy in [0.0, 1.0].
     */
    internal fun computeScreenEntropy(screenState: AniobScreenState): Float {
        val nodes = screenState.nodes
        if (nodes.size <= 1) return 0f
        val counts = nodes.groupingBy { it.className }.eachCount()
        val n = nodes.size.toDouble()
        var entropy = 0.0
        for (c in counts.values) {
            val p = c.toDouble() / n
            if (p > 0.0) entropy -= p * kotlin.math.ln(p)
        }
        val maxEntropy = kotlin.math.ln(n).coerceAtLeast(1.0)
        return (entropy / maxEntropy).toFloat().coerceIn(0f, 1f)
    }

    private fun isMultiHopCrossAppPrompt(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val conjunctions = listOf(" and then ", " then send ", " copy ", " forward to ", " compare ")
        return conjunctions.count { lower.contains(it) } >= 2
    }
}
