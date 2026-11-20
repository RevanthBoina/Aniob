package com.aniob.app.bench

import com.aniob.app.service.AniobAccessibilityService
import com.aniob.core.bench.*
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepPipeline
import com.aniob.core.verify.FastVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live-device binding for an instrumentation harness on a dedicated lab device. The harness must
 * restore the prepared app/fixture and supply an independent world-state probe and oracles. There
 * is no default reset, synthetic response, user-data clearing, or automatic approval.
 */
class AccessibilityBenchmarkEnvironment(
    private val accessibility: AniobAccessibilityService,
    override val runtime: BenchmarkRuntime,
    private val restoreFixture: suspend (BenchmarkCase) -> Unit,
    private val probeWorld: suspend () -> WorldProbe,
    private val taskOracle: suspend (BenchmarkCase, BenchmarkObservation) -> BenchmarkAudit,
    private val actionOracle: suspend (BenchmarkCase, AniobAction, BenchmarkObservation, BenchmarkObservation, Boolean) -> BenchmarkActionAudit
) : BenchmarkEnvironment {
    data class WorldProbe(val fingerprint: String, val evidenceRef: String)

    init { require(!runtime.simulated) }

    override suspend fun restore(case: BenchmarkCase) {
        check(!AniobAccessibilityService.isTaskActive) { "Stop interactive ANIOb execution before benchmarking" }
        restoreFixture(case)
    }

    private suspend fun screen() = withContext(Dispatchers.Main.immediate) { accessibility.captureCurrentScreenState() }

    override suspend fun observe(): BenchmarkObservation = withWorld(screen())

    private suspend fun withWorld(screen: AniobScreenState): BenchmarkObservation {
        val probe = probeWorld()
        return BenchmarkObservation(screen, probe.fingerprint, probe.evidenceRef)
    }

    override suspend fun dispatch(action: AniobAction): StepPipeline.DispatchOutcome = withContext(Dispatchers.Main.immediate) {
        val callback = CompletableDeferred<Boolean>()
        var origin: AniobScreenState? = null
        var target: AniobNode? = null
        var rejected: DispatchCommand.NoOp? = null
        accessibility.executeActionTracked(action, expectedTreeHash = null,
            onTapDispatch = { live, node -> origin = live; target = node },
            onTargetRejected = { rejected = it }) { callback.complete(it) }
        val accepted = withTimeoutOrNull(3_000L) { callback.await() }
        StepPipeline.DispatchOutcome(accepted == true,
            reason = if (accepted == null) "Android dispatch callback timed out" else rejected?.reason.orEmpty(),
            tapOrigin = origin, tapTarget = target, targetRejection = rejected)
    }

    override suspend fun settle(action: AniobAction, before: BenchmarkObservation): BenchmarkObservation {
        if (action is AniobAction.Finish) return observe()
        val observed = FastVerify.await(before.screen, expectedTexts = FastVerify.expectedTexts(action, null),
            timeoutMs = if (action is AniobAction.OpenApp) 5_000L else FastVerify.MAX_WAIT_MS,
            windowEvents = { accessibility.windowEventSequence() }, capturePoll = { screen() },
            finalCaptureAtTimeout = true, tap = action as? AniobAction.Tap)
        return withWorld(observed.screen ?: screen())
    }

    override suspend fun auditTask(case: BenchmarkCase, observation: BenchmarkObservation) = taskOracle(case, observation)

    override suspend fun auditAction(case: BenchmarkCase, action: AniobAction, before: BenchmarkObservation,
        after: BenchmarkObservation, dispatched: Boolean) = actionOracle(case, action, before, after, dispatched)
}
