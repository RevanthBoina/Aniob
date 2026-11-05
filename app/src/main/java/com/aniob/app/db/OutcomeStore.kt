package com.aniob.app.db

import com.aniob.core.outcome.SealedOutcome

/** The only writer of `task_outcomes`. Accepts a [SealedOutcome], never a raw string, so the type system and the triggers agree. */
class OutcomeStore(private val dao: TaskOutcomeDao) {

    suspend fun record(taskId: String, outcome: SealedOutcome, nowMs: Long = System.currentTimeMillis()) {
        dao.upsert(
            TaskOutcomeEntity(
                taskId = taskId,
                outcome = outcome.outcome.name,
                reason = outcome.reason.take(400),
                evidenceRef = outcome.evidenceRef,
                userDecision = outcome.userDecision?.name,
                recordedAtMs = nowMs
            )
        )
    }

    /** Re-validated on read: a VERIFIED row with no evidence comes back as UNVERIFIED. */
    suspend fun read(taskId: String): SealedOutcome? = dao.get(taskId)?.let {
        SealedOutcome.fromStored(it.outcome, it.reason, it.evidenceRef, it.userDecision)
    }
}
