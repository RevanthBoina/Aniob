package com.aniob.core.execution

import com.aniob.core.config.AgentLimits
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.TaskContext
import com.aniob.core.learning.LearningPipeline
import com.aniob.core.memory.history.NeutralWordingContract
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The ONE step loop every ladder rung shares (finding #1).
 *
 * `executeTaskPipeline` used to be a ~500-line God-function with four near-identical branches
 * (DirectIntent / FastPath / Skill / Model), each privately duplicating safety -> dispatch ->
 * sleep -> capture -> verify -> reflect -> record. Any upgrade applied per-branch drifted four
 * ways within weeks.
 *
 * [executeStep] runs the full ordered pipeline once:
 *
 * ```
 * OBSERVE -> PROPOSE -> GATE -> DISPATCH -> SETTLE -> VERIFY -> REFLECT -> RECORD
 * ```
 *
 * Rungs differ ONLY in [Proposal]; everything downstream is shared. All Android and LLM touch
 * points are injected as lambdas, so the whole loop is JVM-testable without a device.
 */
class StepPipeline(
    private val limits: AgentLimits = AgentLimits.DEFAULT,
    private val learning: LearningPipeline = LearningPipeline.NoOp,
    private val reflectionAgent: AniobReflectionAgent = AniobReflectionAgent(),
    /** Fan-out for the unified recording contract (execution tracker + hippocampus + event log). */
    private val recordStep: (StepRecord) -> Unit = {},
    /** Settle seam: pause for quiescence after the action and return a fresh screen. */
    private val settle: suspend (AniobAction, suspend () -> AniobScreenState) -> AniobScreenState = { _, capture -> capture() }
) {

    /** A proposed next action plus the rung that produced it. Rungs differ only here. */
    sealed interface Proposal {
        val action: AniobAction
        val providerLabel: String

        data class DirectIntent(override val action: AniobAction, val label: String = "INTENT") : Proposal {
            override val providerLabel: String get() = label
        }

        data class FastPath(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "FASTPATH"
        }

        data class Skill(
            override val action: AniobAction,
            val skillName: String = "",
            val riskTier: String = "LOW",
            val requiresConfirmation: Boolean = false
        ) : Proposal {
            override val providerLabel: String = "SKILL"
        }

        data class Model(override val action: AniobAction, val provider: String) : Proposal {
            override val providerLabel: String get() = provider
        }

        data class WatchdogBack(override val action: AniobAction = AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK)) : Proposal {
            override val providerLabel: String = "WATCHDOG"
        }

        data class ScrollRetry(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "SCROLL_RETRY"
        }

        data class ReflectionRemedial(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "REFLECTION"
        }

        data class SessionRecovery(override val action: AniobAction) : Proposal {
            override val providerLabel: String = "SESSION_RECOVERY"
        }
    }

    /** What actually happened when the action was dispatched. */
    data class DispatchOutcome(
        val dispatched: Boolean,
        val reason: String = "",
        val screenAfter: AniobScreenState? = null
    )

    /** One recorded step, fanned out through [recordStep]. */
    data class StepRecord(
        val stepIndex: Int,
        val action: AniobAction,
        val provider: String,
        val dispatched: Boolean,
        val verified: Boolean,
        val failureReason: String?,
        val resolvedNode: AniobNode?,
        val latencyMs: Long,
        val tokensUsed: Int,
        val textEvidence: String? = null,
        val targetBounds: AniobRect? = resolvedNode?.bounds,
        val blockedBySafety: Boolean = false,
        val blockedFingerprint: String? = null
    )

    suspend fun executeStep(
        ctx: TaskContext,
        planningScreen: AniobScreenState,
        proposal: Proposal,
        capture: suspend () -> AniobScreenState,
        dispatch: suspend (AniobAction) -> DispatchOutcome,
        confirm: suspend (String) -> Boolean = { false },
        judge: (String) -> ReflectionOutcome = { ReflectionOutcome.rejected(it, it) },
        targetNode: AniobNode? = null
    ): StepResult {
        currentCoroutineContext().ensureActive()
        val start = System.currentTimeMillis()
        val action = proposal.action

        // OBSERVE: always a fresh capture. Failed capture must not silently reuse stale planning data!
        val observed = runCatching { capture() }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, planningScreen, NeutralWordingContract.neutralize("capture did not return a screen: ${it.message}")).withFailure()
            return finish(
                action, proposal, nextCtx, planningScreen, null,
                StepResult.TerminalState.FAIL, NeutralWordingContract.neutralize("capture did not return a screen: ${it.message}"),
                start, verification = null, dispatched = false
            )
        }

        currentCoroutineContext().ensureActive()

        // Plan-7.5: Prompt Injection Canary check on state-mutating actions
        val isMutating = action is AniobAction.Tap || action is AniobAction.LongPress ||
            action is AniobAction.InputText || action is AniobAction.OpenApp || action is AniobAction.Swipe
        val canaryParams = buildMap {
            if (action is AniobAction.InputText && !ctx.instruction.contains(action.text, ignoreCase = true)) {
                put("text", action.text)
            }
            if (action is AniobAction.OpenApp && !ctx.instruction.contains(action.packageName, ignoreCase = true)) {
                put("package_name", action.packageName)
            }
            if (com.aniob.core.safety.AniobInjectionCanary.containsDelimiter(action.thought)) {
                put("thought", action.thought)
            }
        }
        val canaryVerdict = com.aniob.core.safety.AniobInjectionCanary.evaluate(
            toolName = action.toolName,
            isStateMutating = isMutating,
            params = canaryParams,
            lastTwoObservations = listOf(
                planningScreen.nodes.joinToString("\n") { "${it.text} | ${it.contentDescription}" },
                observed.nodes.joinToString("\n") { "${it.text} | ${it.contentDescription}" }
            )
        )
        if (canaryVerdict.blocked) {
            val reason = canaryVerdict.message ?: "Blocked by prompt injection canary"
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, NeutralWordingContract.neutralize(reason)).withFailure()
            return finish(
                action, proposal, nextCtx, observed, null,
                StepResult.TerminalState.FAIL, reason, start,
                verification = null, dispatched = false
            )
        }

        // GATE: safety sees the *resolved node*, not a substring of toString()
        val resolvedNode = targetNode ?: AniobActionExecutor.resolveTarget(action, observed)?.node
        val skillProposal = proposal as? Proposal.Skill
        val intercept = AniobSafetyInterceptor.evaluateAction(
            action = action,
            targetNode = resolvedNode,
            screenState = observed,
            screenFingerprint = observed.treeHash,
            skillRisk = skillProposal?.riskTier,
            skillRequiresConfirmation = skillProposal?.requiresConfirmation == true
        )
        if (!intercept.isAllowed) {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, NeutralWordingContract.neutralize("blocked before execution: ${intercept.reason}"))
                .withFailure()
            return finish(
                action, proposal, nextCtx, observed, resolvedNode,
                StepResult.TerminalState.FAIL, intercept.reason, start,
                verification = null, dispatched = false,
                blockedBySafety = true,
                blockedFingerprint = observed.treeHash.takeIf { it.isNotBlank() }
            )
        }

        // Plan-6.4: Pre-Execution Physical Safety Net Gate (Gate B)
        var effectiveAction = action
        var effectiveNode = resolvedNode
        if (resolvedNode != null) {
            val gateB = com.aniob.core.gates.AniobPhysicalSafetyNetGate.evaluate(
                targetNode = resolvedNode,
                targetX = resolvedNode.centerX,
                targetY = resolvedNode.centerY,
                liveScreen = observed
            )
            if (!gateB.allowed) {
                val nextCtx = ctx.withStep(
                    ctx.stepIndex + 1,
                    observed,
                    NeutralWordingContract.neutralize("blocked by physical safety gate: ${gateB.reason}")
                ).withFailure()
                return finish(
                    action, proposal, nextCtx, observed, resolvedNode,
                    StepResult.TerminalState.FAIL, gateB.reason, start,
                    verification = null, dispatched = false
                )
            }
            val healed = gateB.healedTarget
            if (healed != null && healed.id != resolvedNode.id) {
                effectiveNode = healed
                val healedTarget = when {
                    healed.viewId.isNotBlank() -> com.aniob.core.domain.SemanticTarget.ResourceId(healed.viewId)
                    healed.text.isNotBlank() -> com.aniob.core.domain.SemanticTarget.Text(healed.text, exact = true)
                    healed.contentDescription.isNotBlank() -> com.aniob.core.domain.SemanticTarget.ContentDesc(healed.contentDescription, exact = true)
                    else -> com.aniob.core.domain.SemanticTarget.BoundingBox(healed.bounds.left, healed.bounds.top, healed.bounds.right, healed.bounds.bottom)
                }
                effectiveAction = when (action) {
                    is AniobAction.Tap -> action.copy(target = healedTarget)
                    is AniobAction.LongPress -> action.copy(target = healedTarget)
                    is AniobAction.InputText -> action.copy(target = healedTarget)
                    else -> action
                }
            }
        }

        // Typing into a search field is only half an action: the box holds the query, but nothing
        // is searched until the keyboard's own action key is pressed. Models rarely say so, and a
        // separate "find the enter key" step is exactly what they fail at, so submit is derived
        // from the grounded field metadata rather than the user's wording.
        val typed = effectiveAction as? AniobAction.InputText
        if (typed != null && !typed.submit && effectiveNode != null) {
            val field = effectiveNode
            val isSearchField = field.viewId.contains("search", ignoreCase = true) ||
                field.contentDescription.contains("search", ignoreCase = true) ||
                field.className.contains("search", ignoreCase = true)
            if (isSearchField) {
                effectiveAction = typed.copy(submit = true)
            }
        }

        if (intercept.requiresConfirmation) {
            currentCoroutineContext().ensureActive()
            val approved = confirm(effectiveAction.describeAction())
            currentCoroutineContext().ensureActive()
            if (!approved) {
                val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, "the user declined: ${effectiveAction.describeAction()}")
                    .withFailure()
                return finish(
                    effectiveAction, proposal, nextCtx, observed, effectiveNode,
                    StepResult.TerminalState.FAIL, "User declined confirmation", start,
                    verification = null, dispatched = false
                )
            }
        }

        // DISPATCH: grounding is redone against the live tree inside the seam; a miss is never a tap.
        currentCoroutineContext().ensureActive()
        val outcome = runCatching { dispatch(effectiveAction) }
            .getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                DispatchOutcome(dispatched = false, reason = "dispatch threw: ${it.message}")
            }

        val liveCommand = AniobActionExecutor.planDispatch(effectiveAction, observed)

        // DispatchOutcome.dispatched=false must prevent a verified action
        if (!outcome.dispatched) {
            val nextCtx = ctx.withStep(ctx.stepIndex + 1, observed, NeutralWordingContract.neutralize("dispatch did not complete: ${outcome.reason}")).withFailure()
            return finish(
                effectiveAction, proposal, nextCtx, observed, effectiveNode,
                StepResult.TerminalState.FAIL, outcome.reason.ifBlank { "Action not dispatched" },
                start, verification = null, dispatch = liveCommand, dispatched = false
            )
        }

        currentCoroutineContext().ensureActive()

        // SETTLE: idle-gate instead of a fixed sleep
        val screenAfter = outcome.screenAfter ?: runCatching { settle(effectiveAction, capture) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            observed
        }

        currentCoroutineContext().ensureActive()

        // VERIFY
        val verification = if (proposal is Proposal.DirectIntent) {
            val summary = (effectiveAction as? AniobAction.Finish)?.summary ?: effectiveAction.describeAction()
            if (ctx.successCriteria != null && ctx.successCriteria.hasObservableCheck) {
                DeterministicVerifier.verifyFinish(ctx.successCriteria, screenAfter, ctx.stepIndex + 1)
            } else {
                DeterministicVerifier.VerificationResult(
                    isExpected = false,
                    durationMs = 0L,
                    reason = "Direct intent dispatched: $summary (unverified: no observable completion evidence)"
                )
            }
        } else if (effectiveAction is AniobAction.Finish) {
            DeterministicVerifier.verifyFinish(ctx.successCriteria, screenAfter, ctx.stepIndex + 1)
        } else {
            DeterministicVerifier.verify(effectiveAction, observed, screenAfter)
        }

        // REFLECT
        var nextCtx = ctx.withStep(ctx.stepIndex + 1, screenAfter, historyEntry(effectiveAction, verification, proposal))
            .withAction(effectiveAction)
        var reflection: ReflectionOutcome? = null
        var terminal = StepResult.TerminalState.CONTINUE

        if (effectiveAction is AniobAction.Fail) {
            terminal = StepResult.TerminalState.FAIL
        } else if (effectiveAction is AniobAction.Finish) {
            // Finish is PROVISIONAL: it only becomes SUCCESS with evidence
            val verdict = if (verification.isExpected) {
                ReflectionOutcome.confirmed(verification.reason)
            } else {
                reflectionAgent.reflectFinish(
                    criteria = ctx.successCriteria,
                    finalScreen = screenAfter,
                    steps = ctx.stepIndex + 1,
                    deterministic = verification,
                    llmJudge = judge
                )
            }
            reflection = verdict
            if (verdict.confirmed) {
                terminal = StepResult.TerminalState.SUCCESS
            } else {
                nextCtx = nextCtx.withRejectedFinish(verdict.feedback)
                    .withFailure()
                if (nextCtx.rejectedFinishCount >= MAX_REJECTED_FINISHES || proposal is Proposal.DirectIntent) {
                    terminal = StepResult.TerminalState.FAIL
                }
            }
        } else if (!verification.isExpected) {
            nextCtx = nextCtx.withFailure()
            val legacy = reflectionAgent.reflect(observed, screenAfter, effectiveAction, verifiedSuccess = false)
            reflection = ReflectionOutcome(
                confirmed = false,
                reason = legacy.reason,
                feedback = legacy.reason,
                recoveryStrategy = legacy.remedialAction?.describeAction()
            )
            if (legacy.remedialAction is AniobAction.Fail) {
                terminal = StepResult.TerminalState.FAIL
            } else if (nextCtx.consecutiveFailures >= limits.watchdogLoopThreshold) {
                // Loop guard: hand back a remedial action rather than spinning on the same miss.
                // Repeated failure must NOT call withSuccess() to reset the counter!
                nextCtx = nextCtx.withStep(
                    nextCtx.stepIndex,
                    screenAfter,
                    NeutralWordingContract.neutralize(
                        "watchdog threshold (${limits.watchdogLoopThreshold}) reached: ${legacy.remedialAction?.describeAction() ?: "backtrack recommended"}"
                    )
                )
            }
        } else {
            nextCtx = nextCtx.withSuccess()
        }

        return finish(
            effectiveAction, proposal, nextCtx, screenAfter, effectiveNode,
            terminal, verification.reason, start, verification, reflection, liveCommand,
            dispatched = true,
            learningScreen = observed
        )
    }

    private fun finish(
        action: AniobAction,
        proposal: Proposal,
        nextCtx: TaskContext,
        screen: AniobScreenState,
        resolvedNode: AniobNode?,
        terminal: StepResult.TerminalState,
        reason: String,
        start: Long,
        verification: DeterministicVerifier.VerificationResult?,
        reflection: ReflectionOutcome? = null,
        dispatch: DispatchCommand? = null,
        dispatched: Boolean = true,
        learningScreen: AniobScreenState = screen,
        blockedBySafety: Boolean = false,
        blockedFingerprint: String? = null
    ): StepResult {
        val verified = verification?.isExpected == true && terminal != StepResult.TerminalState.FAIL && dispatched
        val computedTokens = (action.describeAction().length + screen.treeHash.length) / 4 + 50
        val record = StepRecord(
            stepIndex = nextCtx.stepIndex,
            action = action,
            provider = proposal.providerLabel,
            dispatched = dispatched,
            verified = verified,
            failureReason = if (verified) null else reason,
            resolvedNode = resolvedNode,
            latencyMs = System.currentTimeMillis() - start,
            tokensUsed = computedTokens,
            textEvidence = verification?.textEvidence,
            targetBounds = resolvedNode?.bounds,
            blockedBySafety = blockedBySafety,
            blockedFingerprint = blockedFingerprint
        )
        // RECORD: one call site, verified-only for learning (failures must never distill).
        recordStep(record)
        if (verified && dispatched) {
            learning.onVerifiedStep(nextCtx, action, learningScreen)
            if (terminal == StepResult.TerminalState.SUCCESS) {
                learning.onTaskSuccess(nextCtx, nextCtx.trajectory)
            }
        }
        return StepResult(
            action = action,
            dispatch = dispatch ?: DispatchCommand.NoOp("pipeline"),
            verification = verification,
            reflection = reflection,
            nextCtx = nextCtx.addTokens(computedTokens),
            terminalState = terminal,
            resolvedNode = resolvedNode,
            tokensUsed = computedTokens,
            latencyMs = record.latencyMs
        )
    }

    private fun historyEntry(
        action: AniobAction,
        verification: DeterministicVerifier.VerificationResult,
        proposal: Proposal
    ): String = "${proposal.providerLabel}: ${action.describeAction()} -> " +
        if (verification.isExpected) "the expected change was observed"
        else "the expected change was not observed (" +
            NeutralWordingContract.neutralize(verification.reason) + ")"

    companion object {
        /** A Finish rejected this many times becomes a hard failure (no infinite retry, no fake SUCCESS). */
        const val MAX_REJECTED_FINISHES = 3
    }
}