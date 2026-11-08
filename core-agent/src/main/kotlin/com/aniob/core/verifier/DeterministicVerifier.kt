package com.aniob.core.verifier

import com.aniob.core.domain.*

/**
 * High-speed deterministic action verifier (<10ms, ZERO LLM calls).
 */
object DeterministicVerifier {

    data class VerificationResult(
        val isExpected: Boolean,
        val durationMs: Long,
        val reason: String,
        /** Backwards-compatible aliases (AIM pins use isExpected/reason as primary fields). */
        val isSuccessful: Boolean = isExpected,
        val explanation: String = reason,
        val textEvidence: String? = null,
        val evaluations: List<com.aniob.core.domain.CriterionEvaluation> = emptyList()
    )

    /** Shared verdict for every action whose only evidence is "the screen moved". */
    private fun effectOr(
        before: AniobScreenState?,
        after: AniobScreenState,
        pass: String,
        fail: String
    ): Pair<Boolean, String> = when {
        before == null -> true to pass
        changed(before, after) -> true to pass
        ScreenEffect.between(before, after).customSurface -> true to "Dispatched on a custom surface; effect not observable in the tree."
        else -> false to fail
    }

    /** Any of: tree hash, structural hash, package, keyboard, or content-keyed diff moved. */
    private fun changed(before: AniobScreenState, after: AniobScreenState): Boolean =
        before.treeHash != after.treeHash ||
            before.packageName != after.packageName ||
            (before.screenHash.isNotBlank() && after.screenHash.isNotBlank() && before.screenHash != after.screenHash) ||
            ScreenEffect.between(before, after).changed

    /**
     * Verifies that the previous action caused an expected state transition.
     */
    fun verify(
        action: AniobAction,
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState
    ): VerificationResult {
        val start = System.currentTimeMillis()

        // A Finish is PROVISIONAL — it carries no evidence, so it is never auto-successed.
        // The pipeline must call verifyFinish() with the task's SuccessCriteria before it may
        // report SUCCESS. Fail, however, is a genuine terminal failure.
        if (action is AniobAction.Finish) {
            return VerificationResult(false, System.currentTimeMillis() - start, "Finish is provisional: no evidence")
        }
        if (action is AniobAction.Fail) {
            return VerificationResult(false, System.currentTimeMillis() - start, action.reason)
        }

        if (screenBefore == null && action !is AniobAction.OpenApp && action !is AniobAction.InputText) {
            return VerificationResult(true, System.currentTimeMillis() - start, "Initial step verified.")
        }

        val result = when (action) {
            is AniobAction.Tap -> {
                if (screenBefore != null && changed(screenBefore, screenAfter)) {
                    true to "Tap confirmed: screen transitioned."
                } else if (screenBefore == null) {
                    true to "Tap acknowledged on initial screen."
                } else if (ScreenEffect.between(screenBefore, screenAfter).customSurface) {
                    // Canvas/WebView/video surfaces cannot prove a no-op: do not fail the step on silence.
                    true to "Tap dispatched on a custom surface; effect not observable in the tree."
                } else {
                    false to "No-effect: screen content unchanged after Tap on ${action.describeTarget()}."
                }
            }

            is AniobAction.LongPress -> effectOr(
                screenBefore, screenAfter,
                pass = "LongPress confirmed: UI state transitioned.",
                fail = "No-effect: screen content unchanged after LongPress for ${action.durationMs}ms."
            )

            is AniobAction.OpenApp -> {
                if (screenAfter.packageName.equals(action.packageName, ignoreCase = true)) {
                    true to "OpenApp confirmed: target package foregrounded."
                } else {
                    false to "OpenApp failed: expected package '${action.packageName}', but foreground was '${screenAfter.packageName}'."
                }
            }

            is AniobAction.InputText -> {
                val targetAfter = screenAfter.nodes.firstOrNull { action.target.matches(it) }
                val targetBefore = screenBefore?.nodes?.firstOrNull { action.target.matches(it) }
                val shiftedTargetAfter = if (targetAfter != null && targetAfter.text.contains(action.text, ignoreCase = true)) {
                    targetAfter
                } else if (targetBefore != null) {
                    // Handle dynamic SoM index shifts after typing (e.g., clear button or autocomplete rows inserted)
                    screenAfter.nodes.firstOrNull { cand ->
                        cand.isEditable &&
                            cand.text.contains(action.text, ignoreCase = true) &&
                            ((targetBefore.viewId.isNotBlank() && cand.viewId == targetBefore.viewId) ||
                                !cand.bounds.intersect(targetBefore.bounds).isEmpty)
                    }
                } else null

                val passwordConfirmed = (targetAfter?.isPassword == true || targetBefore?.isPassword == true) &&
                    screenBefore != null && screenBefore.treeHash != screenAfter.treeHash

                if (shiftedTargetAfter != null || passwordConfirmed) {
                    true to "InputText confirmed: text present in target node."
                } else {
                    false to "InputText failed: text '${action.text}' not found in target."
                }
            }

            is AniobAction.Swipe -> effectOr(
                screenBefore, screenAfter,
                pass = "Swipe confirmed: view content shifted.",
                fail = "Swipe had no effect (reached list boundary or gesture unrecognized)."
            )

            is AniobAction.SystemKey -> effectOr(
                screenBefore, screenAfter,
                pass = "System key press confirmed: screen or package changed.",
                fail = "System key press had no effect."
            )

            is AniobAction.PressKey -> effectOr(
                screenBefore, screenAfter,
                pass = "Key press confirmed: screen or package changed.",
                fail = "Key press had no effect."
            )

            is AniobAction.Wait -> effectOr(
                screenBefore, screenAfter,
                pass = "Wait confirmed: UI settled to a new state.",
                fail = "No-effect: screen content unchanged after Wait for ${action.durationMs}ms."
            )

            is AniobAction.ConfirmWithUser -> {
                true to "Confirmation gate handled."
            }

            else -> {
                true to "Action ${action.toolName} verified."
            }
        }

        val duration = System.currentTimeMillis() - start
        val textEvidence = when (action) {
            is AniobAction.OpenApp -> if (result.first) screenAfter.packageName else null
            is AniobAction.InputText -> if (result.first) action.text else null
            is AniobAction.Tap -> if (result.first && screenBefore != null && screenBefore.packageName != screenAfter.packageName) {
                "${screenBefore.packageName} -> ${screenAfter.packageName}"
            } else null
            else -> null
        }
        return VerificationResult(isExpected = result.first, durationMs = duration, reason = result.second, textEvidence = textEvidence)
    }

    /**
     * Decides whether a provisional [AniobAction.Finish] may become SUCCESS.
     *
     * This is the evidence gate: the task's [SuccessCriteria] must be satisfied against the final
     * screen before the loop is allowed to declare victory. An empty criteria set or minSteps-only
     * carries no evidence, so it is reported as unverified.
     */
    fun verifyFinish(
        criteria: SuccessCriteria?,
        finalScreen: AniobScreenState,
        steps: Int,
        device: DeviceStateSnapshot = DeviceStateSnapshot.UNKNOWN
    ): VerificationResult {
        val start = System.currentTimeMillis()
        if (criteria == null || criteria.isEmpty || !criteria.hasObservableCheck) {
            return VerificationResult(
                false,
                System.currentTimeMillis() - start,
                "No observable success criteria to verify against (minSteps alone is not evidence)"
            )
        }
        val evaluations = criteria.evaluateCriteria(finalScreen, steps, device)
        val nonPassed = evaluations.filter { it.state != com.aniob.core.domain.CriterionState.PASSED }
        val duration = System.currentTimeMillis() - start
        val passedEvidence = evaluations.filter { it.state == com.aniob.core.domain.CriterionState.PASSED && it.textEvidence != null }
            .joinToString("; ") { "${it.description}: ${it.textEvidence}" }
            .ifBlank { null }
        return if (nonPassed.isEmpty() && evaluations.isNotEmpty()) {
            VerificationResult(
                isExpected = true,
                durationMs = duration,
                reason = "Finish evidence satisfied: all criteria met",
                textEvidence = passedEvidence,
                evaluations = evaluations
            )
        } else {
            VerificationResult(
                isExpected = false,
                durationMs = duration,
                reason = "Unmet or inconclusive criteria: ${nonPassed.joinToString("; ") { "${it.description}: ${it.detail.ifBlank { it.state.name }}" }}",
                textEvidence = passedEvidence,
                evaluations = evaluations
            )
        }
    }
}
