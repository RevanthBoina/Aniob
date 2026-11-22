package com.aniob.core.capability

import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.EntityReference
import com.aniob.core.domain.MissingCapabilityInput
import com.aniob.core.domain.SuccessCriteria
import org.junit.Assert.*
import org.junit.Test

class AniobTaskPlanTest {
    private val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }

    @Test fun `answer and clarification modes validate their required fields`() {
        val answer = AniobTaskPlan(AniobTaskPlan.Mode.ANSWER, "Answer question", response = "Here is the answer")
        assertTrue(answer.validate(registry).isEmpty())
        assertTrue(answer.copy(response = null).validate(registry).isNotEmpty())
        val clarify = AniobTaskPlan(
            AniobTaskPlan.Mode.CLARIFY, "Set volume", response = "What volume?",
            unresolvedInputs = listOf(MissingCapabilityInput("volume_set", "percent")),
            invocations = listOf(CapabilityInvocation("volume_set")),
            postconditions = SuccessCriteria(mustShowPackage = listOf("com.example"))
        )
        assertTrue(clarify.validate(registry).isEmpty())
        assertTrue(clarify.copy(unresolvedInputs = listOf(MissingCapabilityInput("volume_set", "stream"))).validate(registry).isEmpty())
        assertTrue(clarify.copy(unresolvedInputs = emptyList()).validate(registry).isEmpty())
        assertTrue(clarify.copy(unresolvedInputs = listOf(MissingCapabilityInput("volume_set", "unknown"))).validate(registry).isNotEmpty())
    }

    @Test fun `ordered calls and typed bindings survive validation`() {
        val calls = listOf(CapabilityInvocation("torch_on"), CapabilityInvocation("torch_off"))
        val plan = AniobTaskPlan(
            AniobTaskPlan.Mode.EXECUTE, "Turn light on then off", invocations = calls,
            entityBindings = mapOf("example" to EntityReference("file", "opaque-id")),
            postconditions = SuccessCriteria(deviceAsserts = listOf(DeviceAssertion.TORCH_OFF))
        )
        assertTrue(plan.validate(registry).isEmpty())
        assertEquals(calls, plan.invocations)
        assertEquals("opaque-id", plan.entityBindings["example"]?.id)
        assertTrue(plan.copy(postconditions = SuccessCriteria(minSteps = 2)).validate(registry).isEmpty())
    }

    @Test fun `read and execute reject unknown capabilities and bad arguments without requiring invented evidence`() {
        for (mode in listOf(AniobTaskPlan.Mode.READ, AniobTaskPlan.Mode.EXECUTE)) {
            val unknown = AniobTaskPlan(mode, "A goal", invocations = listOf(CapabilityInvocation("made_up")),
                postconditions = SuccessCriteria(mustShowPackage = listOf("com.example")))
            assertTrue(unknown.validate(registry).any { it.contains("unknown capability") })
            val invalid = unknown.copy(invocations = listOf(CapabilityInvocation("volume_set")))
            assertTrue(invalid.validate(registry).any { it.contains("percent") })
            val capability = if (mode == AniobTaskPlan.Mode.READ) "volume_get" else "torch_on"
            val withoutEvidence = AniobTaskPlan(mode, "A goal", invocations = listOf(CapabilityInvocation(capability)))
            assertTrue(withoutEvidence.validate(registry).isEmpty())
        }
    }
}
