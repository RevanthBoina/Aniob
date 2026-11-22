package com.aniob.core.verifier

import com.aniob.core.domain.*
import com.aniob.core.execution.AniobActionExecutor

/**
 * High-speed deterministic action verifier (<10ms, ZERO LLM calls).
 */
object DeterministicVerifier {

    enum class Status { VERIFIED, UNCONFIRMED, FAILED }
    enum class EvidenceCategory { NONE, INPUT_FOCUS, IME_VISIBLE, ACCESSIBILITY_EVENT, TARGET_STATE,
        SCREEN_TRANSITION, FOREGROUND_PACKAGE, INPUT_TEXT, TASK_CRITERIA, OTHER }
    enum class FailureKind { TARGET_MISSING, TARGET_UNAVAILABLE, DISPATCH_REJECTED, NO_PROGRESS }

    data class VerificationResult(
        val isExpected: Boolean,
        val durationMs: Long,
        val reason: String,
        /** Backwards-compatible aliases (AIM pins use isExpected/reason as primary fields). */
        val isSuccessful: Boolean = isExpected,
        val explanation: String = reason,
        val textEvidence: String? = null,
        val evaluations: List<com.aniob.core.domain.CriterionEvaluation> = emptyList(),
        val status: Status = if (isExpected) Status.VERIFIED else Status.FAILED,
        val failureKind: FailureKind? = null,
        val evidenceCategory: EvidenceCategory = EvidenceCategory.NONE
    )

    /**
     * Verifies that the previous action caused an expected state transition.
     */
    fun verify(
        action: AniobAction,
        screenBefore: AniobScreenState?,
        screenAfter: AniobScreenState,
        dispatched: Boolean = true,
        resolvedNode: AniobNode? = null
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

        if (action is AniobAction.Tap) {
            val target = resolvedNode ?: screenBefore?.let {
                AniobActionExecutor.resolveTarget(action, it)?.node ?: TapEvidence.unavailableTarget(action.target, it)
            }
            return verifyTap(screenBefore, screenAfter, target, dispatched, start)
        }
        if (!dispatched) return VerificationResult(false, System.currentTimeMillis() - start,
            "Android did not accept dispatch", failureKind = FailureKind.DISPATCH_REJECTED)
        // A device adapter accepting a command is not an observation of its effects. A later
        // task-level verifier may use an independent device probe or fresh screen as evidence.
        if (action is AniobAction.DeviceControl) return VerificationResult(
            false, System.currentTimeMillis() - start, "Device command dispatched; effect not independently verified",
            status = Status.UNCONFIRMED
        )

        if (screenBefore == null && action !is AniobAction.OpenApp && action !is AniobAction.InputText) {
            return VerificationResult(true, System.currentTimeMillis() - start, "Initial step verified.")
        }

        val result = when (action) {
            is AniobAction.LongPress -> {
                if (screenBefore != null && screenBefore.treeHash != screenAfter.treeHash) {
                    true to "LongPress confirmed: UI state transitioned."
                } else {
                    false to "No-effect: treeHash unchanged after LongPress for ${action.durationMs}ms."
                }
            }

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

            is AniobAction.Swipe -> {
                if (screenBefore != null && screenBefore.treeHash != screenAfter.treeHash) {
                    true to "Swipe confirmed: view content shifted."
                } else {
                    false to "Swipe had no effect (reached list boundary or gesture unrecognized)."
                }
            }

            is AniobAction.SystemKey -> {
                if (screenBefore != null && (screenBefore.treeHash != screenAfter.treeHash || screenBefore.packageName != screenAfter.packageName)) {
                    true to "System key press confirmed: screen or package changed."
                } else {
                    false to "System key press had no effect."
                }
            }

            is AniobAction.PressKey -> {
                if (screenBefore != null && (screenBefore.treeHash != screenAfter.treeHash || screenBefore.packageName != screenAfter.packageName)) {
                    true to "Key press confirmed: screen or package changed."
                } else {
                    false to "Key press had no effect."
                }
            }

            is AniobAction.Wait -> {
                if (screenBefore != null && screenBefore.treeHash != screenAfter.treeHash) {
                    true to "Wait confirmed: UI settled to a new state."
                } else {
                    false to "No-effect: treeHash unchanged after Wait for ${action.durationMs}ms."
                }
            }

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
            else -> null
        }
        val category = if (!result.first) EvidenceCategory.NONE else when (action) {
            is AniobAction.OpenApp -> EvidenceCategory.FOREGROUND_PACKAGE
            is AniobAction.InputText -> EvidenceCategory.INPUT_TEXT
            is AniobAction.LongPress, is AniobAction.Swipe, is AniobAction.SystemKey,
            is AniobAction.PressKey, is AniobAction.Wait -> EvidenceCategory.SCREEN_TRANSITION
            else -> EvidenceCategory.OTHER
        }
        return VerificationResult(isExpected = result.first, durationMs = duration, reason = result.second,
            textEvidence = textEvidence, evidenceCategory = category)
    }

    private fun verifyTap(
        before: AniobScreenState?,
        after: AniobScreenState,
        target: AniobNode?,
        dispatched: Boolean,
        start: Long
    ): VerificationResult {
        fun result(status: Status, reason: String, failure: FailureKind? = null, evidence: String? = null,
            category: EvidenceCategory = EvidenceCategory.NONE) =
            VerificationResult(status == Status.VERIFIED, System.currentTimeMillis() - start, reason,
                textEvidence = evidence, status = status, failureKind = failure, evidenceCategory = category)

        if (before == null || target == null || target !in before.nodes) return result(Status.FAILED,
            "Tap target not found on the pre-dispatch screen", FailureKind.TARGET_MISSING)
        if (!TapEvidence.isActionable(target, before)) return result(Status.FAILED,
            "Tap target unavailable or not actionable before dispatch", FailureKind.TARGET_UNAVAILABLE)
        if (!dispatched) return result(Status.FAILED,
            "Android did not accept tap dispatch", FailureKind.DISPATCH_REJECTED)

        val current = TapEvidence.findTarget(after.nodes, target)
        val interaction = TapEvidence.interaction(before, after, target, current)
        if (interaction != null) return result(Status.VERIFIED, "Tap confirmed: ${interaction.detail}.", category = interaction.category)
        if (TapEvidence.screenTransition(before, after)) return result(Status.VERIFIED,
            "Tap confirmed: screen transitioned.", evidence = if (before.packageName != after.packageName)
                "${before.packageName} -> ${after.packageName}" else null, category = EvidenceCategory.SCREEN_TRANSITION)

        // A partial observation can omit a dispatched control; absence after dispatch is not a
        // grounding miss and does not prove the gesture failed (or that it should be replayed).
        return result(Status.UNCONFIRMED, "Tap dispatched; no conclusive interaction evidence. Recheck the screen before choosing the next action.")
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
                evaluations = evaluations,
                evidenceCategory = EvidenceCategory.TASK_CRITERIA
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
