package com.aniob.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceActionGatewayTest {

    private var now = 10_000L
    private lateinit var gateway: DeviceActionGateway
    private val log = mutableListOf<DeviceActionGateway.DeviceActionLogEntry>()

    @Before fun setUp() {
        now = 10_000L
        log.clear()
        gateway = DeviceActionGateway(
            duplicateWindowMs = 2_000,
            repeatWindowMs = 5 * 60_000,
            clock = { now },
            logSink = { log += it }
        )
    }

    private fun dispatchOk(): () -> DeviceActionGateway.DispatchResult =
        { DeviceActionGateway.DispatchResult(true, "done") }

    @Test
    fun identicalSignatureTwiceWithinDuplicateWindowDispatchesOnce() {
        val first = gateway.execute(taskId = "t", originAgent = "TEST", signature = "open_app:com.vending:", requestedAction = "open_app", dispatch = dispatchOk())
        now += 500
        var secondDispatchRan = false
        val second = gateway.execute(taskId = "t", originAgent = "TEST", signature = "open_app:com.vending:", requestedAction = "open_app", dispatch = { secondDispatchRan = true; DeviceActionGateway.DispatchResult(true, "done") })
        assertTrue(first.ok)
        assertTrue(second.deduplicated)
        assertFalse(secondDispatchRan)
        assertEquals(DeviceActionGateway.DeviceActionState.DUPLICATE, second.record.state)
    }

    @Test
    fun sameExecutionIdReplayNeverDispatchesTwice() {
        var dispatches = 0
        val id = "exec-1"
        val first = gateway.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        now += 10_000 // past every window
        val replay = gateway.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        assertTrue(replay.deduplicated)
        assertTrue(replay.record.reusedPreviousResult)
        assertEquals(first.record.state, replay.record.state)
    }

    @Test
    fun sameExecutionIdReplayOfFailureDoesNotRetrySilently() {
        var dispatches = 0
        val id = "exec-fail"
        gateway.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(false, "boom") })
        now += 10_000
        val replay = gateway.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        assertFalse(replay.ok)
        assertTrue(replay.deduplicated)
    }

    @Test
    fun sameTaskSameSignatureAfterDuplicateWindowIsServedFromLedger() {
        var dispatches = 0
        gateway.execute(taskId = "task-7", originAgent = "TEST", signature = "device_command:volume_set:percent=70", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        now += 30_000 // past duplicate window, inside repeat window
        val second = gateway.execute(taskId = "task-7", originAgent = "TEST", signature = "device_command:volume_set:percent=70", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        assertTrue(second.ok)
        assertTrue(second.deduplicated)
        assertTrue(second.record.reusedPreviousResult)
    }

    @Test
    fun differentTaskAfterDuplicateWindowDispatchesAgain() {
        var dispatches = 0
        gateway.execute(taskId = "task-1", originAgent = "TEST", signature = "device_command:torch_on:", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        now += 30_000
        gateway.execute(taskId = "task-2", originAgent = "TEST", signature = "device_command:torch_on:", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(2, dispatches)
    }

    @Test
    fun sameTaskLaunchReFiresBecauseLaunchIsARealRequest() {
        var dispatches = 0
        gateway.execute(taskId = "task-9", originAgent = "TEST", signature = "open_app:com.clock:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        now += 30_000
        val second = gateway.execute(taskId = "task-9", originAgent = "TEST", signature = "open_app:com.clock:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(2, dispatches)
        assertFalse(second.deduplicated)
    }

    @Test
    fun failedActionIsAllowedToRetryAndAttemptIsCounted() {
        val first = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { DeviceActionGateway.DispatchResult(false, "nope") })
        now += 30_000
        val second = gateway.execute(taskId = "t2", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = dispatchOk())
        assertFalse(first.ok)
        assertEquals(1, first.record.attempt)
        assertTrue(second.ok)
        assertEquals(2, second.record.attempt)
    }

    @Test
    fun ledgerIsBoundedAndExpiryPurgesOldRecords() {
        val small = DeviceActionGateway(maxRecords = 5, duplicateWindowMs = 10, repeatWindowMs = 1_000, clock = { now })
        repeat(8) { i ->
            small.execute(taskId = "t$i", originAgent = "TEST", signature = "sig$i", requestedAction = "a", dispatch = dispatchOk())
        }
        assertTrue(small.snapshot().size <= 5)
        now += 60_000 // everything expires beyond the repeat horizon
        small.execute(taskId = "late", originAgent = "TEST", signature = "sig-new", requestedAction = "a", dispatch = dispatchOk())
        assertTrue(small.snapshot().all { it.createdAtMs >= now - 60_000 })
    }

    @Test
    fun logSinkReceivesTheFullLifecycle() {
        gateway.execute(taskId = "t", originAgent = "LOCAL_SLM", signature = "s1", requestedAction = "torch_on", resolvedApplication = null, dispatch = dispatchOk())
        val states = log.map { it.state }
        assertTrue(states.contains(DeviceActionGateway.DeviceActionState.PENDING))
        assertTrue(states.contains(DeviceActionGateway.DeviceActionState.EXECUTING))
        assertTrue(states.contains(DeviceActionGateway.DeviceActionState.COMPLETED))
        assertTrue(log.all { it.executionId.isNotBlank() && it.originAgent == "LOCAL_SLM" && it.signature == "s1" })
    }

    @Test
    fun failedOriginalWithinDuplicateWindowIsRetriedNotServed() {
        var dispatches = 0
        val first = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(false, "nope") })
        now += 500 // inside the 2s duplicate window, but the first call FAILED
        val second = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertFalse(first.ok)
        assertEquals(2, dispatches)
        assertTrue(second.ok)
        assertFalse(second.deduplicated)
        assertEquals(2, second.record.attempt)
    }

    @Test
    fun deviceCommandFailedIsNotReusedForSameTaskRepeat() {
        var dispatches = 0
        gateway.execute(taskId = "task-1", originAgent = "TEST", signature = "device_command:volume_set:percent=70", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(false, "boom") })
        now += 30_000 // past dup window, inside repeat window, same task
        val second = gateway.execute(taskId = "task-1", originAgent = "TEST", signature = "device_command:volume_set:percent=70", requestedAction = "a", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(2, dispatches)
        assertTrue(second.ok)
        assertFalse(second.deduplicated)
        assertEquals(2, second.record.attempt)
    }

    @Test
    fun reentrantDispatchDuringInFlightIsRejectedNotDeadlocked() {
        var inner: DeviceActionGateway.DeviceActionOutcome? = null
        val outcome = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = {
            // The action lambda fires a *re-entrant* identical request from the same thread.
            // Dispatch runs outside the lock, so this must return (suppressed), never block.
            inner = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { DeviceActionGateway.DispatchResult(true, "inner") })
            DeviceActionGateway.DispatchResult(true, "outer")
        })
        assertTrue(outcome.ok)
        assertEquals(DeviceActionGateway.DeviceActionState.DUPLICATE, inner?.record?.state)
        assertFalse(inner!!.ok)
        assertTrue(inner!!.record.detail.contains("in flight"))
    }

    /** Store that records every snapshot it is given (to simulate a mid-dispatch crash). */
    private class RecordingStore : DeviceActionGateway.DeviceActionLedgerStore {
        val history = mutableListOf<String>()
        var stored: String? = null
        override fun load(): String? = stored
        override fun save(snapshot: String) { stored = snapshot; history += snapshot }
    }

    @Test
    fun crossProcessReplayOfSameExecutionIdNeverDispatchesTwice() {
        val store = RecordingStore()
        var dispatches = 0
        val id = "exec-shared"
        val process1 = DeviceActionGateway(duplicateWindowMs = 2_000, repeatWindowMs = 300_000, clock = { now }, ledgerStore = store)
        process1.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "open_app:com.vending:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        // Simulate process death + cold start with the same durable ledger.
        val process2 = DeviceActionGateway(duplicateWindowMs = 2_000, repeatWindowMs = 300_000, clock = { now }, ledgerStore = store)
        val replay = process2.execute(executionId = id, taskId = "t", originAgent = "TEST", signature = "open_app:com.vending:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        assertTrue(replay.deduplicated)
        assertTrue(replay.ok)
        assertTrue(replay.record.reusedPreviousResult)
    }

    @Test
    fun leftoverExecutingAfterProcessDeathIsReconciledToInterrupted() {
        val store = RecordingStore()
        var dispatches = 0
        val deadId = "exec-dead"
        val process1 = DeviceActionGateway(duplicateWindowMs = 2_000, repeatWindowMs = 300_000, clock = { now }, ledgerStore = store)
        process1.execute(executionId = deadId, taskId = "t", originAgent = "TEST", signature = "open_app:com.drive:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        // A crash mid-dispatch leaves the pre-dispatch EXECUTING snapshot behind (the first and
        // only save before the final COMPLETED one).
        val midFlightSnapshot = store.history.first()
        // Cold start from that mid-flight state only.
        val crashedStore = RecordingStore().apply { stored = midFlightSnapshot }
        val process2 = DeviceActionGateway(duplicateWindowMs = 2_000, repeatWindowMs = 300_000, clock = { now }, ledgerStore = crashedStore)
        val restored = process2.snapshot().first { it.executionId == deadId }
        assertEquals(DeviceActionGateway.DeviceActionState.INTERRUPTED, restored.state)
        // Replaying the dead execution reports failure honestly, never re-dispatches blindly.
        val replay = process2.execute(executionId = deadId, taskId = "t", originAgent = "TEST", signature = "open_app:com.drive:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(1, dispatches)
        assertFalse(replay.ok)
        assertTrue(replay.deduplicated)
        // A fresh attempt in a NEW execution after the crash counts as attempt 2 (1 INTERRUPTED).
        val retry = process2.execute(taskId = "t2", originAgent = "TEST", signature = "open_app:com.drive:", requestedAction = "open_app", dispatch = { dispatches++; DeviceActionGateway.DispatchResult(true, "ok") })
        assertEquals(2, dispatches)
        assertEquals(2, retry.record.attempt)
    }

    @Test
    fun exceptionsInDispatchBecomeFailedRecordsNotCrashes() {
        val outcome = gateway.execute(taskId = "t", originAgent = "TEST", signature = "s1", requestedAction = "a", dispatch = { throw IllegalStateException("kaboom") })
        assertFalse(outcome.ok)
        assertEquals(DeviceActionGateway.DeviceActionState.FAILED, outcome.record.state)
        assertTrue(outcome.record.detail.contains("kaboom"))
    }
}
