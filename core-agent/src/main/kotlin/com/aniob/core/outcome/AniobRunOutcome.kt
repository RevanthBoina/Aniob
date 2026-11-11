package com.aniob.core.outcome

import com.aniob.core.verification.Evidence
import com.aniob.core.verification.EvidenceKind

/**
 * Step 1 — the invariant that makes false success structurally impossible.
 *
 * The executor emits ACTIONS. Only an independent observer (a probe, or a fresh screen observation)
 * emits EVIDENCE. [EvidenceRef.Origin] deliberately has no "EXECUTOR" member, so there is no way to
 * write "I did it, therefore it worked" into a [SealedOutcome].
 *
 * The same rule is enforced a second time in SQLite (see `OutcomeGuard` in :app), so a future code path
 * that bypasses this file still cannot persist VERIFIED without an evidence reference.
 */
enum class RunOutcome {
    /** A conversational answer. Asserts nothing about the device. */
    ANSWERED,

    /** The user must perform the last step (system dialog, biometric, payment). Not a success, not a failure. */
    DELEGATED,

    /** An observer saw the post-condition. Requires evidence. */
    VERIFIED,

    /** Dispatched, but nothing observable confirms it. */
    UNVERIFIED,

    FAILED,
    STOPPED
}

enum class UserDecision(val emoji: String) { YES("\uD83D\uDC4D"), NO("\uD83D\uDC4E") }

data class EvidenceRef(
    val origin: Origin,
    /** e.g. "probe_media", "package_manager", "screen#14", "user" */
    val source: String,
    /** What was actually observed, not a paraphrase. */
    val excerpt: String,
    val atStep: Int? = null
) {
    enum class Origin { PROBE, SCREEN_OBSERVATION, USER_ATTESTATION }

    val isConcrete: Boolean get() = source.isNotBlank() && excerpt.isNotBlank()

    fun encode(): String =
        listOf(origin.name, source.clean(), excerpt.clean(200), atStep?.toString().orEmpty()).joinToString("|")

    private fun String.clean(limit: Int = 80) = replace('|', '/').replace('\n', ' ').trim().take(limit)

    companion object {
        fun decode(s: String): EvidenceRef? {
            val p = s.split('|')
            if (p.size < 3) return null
            val origin = Origin.values().firstOrNull { it.name == p[0] } ?: return null
            return EvidenceRef(origin, p[1], p[2], p.getOrNull(3)?.toIntOrNull()).takeIf { it.isConcrete }
        }

        /**
         * Only observer-made evidence converts. `RECORDED_STEP` is the executor's own step log and
         * `NOTE_EXCERPT` is text the agent wrote itself: neither may prove anything.
         */
        fun from(e: Evidence): EvidenceRef? = when (e.kind) {
            EvidenceKind.PROBE_OUTPUT -> EvidenceRef(Origin.PROBE, e.source, e.excerpt, e.atStep)
            EvidenceKind.SCREEN_OBSERVATION -> EvidenceRef(Origin.SCREEN_OBSERVATION, e.source, e.excerpt, e.atStep)
            EvidenceKind.NOTE_EXCERPT, EvidenceKind.RECORDED_STEP -> null
        }?.takeIf { it.isConcrete }
    }
}

/** Constructed only through the factories below; [verified] cannot be reached without concrete evidence. */
class SealedOutcome private constructor(
    val outcome: RunOutcome,
    val reason: String,
    val evidence: List<EvidenceRef>,
    val userDecision: UserDecision? = null
) {
    /** The value persisted in `task_outcomes.evidenceRef`; null exactly when there is no evidence. */
    val evidenceRef: String? get() = evidence.joinToString("\n") { it.encode() }.ifEmpty { null }

    val isUserAttested: Boolean get() = evidence.any { it.origin == EvidenceRef.Origin.USER_ATTESTATION }

    /** Counts toward "verified by the device", i.e. not merely "the user said so". */
    val isMachineVerified: Boolean get() = outcome == RunOutcome.VERIFIED && evidence.any { it.origin != EvidenceRef.Origin.USER_ATTESTATION }

    /**
     * The user's thumbs. YES on an unconfirmed result attaches a USER_ATTESTATION and promotes it (shown
     * as "confirmed by you", never counted as machine-verified). NO on anything that claimed success demotes it to FAILED.
     */
    fun withUserDecision(decision: UserDecision, atMs: Long = 0L): SealedOutcome = when {
        decision == UserDecision.YES && (outcome == RunOutcome.UNVERIFIED || outcome == RunOutcome.DELEGATED || outcome == RunOutcome.FAILED) ->
            SealedOutcome(
                RunOutcome.VERIFIED, reason,
                evidence + EvidenceRef(EvidenceRef.Origin.USER_ATTESTATION, "user", "thumbs-up at $atMs"),
                decision
            )
        decision == UserDecision.NO &&
            (outcome == RunOutcome.VERIFIED || outcome == RunOutcome.UNVERIFIED || outcome == RunOutcome.DELEGATED) ->
            SealedOutcome(RunOutcome.FAILED, "You reported this did not work. Was: $outcome. $reason".take(300), evidence, decision)
        else -> SealedOutcome(outcome, reason, evidence, decision)
    }

    companion object {
        fun answered(reason: String) = SealedOutcome(RunOutcome.ANSWERED, reason, emptyList())
        fun delegated(whatUserMustDo: String) = SealedOutcome(RunOutcome.DELEGATED, whatUserMustDo, emptyList())
        fun unverified(reason: String) = SealedOutcome(RunOutcome.UNVERIFIED, reason, emptyList())
        fun failed(reason: String) = SealedOutcome(RunOutcome.FAILED, reason, emptyList())
        fun stopped(reason: String) = SealedOutcome(RunOutcome.STOPPED, reason, emptyList())

        /** Throws when there is nothing concrete to attach: a bug in the caller, never a runtime "success". */
        fun verified(reason: String, evidence: List<EvidenceRef>): SealedOutcome {
            val concrete = evidence.filter { it.isConcrete }
            require(concrete.isNotEmpty()) { "VERIFIED requires at least one concrete evidence reference" }
            return SealedOutcome(RunOutcome.VERIFIED, reason, concrete)
        }

        /** Never throws: the call-site helper. No evidence means UNVERIFIED, whatever the executor claimed. */
        fun verifiedOrDowngrade(reason: String, evidence: List<EvidenceRef>): SealedOutcome =
            if (evidence.any { it.isConcrete }) verified(reason, evidence) else unverified(reason.ifBlank { "no observable evidence" })

        /** Re-validates on read, so a row written by an older build or by hand cannot resurrect a false VERIFIED. */
        fun fromStored(outcome: String, reason: String, evidenceRef: String?, decision: String?): SealedOutcome? {
            val o = RunOutcome.values().firstOrNull { it.name == outcome } ?: return null
            val ev = evidenceRef.orEmpty().lines().mapNotNull { EvidenceRef.decode(it) }
            val d = UserDecision.values().firstOrNull { it.name == decision }
            return when (o) {
                RunOutcome.VERIFIED -> if (ev.isEmpty()) SealedOutcome(RunOutcome.UNVERIFIED, "stored VERIFIED had no evidence: $reason", emptyList(), d) else SealedOutcome(o, reason, ev, d)
                else -> SealedOutcome(o, reason, ev, d)
            }
        }
    }
}
