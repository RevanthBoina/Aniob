package com.aniob.core.concurrency

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AniobParallelTest {

    @Test
    fun bothRunsConcurrentlyNotSequentially() = runBlocking {
        // Each side waits for the other: this only completes if both are running at the same time.
        val leftReady = CompletableDeferred<Unit>()
        val rightReady = CompletableDeferred<Unit>()
        val result = withTimeout(5_000) {
            AniobParallel.both(
                left = { leftReady.complete(Unit); rightReady.await(); "L" },
                right = { rightReady.complete(Unit); leftReady.await(); "R" }
            )
        }
        assertEquals("L" to "R", result)
    }

    @Test
    fun bothCancelsTheSiblingWhenOneFails() = runBlocking {
        val siblingCancelled = CompletableDeferred<Boolean>()
        try {
            AniobParallel.both<Unit, Unit>(
                left = {
                    try { delay(60_000) } finally { siblingCancelled.complete(true) }
                },
                right = { delay(20); error("boom") }
            )
            fail("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
        assertTrue(withTimeout(5_000) { siblingCancelled.await() })
    }

    @Test
    fun mapBoundedKeepsOrderAndNeverExceedsTheLimit() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val out = AniobParallel.mapBounded((1..12).toList(), parallelism = 3) { n ->
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(15)
            inFlight.decrementAndGet()
            n * 2
        }
        assertEquals((1..12).map { it * 2 }, out)
        assertTrue("peak ${peak.get()} must stay <= 3", peak.get() <= 3)
        assertTrue("work really overlapped", peak.get() > 1)
    }

    @Test
    fun bestEffortNeverThrowsAndNeverWaitsForever() = runBlocking {
        assertEquals("fallback", AniobParallel.bestEffort(30, "fallback") { delay(60_000); "late" })
        assertEquals("fallback", AniobParallel.bestEffort(1_000, "fallback") { error("warm-up failed") })
        assertEquals("ok", AniobParallel.bestEffort(1_000, "fallback") { "ok" })
    }

    @Test
    fun bestEffortStillHonoursStop() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            AniobParallel.bestEffort(60_000, "fallback") { started.complete(Unit); delay(60_000); "late" }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue("cancellation must propagate, not become the fallback", job.isCancelled)
    }
}
