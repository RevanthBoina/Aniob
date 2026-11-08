package com.aniob.app.workflow

import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.core.workflow.OperationIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Shared ApprovalController.
 * Owns pending confirmation, identity binding, and approval validation across
 * both screen automation and new typed workflow tools.
 *
 * Invariant: Every approval is strictly bound to taskId, actionId, generation,
 * destination, and approved payload hash. Editing a draft or changing destination
 * invalidates previous approvals.
 */
class ApprovalController {

    private val _confirmRequest = MutableStateFlow<ConfirmRequest?>(null)
    val confirmRequest: StateFlow<ConfirmRequest?> = _confirmRequest.asStateFlow()

    private val _confirmationHistory = MutableStateFlow<List<ConfirmationRecord>>(emptyList())
    val confirmationHistory: StateFlow<List<ConfirmationRecord>> = _confirmationHistory.asStateFlow()

    @Volatile private var pendingDeferred: CompletableDeferred<ConfirmDecision>? = null
    @Volatile private var boundIdentity: OperationIdentity? = null

    // Memoized approvals for current task
    private val taskApprovals = mutableSetOf<String>()

    private fun approvalMemoKey(identity: OperationIdentity, what: String): String {
        return "${identity.taskId}|${identity.generation}|${identity.destinationKey}|${identity.approvedPayloadHash ?: ""}|$what"
    }

    /**
     * Suspends until the user confirms, denies, or explicitly cancels the task. There is no timeout.
     */
    suspend fun requestApproval(
        request: ConfirmRequest,
        identity: OperationIdentity
    ): ConfirmDecision {
        val memoKey = approvalMemoKey(identity, request.what)
        synchronized(this) {
            if (taskApprovals.contains(memoKey)) {
                return ConfirmDecision.APPROVE_FOR_TASK
            }
        }

        val deferred = CompletableDeferred<ConfirmDecision>()
        synchronized(this) {
            pendingDeferred?.cancel()
            pendingDeferred = deferred
            boundIdentity = identity
            _confirmRequest.value = request
        }

        var decision = ConfirmDecision.DENY
        try {
            decision = try {
                deferred.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Replacing/clearing this request while its task is still active is an explicit
                // cancellation and resolves as DENY. Cancellation of the task itself propagates.
                if (currentCoroutineContext().isActive) ConfirmDecision.DENY else throw cancelled
            }
        } finally {
            synchronized(this) {
                if (pendingDeferred === deferred) {
                    val current = boundIdentity
                    if (current == identity) {
                        if (decision == ConfirmDecision.APPROVE_FOR_TASK) {
                            taskApprovals.add(memoKey)
                        }

                        val record = ConfirmationRecord(
                            what = request.what,
                            risk = request.risk,
                            decision = decision,
                            at = System.currentTimeMillis()
                        )
                        _confirmationHistory.value = (listOf(record) + _confirmationHistory.value).take(20)
                    }

                    _confirmRequest.value = null
                    pendingDeferred = null
                    boundIdentity = null
                }
            }
        }

        return decision
    }

    /**
     * Resolves pending confirmation.
     * Rejects callbacks with missing, blank, or mismatched IDs.
     */
    fun resolveConfirmation(
        decision: ConfirmDecision,
        taskId: String? = null,
        requestId: String? = null
    ): Boolean {
        synchronized(this) {
            val req = _confirmRequest.value ?: return false
            val ident = boundIdentity ?: return false

            if (taskId.isNullOrBlank() || requestId.isNullOrBlank()) {
                return false
            }
            if (taskId != ident.taskId || requestId != req.id) {
                return false
            }

            val deferred = pendingDeferred ?: return false
            deferred.complete(decision)
            return true
        }
    }

    /**
     * Clears all approvals and cancels any pending request for a given task.
     */
    fun clearForTask(taskId: String) {
        synchronized(this) {
            val prefix = "$taskId|"
            taskApprovals.removeAll { it.startsWith(prefix) }
            if (boundIdentity?.taskId == taskId) {
                pendingDeferred?.cancel()
                pendingDeferred = null
                boundIdentity = null
                _confirmRequest.value = null
            }
        }
    }

    fun clearAll() {
        synchronized(this) {
            taskApprovals.clear()
            pendingDeferred?.cancel()
            pendingDeferred = null
            boundIdentity = null
            _confirmRequest.value = null
        }
    }
}
