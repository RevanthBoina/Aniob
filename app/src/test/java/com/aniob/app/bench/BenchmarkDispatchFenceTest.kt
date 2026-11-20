package com.aniob.app.bench

import com.aniob.core.bench.BenchmarkDispatchUnresolved
import com.aniob.core.bench.BenchmarkRecoveryBlocked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BenchmarkDispatchFenceTest {
    @Test fun timeoutDoesNotReleaseOldGestureOrAllowSecondDispatch() = runBlocking {
        val fence = BenchmarkDispatchFence(10, 10)
        lateinit var late: (Boolean) -> Unit
        var issued = 0
        var resets = 0
        try {
            fence.dispatch { issued++; late = it }
            fail("Expected unresolved dispatch")
        } catch (_: BenchmarkDispatchUnresolved) { }
        try {
            fence.dispatch { issued++ }
            fail("Expected second action to be fenced")
        } catch (_: BenchmarkDispatchUnresolved) { }
        try {
            fence.beforeReset({ false }) { resets++ }
            fail("Expected reset to be fenced")
        } catch (_: BenchmarkRecoveryBlocked) { }
        assertEquals(1, issued)
        assertEquals(0, resets)
        late(true)
        fence.beforeReset({ error("Late callback already drained") }) { resets++ }
        assertEquals(1, resets)
        assertFalse(fence.dispatch { it(false) })
    }

    @Test fun cancellationKeepsFenceUntilProvenRecovery() = runBlocking {
        val fence = BenchmarkDispatchFence(10_000, 10)
        val issued = CompletableDeferred<(Boolean) -> Unit>()
        val job = launch { fence.dispatch { issued.complete(it) } }
        val old = issued.await()
        job.cancelAndJoin()
        val events = mutableListOf<String>()
        fence.beforeReset({ events += "input queue recovered"; true }) { events += "fixture reset" }
        old(true) // old ticket cannot satisfy a future dispatch
        assertFalse(fence.dispatch { it(false) })
        assertEquals(listOf("input queue recovered", "fixture reset"), events)
    }
}
