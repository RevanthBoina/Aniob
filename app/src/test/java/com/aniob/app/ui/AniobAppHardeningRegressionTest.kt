package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.app.db.ActiveTaskEntity
import com.aniob.app.ui.model.NextAction
import com.aniob.app.ui.model.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobAppHardeningRegressionTest {

    @Test
    fun stopCurrentTaskImmediatelyTransitionsToStopped() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("Test task for stop")
        assertTrue("Task should be marked running initially", vm.uiState.value.isRunning)

        vm.stopCurrentTask()
        assertFalse("Task should not be running after stopCurrentTask", vm.uiState.value.isRunning)
        val summary = vm.uiState.value.lastSummary
        assertNotNull("Stop must post a summary card", summary)
        assertEquals(Outcome.STOPPED, summary?.outcome)
        assertTrue(summary?.reason?.contains("Stopped") == true)
    }

    @Test
    fun doubleSubmitRejectedWhenTaskIsRunning() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("First task")
        val firstTask = vm.uiState.value.activeTask
        assertNotNull(firstTask)

        // Attempting to submit a second task while running is rejected
        vm.submitTask("Second task")
        val currentTask = vm.uiState.value.activeTask
        assertEquals("Second task should be rejected while first is running", firstTask?.id, currentTask?.id)

        vm.stopCurrentTask()
    }

    @Test
    fun summaryCardNextActionsAlignedWithOutcome() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        val successSummary = vm.buildSummary(
            prompt = "Open settings",
            outcome = Outcome.SUCCESS,
            steps = 2,
            durationMs = 1200L,
            provider = "INTENT",
            evidence = emptyList()
        )
        assertEquals(Outcome.SUCCESS, successSummary.outcome)
        assertEquals(
            listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS),
            successSummary.nextActions
        )

        val unverifiedSummary = vm.buildSummary(
            prompt = "Do unknown thing",
            outcome = Outcome.UNVERIFIED,
            steps = 3,
            durationMs = 2000L,
            provider = "LOCAL_SLM",
            evidence = emptyList(),
            reason = "No observable criteria"
        )
        assertEquals(Outcome.UNVERIFIED, unverifiedSummary.outcome)
        assertEquals(
            listOf(NextAction.RETRY, NextAction.VIEW_STEPS),
            unverifiedSummary.nextActions
        )

        val stoppedSummary = vm.buildSummary(
            prompt = "Stopped action",
            outcome = Outcome.STOPPED,
            steps = 1,
            durationMs = 500L,
            provider = "NONE",
            evidence = emptyList()
        )
        assertEquals(Outcome.STOPPED, stoppedSummary.outcome)
        assertEquals(listOf(NextAction.VIEW_STEPS), stoppedSummary.nextActions)

        val failedSummary = vm.buildSummary(
            prompt = "Failed action",
            outcome = Outcome.FAILED,
            steps = 4,
            durationMs = 3500L,
            provider = "FASTPATH",
            evidence = emptyList()
        )
        assertEquals(Outcome.FAILED, failedSummary.outcome)
        assertEquals(
            listOf(NextAction.RETRY, NextAction.EXPLORE_APP, NextAction.VIEW_STEPS),
            failedSummary.nextActions
        )
    }

    @Test
    fun targetMissRetryMemoryIsScopedToTaskAndTarget() {
        val ledger = com.aniob.core.execution.TaskTargetMissLedger()
        ledger.beginTask("task_a")

        assertEquals(1, ledger.recordMiss("task_a", "target_a"))
        assertEquals(1, ledger.recordMiss("task_a", "target_b"))
        ledger.recordSuccess("task_a", "target_a")
        assertEquals("Success clears only that target's retry count", 1, ledger.recordMiss("task_a", "target_a"))
        assertEquals("A different target retains its own count", 2, ledger.recordMiss("task_a", "target_b"))

        ledger.beginTask("task_b")
        assertEquals("An old task cannot mutate the new task's ledger", 0, ledger.recordMiss("task_a", "target_a"))
        assertEquals(1, ledger.recordMiss("task_b", "target_a"))
    }

    @Test
    fun activeTaskMarkerRoomPersistence() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val dao = app.database.activeTaskDao()

        dao.clearActiveTasks()
        assertNull("Active task should be null after clear", dao.getActiveTask())

        val entity = ActiveTaskEntity(
            taskId = "task_test_persist",
            rawPrompt = "Test persistent prompt",
            currentStep = 3,
            lastKnownEffect = "Tapped button",
            updatedAt = System.currentTimeMillis()
        )
        dao.setActiveTask(entity)

        val retrieved = dao.getActiveTask()
        assertNotNull("Active task should be retrieved from Room", retrieved)
        assertEquals("task_test_persist", retrieved?.taskId)
        assertEquals(3, retrieved?.currentStep)
        assertEquals("Tapped button", retrieved?.lastKnownEffect)

        dao.clearActiveTasks()
        assertNull("Active task should be cleared", dao.getActiveTask())
    }
}
