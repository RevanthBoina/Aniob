package com.aniob.core.execution

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTargetMissLedgerTest {

    @Test
    fun repeatedMissesAreTargetAndTaskScoped() {
        val ledger = TaskTargetMissLedger()
        ledger.beginTask("task-one")

        assertEquals(1, ledger.recordMiss("task-one", "text:save"))
        assertEquals(1, ledger.recordMiss("task-one", "text:cancel"))
        assertEquals(2, ledger.recordMiss("task-one", "text:save"))

        // A stale callback from a previous task cannot reset or increment the new task's state.
        ledger.beginTask("task-two")
        assertEquals(0, ledger.recordMiss("task-one", "text:save"))
        assertEquals(1, ledger.recordMiss("task-two", "text:save"))
    }

    @Test
    fun successClearsOnlyThatTargetsMisses() {
        val ledger = TaskTargetMissLedger()
        ledger.beginTask("task-one")
        ledger.recordMiss("task-one", "text:save")
        ledger.recordMiss("task-one", "text:cancel")

        ledger.recordSuccess("task-one", "text:save")
        assertEquals(1, ledger.recordMiss("task-one", "text:save"))
        assertEquals(2, ledger.recordMiss("task-one", "text:cancel"))
    }
}
