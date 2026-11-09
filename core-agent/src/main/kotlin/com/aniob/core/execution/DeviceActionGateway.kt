package com.aniob.core.execution

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
 *  - **Single execution authority / execution locking** — every call through [execute] runs under one
 *    monitor, so two conflicting actions can never be in flight at once.
 *  - **Intent deduplication** — a second call with the same signature inside [duplicateWindowMs] is
 *    dropped and reported as [DeviceActionState.DUPLICATE] instead of dispatching again.
 *  - **Idempotency** — a caller that supplies an `executionId` and retries after a crash/timeout
 *    gets the recorded outcome, never a second dispatch.
 *  - **Execution state tracking** — every request is recorded as PENDING → EXECUTING →
 *    COMPLETED | FAILED (or DUPLICATE when rejected). The record carries the originating agent, the
 *    requested action, the resolved application and the result.
 *  - **Controlled retries** — a signature whose last record is COMPLETED in the same task is served
 *    from the ledger instead of re-firing; one whose last record is FAILED is allowed (and counted)
 *    as a genuine retry. Retrying because confirmation is delayed is impossible here: the lock is
 *    held until the action genuinely returns.
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
    private val logSink: (DeviceActionLogEntry) -> Unit = {}
) {

    enum class DeviceActionState { PENDING, EXECUTING, COMPLETED, FAILED, DUPLICATE }

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
        /** 1 for the first attempt of a signature, 2+ after a FAILED record. */
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
        /** True when a duplicate was suppressed instead of dispatched. */
        val deduplicated: Boolean,
        val record: DeviceActionRecord
    )

    /** Thrown never: [execute] converts every failure into a FAILED record. */
    private val lock = Any()

    /** Insertion-ordered, bounded ledger. Keyed by executionId. */
    private val records = object : LinkedHashMap<String, DeviceActionRecord>(maxRecords + 16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeviceActionRecord>?): Boolean =
            size > maxRecords
    }

    /**
     * Performs [dispatch] at most once per [executionId], and never while an identical signature
     * is in flight or has just completed.
     *
     * @param executionId caller-supplied idempotency key. Retrying with the same id after a lost
     *   callback returns the recorded outcome instead of dispatching a second time. When null, one
     *   is generated and returned through the outcome for correlation.
     * @param taskId the task that asked for this action. Repeated dispatches of the same signature
     *   inside one task are served from the ledger for [repeatWindowMs].
     * @param originAgent which agent/ pathway asked ("LOCAL_SLM", "OMNIROUTE_CLOUD", "INTENT",
     *   "WORKFLOW", "ACCESSIBILITY", "VIEWMODEL"…). Recorded verbatim for the audit trail.
     * @param signature stable action identity: `kind:target[:extra]`. Two calls with the same
     *   signature are the *same* action for locking/dedup purposes.
     * @param requestedAction human-readable action name for logs ("open_app", "open_url"…).
     * @param resolvedApplication the resolved package, when known. Recorded, never guessed.
     * @param dispatch the actual effect. Called exactly once, under the lock.
     */
    fun execute(
        executionId: String? = null,
        taskId: String?,
        originAgent: String,
        signature: String,
        requestedAction: String,
        resolvedApplication: String? = null,
        dispatch: () -> DispatchResult
    ): DeviceActionOutcome = synchronized(lock) {
        val now = clock()
        purgeLocked(now)

        // 1. Idempotency: the same execution identifier never dispatches twice.
        val id = executionId?.takeIf { it.isNotBlank() } ?: newExecutionId()
        records[id]?.let { existing ->
            log(existing, DeviceActionState.DUPLICATE, "idempotent replay suppressed (state=${existing.state})")
            return DeviceActionOutcome(
                ok = existing.state == DeviceActionState.COMPLETED,
                deduplicated = true,
                record = existing.copy(reusedPreviousResult = true)
            )
        }

        // 2. Duplicate suppression while an identical signature is in flight or just fired.
        val recentSameSignature = records.values.filter { it.signature == signature }
        val inFlight = recentSameSignature.firstOrNull { it.state == DeviceActionState.PENDING || it.state == DeviceActionState.EXECUTING }
        if (inFlight != null) {
            val record = recordLocked(
                executionId = id, taskId = taskId, originAgent = originAgent,
                signature = signature, requestedAction = requestedAction,
                resolvedApplication = resolvedApplication, state = DeviceActionState.DUPLICATE,
                detail = "suppressed duplicate of in-flight action ${inFlight.executionId}", attempt = 1, now = now
            )
            log(record, DeviceActionState.DUPLICATE, "suppressed duplicate of in-flight action ${inFlight.executionId}")
            return DeviceActionOutcome(ok = false, deduplicated = true, record = record)
        }
        val justFired = recentSameSignature.firstOrNull {
            it.state == DeviceActionState.COMPLETED && now - it.createdAtMs < duplicateWindowMs
        }
        if (justFired != null) {
            val record = recordLocked(
                executionId = id, taskId = taskId, originAgent = originAgent,
                signature = signature, requestedAction = requestedAction,
                resolvedApplication = resolvedApplication, state = DeviceActionState.DUPLICATE,
                detail = "suppressed duplicate of action ${justFired.executionId} completed ${now - justFired.createdAtMs}ms ago",
                attempt = 1, now = now
            )
            log(record, DeviceActionState.DUPLICATE, record.detail)
            // The identical action already happened moments ago; the caller's intent is satisfied.
            return DeviceActionOutcome(ok = true, deduplicated = true, record = record)
        }

        // 3. Same task, same *command*, recently COMPLETED: serve from the ledger instead of re-firing.
        // Launch signatures are excluded on purpose: opening an app twice in one task can be a real
        // request (re-fronting it after drift), while a re-issued device command is almost always
        // a retry loop watching the same result.
        if (taskId != null && signature.startsWith("device_command:")) {
            val sameTaskCompleted = recentSameSignature.lastOrNull {
                it.taskId == taskId && it.state == DeviceActionState.COMPLETED &&
                    now - it.createdAtMs < repeatWindowMs
            }
            if (sameTaskCompleted != null) {
                val record = recordLocked(
                    executionId = id, taskId = taskId, originAgent = originAgent,
                    signature = signature, requestedAction = requestedAction,
                    resolvedApplication = resolvedApplication, state = DeviceActionState.COMPLETED,
                    detail = "reused outcome of ${sameTaskCompleted.executionId} (completed ${now - sameTaskCompleted.createdAtMs}ms ago)",
                    attempt = 1, now = now, finishedAtMs = now, reusedPreviousResult = true
                )
                log(record, DeviceActionState.COMPLETED, record.detail)
                return DeviceActionOutcome(ok = true, deduplicated = true, record = record)
            }
        }

        // 4. Tracked execution. A FAILED predecessor means this is a genuine, counted retry.
        val priorFailure = recentSameSignature.count { it.state == DeviceActionState.FAILED }
        val attempt = priorFailure + 1
        val pending = recordLocked(
            executionId = id, taskId = taskId, originAgent = originAgent,
            signature = signature, requestedAction = requestedAction,
            resolvedApplication = resolvedApplication, state = DeviceActionState.PENDING,
            detail = "accepted", attempt = attempt, now = now
        )
        log(pending, DeviceActionState.PENDING, "accepted")
        val executing = pending.copy(state = DeviceActionState.EXECUTING)
        records[id] = executing
        log(executing, DeviceActionState.EXECUTING, "dispatching")

        val result: DispatchResult = try {
            dispatch()
        } catch (t: Throwable) {
            DispatchResult(false, "dispatch threw: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
        val finished = clock()
        val terminal = executing.copy(
            state = if (result.ok) DeviceActionState.COMPLETED else DeviceActionState.FAILED,
            detail = result.detail.ifBlank { if (result.ok) "dispatched" else "dispatch failed" },
            finishedAtMs = finished
        )
        records[id] = terminal
        log(terminal, terminal.state, terminal.detail)
        DeviceActionOutcome(ok = result.ok, deduplicated = false, record = terminal)
    }

    /** Snapshot of the bounded ledger, newest last. For diagnostics and tests. */
    fun snapshot(): List<DeviceActionRecord> = synchronized(lock) { records.values.toList() }

    /** Forget everything (tests; the app never calls this mid-task). */
    fun clear() = synchronized(lock) { records.clear() }

    // ------------------------------------------------------------------------------------------

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
