package com.aniob.core.capability

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.TaskContext
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.providers.AniobToolCallParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CapabilityActionBridgeTest {
    private var state = AniobCapabilityManifest.DeviceState()
    private val registry = AniobCapabilityRegistry.deviceCommands { state }

    @Test fun `validated arguments become the canonical device action`() {
        val invocation = CapabilityInvocation("volume_set", mapOf("percent" to CapabilityValue.Decimal(BigDecimal("42.5"))))
        val result = AniobToolCallParser.capabilityToAction(invocation, registry) as AniobToolCallParser.CapabilityConversion.Proposed
        assertEquals(DeviceCommand.VOLUME_SET, result.action.command)
        assertEquals("42.5", result.action.args["percent"])
    }

    @Test fun `unknown invalid and newly unavailable invocations never become actions`() {
        assertTrue(AniobToolCallParser.capabilityToAction(CapabilityInvocation("flashlight_on"), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
        assertTrue(AniobToolCallParser.capabilityToAction(CapabilityInvocation("volume_set", mapOf("percent" to CapabilityValue.Text("wrong"))), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
        state = state.copy(notificationAccess = false)
        assertTrue(AniobToolCallParser.capabilityToAction(CapabilityInvocation("otp_copy"), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
    }

    @Test fun `legacy device arguments pass the same typed registry and live availability`() {
        val converted = AniobToolCallParser.legacyDeviceAction(DeviceCommand.VOLUME_SET, mapOf("percent" to "42.5"), registry)
        assertTrue(converted is AniobToolCallParser.CapabilityConversion.Proposed)
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.VOLUME_SET, mapOf("percent" to "NaN"), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.VOLUME_SET, mapOf("percent" to "42", "invented" to "x"), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.VOLUME_SET, emptyMap(), registry) is AniobToolCallParser.CapabilityConversion.Rejected)
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.ALARM_SET, mapOf("hour" to "7"), registry) is AniobToolCallParser.CapabilityConversion.Proposed)
        val call = mapOf("number" to "+1234567", "speaker" to "TRUE")
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.CALL, call, registry) is AniobToolCallParser.CapabilityConversion.Proposed)
        state = state.copy(callPhone = false)
        assertTrue(AniobToolCallParser.legacyDeviceAction(DeviceCommand.CALL, call, registry) is AniobToolCallParser.CapabilityConversion.Rejected)
    }

    @Test fun `permission revoked during approval blocks the actual device dispatch`() = runBlocking {
        val wireArgs = mapOf("number" to "+1234567")
        val proposed = AniobToolCallParser.legacyDeviceAction(DeviceCommand.CALL, wireArgs, registry)
            as AniobToolCallParser.CapabilityConversion.Proposed
        val screen = AniobScreenState(packageName = "com.example", treeHash = "h")
        var deviceCalls = 0
        val result = StepPipeline().executeStep(
            TaskContext(instruction = "Call +1234567"), screen,
            StepPipeline.Proposal.Model(proposed.action, "FAKE"), capture = { screen },
            confirm = { _, _, _ -> state = state.copy(callPhone = false); true },
            dispatch = {
                val live = AniobToolCallParser.legacyDeviceAction(DeviceCommand.CALL, wireArgs, registry)
                if (live is AniobToolCallParser.CapabilityConversion.Proposed) deviceCalls++
                StepPipeline.DispatchOutcome(live is AniobToolCallParser.CapabilityConversion.Proposed)
            }
        )
        assertEquals(0, deviceCalls)
        assertEquals(StepResult.TerminalState.FAIL, result.terminalState)
    }

    @Test fun `known contact entity crosses the guarded pipeline without becoming string data`() = runBlocking {
        val ref = com.aniob.core.domain.EntityReference("contact", "opaque-contact-id")
        val invocation = CapabilityInvocation("call", mapOf("contact" to CapabilityValue.Entity(ref)))
        val action = (AniobToolCallParser.capabilityToAction(invocation, registry)
            as AniobToolCallParser.CapabilityConversion.Proposed).action
        assertEquals(mapOf("contact" to ref), action.entityArguments)
        assertFalse(action.args.values.any { it.contains(ref.id) })
        val screen = AniobScreenState(packageName = "com.example", treeHash = "h")
        var dispatched = false
        val result = StepPipeline().executeStep(
            TaskContext(instruction = "Call the contact I selected"), screen,
            StepPipeline.Proposal.Model(action, "STRUCTURED_PLAN"), capture = { screen },
            confirm = { _, _, _ -> true },
            dispatch = { dispatched = true; StepPipeline.DispatchOutcome(true, "accepted") }
        )
        assertTrue(dispatched)
        assertEquals(StepResult.TerminalState.CONTINUE, result.terminalState)
        assertEquals(com.aniob.core.verifier.DeterministicVerifier.Status.UNCONFIRMED, result.verification?.status)
    }

    @Test fun `a converted call still requires the shared approval gate`() = runBlocking {
        val invocation = CapabilityInvocation("call", mapOf("number" to CapabilityValue.Text("+1234567")))
        val action = (AniobToolCallParser.capabilityToAction(invocation, registry) as AniobToolCallParser.CapabilityConversion.Proposed).action
        val screen = AniobScreenState(packageName = "com.example", treeHash = "h")
        var dispatched = false
        var asked = false
        val result = StepPipeline().executeStep(
            ctx = TaskContext(instruction = "Call +1234567"),
            planningScreen = screen,
            proposal = StepPipeline.Proposal.Model(action, "FAKE"),
            capture = { screen },
            confirm = { _, _, _ -> asked = true; false },
            dispatch = { dispatched = true; StepPipeline.DispatchOutcome(true) }
        )
        assertTrue(asked)
        assertFalse(dispatched)
        assertEquals(StepResult.TerminalState.FAIL, result.terminalState)
    }
}
