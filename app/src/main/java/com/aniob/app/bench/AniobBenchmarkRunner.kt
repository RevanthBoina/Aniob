package com.aniob.app.bench

import com.aniob.core.bench.*
import com.aniob.core.bench.AniobBenchmarkManifest.Track
import com.aniob.core.domain.*
import com.aniob.core.execution.AniobActionExecutor
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.external.ChatTurn
import com.aniob.core.memory.RememberCache
import com.aniob.core.providers.AniobNativeLlmEngine
import com.aniob.core.providers.AniobToolCallParser
import com.aniob.core.text.AniobTextFold
import com.aniob.core.skills.AniobSkillEngine
import com.aniob.core.skills.AniobSkillMatcher
import com.aniob.core.auth.AniobSecretRedactor
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/** Controlled runner. It does not read or write the user's conversation, replay or skill stores. */
class AniobBenchmarkRunner(
    private val environment: BenchmarkEnvironment,
    private val generator: BenchmarkGenerator,
    private val cooldownMs: Long = 1000L,
    private val nanoTime: () -> Long = System::nanoTime
) {
    init { require(cooldownMs >= 0) }

    private val runLock = Mutex()

    /** [record] can persist each trial immediately so completed trials survive an interrupted study. */
    suspend fun runBenchmark(plan: BenchmarkPlan, record: (BenchmarkTrialResult) -> Unit = {}): List<BenchmarkTrialResult> = runLock.withLock {
        require(plan.runtime == environment.runtime) { "Benchmark runtime does not match the pre-registered plan" }
        require(plan.runtime.decoding == generator.decoding) { "Generator decoding does not match the pre-registered plan" }
        val schedule = plan.schedule()
        val runIds = schedule.map { it.runId }.toSet()
        require(plan.cases.none { case -> (listOfNotNull(case.experience) +
            case.retrieval?.candidates.orEmpty().map { it.artifact }).any { it.sourceRunId in runIds || it.sourceRunId == plan.sessionId }
        }) { "Training/evaluation overlap" }
        val results = mutableListOf<BenchmarkTrialResult>()
        for (trial in schedule) {
            if (results.isNotEmpty() && cooldownMs > 0) delay(cooldownMs)
            var interruption: BenchmarkTrialResult? = null
            val result = try {
                runTrial(trial) { interruption = it }
            } catch (e: CancellationException) {
                record(interruption ?: BenchmarkTrialResult(trial, BenchmarkTaskStatus.INTERRUPTED, null, false,
                    "Cancelled before reset verification", null))
                throw e
            } catch (e: Exception) {
                BenchmarkTrialResult(trial, BenchmarkTaskStatus.EXECUTION_ERROR, null, false, diagnostic(e), null)
            }
            results += result
            record(result)
        }
        results
    }

    private suspend fun runTrial(trial: BenchmarkTrial, checkpoint: (BenchmarkTrialResult) -> Unit): BenchmarkTrialResult {
        val case = trial.case
        val task = case.task
        var resetError: String? = null
        var recoveryBlocked = false
        val reset = runCatching {
            withTimeoutOrNull(task.timeoutSeconds * 1000L) {
                environment.restore(case)
                val observation = environment.observe()
                observation to environment.auditTask(case, observation)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            recoveryBlocked = it is BenchmarkRecoveryBlocked
            resetError = "reset failed: ${diagnostic(it)}"
            null
        }
        val initial = reset?.first
        val initialAudit = reset?.second
        val startStamp = initial?.stamp()
        if (initial == null || startStamp != case.expectedStart || initialAudit?.verdict != AuditVerdict.NOT_MET) {
            val reason = when {
                initial == null -> resetError ?: "reset timed out"
                startStamp != case.expectedStart -> "initial screen or world fingerprint did not match"
                else -> "independent precondition not established (criterion already met or unknown)"
            }
            return BenchmarkTrialResult(trial, if (recoveryBlocked) BenchmarkTaskStatus.RECOVERY_BLOCKED else BenchmarkTaskStatus.INVALID_RESET, startStamp, false, reason,
                durationMs = null, initialAudit = initialAudit)
        }

        // Every trial gets a fresh policy, replay store and TaskContext; no writes go back to the seed.
        val selection = case.retrieval?.let { selectExperience(case) }
        val policy = ExperiencePolicy(trial, selection?.first, selection?.second.orEmpty())
        val retrieval = case.retrieval?.let { BenchmarkRetrievalResult(selection?.first?.artifactId, requireNotNull(selection).third) }
        val pending = mutableListOf<PendingAudit>()
        val seen = mutableSetOf<String>()
        var lastState = startStamp
        fun repeated(key: String?, pre: BenchmarkStart?, post: BenchmarkStart?): Boolean {
            if (pre != lastState) seen.clear()
            val duplicate = key != null && !seen.add(key)
            if (post != pre) seen.clear()
            lastState = post
            return duplicate
        }
        var settledObservation: BenchmarkObservation? = null
        var preDispatch: BenchmarkObservation = initial
        var platformTapOrigin: AniobScreenState? = null
        val pipeline = StepPipeline(settle = { action, _ ->
            val origin = platformTapOrigin?.let { preDispatch.copy(screen = it) } ?: preDispatch
            environment.settle(action, origin).also { currentObservation ->
                settledObservation = currentObservation
            }.screen
        })
        var ctx = TaskContext(task.goal, task.observableCriteria, taskId = trial.runId)
        var current: BenchmarkObservation = initial
        var agentSuccess = false
        var reason = "step limit reached"
        val started = nanoTime()
        fun elapsed() = (nanoTime() - started) / 1_000_000.0
        val deadlineMs = task.timeoutSeconds * 1000L
        var progress: Progress? = null
        var executionError: Exception? = null
        fun interrupted() = BenchmarkTrialResult(trial, BenchmarkTaskStatus.INTERRUPTED, startStamp, true,
            "Cancelled during trial", elapsed(), pending.map { it.row } + listOfNotNull(progress?.let { p ->
                BenchmarkActionRow(p.step, (p.action ?: p.proposal?.action)?.toolName ?: "generation",
                    if (p.attempted) BenchmarkActionStatus.UNCONFIRMED else BenchmarkActionStatus.FAILED,
                    DeterministicVerifier.EvidenceCategory.NONE, p.attempted, p.accepted, false,
                    elapsed() - p.startedMs, elapsed(), generationMs = p.generationMs, source = source(p.proposal, task.track),
                    dispatchResolved = p.dispatchResolved, failureKind = "INTERRUPTED", failureReason = "Cancelled during step", appliedGenerationSeed = p.seed)
            }), policy.decisions.toList(), initialAudit, loadedArtifactId = policy.seed?.artifactId, retrieval = retrieval)
        suspend fun execute(): BenchmarkObservation? = withTimeoutOrNull(deadlineMs) {
            for (step in 0 until task.maxSteps) {
                val stepStarted = elapsed()
                val before = environment.observe()
                current = before
                val flight = Progress(step, stepStarted, before).also { progress = it }
                var planned = policy.propose(step, before.screen)
                var generation: BenchmarkGeneration? = null
                var generationMs: Double? = null
                var schemaValid = true
                var failureKind: String? = null
                var failureReason: String? = null
                if (planned == null) {
                    if (task.track == Track.DETERMINISTIC_SHORTCUT) {
                        planned = StepPipeline.Proposal.Model(AniobAction.OpenApp(task.observableCriteria.mustShowPackage.single()), "SHORTCUT")
                    } else {
                        val modelPrompt = prompt(trial, before.screen, ctx, policy.history)
                        val genStart = elapsed()
                        val requestedSeed = trial.generationSeed + step
                        flight.seed = requestedSeed.takeIf { generator.decoding.mode == DecodingMode.SEEDED_SAMPLING }
                        generation = try {
                            withTimeoutOrNull(environment.runtime.generationTimeoutMs) { generator.generate(modelPrompt, requestedSeed) }
                                .also { if (it == null) {
                                    failureKind = "GENERATION_TIMEOUT"
                                    failureReason = "Model generation exceeded ${environment.runtime.generationTimeoutMs} ms"
                                } }
                        } catch (e: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive() // do not swallow the trial deadline or caller cancellation
                            failureKind = "GENERATION_TIMEOUT"
                            failureReason = diagnostic(e)
                            null
                        } catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            failureKind = "PROVIDER_ERROR"
                            failureReason = diagnostic(e)
                            null
                        } finally {
                            flight.generationMs = elapsed() - genStart
                        }
                        flight.generation = generation
                        generationMs = flight.generationMs
                        // Parse the shared schema; let StepPipeline record live grounding failures.
                        val parsed = generation?.output?.takeIf { it.isNotBlank() }?.let {
                            try { AniobToolCallParser.parseToAction(it) } catch (_: IllegalArgumentException) { null }
                        }
                        schemaValid = parsed != null
                        if (!schemaValid && failureKind == null) {
                            failureKind = "INVALID_OUTPUT"
                            failureReason = if (generation?.output.isNullOrBlank()) "Provider returned no action" else "Response did not match the action schema"
                        }
                        planned = StepPipeline.Proposal.Model(parsed ?: AniobAction.Fail("Invalid model output"), environment.runtime.provider)
                    }
                }
                val action = planned.action
                flight.proposal = planned
                flight.schemaValid = schemaValid
                val allowed = action.toolName in task.allowedActionTypes || action is AniobAction.Fail
                val control = action is AniobAction.Finish || action is AniobAction.Fail
                var attempted = false
                var accepted = false
                var result: StepResult? = null
                var actualBefore = before
                preDispatch = before
                platformTapOrigin = null
                settledObservation = null
                var dispatchIdentity: String? = null
                if (schemaValid && allowed && action !is AniobAction.Fail) {
                    flight.actionStartedMs = elapsed()
                    result = pipeline.executeStep(ctx, before.screen, planned,
                        capture = { environment.observe().also { current = it }.screen },
                        dispatch = { effective ->
                            if (effective is AniobAction.Finish) {
                                // A provisional Finish is never sent to the device.
                                StepPipeline.DispatchOutcome(dispatched = true)
                            } else {
                                attempted = true
                                flight.attempted = true
                                actualBefore = current
                                preDispatch = current
                                flight.before = actualBefore
                                flight.action = effective
                                dispatchIdentity = fingerprint(effective, actualBefore.screen)
                                flight.fingerprint = dispatchIdentity
                                val dispatch = try {
                                    environment.dispatch(effective)
                                } catch (e: Exception) {
                                    // StepPipeline converts non-cancellation dispatch exceptions to rejections.
                                    // Retain the original exception so an unknown physical outcome stops this trial.
                                    flight.dispatchError = e
                                    throw e
                                }
                                dispatch.also {
                                    accepted = it.dispatched
                                    flight.accepted = accepted
                                    flight.dispatchResolved = true
                                    if (!accepted) failureReason = it.reason.takeIf(String::isNotBlank)?.let(::safeDiagnostic)
                                    platformTapOrigin = it.tapOrigin
                                }.copy(screenAfter = null)
                            }
                        })
                    flight.dispatchError?.let { throw it }
                    ctx = result.nextCtx
                }
                if (failureKind == null) failureKind = when {
                    !allowed -> "DISALLOWED_TOOL"
                    attempted && !accepted -> "DISPATCH_REJECTED"
                    result?.verification?.status == DeterministicVerifier.Status.FAILED ->
                        result.verification.failureKind?.name ?: "VERIFICATION_FAILED"
                    result?.verification?.status == DeterministicVerifier.Status.UNCONFIRMED -> "VERIFICATION_UNCONFIRMED"
                    action is AniobAction.Fail -> "MODEL_REPORTED_FAILURE"
                    else -> null
                }
                if (failureKind != null && failureReason == null) failureReason = when {
                    !allowed -> "Tool outside manifest allowlist"
                    attempted && !accepted -> result?.verification?.reason ?: "Dispatch rejected"
                    action is AniobAction.Fail -> action.reason
                    else -> result?.verification?.reason ?: result?.reflection?.reason
                }?.let(::safeDiagnostic)
                val status = if (!schemaValid || (attempted && !accepted) ||
                    (control && result?.verification?.status == DeterministicVerifier.Status.FAILED)) BenchmarkActionStatus.FAILED
                else classifyAction(result, attempted, control, schemaValid && allowed)
                flight.status = status
                flight.evidence = result?.verification?.takeIf { status == BenchmarkActionStatus.VERIFIED }?.evidenceCategory
                val after = if (attempted) environment.observe().also { current = it } else settledObservation ?: current
                val ended = elapsed()
                val preState = actualBefore.stamp()
                val postState = after.stamp()
                val duplicate = repeated(dispatchIdentity, preState, postState)
                pending += PendingAudit(
                    BenchmarkActionRow(step, if (schemaValid) action.toolName else "invalid_output", status,
                        result?.verification?.takeIf { status == BenchmarkActionStatus.VERIFIED }?.evidenceCategory
                            ?: DeterministicVerifier.EvidenceCategory.NONE,
                        attempted, accepted, control && schemaValid, ended - stepStarted, ended,
                        generationMs, generation?.inputTokens, generation?.outputTokens, generation?.usageSource,
                        repeatedWithoutStateChange = duplicate, actionFingerprint = dispatchIdentity,
                        source = source(planned, task.track),
                        actionLatencyMs = flight.actionStartedMs?.let { ended - it }, preState = preState, postState = postState,
                        failureKind = failureKind, failureReason = failureReason, appliedGenerationSeed = flight.seed), result?.action ?: action, actualBefore, after)
                progress = null
                policy.afterStep(step, planned, status)
                when {
                    !schemaValid || (attempted && !accepted) -> { reason = "$failureKind: $failureReason"; break }
                    !allowed -> { reason = "tool outside manifest allowlist"; break }
                    action is AniobAction.Fail -> { reason = "model reported failure"; break }
                    result?.terminalState == StepResult.TerminalState.SUCCESS -> {
                        agentSuccess = true; reason = "agent reported completion"; break
                    }
                    task.track == Track.DETERMINISTIC_SHORTCUT -> {
                        agentSuccess = accepted && DeterministicVerifier.verifyFinish(task.observableCriteria, after.screen, 1).isExpected
                        reason = "shortcut dispatched"; break
                    }
                    // A stale learned proposal that failed before dispatch can fall back to the model;
                    // never retry a dispatched action automatically.
                    result?.terminalState == StepResult.TerminalState.FAIL &&
                        !(planned is StepPipeline.Proposal.Skill && !attempted &&
                            result.verification?.failureKind == DeterministicVerifier.FailureKind.TARGET_MISSING) -> {
                        reason = "pipeline terminated"; break
                    }
                }
            }
            environment.observe().also { current = it }
        }
        val final = try {
            execute()
        } catch (e: CancellationException) {
            checkpoint(interrupted())
            throw e
        } catch (e: Exception) {
            executionError = e
            reason = diagnostic(e)
            null
        }
        val duration = elapsed()
        val timedOut = executionError == null && (final == null || duration > deadlineMs)
        val unresolved = executionError is BenchmarkDispatchUnresolved || progress?.let { it.attempted && !it.dispatchResolved } == true
        // Never audit an unresolved dispatch against a screen the queued input could still change.
        // For other terminal paths, a fresh observation is preferable to the last pre-action snapshot.
        checkpoint(interrupted())
        val freshFinal = if (unresolved) null else final ?: auditRead { environment.observe() }
        progress?.let { p ->
            val action = p.action ?: p.proposal?.action
            val control = action is AniobAction.Finish || action is AniobAction.Fail
            val preState = p.before.stamp()
            val postState = freshFinal?.stamp()
            pending += PendingAudit(BenchmarkActionRow(p.step, action?.toolName ?: "interrupted_generation",
                p.status ?: when {
                    p.attempted && (p.accepted || !p.dispatchResolved) -> BenchmarkActionStatus.UNCONFIRMED
                    else -> BenchmarkActionStatus.FAILED
                }, p.evidence ?: DeterministicVerifier.EvidenceCategory.NONE, p.attempted, p.accepted,
                control && p.schemaValid, duration - p.startedMs, duration, p.generationMs,
                p.generation?.inputTokens, p.generation?.outputTokens, p.generation?.usageSource,
                repeatedWithoutStateChange = repeated(p.fingerprint, preState, postState), actionFingerprint = p.fingerprint,
                source = source(p.proposal, task.track), failureKind = when {
                    unresolved -> "DISPATCH_UNRESOLVED"
                    timedOut && p.proposal == null && p.generationMs != null -> "GENERATION_TIMEOUT"
                    timedOut -> "DEADLINE"
                    else -> "EXECUTION_ERROR"
                }, dispatchResolved = p.dispatchResolved, failureReason = if (timedOut) "Trial deadline exceeded" else reason, appliedGenerationSeed = p.seed,
                actionLatencyMs = p.actionStartedMs?.let { duration - it }, preState = preState, postState = postState), action, p.before, freshFinal)
        }
        // Auditing is outside timing, has no configuration/status input and never feeds the policy.
        val finalAudit = freshFinal?.let { auditRead { environment.auditTask(case, it) } } ?: BenchmarkAudit(AuditVerdict.UNKNOWN)
        val rows = pending.map { p ->
            if (p.row.dispatchAttempted && p.action != null && p.after != null)
                p.row.copy(audit = auditRead { environment.auditAction(case, p.action, p.before, p.after, p.row.dispatched) })
            else p.row
        }
        val taskStatus = when {
            unresolved -> BenchmarkTaskStatus.DISPATCH_UNRESOLVED
            timedOut -> BenchmarkTaskStatus.TIMED_OUT
            executionError != null -> BenchmarkTaskStatus.EXECUTION_ERROR
            finalAudit.verdict == AuditVerdict.UNKNOWN -> BenchmarkTaskStatus.INCONCLUSIVE
            finalAudit.verdict == AuditVerdict.MET && rows.any { it.dispatchAttempted } -> BenchmarkTaskStatus.VERIFIED
            finalAudit.verdict == AuditVerdict.MET -> BenchmarkTaskStatus.INCONCLUSIVE
            else -> BenchmarkTaskStatus.NOT_MET
        }
        return BenchmarkTrialResult(trial, taskStatus, startStamp, true, if (unresolved) "Dispatch unresolved: ${if (timedOut) "trial deadline exceeded" else reason}" else if (timedOut) "deadline exceeded" else reason,
            duration, rows, policy.decisions.toList(), initialAudit, finalAudit, agentSuccess, policy.seed?.artifactId, retrieval)
    }

    private suspend fun <T> auditRead(read: suspend () -> T): T? = try {
        withTimeoutOrNull(AUDIT_CAPTURE_TIMEOUT_MS) { read() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private data class PendingAudit(val row: BenchmarkActionRow, val action: AniobAction?,
        val before: BenchmarkObservation, val after: BenchmarkObservation?)

    private class Progress(val step: Int, val startedMs: Double, var before: BenchmarkObservation) {
        var proposal: StepPipeline.Proposal? = null
        var generation: BenchmarkGeneration? = null
        var generationMs: Double? = null
        var schemaValid = false
        var action: AniobAction? = null
        var actionStartedMs: Double? = null
        var attempted = false
        var dispatchResolved = false
        var dispatchError: Exception? = null
        var seed: Int? = null
        var accepted = false
        var fingerprint: String? = null
        var status: BenchmarkActionStatus? = null
        var evidence: DeterministicVerifier.EvidenceCategory? = null
    }

    companion object {
        private const val AUDIT_CAPTURE_TIMEOUT_MS = 2_000L

        private fun source(proposal: StepPipeline.Proposal?, track: Track): String = when (proposal) {
            is StepPipeline.Proposal.Skill -> "SKILL"
            is StepPipeline.Proposal.FastPath -> "REPLAY"
            else -> if (track == Track.DETERMINISTIC_SHORTCUT) "SHORTCUT" else "MODEL"
        }

        /** Native generate(prompt) has no verified seed or deterministic-decoding API. */
        fun nativeGenerator(engine: AniobNativeLlmEngine, knownParameters: Map<String, String> = emptyMap()): BenchmarkGenerator {
            require(engine.isReady()) { "Load the benchmark model before measurement" }
            return BenchmarkGenerator.configured(BenchmarkDecoding(effectiveParameters = knownParameters)) { prompt, _ ->
                BenchmarkGeneration(checkNotNull(runInterruptible(Dispatchers.IO) { engine.generate(prompt) }) {
                    "Native generation returned no result; engine does not expose the underlying error"
                })
            }
        }

        internal fun classifyAction(result: StepResult?, attempted: Boolean, control: Boolean, allowed: Boolean): BenchmarkActionStatus {
            if (control || !allowed || result == null || result.verificationSkippedFast) return BenchmarkActionStatus.SKIPPED
            val verification = result.verification
            return when {
                verification?.status == DeterministicVerifier.Status.UNCONFIRMED && attempted -> BenchmarkActionStatus.UNCONFIRMED
                verification?.status == DeterministicVerifier.Status.VERIFIED && attempted -> BenchmarkActionStatus.VERIFIED
                attempted || verification?.failureKind != null -> BenchmarkActionStatus.FAILED
                else -> BenchmarkActionStatus.SKIPPED
            }
        }

        private fun safeDiagnostic(message: String) = AniobSecretRedactor.redact(message).take(500)

        private fun diagnostic(error: Throwable): String = safeDiagnostic(listOfNotNull(
            error.javaClass.simpleName, error.message?.takeIf(String::isNotBlank)).joinToString(": "))

        private fun selectExperience(case: BenchmarkCase): Triple<BenchmarkExperience?, Map<String, String>, String> {
            val catalog = requireNotNull(case.retrieval)
            val engine = AniobSkillEngine()
            engine.getLoadedSkills().forEach { engine.removeSkill(it.name) }
            catalog.candidates.forEach { engine.registerSkill(it.skill) }
            val match = AniobSkillMatcher(engine).match(case.task.goal)
            return Triple(catalog.candidates.singleOrNull { it.skill == match.skill }?.artifact, match.extractedSlots, match.reason)
        }

        private fun fingerprint(action: AniobAction, screen: AniobScreenState): String? {
            val target = AniobActionExecutor.resolveTarget(action, screen)?.node
            val identity = target?.let { listOf(it.className, it.viewId, it.text, it.contentDescription, it.bounds).toString() }
            val key = when (action) {
                is AniobAction.Tap -> identity
                is AniobAction.LongPress -> identity?.let { "$it:${action.durationMs}" }
                is AniobAction.InputText -> identity?.let { "$it:${action.text}:${action.submit}:${action.clearFirst}" }
                is AniobAction.Swipe -> "${action.direction}:${action.distancePx}:${action.container}"
                is AniobAction.OpenApp -> "${action.packageName}:${action.activityName}"
                is AniobAction.PressKey -> action.key.name
                is AniobAction.SystemKey -> action.key.name
                else -> null // e.g. polling/waiting isn't inferred to be a duplicate.
            }
            return key?.let { benchmarkDigest("${action.toolName}:$it") }
        }

        private fun prompt(trial: BenchmarkTrial, screen: AniobScreenState, ctx: TaskContext, history: List<ChatTurn>) = buildString {
            appendLine("Goal: ${trial.case.task.goal}")
            if (trial.configuration == BenchmarkConfiguration.HISTORY_ONLY) {
                appendLine("Conversation history (reference only):")
                appendLine(ChatTurn.flatten(history))
                ctx.condensedHistory.takeLast(12).forEach(::appendLine)
            }
            appendLine("Screen Package: ${screen.packageName}")
            screen.nodes.take(60).forEach { appendLine(it.toOptimizedTokenString()) }
            appendLine("Allowed tools: ${trial.case.task.allowedActionTypes.sorted().joinToString()}")
            appendLine("Return one tool call as JSON, with a semantic target and no coordinates.")
            appendLine(AniobActionSchema.toActionJson(AniobAction.Tap(SemanticTarget.SomIndex(1))))
            appendLine(AniobActionSchema.toActionJson(AniobAction.Finish("Task complete")))
        }
    }
}

/** Isolated, one-mechanism policies; production replay, slot binding, grounding and gates are reused. */
private class ExperiencePolicy(private val trial: BenchmarkTrial, retrieved: BenchmarkExperience?,
    retrievedSlots: Map<String, String>) {
    val seed = (retrieved ?: trial.case.experience).takeUnless { trial.configuration == BenchmarkConfiguration.NO_MEMORY }
    private val slots = if (trial.case.retrieval == null) trial.case.slots else retrievedSlots
    val history = seed?.history.orEmpty().takeLast(12)
    val decisions = mutableListOf<BenchmarkReuse>()
    private val replay = if (trial.configuration == BenchmarkConfiguration.TRAJECTORY_REPLAY) {
        seed?.trajectoryJson?.let { RememberCache.fromJson(it).also { cache -> require(cache.size > 0) } }
    } else null
    private val skill = seed?.skill.takeIf {
        trial.configuration == BenchmarkConfiguration.LEARNED_SKILLS || trial.configuration == BenchmarkConfiguration.VALIDATED_SKILLS
    }
    private var skillIndex = 0
    private var skillStopped = false
    private var replayStopped = false

    fun propose(step: Int, screen: AniobScreenState): StepPipeline.Proposal? {
        if (replay != null && !replayStopped) {
            // Replay one verified transition at a time, never an unchecked tail from an old screen.
            val action = replay.lookup(trial.case.task.goal, screen)?.firstOrNull()
            note(step, if (action == null) ReuseDecision.NO_MATCH else ReuseDecision.REPLAYED)
            if (action == null) replayStopped = true
            return action?.let { StepPipeline.Proposal.FastPath(it) }
        }
        val skill = skill ?: return null
        if (skillStopped) return null
        val skillStep = skill.steps.getOrNull(skillIndex++) ?: return null
        val bound = skillStep.toAniobAction(slots)
        val reference = seed!!.references[skillStep.stepIndex]
        if (bound == null || skill.targetPackage?.let { it != screen.packageName } == true ||
            (bound !is AniobAction.Finish && bound.toolName !in trial.case.task.allowedActionTypes)) {
            note(step, ReuseDecision.REJECTED); skillStopped = true; return null
        }
        if (trial.configuration == BenchmarkConfiguration.LEARNED_SKILLS || bound.semanticTarget() == null) {
            if (bound.semanticTarget() != null && AniobActionExecutor.resolveTarget(bound, screen) == null) {
                note(step, ReuseDecision.REJECTED); skillStopped = true; return null
            }
            note(step, ReuseDecision.REUSED)
            return StepPipeline.Proposal.Skill(bound, skill.name, skill.riskTier, skill.confirmationRequired)
        }
        if (reference == null || reference.packageName != screen.packageName) {
            note(step, ReuseDecision.REJECTED); skillStopped = true; return null
        }
        fun matches(node: AniobNode): Boolean = node.isVisibleToUser && node.isEnabled &&
            node.className == reference.className &&
            (AniobTextFold.same(node.text, reference.label) || AniobTextFold.same(node.contentDescription, reference.label)) &&
            (bound !is AniobAction.InputText || node.isEditable)
        val grounded = AniobActionExecutor.resolveTarget(bound, screen)?.node
        if (grounded != null && matches(grounded)) {
            note(step, ReuseDecision.REUSED)
            return StepPipeline.Proposal.Skill(bound, skill.name, skill.riskTier, skill.confirmationRequired)
        }
        val alternative = screen.nodes.singleOrNull(::matches)
        val adapted = alternative?.let { com.aniob.core.perception.BurstTargetGuard.retarget(bound, it) }
        if (adapted == null || AniobActionExecutor.resolveTarget(adapted, screen)?.node != alternative) {
            note(step, ReuseDecision.ADAPTATION_FAILED); note(step, ReuseDecision.REJECTED)
            skillStopped = true
            return null
        }
        note(step, ReuseDecision.ADAPTED)
        return StepPipeline.Proposal.Skill(adapted, skill.name, skill.riskTier, skill.confirmationRequired)
    }

    fun afterStep(step: Int, proposal: StepPipeline.Proposal, status: BenchmarkActionStatus) {
        if (proposal is StepPipeline.Proposal.Skill && status != BenchmarkActionStatus.VERIFIED && proposal.action !is AniobAction.Finish) {
            skillStopped = true
            if (trial.configuration == BenchmarkConfiguration.VALIDATED_SKILLS) note(step, ReuseDecision.VALIDATION_FAILED)
        }
        if (proposal is StepPipeline.Proposal.FastPath && status != BenchmarkActionStatus.VERIFIED) replayStopped = true
    }

    private fun note(step: Int, decision: ReuseDecision) {
        decisions += BenchmarkReuse(step, requireNotNull(seed).artifactId, skill?.name, skill?.version, decision)
    }
}
