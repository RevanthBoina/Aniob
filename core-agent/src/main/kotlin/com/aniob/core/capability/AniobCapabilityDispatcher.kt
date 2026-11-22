package com.aniob.core.capability

/**
 * Platform adapter. Implementations must retain the existing gateway, injection and safety checks.
 * A successful return means only that dispatch was accepted, not that the postcondition holds.
 */
fun interface CapabilityExecutionPort {
    fun dispatch(executionKey: String, arguments: Map<String, CapabilityValue>): String
}

sealed interface CapabilityDispatchResult {
    data class Rejected(val reasons: List<String>) : CapabilityDispatchResult
    data class Submitted(val detail: String) : CapabilityDispatchResult
}

/** Approval is explicit and applies to the exact validated invocation, not to a label. */
class AniobCapabilityDispatcher(
    private val registry: AniobCapabilityRegistry,
    private val port: CapabilityExecutionPort,
    private val approve: (CapabilityInvocation) -> Boolean = { false }
) {
    fun dispatch(proposal: CapabilityInvocation): CapabilityDispatchResult {
        // Do not let a caller mutate the arguments between validation and the adapter call.
        val invocation = proposal.copy(arguments = java.util.Collections.unmodifiableMap(LinkedHashMap(proposal.arguments)))
        val errors = registry.validate(invocation)
        if (errors.isNotEmpty()) return CapabilityDispatchResult.Rejected(errors)
        val capability = registry.find(invocation.capabilityId)!!
        if (capability.risk == CapabilityRisk.APPROVAL_REQUIRED && !approve(invocation)) {
            return CapabilityDispatchResult.Rejected(listOf("approval required"))
        }
        // Permission/service state can change while the user is deciding.
        val afterApproval = registry.validate(invocation)
        if (afterApproval.isNotEmpty()) return CapabilityDispatchResult.Rejected(afterApproval)
        return CapabilityDispatchResult.Submitted(port.dispatch(capability.executionKey, invocation.arguments))
    }
}
