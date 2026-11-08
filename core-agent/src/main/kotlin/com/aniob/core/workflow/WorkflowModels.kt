package com.aniob.core.workflow

/**
 * Shared workflow limits across all platforms and components.
 */
object WorkflowLimits {
    const val TEXT_BRIEF_MAX_BYTES: Int = 64 * 1024 // 64 KiB
    const val EDITABLE_DOC_MAX_BYTES: Int = 512 * 1024 // 512 KiB
    const val MAX_ATTACHMENTS: Int = 8
    const val MAX_ATTACHMENT_TOTAL_BYTES: Long = 20L * 1024 * 1024 // 20 MiB
    const val MAX_SERVICE_RECOMMENDATIONS: Int = 3
    const val MAX_FALLBACK_LAUNCHES: Int = 1
    const val MAX_BROWSER_PAGES: Int = 3
}

/**
 * Provenance of a destination candidate.
 */
enum class DestinationProvenance {
    BUILTIN,
    USER_PREFERENCE,
    USER_INPUT,
    DISCOVERED,
    SYSTEM_RESOLVED
}

/**
 * High-level category of destination.
 */
enum class DestinationCategory {
    APP,
    WEBSITE,
    REASONING_SERVICE,
    BUILDER_SERVICE,
    DOCUMENT_HANDLER,
    GENERIC
}

/**
 * Validated destination specification.
 */
data class Destination(
    val handler: String, // package name or host
    val url: String? = null,
    val category: DestinationCategory = DestinationCategory.GENERIC,
    val provenance: DestinationProvenance = DestinationProvenance.USER_INPUT,
    val compatibilityConstraints: List<String> = emptyList(),
    val isApproved: Boolean = false,
    /** Human-readable installed-app/site label; [handler] remains the canonical package or host. */
    val label: String? = null
) {
    val destinationKey: String
        get() = url ?: handler
}

/**
 * Typed reference to payload data.
 * URI strings are opaque identifiers, not filesystem paths.
 */
data class PayloadReference(
    val id: String,
    val mimeType: String,
    val byteCount: Long,
    val contentHash: String, // SHA-256
    val isSensitive: Boolean = false,
    val requiresConsent: Boolean = true,
    val opaqueUri: String? = null
)

/**
 * Cryptographically/logically identified operation token.
 * Editing draft or payload invalidates old approvedPayloadHash.
 */
data class OperationIdentity(
    val taskId: String,
    val actionId: String,
    val generation: Long,
    val destinationKey: String,
    val approvedPayloadHash: String? = null
)

/**
 * Explicit workflow actions.
 */
sealed class WorkflowAction {
    abstract val actionId: String

    data class OpenApp(
        override val actionId: String,
        val destination: Destination,
        val payload: PayloadReference? = null,
        val draftText: String? = null
    ) : WorkflowAction()

    data class OpenWebsite(
        override val actionId: String,
        val destination: Destination,
        val briefText: String? = null,
        val payload: PayloadReference? = null
    ) : WorkflowAction()

    data class SharePayload(
        override val actionId: String,
        val payload: PayloadReference,
        val targetPackage: String? = null
    ) : WorkflowAction()

    data class ChooseDocument(
        override val actionId: String,
        val mimeTypes: List<String> = listOf("*/*")
    ) : WorkflowAction()

    data class EditText(
        override val actionId: String,
        val initialText: String,
        val isReadOnly: Boolean = false
    ) : WorkflowAction()

    data class SaveCopy(
        override val actionId: String,
        val draftPayload: PayloadReference,
        val suggestedName: String
    ) : WorkflowAction()

    data class ReplaceDocument(
        override val actionId: String,
        val targetDocumentId: String,
        val draftPayload: PayloadReference,
        val originalFingerprint: String
    ) : WorkflowAction()

    data class ReadPage(
        override val actionId: String,
        val url: String
    ) : WorkflowAction()

    data class DelegateExistingTask(
        override val actionId: String,
        val prompt: String
    ) : WorkflowAction()
}

/**
 * Capability state for an adapter or handler.
 */
data class CapabilitySnapshot(
    val supportedAdapter: String,
    val requiredPermissions: List<String> = emptyList(),
    val handlerAvailability: HandlerAvailability = HandlerAvailability.UNKNOWN,
    val networkAvailable: Boolean = true,
    val supportedDocumentOperations: List<String> = emptyList()
) {
    enum class HandlerAvailability {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN
    }
}

/**
 * Typed execution outcome for workflow actions.
 */
sealed class WorkflowResult {
    data class LaunchAccepted(
        val receiptId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class WaitingForUser(
        val reason: String,
        val continuationToken: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class CompletedWithEvidence(
        val evidenceKind: String,
        val detail: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class UserReportedCompletion(
        val userNotes: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class Failed(
        val errorReason: String,
        val canRetry: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class Cancelled(
        val reason: String = "User cancelled",
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()

    data class EffectUnknown(
        val details: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : WorkflowResult()
}

/**
 * Pure state machine representation of a workflow.
 */
sealed class WorkflowState {
    data class Draft(
        val prompt: String,
        val brief: String? = null
    ) : WorkflowState()

    data class NeedsChoice(
        val candidates: List<Destination>,
        val prompt: String
    ) : WorkflowState()

    data class NeedsAccess(
        val requiredPermissionOrGrant: String,
        val reason: String
    ) : WorkflowState()

    data class NeedsApproval(
        val identity: OperationIdentity,
        val action: WorkflowAction,
        val destination: Destination,
        val summary: String
    ) : WorkflowState()

    data class Ready(
        val identity: OperationIdentity,
        val action: WorkflowAction
    ) : WorkflowState()

    data class Dispatching(
        val identity: OperationIdentity,
        val action: WorkflowAction,
        val payloadHash: String?
    ) : WorkflowState()

    data class WaitingForUser(
        val identity: OperationIdentity,
        val destination: Destination,
        val brief: String? = null,
        val waitingSince: Long = System.currentTimeMillis()
    ) : WorkflowState()

    data class ResultReady(
        val identity: OperationIdentity,
        val result: WorkflowResult
    ) : WorkflowState()

    data class Completed(
        val identity: OperationIdentity,
        val result: WorkflowResult
    ) : WorkflowState()

    data class Failed(
        val identity: OperationIdentity?,
        val reason: String
    ) : WorkflowState()

    data class Cancelled(
        val identity: OperationIdentity?,
        val reason: String
    ) : WorkflowState()

    data class Interrupted(
        val identity: OperationIdentity?,
        val reason: String
    ) : WorkflowState()

    data class EffectUnknown(
        val identity: OperationIdentity?,
        val reason: String
    ) : WorkflowState()

    val stateName: String
        get() = when (this) {
            is Draft -> "Draft"
            is NeedsChoice -> "NeedsChoice"
            is NeedsAccess -> "NeedsAccess"
            is NeedsApproval -> "NeedsApproval"
            is Ready -> "Ready"
            is Dispatching -> "Dispatching"
            is WaitingForUser -> "WaitingForUser"
            is ResultReady -> "ResultReady"
            is Completed -> "Completed"
            is Failed -> "Failed"
            is Cancelled -> "Cancelled"
            is Interrupted -> "Interrupted"
            is EffectUnknown -> "EffectUnknown"
        }
}
