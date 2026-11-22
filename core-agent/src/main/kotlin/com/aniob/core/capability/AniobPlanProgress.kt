package com.aniob.core.capability

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.DeviceRisk
import com.aniob.core.execution.StepResult

/**
 * Decides only whether another proposal may be considered. Dispatch acceptance here is never
 * completion evidence: the task still closes through independent verification. No action is run.
 */
object AniobPlanProgress {
    sealed interface Decision {
        data class Next(val index: Int) : Decision
        data object AwaitSelection : Decision
        data object Stop : Decision
    }

    fun afterStep(
        plan: AniobTaskPlan,
        index: Int,
        expected: AniobAction.DeviceControl?,
        result: StepResult,
        hasPendingEntityChoice: Boolean
    ): Decision {
        val actual = result.action as? AniobAction.DeviceControl
        val matches = actual != null && expected != null &&
            actual.command == expected.command && actual.args == expected.args &&
            actual.entityArguments == expected.entityArguments &&
            plan.invocations.getOrNull(index)?.capabilityId == actual.command.wire
        if (!matches) return Decision.Stop
        if (!result.dispatched || result.terminalState != StepResult.TerminalState.CONTINUE) {
            return if (hasPendingEntityChoice && !result.dispatched) Decision.AwaitSelection else Decision.Stop
        }
        // Calls opening an editor / external screen or requiring explicit approval cannot be
        // blindly followed by another call, even after the approved first dispatch.
        if (!actual!!.command.completesOnDispatch || actual.command.risk == DeviceRisk.CONFIRM) return Decision.Stop
        val next = index + 1
        return if (next < plan.invocations.size) Decision.Next(next) else Decision.Stop
    }
}
