package com.aniob.core.capability

import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.providers.AniobToolCallParser
import org.junit.Assert.*
import org.junit.Test

class AniobPlanRuntimeTest {
    @Test fun `offline shortcut creates typed plan without executing it`() {
        var state = AniobCapabilityManifest.DeviceState()
        val registry = AniobCapabilityRegistry.deviceCommands { state }
        val plan = (AniobPlanRuntime.fromDeviceShortcut("set volume", DeviceCommand.VOLUME_SET,
            mapOf("percent" to "42"), SuccessCriteria(), registry) as AniobPlanRuntime.Result.Accepted).plan
        assertEquals(AniobTaskPlan.Mode.EXECUTE, plan.mode)
        assertTrue(plan.invocations.single().arguments["percent"] is CapabilityValue.Decimal)
        assertTrue(AniobPlanRuntime.nextAction(plan, 0, registry) is AniobToolCallParser.CapabilityConversion.Proposed)
        state = state.copy(notificationAccess = false)
        val unavailable = AniobTaskPlan(AniobTaskPlan.Mode.READ, "read code", listOf(CapabilityInvocation("otp_status")))
        assertTrue(AniobPlanRuntime.nextAction(unavailable, 0, registry) is AniobToolCallParser.CapabilityConversion.Rejected)
    }

    @Test fun `invalid shortcut and mutating READ are rejected`() {
        val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }
        assertTrue(AniobPlanRuntime.fromDeviceShortcut("set volume", DeviceCommand.VOLUME_SET,
            mapOf("percent" to "NaN"), SuccessCriteria(), registry) is AniobPlanRuntime.Result.Rejected)
        val read = AniobTaskPlan(AniobTaskPlan.Mode.READ, "switch torch", listOf(CapabilityInvocation("torch_on")))
        assertTrue(AniobPlanRuntime.nextAction(read, 0, registry) is AniobToolCallParser.CapabilityConversion.Rejected)
    }
    @Test fun `model plan is converted to a proposal not executed`() {
        val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }
        val raw = """{"mode":"EXECUTE","requested_outcome":"turn on torch","invocations":[{"capability_id":"torch_on","arguments":{}}],"postconditions":{"device_asserts":["TORCH_ON"]}}"""
        val parsed = com.aniob.core.providers.AniobStructuredOutput.parseTaskPlan(raw, registry)
            as com.aniob.core.providers.AniobStructuredOutput.TaskPlanParse.Accepted
        val action = AniobPlanRuntime.firstAction(parsed.plan, registry)
        assertTrue(action is com.aniob.core.domain.AniobAction.DeviceControl)
        assertEquals(DeviceCommand.TORCH_ON, (action as com.aniob.core.domain.AniobAction.DeviceControl).command)
        assertEquals(com.aniob.core.domain.DeviceAssertion.TORCH_ON, parsed.plan.postconditions.deviceAsserts.single())
        val clarify = AniobTaskPlan(AniobTaskPlan.Mode.CLARIFY, "send a message", response = "Who is the recipient?")
        assertTrue(AniobPlanRuntime.firstAction(clarify, registry) is com.aniob.core.domain.AniobAction.Fail)
        val answer = AniobTaskPlan(AniobTaskPlan.Mode.ANSWER, "answer", response = "Hello")
        assertTrue(AniobPlanRuntime.firstAction(answer, registry) is com.aniob.core.domain.AniobAction.Finish)
    }

    @Test fun `additional plan criteria cannot remove existing checks`() {
        val original = SuccessCriteria(mustShowPackage = listOf("com.example"), minSteps = 2,
            requireInstallConfirmation = true)
        val added = SuccessCriteria(mustContainText = listOf("Done"), minSteps = 1)
        val merged = original.requiringAlso(added)
        assertEquals(listOf("com.example"), merged.mustShowPackage)
        assertEquals(listOf("Done"), merged.mustContainText)
        assertEquals(2, merged.minSteps)
        assertTrue(merged.requireInstallConfirmation)
    }
    @Test fun `known opaque recipient survives typed plan without becoming a string`() {
        val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }
        val ref = com.aniob.core.domain.EntityReference("contact", "opaque-7f2")
        val raw = """{"mode":"EXECUTE","requested_outcome":"message my chosen contact","invocations":[{"capability_id":"sms_message","arguments":{"phone":{"kind":"contact","id":"contact_recipient"},"text":"Hello"}}]}"""
        val parsed = com.aniob.core.providers.AniobStructuredOutput.parseTaskPlan(
            raw, registry, knownEntityBindings = mapOf("contact_recipient" to ref)
        ) as com.aniob.core.providers.AniobStructuredOutput.TaskPlanParse.Accepted
        val action = AniobPlanRuntime.firstAction(parsed.plan, registry) as com.aniob.core.domain.AniobAction.DeviceControl
        assertEquals(mapOf("phone" to ref), action.entityArguments)
        assertEquals("__selected_entity__", action.args["contact"])
        assertFalse(action.args.values.any { it == ref.id })
        assertFalse(action.toString().contains(ref.id))
        val invented = com.aniob.core.providers.AniobStructuredOutput.parseTaskPlan(raw, registry)
        assertTrue(invented is com.aniob.core.providers.AniobStructuredOutput.TaskPlanParse.Rejected)
    }
}
