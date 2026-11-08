package com.aniob.core.execution

import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext
import com.aniob.core.verify.FastVerify
import kotlinx.coroutines.CancellationException

/**
 * Executes a bundled plan ([ActionBurst]) one member at a time, and decides after each one whether
 * the plan still fits the screen.
 *
 * This is the missing half of the multi-action contract. The pieces each existed and were correct
 * on their own — the parser, the guard, the step pipeline — but a plan was only ever as good as the
 * screen it was written on: the loop executed members back to back, dispatched the tap that opens
 * the next screen, and then tried to type into a text field of the *previous* screen. The action
 * missed, verification failed, the loop counted a failure, and the task that should have taken one
 * model call took three round trips plus a recovery. Every member after the first screen change was
 * wasted, and the burst mode could be slower than the baseline it was meant to beat.
 *
 * The rule implemented here is the one a person follows: **after an action, look again**. A member
 * is dispatched only if the screen in front of us still shows its target; otherwise the plan is
 * closed at that point, whatever already ran is kept (it really happened), and the loop plans the
 * next step from a fresh observation. A plan is never allowed to fail the task by being stale.
 *
 * Pure JVM: capture, dispatch and the per-step pipeline are lambdas, so every branch is unit-
 * testable without a device. `StepPipeline.executeStep` is called for each member exactly as it is
 * for a single-action step — a bundled plan gets no shortcut through safety, gating or
 * verification.
 */
class ActionBurstRunner(
    /** Re-reads the window before a target-bearing first member and between later members. */
    private val capture: suspend () -> AniobScreenState?,
    /**
     * Runs one member through the full step pipeline (safety → dispatch → settle → verify →
     * reflect → record) against [AniobScreenState] `planningScreen`.
     */
    private val executeMember: suspend (
        member: AniobAction,
        ctx: TaskContext,
        planningScreen: AniobScreenState,
        targetNode: AniobNode?
    ) -> StepResult,
    /** Resolves a member's target on a live screen; injected so tests can supply a fixed tree. */
    private val resolveTarget: (AniobAction, AniobScreenState) -> AniobNode? = { action, screen ->
        com.aniob.core.perception.BurstPlanScope.resolves(action, screen)
    },
    /**
     * Number of members that may run without a fresh capture in between. 1 means "always look
     * again after an action", which is what the plain baseline condition does.
     */
    private val captureEvery: Int = 1
) {

    private fun targetOnOrigin(member: AniobAction, origin: AniobScreenState): AniobNode? {
        val semantic = member.semanticTarget()
        return if (member is AniobAction.InputText &&
            semantic is com.aniob.core.domain.SemanticTarget.SomIndex &&
            semantic.index == ActionBurst.UNNAMED_TARGET
        ) {
            com.aniob.core.perception.BurstPlanScope.soleEditable(origin)
        } else {
            resolveTarget(member, origin)
        }
    }

    private fun validateFreshTarget(
        member: AniobAction,
        origin: AniobScreenState,
        fresh: AniobScreenState
    ): com.aniob.core.perception.BurstTargetGuard.Verdict {
        if (com.aniob.core.perception.BurstTargetGuard.screenNavigated(origin, fresh)) {
            return com.aniob.core.perception.BurstTargetGuard.Verdict(
                allowed = false,
                reason = "screen navigated to ${fresh.packageName}"
            )
        }
        val semantic = member.semanticTarget()
        if (member is AniobAction.InputText &&
            semantic is com.aniob.core.domain.SemanticTarget.SomIndex &&
            semantic.index == ActionBurst.UNNAMED_TARGET
        ) {
            val plannedField = com.aniob.core.perception.BurstPlanScope.soleEditable(origin)
                ?: return com.aniob.core.perception.BurstTargetGuard.Verdict(
                    allowed = false,
                    reason = "the unnamed input no longer has one editable field"
                )
            if (plannedField.isPassword) {
                return com.aniob.core.perception.BurstTargetGuard.Verdict(
                    allowed = false,
                    reason = "unnamed input is not allowed for a protected field"
                )
            }
            val liveField = com.aniob.core.perception.BurstTargetGuard.match(fresh.nodes, plannedField)
                ?: return com.aniob.core.perception.BurstTargetGuard.Verdict(
                    allowed = false,
                    reason = "the unnamed input field is no longer on screen"
                )
            if (liveField.isPassword) {
                return com.aniob.core.perception.BurstTargetGuard.Verdict(
                    allowed = false,
                    reason = "unnamed input is not allowed for a protected field"
                )
            }
            return if (liveField.isEnabled) {
                com.aniob.core.perception.BurstTargetGuard.Verdict(true, matchedNode = liveField)
            } else {
                com.aniob.core.perception.BurstTargetGuard.Verdict(false, "the unnamed input field is disabled")
            }
        }
        return com.aniob.core.perception.BurstTargetGuard.check(member, origin, fresh)
    }

    /** Why a plan stopped early. */
    enum class Stop {
        /** Every member ran. */
        NONE,

        /** A member's target is not on the screen any more: re-plan from a fresh capture. */
        TARGET_GONE,

        /** The screen navigated to another package: re-plan from there. */
        NAVIGATED,

        /** A member was blocked by safety or could not be dispatched: the plan is closed. */
        BLOCKED,

        /** The run was stopped or paused by the user. */
        ABORTED,

        /** Capturing the screen failed; never dispatch blind. */
        NO_CAPTURE
    }

    /**
     * @param ran members that were actually executed, in order.
     * @param lastResult the [StepResult] of the last executed member (null when none ran).
     * @param ctx the task context after the executed members.
     * @param screen the live screen after the last executed member.
     * @param stop why the plan stopped; [Stop.NONE] means it ran to the end.
     * @param completed the plan claimed completion and every member of it ran.
     * @param detail human-readable reason for [stop], for the event log and the transcript.
     */
    data class Outcome(
        val ran: List<AniobAction>,
        val lastResult: StepResult?,
        val ctx: TaskContext,
        val screen: AniobScreenState,
        val stop: Stop,
        val detail: String = "",
        val completed: Boolean = false,
        /** Grounded semantic target lost before dispatch, when this was a true target miss. */
        val missedAction: AniobAction? = null,
        /** Stable identity of the target on the screen the plan was based on. */
        val missedNode: AniobNode? = null
    ) {
        /** True when the next step must be planned from a new observation. */
        val shouldReplan: Boolean
            get() = stop == Stop.TARGET_GONE || stop == Stop.NAVIGATED

        /** True when the run must not continue at all. */
        val isHardStop: Boolean
            get() = stop == Stop.BLOCKED || stop == Stop.ABORTED
    }

    /**
     * Runs [plan] against [origin], the observation the plan was made for.
     *
     * [shouldContinue] is polled before every member so a stop or pause request interrupts the plan
     * between actions rather than after it.
     */
    suspend fun run(
        plan: ActionBurst,
        origin: AniobScreenState,
        ctx: TaskContext,
        shouldContinue: () -> Boolean = { true }
    ): Outcome {
        val ran = ArrayList<AniobAction>(plan.actions.size)
        var currentCtx = ctx
        var currentScreen = origin
        var lastResult: StepResult? = null
        var dispatchedSinceCapture = 0

        if (plan.actions.isEmpty()) {
            return Outcome(ran, null, currentCtx, currentScreen, Stop.NONE, completed = plan.isComplete)
        }

        for ((index, member) in plan.actions.withIndex()) {
            if (!shouldContinue()) {
                return Outcome(ran, lastResult, currentCtx, currentScreen, Stop.ABORTED, "the run stopped")
            }

            // Re-read before the first element action too: the observation may have gone stale
            // between planning and dispatch. Later actions are re-read so prior actions cannot
            // silently retarget them to a different widget.
            var liveScreen = currentScreen
            var matchedTarget: AniobNode? = null
            val mustRefresh = (index == 0 && member.semanticTarget() != null) ||
                (index > 0 && dispatchedSinceCapture >= captureEvery)
            if (mustRefresh) {
                val fresh = try {
                    capture()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (fresh == null) {
                    return Outcome(
                        ran, lastResult, currentCtx, currentScreen, Stop.NO_CAPTURE,
                        "could not read the screen before action ${index + 1}; not dispatching blind"
                    )
                }
                val verdict = validateFreshTarget(member, origin, fresh)
                if (!verdict.allowed) {
                    val stop = when {
                        verdict.reason.startsWith("screen navigated") -> Stop.NAVIGATED
                        else -> Stop.TARGET_GONE
                    }
                    return Outcome(
                        ran, lastResult, currentCtx, fresh, stop,
                        "action ${index + 1} (${member.toolName}) dropped: ${verdict.reason}",
                        missedAction = member.takeIf {
                            it.semanticTarget() != null && stop == Stop.TARGET_GONE
                        },
                        missedNode = if (stop == Stop.TARGET_GONE && member.semanticTarget() != null) {
                            targetOnOrigin(member, origin)
                        } else null
                    )
                }
                matchedTarget = verdict.matchedNode
                liveScreen = fresh
                dispatchedSinceCapture = 0
            }

            // The guard matched the original widget by identity, not by its recycled prompt ID.
            // Carry that node into dispatch rather than resolving the old ID on the new screen.
            val target = matchedTarget ?: resolveTarget(member, liveScreen)
            val dispatchMember = if (target != null && member.semanticTarget() is com.aniob.core.domain.SemanticTarget.SomIndex) {
                val liveTarget = com.aniob.core.domain.SemanticTarget.SomIndex(target.id)
                when (member) {
                    is AniobAction.Tap -> member.copy(target = liveTarget)
                    is AniobAction.LongPress -> member.copy(target = liveTarget)
                    is AniobAction.InputText -> member.copy(target = liveTarget)
                    else -> member
                }
            } else member
            val result = executeMember(dispatchMember, currentCtx, liveScreen, target)
            lastResult = result
            ran.add(result.action)
            currentCtx = result.nextCtx
            currentScreen = result.nextCtx.lastScreenState ?: liveScreen
            dispatchedSinceCapture++

            when (result.terminalState) {
                StepResult.TerminalState.SUCCESS ->
                    return Outcome(ran, result, currentCtx, currentScreen, Stop.NONE, completed = true)
                StepResult.TerminalState.FAIL ->
                    // A safety block is final; anything else may simply be a plan that no longer
                    // fits the screen, which the next observation resolves.
                    return Outcome(
                        ran, result, currentCtx, currentScreen, Stop.BLOCKED,
                        "action ${index + 1} (${member.toolName}) was blocked or failed: " +
                            (result.verification?.reason ?: ""),
                        missedAction = member.takeIf {
                            it.semanticTarget() != null &&
                                (result.dispatch as? DispatchCommand.NoOp)?.reason?.startsWith("grounding_miss:") == true
                        },
                        missedNode = result.resolvedNode
                    )
                StepResult.TerminalState.CONTINUE -> Unit
            }
        }

        return Outcome(ran, lastResult, currentCtx, currentScreen, Stop.NONE, completed = plan.isComplete)
    }

    companion object {
        /**
         * Identity of a screen for the "did anything change" question. Same definition the
         * verification policy uses, so the runner and the verifier can never disagree about
         * whether the screen moved.
         */
        fun screenIdentity(screen: AniobScreenState): String = FastVerify.screenIdentity(screen)
    }
}
