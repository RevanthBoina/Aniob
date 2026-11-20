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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val actionOracle: suspend (BenchmarkCase, AniobAction, BenchmarkObservation, BenchmarkObservation, Boolean) -> BenchmarkActionAudit,
    /** Optional lab-only recovery; true must prove the old input queue can no longer deliver a gesture. */
    private val recoverInputQueue: suspend () -> Boolean = { false }
) : BenchmarkEnvironment {
    data class WorldProbe(val fingerprint: String, val evidenceRef: String)

    private val dispatchFence = BenchmarkDispatchFence()

    init { require(!runtime.simulated) }

    override suspend fun restore(case: BenchmarkCase) {
        check(!AniobAccessibilityService.isTaskActive) { "Stop interactive ANIOb execution before benchmarking" }
        dispatchFence.beforeReset(recoverInputQueue) { restoreFixture(case) }
    }

    private suspend fun screen() = withContext(Dispatchers.Main.immediate) { accessibility.captureCurrentScreenState() }

    override suspend fun observe(): BenchmarkObservation = withWorld(screen())

    private suspend fun withWorld(screen: AniobScreenState): BenchmarkObservation {
        val probe = probeWorld()
        return BenchmarkObservation(screen, probe.fingerprint, probe.evidenceRef)
    }

    override suspend fun dispatch(action: AniobAction): StepPipeline.DispatchOutcome = withContext(Dispatchers.Main.immediate) {
        var origin: AniobScreenState? = null
        var target: AniobNode? = null
        var rejected: DispatchCommand.NoOp? = null
        check(!AniobAccessibilityService.isTaskActive) { "Concurrent interactive execution is not permitted" }
        val accepted = dispatchFence.dispatch { complete ->
            accessibility.executeActionTracked(action, expectedTreeHash = null,
                onTapDispatch = { live, node -> origin = live; target = node },
                onTargetRejected = { rejected = it }, callback = complete)
        }
        StepPipeline.DispatchOutcome(accepted,
            reason = if (!accepted) rejected?.reason ?: "Android dispatch rejected or cancelled" else "",
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

/** Retains the physical dispatch ticket independently of its caller's timeout/cancellation. */
internal class BenchmarkDispatchFence(private val callbackMs: Long = 3_000L, private val drainMs: Long = 1_000L) {
    private val lock = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun dispatch(issue: ((Boolean) -> Unit) -> Unit): Boolean = lock.withLock {
        if (pending != null) throw BenchmarkDispatchUnresolved("Previous dispatch requires recovery before another action")
        val ticket = CompletableDeferred<Boolean>() // deliberately not parented to the trial
        pending = ticket
        try {
            issue { ticket.complete(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BenchmarkDispatchUnresolved("Android dispatch threw ${e.javaClass.simpleName} before its outcome was known")
        }
        val completed = withTimeoutOrNull(callbackMs) { ticket.await() }
            ?: throw BenchmarkDispatchUnresolved("Android callback exceeded ${callbackMs} ms; input may still execute")
        pending = null
        completed
    }

    suspend fun beforeReset(recover: suspend () -> Boolean, reset: suspend () -> Unit) = lock.withLock {
        pending?.let { ticket ->
            val resolved = withTimeoutOrNull(drainMs) { ticket.await() } != null
            if (!resolved && !ticket.isCompleted) {
                val recovered = try {
                    recover()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw BenchmarkRecoveryBlocked("Input recovery failed (${e.javaClass.simpleName}); fixture reset refused")
                }
                if (!recovered && !ticket.isCompleted) {
                    throw BenchmarkRecoveryBlocked("Prior Android input is unresolved; fixture reset refused")
                }
            }
            pending = null
        }
        reset()
    }
}
