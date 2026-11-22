package com.aniob.core.domain

import com.aniob.core.capability.AniobCapabilityRegistry
import com.aniob.core.capability.CapabilityInvocation
import com.aniob.core.capability.CapabilityEffect

/** One model proposal; it is not executable until the runtime has validated it. */
data class AniobTaskPlan(
    val mode: Mode,
    val requestedOutcome: String,
    val invocations: List<CapabilityInvocation> = emptyList(),
    val unresolvedInputs: List<MissingCapabilityInput> = emptyList(),
    val entityBindings: Map<String, EntityReference> = emptyMap(),
    /** Existing completion contract; no separate prompt-derived policy is introduced here. */
    val postconditions: SuccessCriteria = SuccessCriteria(),
    val response: String? = null
) {
    enum class Mode { ANSWER, READ, EXECUTE, CLARIFY }

    /**
     * Checks structure and current registry state. The dispatcher must validate again at dispatch.
     * Missing postconditions do not prevent acting. They do prevent reporting verified success;
     * the runtime must use independently observed evidence at completion.
     */
    fun validate(registry: AniobCapabilityRegistry): List<String> = buildList {
        if (requestedOutcome.isBlank()) add("requested outcome is required")
        if (entityBindings.keys.any { it.isBlank() }) add("entity binding names must not be blank")
        if ((postconditions.mustContainText + postconditions.mustShowPackage +
                    postconditions.mustShowActivity + postconditions.mustBeInstalled).any { it.isBlank() }) {
            add("postcondition targets must not be blank")
        }
        when (mode) {
            Mode.ANSWER -> {
                if (response.isNullOrBlank()) add("answer response is required")
                if (invocations.isNotEmpty() || unresolvedInputs.isNotEmpty()) add("answer cannot contain calls or missing inputs")
                if (!postconditions.isEmpty) add("answer cannot contain execution postconditions")
            }
            Mode.CLARIFY -> {
                if (response.isNullOrBlank()) add("clarification question is required")
                // Keep proposed calls and postconditions across clarification; no calls may be
                // dispatched until a resumed plan passes full READ/EXECUTE validation.
                invocations.forEachIndexed { index, invocation ->
                    val capability = registry.find(invocation.capabilityId)
                    if (capability == null) add("call $index: unknown capability '${invocation.capabilityId}'")
                    else capability.schema.validate(invocation.arguments, allowMissingRequired = true)
                        .forEach { add("call $index: $it") }
                }
            }
            Mode.READ, Mode.EXECUTE -> {
                if (invocations.isEmpty()) add("${mode.name} requires capability calls")
                if (unresolvedInputs.isNotEmpty()) add("${mode.name} cannot contain unresolved inputs")
                if (!response.isNullOrBlank()) add("${mode.name} cannot contain a final response")
                invocations.forEachIndexed { index, invocation ->
                    if (mode == Mode.READ && registry.find(invocation.capabilityId)?.effect == CapabilityEffect.MUTATING) {
                        add("call $index: mutating capability cannot be used in READ mode")
                    }
                    registry.validate(invocation).forEach { add("call $index: $it") }
                }
            }
        }
        unresolvedInputs.forEach { input ->
            val capability = registry.find(input.capabilityId)
            if (capability == null) add("unknown capability '${input.capabilityId}'")
            else if (capability.schema.parameters.none { it.name == input.argument }) {
                add("unknown argument '${input.argument}' for '${input.capabilityId}'")
            }
        }
        if (unresolvedInputs.distinctBy { it.capabilityId to it.argument }.size != unresolvedInputs.size) {
            add("duplicate unresolved input")
        }
    }
}

data class MissingCapabilityInput(val capabilityId: String, val argument: String)
