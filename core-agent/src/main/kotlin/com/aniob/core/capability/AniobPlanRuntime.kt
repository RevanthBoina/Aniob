package com.aniob.core.capability

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.providers.AniobToolCallParser

/** Planning and adaptation only. Actual execution must go through the existing guarded step pipeline. */
object AniobPlanRuntime {
    sealed interface Result {
        data class Accepted(val plan: AniobTaskPlan) : Result
        data class Rejected(val reasons: List<String>) : Result
    }

    fun fromDeviceShortcut(
        goal: String, command: DeviceCommand, args: Map<String, String>, criteria: SuccessCriteria,
        registry: AniobCapabilityRegistry
    ): Result = when (val value = AniobToolCallParser.legacyDeviceInvocation(command, args, registry)) {
        is AniobToolCallParser.InvocationConversion.Rejected -> Result.Rejected(value.reasons)
        is AniobToolCallParser.InvocationConversion.Proposed -> {
            val plan = AniobTaskPlan(
                mode = if (registry.find(command.wire)?.effect == CapabilityEffect.READ_ONLY)
                    AniobTaskPlan.Mode.READ else AniobTaskPlan.Mode.EXECUTE,
                requestedOutcome = goal,
                invocations = listOf(value.invocation),
                postconditions = criteria
            )
            val errors = plan.validate(registry)
            if (errors.isEmpty()) Result.Accepted(plan) else Result.Rejected(errors)
        }
    }

    /** Convert the model's validated plan to the first guarded proposal, without dispatching. */
    fun firstAction(plan: AniobTaskPlan, registry: AniobCapabilityRegistry): AniobAction = when (plan.mode) {
        AniobTaskPlan.Mode.ANSWER -> AniobAction.Finish(plan.response.orEmpty())
        AniobTaskPlan.Mode.CLARIFY -> AniobAction.Fail("ASK_USER: ${plan.response.orEmpty()}")
        AniobTaskPlan.Mode.READ, AniobTaskPlan.Mode.EXECUTE -> when (val next = nextAction(plan, 0, registry)) {
            is AniobToolCallParser.CapabilityConversion.Proposed -> next.action
            is AniobToolCallParser.CapabilityConversion.Rejected -> AniobAction.Fail(next.reasons.joinToString("; "))
        }
    }

    /** Re-check availability for the next call; it may have changed since planning or approval. */
    fun nextAction(plan: AniobTaskPlan, index: Int, registry: AniobCapabilityRegistry): AniobToolCallParser.CapabilityConversion {
        if (plan.mode != AniobTaskPlan.Mode.READ && plan.mode != AniobTaskPlan.Mode.EXECUTE) {
            return AniobToolCallParser.CapabilityConversion.Rejected(listOf("plan is not executable"))
        }
        val invocation = plan.invocations.getOrNull(index)
            ?: return AniobToolCallParser.CapabilityConversion.Rejected(listOf("no call at index $index"))
        if (plan.mode == AniobTaskPlan.Mode.READ && registry.find(invocation.capabilityId)?.effect != CapabilityEffect.READ_ONLY) {
            return AniobToolCallParser.CapabilityConversion.Rejected(listOf("mutating capability cannot be used in READ mode"))
        }
        return AniobToolCallParser.capabilityToAction(invocation, registry)
    }
}
