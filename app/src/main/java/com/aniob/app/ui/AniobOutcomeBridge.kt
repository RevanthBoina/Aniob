package com.aniob.app.ui

import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.CriterionEvaluation
import com.aniob.core.domain.CriterionState
import com.aniob.core.outcome.EvidenceRef
import com.aniob.core.outcome.SealedOutcome

/**
 * Maps the legacy UI [Outcome] (SUCCESS/FAILED/STOPPED/INTERRUPTED/UNVERIFIED) onto the closed
 * [com.aniob.core.outcome.RunOutcome] set. SUCCESS only becomes VERIFIED when an observer-made evidence
 * item backs it; otherwise it is honestly UNVERIFIED, whatever the executor claimed.
 */
object AniobOutcomeBridge {

    fun seal(
        outcome: Outcome,
        reason: String,
        evaluations: List<CriterionEvaluation>,
        /** Observed-after-dispatch strings from DeviceControlResult.evidence, e.g. "media_session(Spotify): playing -> paused". */
        deviceEvidence: List<String> = emptyList(),
        delegatedToUser: Boolean = false
    ): SealedOutcome {
        // The executor prompt tells the model to FAIL with "USER_ACTION: ..." at a system dialog it cannot operate.
        val handoff = reason.trim().startsWith("USER_ACTION:", ignoreCase = true)
        if (delegatedToUser || handoff) {
            return SealedOutcome.delegated(reason.trim().removePrefix("USER_ACTION:").trim().ifBlank { "The last step needs you." })
        }
        return when (outcome) {
            Outcome.SUCCESS -> {
                // PASSED criteria are produced by probes/screen reads AFTER the action, not by the executor.
                val fromCriteria = evaluations
                    .filter { it.state == CriterionState.PASSED && it.detail.isNotBlank() }
                    .map { ev ->
                        val origin = if (ev.description.startsWith("Screen")) EvidenceRef.Origin.SCREEN_OBSERVATION else EvidenceRef.Origin.PROBE
                        EvidenceRef(origin, ev.description.take(60), ev.detail)
                    }
                val fromDevice = deviceEvidence.filter { it.isNotBlank() }.map { EvidenceRef(EvidenceRef.Origin.PROBE, it.substringBefore(':').take(60), it.substringAfter(':', it).trim()) }
                SealedOutcome.verifiedOrDowngrade(reason.ifBlank { "Verified" }, fromCriteria + fromDevice)
            }
            Outcome.UNVERIFIED -> SealedOutcome.unverified(reason.ifBlank { "No machine-checkable criteria" })
            Outcome.FAILED -> SealedOutcome.failed(reason.ifBlank { "Unmet criteria" })
            Outcome.STOPPED -> SealedOutcome.stopped("Stopped by you")
            Outcome.INTERRUPTED -> SealedOutcome.stopped("Interrupted")
        }
    }
}
