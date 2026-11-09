package com.aniob.core.execution

import java.util.Base64
import java.util.LinkedHashMap

/**
 * The ONE authority that performs a device action on behalf of an agent.
 *
 * Why this exists. A single user command ("Open Play Store") used to be able to reach the phone
 * through several uncoordinated pathways: the accessibility service's `OPEN_APP` handler, the
 * ViewModel's `dispatchSystemIntent`, the device-command layer, the session-recovery cold start and
 * the workflow launcher. None of them shared an execution identifier, a lock, or a result record, so
 * duplicated dispatches (and OEM default-handler fallbacks) were invisible until two apps were open.
 *
 * [DeviceActionGateway] centralises the execution of those pathways behind one object:
 *
 *  - **Execution locking** — the *decision* (inspect ledger, claim execution id, record PENDING →
 *    EXECUTING) happens under one monitor; the [dispatch] itself runs **outside** the lock, so a
 *    slow dispatch (a PackageManager scan, an 8 MB file read, a system intent that cold-starts
 *    another app) never blocks unrelated actions or the calling thread behind a global monitor.
 *    Exactly-once is preserved by claiming the execution id atomically before dispatching: an
 *    identical concurrent request is rejected as [DeviceActionState.DUPLICATE] (outcome unknown),
 *    never queued behind the first.
 *  - **Intent deduplication** — a second call with the same signature inside [duplicateWindowMs] is
 *    dropped and reported as [DeviceActionState.DUPLICATE]; the outcome cites the original record
 *    and reuses it only when the original ended COMPLETED.
 *  - **Idempotency, across process death** — a caller that supplies an `executionId` and retries
 *    after a crash or a lost callback gets the recorded outcome, never a second dispatch. When a
 *    [ledgerStore] is provided the bounded ledger is persisted on every mutation and restored on
 *    first use, so execution ids and the 5-minute ledger survive process death.
 *  - **Execution state tracking** — every request is recorded as PENDING → EXECUTING →
 *    COMPLETED | FAILED (or DUPLICATE when rejected). On restore, records left in PENDING or
 *    EXECUTING by a dead process become [DeviceActionState.INTERRUPTED] and are never reused as
 *    proof of success.
 *  - **Controlled retries** — a signature whose last record is COMPLETED in the same task is served
 *    from the ledger instead of re-firing; FAILED or INTERRUPTED predecessors count towards a
 *    genuine, counted retry. Retrying because confirmation is delayed is impossible: the lock is
 *    held only for the (fast) decision and finalization, and in-flight duplicates are refused.
 *  - **Centralized logging** — every state transition is emitted to [logSink]; the app layer wires
 *    that sink to the event logger so one query shows which agent fired which action on which app.
 *
 * Pure Kotlin JVM: the Android layer passes lambdas, so the whole contract is unit-testable.
 */
class DeviceActionGateway(
    /** Window in which an identical signature anywhere is a duplicate double-dispatch (ms). */
    private val duplicateWindowMs: Long = DEFAULT_DUPLICATE_WINDOW_MS,
    /** Window in which an identical signature in the same task is served from the ledger (ms). */
    private val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
    /** Bounded ledger; oldest entries fall off the back. */
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logSink: (DeviceActionLogEntry) -> Unit = {},
    /** Optional durable ledger for cross-process idempotency. App layer provides a file impl. */
    private val ledgerStore: DeviceActionLedgerStore? = null
) {

    enum class DeviceActionState { PENDING, EXECUTING, COMPLETED, FAILED, DUPLICATE, INTERRUPTED }

    /** Durable snapshot port for the bounded ledger. Implemented by the app (filesDir). */
    interface DeviceActionLedgerStore {
        /** Raw snapshot previously written by [save], or null when absent/unreadable. */
        fun load(): String?
        /** Atomically persist [snapshot]. Failures are swallowed; never break dispatch over it. */
        fun save(snapshot: String)
    }

    /** One structured record per execution. [state] is the terminal state once [execute] returns. */
    data class DeviceActionRecord(
        val executionId: String,
        val taskId: String?,
        val originAgent: String,
        val signature: String,
        /** What the user asked for, e.g. "open_app" or "system_intent:android.intent.action.VIEW". */
        val requestedAction: String,
        /** The package the action resolved to, e.g. "com.android.vending", or null. */
        val resolvedApplication: String?,
        val state: DeviceActionState,
        val detail: String,
        val createdAtMs: Long,
        val finishedAtMs: Long?,
        /** 1 for the first attempt of a signature, 2+ after FAILED/INTERRUPTED records. */
        val attempt: Int = 1,
        /** True when the call returned the ledger's previous outcome without dispatching again. */
        val reusedPreviousResult: Boolean = false
    ) {
        val durationMs: Long? get() = finishedAtMs?.let { it - createdAtMs }
    }

    /** One line of the centralized diagnostic log. */
    data class DeviceActionLogEntry(
        val timestampMs: Long,
        val executionId: String,
        val taskId: String?,
        val originAgent: String,
        val signature: String,
        val requestedAction: String,
        val resolvedApplication: String?,
        val state: DeviceActionState,
        val detail: String
    )

    /** What the action lambda reports back; the gateway never re-interprets it. */
    data class DispatchResult(val ok: Boolean, val detail: String = "")

    /** The outcome of [execute]; [record] is the authoritative ledger entry. */
    data class DeviceActionOutcome(
        val ok: Boolean,
        /** True when the call returned the ledger's previous outcome without dispatching again. */
        val deduplicated: Boolean,
        val record: DeviceActionRecord
    )

    private val lock = Any()

    /** Insertion-ordered, bounded ledger. Keyed by executionId. */
    private val records = object : LinkedHashMap<String, DeviceActionRecord>(maxRecords + 16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeviceActionRecord>?): Boolean =
            size > maxRecords
    }

    /** Set once the persisted ledger has been read (first lock entry). */
    @Volatile
    private var restored = false

    /**
     * Performs [dispatch] at most once per [executionId], and never while an identical signature
     * is in flight or has just completed. The [dispatch] lambda runs **outside** the internal
     * lock; the lock is held only for ledger decisions and finalization.
     *
     * @param executionId caller-supplied idempotency key. Retrying with the same id after a lost
     *   callback (or process death, when a [ledgerStore] is configured) returns the recorded
     *   outcome instead of dispatching a second time. When null, one is generated and returned
     *   through the outcome for correlation.
     * @param taskId the task that asked for this action. Repeated dispatches of the same signature
     *   inside one task are served from the ledger for [repeatWindowMs].
     * @param originAgent which agent/ pathway asked ("LOCAL_SLM", "OMNIROUTE_CLOUD", "INTENT",
     *   "WORKFLOW", "ACCESSIBILITY", "VIEWMODEL"…). Recorded verbatim for the audit trail.
     * @param signature stable action identity: `kind:target[:extra]`. Two calls with the same
     *   signature are the *same* action for locking/dedup purposes.
     * @param requestedAction human-readable action name for logs ("open_app", "open_url"…).
     * @param resolvedApplication the resolved package, when known. Recorded, never guessed.
     * @param dispatch the actual effect. Called at most once, never while the lock is held.
     */
    fun execute(
        executionId: String? = null,
        taskId: String?,
        originAgent: String,
        signature: String,
        requestedAction: String,
        resolvedApplication: String? = null,
        dispatch: () -> DispatchResult
    ): DeviceActionOutcome {
        // ---- Phase 1 (under the lock): decide — record or reuse, never dispatch here. ----
        val id = executionId?.takeIf { it.isNotBlank() }
        val decisionNow = clock()
        val decision: Decision = synchronized(lock) {
            restoreIfNeededLocked()
            val now = decisionNow
            purgeLocked(now)

            // 1. Idempotency: the same execution identifier never dispatches twice.
            val resolvedId = id ?: newExecutionId()
            records[resolvedId]?.let { existing ->
                log(existing, DeviceActionState.DUPLICATE, "idempotent replay suppressed (state=${existing.state})")
                return@synchronized Decision.Finished(
                    DeviceActionOutcome(
                        ok = existing.state == DeviceActionState.COMPLETED,
                        deduplicated = true,
                        record = existing.copy(reusedPreviousResult = true)
                    )
                )
            }

            // 2. Duplicate suppression while an identical signature is in flight or just fired.
            val recentSameSignature = records.values.filter { it.signature == signature }
            val inFlight = recentSameSignature.firstOrNull {
                it.state == DeviceActionState.PENDING || it.state == DeviceActionState.EXECUTING
            }
            if (inFlight != null) {
                val record = recordLocked(
                    executionId = resolvedId, taskId = taskId, originAgent = originAgent,
                    signature = signature, requestedAction = requestedAction,
                    resolvedApplication = resolvedApplication, state = DeviceActionState.DUPLICATE,
                    detail = "suppressed: identical action ${inFlight.executionId} is still in flight (outcome unknown)",
                    attempt = 1, now = now, reusedPreviousResult = false
                )
                log(record, DeviceActionState.DUPLICATE, record.detail)
                persistLocked()
                return@synchronized Decision.Finished(DeviceActionOutcome(ok = false, deduplicated = true, record = record))
            }
            // Only a COMPLETED predecessor inside the duplicate window is ever treated as success.
            val justFired = recentSameSignature.firstOrNull {
                it.state == DeviceActionState.COMPLETED && now - it.createdAtMs < duplicateWindowMs
            }
            if (justFired != null) {
                val record = recordLocked(
                    executionId = resolvedId, taskId = taskId, originAgent = originAgent,
                    signature = signature, requestedAction = requestedAction,
                    resolvedApplication = resolvedApplication, state = DeviceActionState.DUPLICATE,
                    detail = "suppressed: identical action ${justFired.executionId} already COMPLETED; not re-dispatched",
                    attempt = 1, now = now, reusedPreviousResult = true
                )
                log(record, DeviceActionState.DUPLICATE, record.detail)
                persistLocked()
                return@synchronized Decision.Finished(DeviceActionOutcome(ok = true, deduplicated = true, record = record))
            }

            // 3. Same task, same *command*, recently COMPLETED: serve from the ledger instead of
            // re-firing. Launch signatures are excluded on purpose: opening an app twice in one
            // task can be a real request; a re-issued device command is almost always a retry loop.
            if (taskId != null && signature.startsWith("device_command:")) {
                val sameTaskCompleted = recentSameSignature.lastOrNull {
                    it.taskId == taskId && it.state == DeviceActionState.COMPLETED &&
                        now - it.createdAtMs < repeatWindowMs
                }
                if (sameTaskCompleted != null) {
                    val record = recordLocked(
                        executionId = resolvedId, taskId = taskId, originAgent = originAgent,
                        signature = signature, requestedAction = requestedAction,
                        resolvedApplication = resolvedApplication, state = DeviceActionState.COMPLETED,
                        detail = "reused outcome of ${sameTaskCompleted.executionId} (completed ${now - sameTaskCompleted.createdAtMs}ms ago)",
                        attempt = 1, now = now, finishedAtMs = now, reusedPreviousResult = true
                    )
                    log(record, DeviceActionState.COMPLETED, record.detail)
                    persistLocked()
                    return@synchronized Decision.Finished(DeviceActionOutcome(ok = true, deduplicated = true, record = record))
                }
            }

            // 4. Claim the execution: PENDING -> EXECUTING. A FAILED/INTERRUPTED predecessor means
            // this is a genuine, counted retry.
            val priorAttempts = recentSameSignature.count {
                it.state == DeviceActionState.FAILED || it.state == DeviceActionState.INTERRUPTED
            }
            val attempt = priorAttempts + 1
            val pending = recordLocked(
                executionId = resolvedId, taskId = taskId, originAgent = originAgent,
                signature = signature, requestedAction = requestedAction,
                resolvedApplication = resolvedApplication, state = DeviceActionState.PENDING,
                detail = "accepted", attempt = attempt, now = now
            )
            log(pending, DeviceActionState.PENDING, "accepted")
            val executing = pending.copy(state = DeviceActionState.EXECUTING)
            records[resolvedId] = executing
            log(executing, DeviceActionState.EXECUTING, "dispatching")
            persistLocked()
            Decision.Accepted(executing)
        }
        val executing = when (decision) {
            is Decision.Finished -> return decision.outcome
            is Decision.Accepted -> decision.record
        }

        // ---- Phase 2 (outside the lock): the actual effect. Never under the monitor. ----
        val result: DispatchResult = try {
            dispatch()
        } catch (t: Throwable) {
            DispatchResult(false, "dispatch threw: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }

        // ---- Phase 3 (under the lock): finalize. ----
        return synchronized(lock) {
            val finished = clock()
            val terminal = executing.copy(
                state = if (result.ok) DeviceActionState.COMPLETED else DeviceActionState.FAILED,
                detail = result.detail.ifBlank { if (result.ok) "dispatched" else "dispatch failed" },
                finishedAtMs = finished
            )
            records[terminal.executionId] = terminal
            log(terminal, terminal.state, terminal.detail)
            persistLocked()
            DeviceActionOutcome(ok = result.ok, deduplicated = false, record = terminal)
        }
    }

    /** Snapshot of the bounded ledger, newest last. For diagnostics and tests. */
    fun snapshot(): List<DeviceActionRecord> = synchronized(lock) {
        restoreIfNeededLocked()
        records.values.toList()
    }

    /** Forget everything (tests; the app never calls this mid-task). */
    fun clear() = synchronized(lock) {
        records.clear()
        persistLocked()
    }

    // ------------------------------------------------------------------------------------------

    /** Internal result of the decide phase: either an early outcome, or a claimed execution. */
    private sealed interface Decision {
        data class Finished(val outcome: DeviceActionOutcome) : Decision
        data class Accepted(val record: DeviceActionRecord) : Decision
    }

    private fun recordLocked(
        executionId: String,
        taskId: String?,
        originAgent: String,
        signature: String,
        requestedAction: String,
        resolvedApplication: String?,
        state: DeviceActionState,
        detail: String,
        attempt: Int,
        now: Long,
        finishedAtMs: Long? = null,
        reusedPreviousResult: Boolean = false
    ): DeviceActionRecord {
        val record = DeviceActionRecord(
            executionId = executionId,
            taskId = taskId,
            originAgent = originAgent,
            signature = signature,
            requestedAction = requestedAction,
            resolvedApplication = resolvedApplication,
            state = state,
            detail = detail,
            createdAtMs = now,
            finishedAtMs = finishedAtMs,
            attempt = attempt,
            reusedPreviousResult = reusedPreviousResult
        )
        records[executionId] = record
        return record
    }

    private fun purgeLocked(now: Long) {
        // Entries older than the repeat window can never influence a decision again.
        val horizon = repeatWindowMs.coerceAtLeast(duplicateWindowMs)
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (now - record.createdAtMs > horizon) iterator.remove()
        }
    }

    /** Load and reconcile the persisted ledger once per process. Called with [lock] held. */
    private fun restoreIfNeededLocked() {
        if (restored) return
        restored = true
        val store = ledgerStore ?: return
        val raw = runCatching { store.load() }.getOrNull() ?: return
        val restoredRecords = decodeSnapshot(raw)
        // A dead process can leave PENDING/EXECUTING behind. Those outcomes are unknowable; mark
        // them INTERRUPTED so they are never treated as proof the action happened.
        for (record in restoredRecords) {
            val reconciled = when (record.state) {
                DeviceActionState.PENDING, DeviceActionState.EXECUTING ->
                    record.copy(
                        state = DeviceActionState.INTERRUPTED,
                        detail = "interrupted by process death (was ${record.state}); outcome unknown",
                        finishedAtMs = record.finishedAtMs ?: record.createdAtMs
                    )
                else -> record
            }
            records[reconciled.executionId] = reconciled
        }
        purgeLocked(clock())
        persistLocked()
    }

    private fun persistLocked() {
        val store = ledgerStore ?: return
        val snapshot = encodeSnapshot()
        runCatching { store.save(snapshot) }
    }

    private fun encodeSnapshot(): String =
        records.values.joinToString("\n") { r ->
            listOf(
                r.executionId, r.taskId ?: "", r.originAgent, r.signature, r.requestedAction,
                r.resolvedApplication ?: "", r.state.name, r.detail,
                r.createdAtMs.toString(), r.finishedAtMs?.toString() ?: "",
                r.attempt.toString(), if (r.reusedPreviousResult) "1" else "0"
            ).joinToString("|") { encodeField(it) }
        }

    private fun decodeSnapshot(raw: String): List<DeviceActionRecord> =
        raw.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split('|')
            if (p.size != 12) return@mapNotNull null
            runCatching {
                DeviceActionRecord(
                    executionId = decodeField(p[0]),
                    taskId = decodeField(p[1]).ifBlank { null },
                    originAgent = decodeField(p[2]),
                    signature = decodeField(p[3]),
                    requestedAction = decodeField(p[4]),
                    resolvedApplication = decodeField(p[5]).ifBlank { null },
                    state = DeviceActionState.valueOf(decodeField(p[6])),
                    detail = decodeField(p[7]),
                    createdAtMs = decodeField(p[8]).toLong(),
                    finishedAtMs = decodeField(p[9]).toLongOrNull(),
                    attempt = decodeField(p[10]).toIntOrNull() ?: 1,
                    reusedPreviousResult = decodeField(p[11]) == "1"
                )
            }.getOrNull()
        }.toList()

    private fun encodeField(s: String): String =
        Base64.getEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun decodeField(s: String): String =
        String(Base64.getDecoder().decode(s), Charsets.UTF_8)

    private fun log(record: DeviceActionRecord, state: DeviceActionState, detail: String) {
        runCatching {
            logSink(
                DeviceActionLogEntry(
                    timestampMs = clock(),
                    executionId = record.executionId,
                    taskId = record.taskId,
                    originAgent = record.originAgent,
                    signature = record.signature,
                    requestedAction = record.requestedAction,
                    resolvedApplication = record.resolvedApplication,
                    state = state,
                    detail = detail
                )
            )
        }
    }

    private fun newExecutionId(): String = "dae_${clock().toString(36)}_${(Math.random() * 1e9).toLong().toString(36)}"

    companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MS = 2_000L
        const val DEFAULT_REPEAT_WINDOW_MS = 5L * 60_000L
        const val DEFAULT_MAX_RECORDS = 200

        /** Conventional signatures, so every pathway spells app launches the same way. */
        fun openAppSignature(packageName: String, activityName: String?): String =
            "open_app:$packageName:${activityName ?: ""}"

        fun systemIntentSignature(action: String, uri: String?, pkg: String?): String =
            "system_intent:$action:${uri ?: ""}:${pkg ?: ""}"

        fun deviceCommandSignature(wire: String, argsFingerprint: String): String =
            "device_command:$wire:$argsFingerprint"
    }
}
