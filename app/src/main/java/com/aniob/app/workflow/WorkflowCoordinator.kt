package com.aniob.app.workflow

import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.core.workflow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull

/**
 * WorkflowCoordinator: Coordinates typed workflow execution, capability checks,
 * approval gates, persistence of dispatch intent, and receipt tracking.
 *
 * Invariant: Does not retain Activity or View references directly.
 */
class WorkflowCoordinator(
    private val repository: WorkflowRepository,
    private val taskOwner: TaskOwner,
    private val approvalController: ApprovalController,
    private val platformTools: PlatformTools,
    private val payloadStore: WorkflowPayloadStore,
    private val actionHost: WorkflowActionHost,
    private val destinationPreferences: DestinationPreferences
) {
    private val _workflowState = MutableStateFlow<WorkflowState?>(null)
    val workflowState: StateFlow<WorkflowState?> = _workflowState.asStateFlow()

    fun updateState(state: WorkflowState) {
        _workflowState.value = state
    }

    /**
     * Executes a typed workflow action.
     */
    suspend fun executeAction(
        action: WorkflowAction,
        taskId: String,
        generation: Long
    ): WorkflowResult {
        val destination = when (action) {
            is WorkflowAction.OpenApp -> action.destination
            is WorkflowAction.OpenWebsite -> action.destination
            else -> Destination(handler = "generic", category = DestinationCategory.GENERIC)
        }

        val payload = when (action) {
            is WorkflowAction.OpenApp -> action.payload ?: action.draftText?.let { payloadStore.saveDraft(taskId, it) }
            is WorkflowAction.OpenWebsite -> action.payload
            is WorkflowAction.SharePayload -> action.payload
            else -> null
        }

        val websiteBrief = (action as? WorkflowAction.OpenWebsite)?.briefText
        val payloadHash = payload?.contentHash ?: websiteBrief?.let {
            WorkflowPayloadStore.sha256(it.toByteArray(Charsets.UTF_8))
        }

        val identity = OperationIdentity(
            taskId = taskId,
            actionId = action.actionId,
            generation = generation,
            destinationKey = destination.destinationKey,
            approvedPayloadHash = payloadHash
        )

        // 1. Capability check
        val caps = platformTools.checkCapabilities(destination)
        if (caps.handlerAvailability == CapabilitySnapshot.HandlerAvailability.UNAVAILABLE) {
            val failResult = WorkflowResult.Failed(
                errorReason = "No handler or browser available on this device for ${destination.destinationKey}."
            )
            repository.persistResult(identity, failResult)
            _workflowState.value = WorkflowState.Failed(identity, failResult.errorReason)
            return failResult
        }

        // 2. Approval check if required
        val sensitiveBrief = !websiteBrief.isNullOrBlank() &&
            com.aniob.core.safety.AniobDlpSanitizer.containsSensitiveData(websiteBrief)
        val needsApproval = payload?.isSensitive == true ||
            payload?.requiresConsent == true ||
            sensitiveBrief ||
            (!destination.isApproved && !websiteBrief.isNullOrBlank())
        if (needsApproval) {
            val details = when {
                payload != null -> "Data hash: ${payload.contentHash.take(8)}"
                !websiteBrief.isNullOrBlank() &&
                    com.aniob.core.safety.AniobDlpSanitizer.containsSensitiveData(websiteBrief) ->
                    "Brief contains sensitive content; preview hidden. Approval is bound to the exact brief."
                !websiteBrief.isNullOrBlank() ->
                    "Brief preview: ${com.aniob.core.safety.AniobDlpSanitizer.sanitize(websiteBrief).take(60)}"
                else -> "Target: ${destination.destinationKey}"
            }
            val request = ConfirmRequest(
                id = "confirm_${action.actionId}_$generation",
                title = "Approve sharing with ${destination.destinationKey}",
                what = "Share data with ${destination.destinationKey}",
                why = "The destination will receive external data.",
                risk = ConfirmRequest.Risk.MEDIUM,
                details = details,
                taskId = taskId,
                payload = payloadHash.orEmpty(),
                target = destination.destinationKey,
                identityTarget = destination.destinationKey
            )
            val decision = approvalController.requestApproval(request, identity)
            if (decision == ConfirmDecision.DENY || decision == ConfirmDecision.TIMEOUT_DENY) {
                val cancelResult = WorkflowResult.Cancelled("User declined sharing data with destination.")
                repository.persistResult(identity, cancelResult)
                _workflowState.value = WorkflowState.Cancelled(identity, cancelResult.reason)
                return cancelResult
            }
        }

        // 3. PERSIST DISPATCHING INTENT BEFORE ANY SIDE EFFECT
        // Invariant: If process dies after this write, startup restores EffectUnknown!
        repository.persistDispatching(identity, payload?.contentHash)
        _workflowState.value = WorkflowState.Dispatching(identity, action, payload?.contentHash)

        // 4. Build launch spec
        val spec = when (action) {
            is WorkflowAction.OpenWebsite -> {
                val preferredBrowser = destinationPreferences.getPreferredBrowserPackage().firstOrNull()
                val browserPkg = platformTools.findCustomTabsBrowser(preferredBrowser)
                val intent = platformTools.buildCustomTabsIntent(
                    url = destination.url ?: "https://${destination.handler}",
                    browserPackage = browserPkg
                )
                LaunchSpec.CustomTabs(
                    url = destination.url ?: "https://${destination.handler}",
                    browserPackage = browserPkg,
                    actionId = action.actionId
                )
            }
            is WorkflowAction.OpenApp -> {
                val intent = platformTools.buildAppLaunchIntent(destination.handler)
                if (intent == null) {
                    val failResult = WorkflowResult.Failed("Could not build launch intent for ${destination.handler}")
                    repository.persistResult(identity, failResult)
                    _workflowState.value = WorkflowState.Failed(identity, failResult.errorReason)
                    return failResult
                }
                LaunchSpec.ActivityIntent(intent = intent, actionId = action.actionId)
            }
            else -> {
                val failResult = WorkflowResult.Failed("Unsupported action: ${action.actionId}")
                repository.persistResult(identity, failResult)
                _workflowState.value = WorkflowState.Failed(identity, failResult.errorReason)
                return failResult
            }
        }

        // 5. Dispatch launch through UI host
        var dispatchResult: WorkflowResult? = null
        val launched = actionHost.dispatchLaunch(spec, identity) { receipt ->
            dispatchResult = receipt
        }

        if (!launched || dispatchResult is WorkflowResult.Failed) {
            val error = (dispatchResult as? WorkflowResult.Failed)?.errorReason ?: "Launch could not be dispatched."
            val failResult = WorkflowResult.Failed(error)
            repository.persistResult(identity, failResult)
            _workflowState.value = WorkflowState.Failed(identity, failResult.errorReason)
            return failResult
        }

        // 6. Set waiting or completed state
        if (action is WorkflowAction.OpenWebsite) {
            // Websites transition to durable WaitingForUser state
            repository.persistWaitingForUser(identity, destination, action.briefText)
            val waitingState = WorkflowState.WaitingForUser(
                identity = identity,
                destination = destination,
                brief = action.briefText
            )
            _workflowState.value = waitingState
            return WorkflowResult.WaitingForUser(
                reason = "Waiting for user action on website",
                continuationToken = taskId
            )
        } else {
            val result = WorkflowResult.LaunchAccepted(receiptId = "launch_${action.actionId}")
            repository.persistResult(identity, result)
            _workflowState.value = WorkflowState.ResultReady(identity, result)
            return result
        }
    }

    /**
     * Called when user marks a waiting website task as completed or notes a result.
     */
    suspend fun completeWaitingTask(
        taskId: String,
        userNotes: String = ""
    ): WorkflowResult {
        val workflow = repository.getWorkflow(taskId) ?: return WorkflowResult.Failed("Task not found")
        val identity = OperationIdentity(
            taskId = workflow.taskId,
            actionId = workflow.actionId,
            generation = workflow.generation,
            destinationKey = workflow.destinationKey,
            approvedPayloadHash = workflow.payloadHash
        )
        val result = WorkflowResult.UserReportedCompletion(userNotes = userNotes)
        repository.persistResult(identity, result)
        _workflowState.value = WorkflowState.Completed(identity, result)
        return result
    }

    /**
     * Cancels an active or waiting workflow.
     */
    suspend fun cancelWorkflow(taskId: String, reason: String = "User cancelled"): WorkflowResult {
        val workflow = repository.getWorkflow(taskId)
        val identity = if (workflow != null) {
            OperationIdentity(
                taskId = workflow.taskId,
                actionId = workflow.actionId,
                generation = workflow.generation,
                destinationKey = workflow.destinationKey,
                approvedPayloadHash = workflow.payloadHash
            )
        } else null

        val result = WorkflowResult.Cancelled(reason)
        if (identity != null) {
            repository.persistResult(identity, result)
        }
        _workflowState.value = WorkflowState.Cancelled(identity, reason)
        taskOwner.stop(taskId)
        approvalController.clearForTask(taskId)
        return result
    }
}
