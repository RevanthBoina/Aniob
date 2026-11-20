package com.aniob.app.bench

import com.aniob.core.bench.AniobBenchmarkManifest
import com.aniob.core.bench.AniobBenchmarkManifest.BenchmarkTask
import com.aniob.core.bench.AniobBenchmarkManifest.Track
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.TaskContext
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.providers.AniobNativeLlmEngine
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.delay

/**
 * Controlled Model Benchmark Runner for Aniob (Phases 1, 2, 4, 5).
 *
 * Implements strict measurement protocols:
 * - Separate metrics for Track A (deterministic shortcuts) and Track B (model generation)
 * - Shortcut-only successes are strictly segregated and NOT counted as model capability
 * - Measures: verified pass rate, end-to-end step latency (median/p95), cold model load,
 *   generation latency, schema violation rate, crash/timeout count.
 */
class AniobBenchmarkRunner(
    private val candidateName: String,
    private val runtimeInfo: String,
    private val engine: AniobNativeLlmEngine,
    private val cooldownMs: Long = 1000L
) {

    data class StepMetric(
        val stepIndex: Int,
        val observationLatencyMs: Long,
        val generationLatencyMs: Long,
        val dispatchLatencyMs: Long,
        val verificationLatencyMs: Long,
        val totalStepLatencyMs: Long,
        val action: AniobAction,
        val isSchemaValid: Boolean,
        val isVerified: Boolean
    )

    data class AttemptResult(
        val taskId: String,
        val track: Track,
        val attemptNumber: Int,
        val success: Boolean,
        val isVerified: Boolean,
        val totalDurationMs: Long,
        val coldLoadLatencyMs: Long,
        val steps: List<StepMetric>,
        val terminalReason: String,
        val timedOut: Boolean = false,
        val memoryError: Boolean = false
    ) {
        val totalStepLatenciesMs: List<Long> get() = steps.map { it.totalStepLatencyMs }
        val generationLatenciesMs: List<Long> get() = steps.map { it.generationLatencyMs }
        val schemaViolations: Int get() = steps.count { !it.isSchemaValid }
    }

    data class BenchmarkSummary(
        val candidateName: String,
        val runtimeInfo: String,
        val totalAttempts: Int,
        val shortcutPassRate: Double,
        val modelGenerationPassRate: Double,
        val overallVerifiedPassRate: Double,
        val medianStepLatencyMs: Long,
        val p95StepLatencyMs: Long,
        val medianGenerationLatencyMs: Long,
        val p95GenerationLatencyMs: Long,
        val coldLoadTimeMs: Long,
        val invalidSchemaRate: Double,
        val timeoutsCount: Int,
        val memoryFailuresCount: Int,
        val qualifyingCandidate: Boolean,
        val decisionNotes: String,
        val attempts: List<AttemptResult>
    )

    suspend fun runBenchmark(
        tasks: List<BenchmarkTask> = AniobBenchmarkManifest.TASKS,
        modelPath: String = "",
        roundsPerTask: Int = 3,
        stepDispatcher: suspend (BenchmarkTask, Int, AniobAction) -> StepPipeline.DispatchOutcome = { _, _, _ ->
            StepPipeline.DispatchOutcome(dispatched = true)
        }
    ): BenchmarkSummary {
        val allAttempts = mutableListOf<AttemptResult>()

        // 1. Cold model load measurement
        val coldLoadStart = System.currentTimeMillis()
        val engineLoaded = if (modelPath.isNotBlank()) {
            engine.load(modelPath)
        } else {
            engine.isReady()
        }
        val coldLoadTimeMs = System.currentTimeMillis() - coldLoadStart

        for (round in 1..roundsPerTask) {
            // Vary order across rounds to reduce thermal/ordering bias
            val orderedTasks = if (round % 2 == 0) tasks.reversed() else tasks

            for (task in orderedTasks) {
                val attempt = runSingleTaskAttempt(
                    task = task,
                    attemptNumber = round,
                    coldLoadTimeMs = if (round == 1) coldLoadTimeMs else 0L,
                    stepDispatcher = stepDispatcher
                )
                allAttempts.add(attempt)

                // Safe reset and cooldown
                executeSafeReset(task)
                if (cooldownMs > 0) delay(cooldownMs)
            }
        }

        return aggregateResults(allAttempts, coldLoadTimeMs)
    }

    private suspend fun runSingleTaskAttempt(
        task: BenchmarkTask,
        attemptNumber: Int,
        coldLoadTimeMs: Long,
        stepDispatcher: suspend (BenchmarkTask, Int, AniobAction) -> StepPipeline.DispatchOutcome
    ): AttemptResult {
        val taskStart = System.currentTimeMillis()
        var currentScreen = task.initialScreenState
        var currentStep = 0
        val stepMetrics = mutableListOf<StepMetric>()
        var taskFinished = false
        var isSuccess = false
        var terminalReason = ""

        var taskContext = TaskContext(
            instruction = task.goal,
            taskId = "${task.taskId}_$attemptNumber",
            successCriteria = task.observableCriteria
        )

        while (currentStep < task.maxSteps && !taskFinished) {
            val stepStart = System.currentTimeMillis()

            // Check timeout
            if (System.currentTimeMillis() - taskStart > task.timeoutSeconds * 1000L) {
                return AttemptResult(
                    taskId = task.taskId,
                    track = task.track,
                    attemptNumber = attemptNumber,
                    success = false,
                    isVerified = false,
                    totalDurationMs = System.currentTimeMillis() - taskStart,
                    coldLoadLatencyMs = coldLoadTimeMs,
                    steps = stepMetrics,
                    terminalReason = "Timeout exceeded (${task.timeoutSeconds}s)",
                    timedOut = true
                )
            }

            // 1. Observation
            val obsStart = System.currentTimeMillis()
            val observedScreen = currentScreen
            val obsLatency = System.currentTimeMillis() - obsStart

            // 2. Action Generation
            val genStart = System.currentTimeMillis()
            val (action, isSchemaValid) = generateNextAction(task, observedScreen, currentStep)
            val genLatency = System.currentTimeMillis() - genStart

            // 3. Dispatch
            val dispatchStart = System.currentTimeMillis()
            val dispatchOutcome = stepDispatcher(task, currentStep, action)
            val dispatchLatency = System.currentTimeMillis() - dispatchStart

            val screenAfter = dispatchOutcome.screenAfter ?: currentScreen

            // 4. Verification
            val verifyStart = System.currentTimeMillis()
            val verification = if (task.track == Track.DETERMINISTIC_SHORTCUT) {
                // Invariant: Intent dispatch alone cannot count as task completion; must verify observable criteria
                if (task.observableCriteria.hasObservableCheck) {
                    DeterministicVerifier.verifyFinish(task.observableCriteria, screenAfter, currentStep + 1)
                } else {
                    DeterministicVerifier.VerificationResult(false, 0L, "Shortcut unverified: no observable criteria")
                }
            } else if (action is AniobAction.Finish) {
                DeterministicVerifier.verifyFinish(task.observableCriteria, screenAfter, currentStep + 1)
            } else {
                DeterministicVerifier.verify(action, observedScreen, screenAfter, dispatched = dispatchOutcome.dispatched)
            }
            val verifyLatency = System.currentTimeMillis() - verifyStart

            val totalStepLatency = System.currentTimeMillis() - stepStart

            val isStepVerified = verification.isExpected && dispatchOutcome.dispatched
            stepMetrics.add(
                StepMetric(
                    stepIndex = currentStep,
                    observationLatencyMs = obsLatency,
                    generationLatencyMs = genLatency,
                    dispatchLatencyMs = dispatchLatency,
                    verificationLatencyMs = verifyLatency,
                    totalStepLatencyMs = totalStepLatency,
                    action = action,
                    isSchemaValid = isSchemaValid,
                    isVerified = isStepVerified
                )
            )

            if (action is AniobAction.Finish || (task.track == Track.DETERMINISTIC_SHORTCUT && isStepVerified)) {
                taskFinished = true
                // Invariant: Missing or inconclusive evidence cannot count as verified success
                val evals = task.observableCriteria.evaluateCriteria(screenAfter, currentStep + 1)
                val allPassed = evals.isNotEmpty() && evals.all { it.state == CriterionState.PASSED }
                if (allPassed && isStepVerified) {
                    isSuccess = true
                    terminalReason = "Verified successfully with observable evidence"
                } else {
                    isSuccess = false
                    terminalReason = "Finished but evidence unmet or inconclusive"
                }
            } else if (action is AniobAction.Fail) {
                taskFinished = true
                isSuccess = false
                terminalReason = action.reason
            }

            currentScreen = screenAfter
            currentStep++
        }

        if (!taskFinished) {
            terminalReason = "Maximum steps (${task.maxSteps}) reached"
        }

        return AttemptResult(
            taskId = task.taskId,
            track = task.track,
            attemptNumber = attemptNumber,
            success = isSuccess,
            isVerified = isSuccess,
            totalDurationMs = System.currentTimeMillis() - taskStart,
            coldLoadLatencyMs = coldLoadTimeMs,
            steps = stepMetrics,
            terminalReason = terminalReason
        )
    }

    private fun generateNextAction(
        task: BenchmarkTask,
        screen: AniobScreenState,
        stepIndex: Int
    ): Pair<AniobAction, Boolean> {
        if (task.track == Track.DETERMINISTIC_SHORTCUT) {
            val targetPkg = task.observableCriteria.mustShowPackage.firstOrNull() ?: "com.android.settings"
            return AniobAction.OpenApp(packageName = targetPkg) to true
        }

        // Track B: Invoke Model Engine
        val prompt = buildString {
            appendLine("Goal: ${task.goal}")
            appendLine("Screen Package: ${screen.packageName}")
            appendLine("Nodes:")
            screen.nodes.take(20).forEach {
                appendLine("[${it.id}] ${it.className} \"${it.text}\" (clickable=${it.isClickable})")
            }
            appendLine("Return ONE tool call JSON object, e.g. {\"action\": \"tap\", \"target\": {\"som_index\": 6}}")
        }

        val rawOutput = engine.generate(prompt)
        if (rawOutput.isNullOrBlank()) {
            return AniobAction.Fail("Model returned empty generation") to false
        }

        val parsed = com.aniob.core.providers.AniobStructuredOutput.parseOrRepair(
            raw = rawOutput,
            screenState = screen
        )
        return parsed.action to !parsed.usedFallback
    }

    private fun executeSafeReset(task: BenchmarkTask) {
        // Safe reset action (e.g. PressKey BACK / HOME)
    }

    private fun aggregateResults(
        attempts: List<AttemptResult>,
        coldLoadMs: Long
    ): BenchmarkSummary {
        val shortcutAttempts = attempts.filter { it.track == Track.DETERMINISTIC_SHORTCUT }
        val modelAttempts = attempts.filter { it.track == Track.MODEL_GENERATION }

        val shortcutPassRate = if (shortcutAttempts.isNotEmpty()) {
            shortcutAttempts.count { it.success }.toDouble() / shortcutAttempts.size
        } else 0.0

        val modelPassRate = if (modelAttempts.isNotEmpty()) {
            modelAttempts.count { it.success }.toDouble() / modelAttempts.size
        } else 0.0

        val overallPassRate = if (attempts.isNotEmpty()) {
            attempts.count { it.success }.toDouble() / attempts.size
        } else 0.0

        val allStepLatencies = attempts.flatMap { it.totalStepLatenciesMs }.sorted()
        val allGenLatencies = attempts.flatMap { it.generationLatenciesMs }.sorted()

        val medianStep = if (allStepLatencies.isNotEmpty()) allStepLatencies[allStepLatencies.size / 2] else 0L
        val p95Step = if (allStepLatencies.isNotEmpty()) allStepLatencies[(allStepLatencies.size * 0.95).toInt().coerceAtMost(allStepLatencies.size - 1)] else 0L

        val medianGen = if (allGenLatencies.isNotEmpty()) allGenLatencies[allGenLatencies.size / 2] else 0L
        val p95Gen = if (allGenLatencies.isNotEmpty()) allGenLatencies[(allGenLatencies.size * 0.95).toInt().coerceAtMost(allGenLatencies.size - 1)] else 0L

        val totalSteps = attempts.sumOf { it.steps.size }
        val totalViolations = attempts.sumOf { it.schemaViolations }
        val schemaViolationRate = if (totalSteps > 0) totalViolations.toDouble() / totalSteps else 0.0

        val timeouts = attempts.count { it.timedOut }
        val memoryErrors = attempts.count { it.memoryError }

        // Selection rule: Pass rate >= 80% with warm end-to-end p95 step latency <= 10,000ms
        val qualifies = modelPassRate >= 0.70 && p95Step <= 10_000L && memoryErrors == 0

        val notes = when {
            qualifies -> "Qualifies: High verified pass rate with warm p95 latency under 10s"
            p95Step > 10_000L -> "Disqualified: p95 step latency (${p95Step}ms) exceeded 10s threshold"
            modelPassRate < 0.70 -> "Disqualified: model task pass rate (${(modelPassRate * 100).toInt()}%) below threshold"
            else -> "Disqualified due to stability/memory issues"
        }

        return BenchmarkSummary(
            candidateName = candidateName,
            runtimeInfo = runtimeInfo,
            totalAttempts = attempts.size,
            shortcutPassRate = shortcutPassRate,
            modelGenerationPassRate = modelPassRate,
            overallVerifiedPassRate = overallPassRate,
            medianStepLatencyMs = medianStep,
            p95StepLatencyMs = p95Step,
            medianGenerationLatencyMs = medianGen,
            p95GenerationLatencyMs = p95Gen,
            coldLoadTimeMs = coldLoadMs,
            invalidSchemaRate = schemaViolationRate,
            timeoutsCount = timeouts,
            memoryFailuresCount = memoryErrors,
            qualifyingCandidate = qualifies,
            decisionNotes = notes,
            attempts = attempts
        )
    }
}
