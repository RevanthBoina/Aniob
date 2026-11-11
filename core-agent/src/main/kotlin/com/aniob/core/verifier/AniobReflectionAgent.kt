package com.aniob.core.verifier

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.exec.AniobErrorClass
import com.aniob.core.exec.AniobErrorTaxonomy

data class ReflectionResult(val isExpected: Boolean, val reason: String, val remedialAction: AniobAction? = null)

/**
 * Plan-3.6: Outcome of an on-demand reflection comparing declared expectations with actual screen transitions.
 */
data class OnDemandReflection(
    val matchedExpectation: Boolean,
    val explanation: String,
    val strategicHint: String? = null
)

class AniobReflectionAgent {
    fun reflect(
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState?,
        lastAction: AniobAction,
        verifiedSuccess: Boolean,
        errorClass: AniobErrorClass? = null
    ): ReflectionResult {
        if (screenBefore == null || screenAfter == null) return ReflectionResult(true, "No screen to compare")

        val detectedError = errorClass ?: when {
            screenBefore.treeHash == screenAfter.treeHash && !verifiedSuccess -> AniobErrorClass.NO_EFFECT
            screenAfter.nodes.isEmpty() -> AniobErrorClass.WRONG_SCREEN
            screenAfter.packageName != screenBefore.packageName && lastAction !is AniobAction.OpenApp -> AniobErrorClass.WRONG_SCREEN
            else -> null
        }

        if (detectedError != null) {
            val canRetry = AniobErrorTaxonomy.recordError(detectedError)
            if (!canRetry) {
                return ReflectionResult(
                    false,
                    "Circuit breaker: ${detectedError.name} failed ${AniobErrorTaxonomy.MAX_RETRIES_PER_CLASS} times",
                    AniobAction.Fail("Circuit breaker ${detectedError.name}")
                )
            }
            val handler = AniobErrorTaxonomy.getHandler(detectedError)
            val result = handler(detectedError)
            return ReflectionResult(false, "Deterministic handler for ${detectedError.name}: ${result.reason}", result.action)
        }
        return ReflectionResult(true, "Screen changed ${screenBefore.treeHash.take(6)} -> ${screenAfter.treeHash.take(6)} verified=$verifiedSuccess")
    }

    /**
     * Plan-3.6: On-demand post-execution reflection against declared expectation.
     * Evaluated when requested or when an anomaly is suspected, avoiding always-on token and latency overhead.
     */
    fun reflectOnDemand(
        expectedProofOfSuccess: String,
        screenBefore: AniobScreenState,
        screenAfter: AniobScreenState,
        action: AniobAction
    ): OnDemandReflection {
        val screenChanged = screenBefore.treeHash != screenAfter.treeHash

        // Check if expected screen change is visible or indicated in the post-action screen state
        // Folded, not lowercased: a Turkish-locale "İNSTALL" must still prove an "install" expectation.
        // An empty expectation proves nothing (String.contains("") is always true).
        val expectedClean = com.aniob.core.text.AniobTextFold.fold(expectedProofOfSuccess)
        val textMatches = expectedClean.isNotEmpty() && screenAfter.nodes.any { node ->
            com.aniob.core.text.AniobTextFold.fold(node.text).contains(expectedClean) ||
                com.aniob.core.text.AniobTextFold.fold(node.contentDescription).contains(expectedClean)
        }

        if (!screenChanged) {
            return OnDemandReflection(
                matchedExpectation = false,
                explanation = "Action '${action.describeAction()}' produced no detectable screen change.",
                strategicHint = "The previous action did not alter the screen. Verify if the element was responsive or requires an alternative input mode (e.g. scroll or keyboard enter)."
            )
        }

        if (expectedClean.isNotBlank() && !textMatches && expectedClean.length > 3) {
            return OnDemandReflection(
                matchedExpectation = false,
                explanation = "Expected proof '$expectedProofOfSuccess' was not found on the post-action screen.",
                strategicHint = "The screen changed, but expected evidence ('$expectedProofOfSuccess') is missing. Check if a loading state, modal, or unexpected view is displayed before continuing."
            )
        }

        return OnDemandReflection(
            matchedExpectation = true,
            explanation = "Outcome matched expected screen transition."
        )
    }

    /**
     * Judges a provisional `Finish`.
     */
    fun reflectFinish(
        criteria: com.aniob.core.domain.SuccessCriteria,
        finalScreen: AniobScreenState,
        steps: Int,
        deterministic: DeterministicVerifier.VerificationResult,
        hasExecutedActions: Boolean = false,
        hasDeviceEvidence: Boolean = false,
        llmJudge: (String) -> com.aniob.core.execution.ReflectionOutcome = {
            com.aniob.core.execution.ReflectionOutcome.confirmed("No judge evidence; accepting only as unverified")
        }
    ): com.aniob.core.execution.ReflectionOutcome {
        if (deterministic.isExpected) {
            return com.aniob.core.execution.ReflectionOutcome.confirmed(deterministic.reason)
        }
        if (criteria.isFullyCheckable) {
            return com.aniob.core.execution.ReflectionOutcome.rejected(
                deterministic.reason,
                "The screen does not satisfy the task criteria yet (${deterministic.reason})."
            )
        }
        if (hasDeviceEvidence) return com.aniob.core.execution.ReflectionOutcome.confirmed("Previous device command completed on dispatch")
        if (hasExecutedActions) return com.aniob.core.execution.ReflectionOutcome.confirmed(
            "Actions executed but no machine-checkable criteria exist; outcome unverified")
        return llmJudge(deterministic.reason)
    }
}
