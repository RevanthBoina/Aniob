package com.aniob.core.capability

import com.aniob.core.domain.*
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepResult
import org.junit.Assert.*
import org.junit.Test

class AniobPlanProgressTest {
    private val action = AniobAction.DeviceControl(DeviceCommand.TORCH_ON)
    private val plan = AniobTaskPlan(AniobTaskPlan.Mode.EXECUTE, "turn light on and off", listOf(
        CapabilityInvocation("torch_on"), CapabilityInvocation("torch_off")
    ))
    private fun result(action: AniobAction = this.action, dispatched: Boolean = true,
        state: StepResult.TerminalState = StepResult.TerminalState.CONTINUE) = StepResult(
        action, DispatchCommand.SystemIntent("DEVICE_CONTROL"), nextCtx = TaskContext("goal"),
        terminalState = state, dispatched = dispatched
    )

    @Test fun `accepted dispatch advances but is not a completion verdict`() {
        assertEquals(AniobPlanProgress.Decision.Next(1),
            AniobPlanProgress.afterStep(plan, 0, action, result(), false))
    }

    @Test fun `denied blocked different and stale calls do not advance`() {
        val rejected = result(dispatched = false, state = StepResult.TerminalState.FAIL)
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(plan, 0, action, rejected, false))
        assertEquals(AniobPlanProgress.Decision.AwaitSelection, AniobPlanProgress.afterStep(plan, 0, action, rejected, true))
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(plan, 1, action, result(), false))
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(plan, 0, action,
            result(AniobAction.DeviceControl(DeviceCommand.TORCH_OFF)), false))
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(plan, 0, action,
            result(state = StepResult.TerminalState.UNVERIFIED), false))
    }

    @Test fun `approved high risk dispatch interrupts the queue for fresh planning`() {
        val call = AniobAction.DeviceControl(DeviceCommand.CALL, mapOf("number" to "+15551234567"))
        val callPlan = AniobTaskPlan(AniobTaskPlan.Mode.EXECUTE, "call and switch on torch", listOf(
            CapabilityInvocation("call", mapOf("number" to CapabilityValue.Text("+15551234567"))),
            CapabilityInvocation("torch_on")
        ))
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(callPlan, 0, call, result(call), false))
    }

    @Test fun `opening an editor interrupts the queue for fresh planning`() {
        val sms = AniobAction.DeviceControl(DeviceCommand.MESSAGE_SMS, mapOf("phone" to "123"))
        val editorPlan = AniobTaskPlan(AniobTaskPlan.Mode.EXECUTE, "message", listOf(
            CapabilityInvocation("sms_message"), CapabilityInvocation("torch_on")))
        assertEquals(AniobPlanProgress.Decision.Stop, AniobPlanProgress.afterStep(editorPlan, 0, sms, result(sms), false))
    }
}
