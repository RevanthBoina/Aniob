package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.core.domain.AniobTask
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.TaskContext
import com.aniob.core.domain.TaskStatus
import com.aniob.core.run.RunLane
import com.aniob.core.run.TaskInputKind
import kotlinx.coroutines.flow.update
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
class AniobViewModelExecutionWiringTest {

    @Test
    fun setFastPathEnabledSynchronizesRouterAndUiState() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.setFastPathEnabled(false)
        assertFalse("uiState.fastPathEnabled must be false", vm.uiState.value.fastPathEnabled)
        assertFalse("executionRouter.fastPathEnabled must be synced to false", vm.executionRouter.fastPathEnabled)

        vm.setFastPathEnabled(true)
        assertTrue("uiState.fastPathEnabled must be true", vm.uiState.value.fastPathEnabled)
        assertTrue("executionRouter.fastPathEnabled must be synced to true", vm.executionRouter.fastPathEnabled)
    }

    @Test
    fun queueFollowUpStoresSingleQueuedTaskAndCancelsGracefully() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("First in-flight task")
        assertTrue("Task should be marked running", vm.uiState.value.isRunning)

        vm.queueFollowUp("Follow-up second task")
        assertEquals("Follow-up second task", vm.uiState.value.queuedPrompt)

        // Queueing another replaces or keeps single-owner queue
        vm.queueFollowUp("Follow-up third task")
        assertEquals("Follow-up third task", vm.uiState.value.queuedPrompt)

        vm.cancelQueuedFollowUp()
        assertNull("Queued prompt should be cleared after cancellation", vm.uiState.value.queuedPrompt)

        vm.stopCurrentTask()
    }

    @Test
    fun taskStartAppendsNoAskPolicyHeadsUpMessage() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("Pay the bill, enter the OTP, then send a receipt")

        // The task coroutine runs off the test thread; watch the transcript across the window in
        // which the removed heads-up used to be appended (it was the first thing the job did).
        val deadline = System.currentTimeMillis() + 1_000L
        do {
            val narrated = vm.uiState.value.chatMessages.filter {
                it.provider == "ASK_POLICY" || it.badge == "Heads-up" || it.id.startsWith("msg_heads_up_")
            }
            assertTrue("No ASK_POLICY heads-up may be appended at task start: $narrated", narrated.isEmpty())
            Thread.sleep(25)
        } while (System.currentTimeMillis() < deadline)

        vm.stopCurrentTask()
    }

    @Test
    fun preflightOfflineShortcutExemption() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        // "turn on flashlight" or "open website https://example.com" should resolve as offline/intent shortcuts
        val doctorResult = vm.checkDoctorPreflight("open website https://example.com")
        assertTrue("Offline/intent shortcut must pass doctor preflight even without model downloads", doctorResult.allPassed)
    }

    @Test
    fun updateSettingsPersistsOmniRouteKeyModelAndBaseUrlAndRefreshesSystemState() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.updateSettings(
            apiKey = "sk-test-omniroute-123",
            model = "auto/smart",
            baseUrl = "https://omniroute.online/v1"
        )

        assertEquals("sk-test-omniroute-123", vm.uiState.value.omnirouteApiKey)
        assertEquals("auto/smart", vm.uiState.value.omnirouteModel)
        assertEquals("https://omniroute.online/v1", vm.uiState.value.omnirouteBaseUrl)
        assertTrue("SystemState modelReady must be true after saving OmniRoute API key", app.systemState.state.value.modelReady)
        assertTrue("Active brain label must reflect Cloud when configured", vm.getActiveBrainLabel().contains("Cloud"))

        // Clean up test state
        vm.updateSettings(apiKey = "", model = "auto", baseUrl = "")
    }

    @Test
    fun selectedAccountDoesNotBecomeReadyFromAnUnrelatedGatewayKey() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val secrets = com.aniob.app.data.AniobSecretStore(app)
        val source = app.accountAuth.cloudSource
        val key = secrets.getOmniRouteApiKey()
        val address = secrets.getOmniRouteBaseUrl()
        try {
            secrets.setOmniRouteApiKey("test-key")
            secrets.setOmniRouteBaseUrl("https://gateway.example.test/v1")
            app.accountAuth.cloudSource = com.aniob.core.auth.AniobCloudSource.ACCOUNT
            app.systemState.refresh()
            assertFalse(app.systemState.state.value.cloudReady)
            app.accountAuth.cloudSource = com.aniob.core.auth.AniobCloudSource.OMNIROUTE
            app.systemState.refresh()
            assertTrue(app.systemState.state.value.cloudReady)
        } finally {
            secrets.setOmniRouteApiKey(key)
            secrets.setOmniRouteBaseUrl(address)
            app.accountAuth.cloudSource = source
            app.systemState.refresh()
        }
    }

    @Test
    fun intentShortcutClosingRequiresObservableScreenCriteria() {
        val bareCriteria = com.aniob.core.domain.SuccessCriteria(minSteps = 1)
        assertFalse("Bare minSteps has no observable check", bareCriteria.hasObservableCheck)

        val observableCriteria = com.aniob.core.domain.SuccessCriteria(
            minSteps = 1,
            mustContainText = listOf("Wi-Fi")
        )
        assertTrue("Criteria with text expectation has observable check", observableCriteria.hasObservableCheck)

        val landedScreen = com.aniob.core.domain.AniobScreenState(
            packageName = "com.android.settings",
            nodes = listOf(com.aniob.core.domain.AniobNode(id = 1, className = "android.widget.TextView", text = "Wi-Fi"))
        )

        val bareVerdict = if (bareCriteria.hasObservableCheck) {
            bareCriteria.evaluateCriteria(landedScreen, 1)
        } else {
            emptyList()
        }
        assertTrue("Bare criteria produces empty verdict falling through to UNVERIFIED", bareVerdict.isEmpty())

        val observableVerdict = if (observableCriteria.hasObservableCheck) {
            observableCriteria.evaluateCriteria(landedScreen, 1)
        } else {
            emptyList()
        }
        assertTrue("Observable criteria evaluates to non-empty verdict", observableVerdict.isNotEmpty())
        assertTrue("All criteria passed on landed screen", observableVerdict.all { it.state == com.aniob.core.domain.CriterionState.PASSED })
    }

    // ---------------------------------------------------------------------------------------- //
    // PART 4/5: the running task keeps its identity and its progress; new words join it.
    // ---------------------------------------------------------------------------------------- //

    /** A run object as the pipeline leaves it when it asks the user something. */
    private fun parkedRun(task: AniobTask, step: Int = 2, generation: Long = 1L): PipelineRun {
        val pipe = PipelineRun(
            task = task,
            taskGeneration = generation,
            lane = RunLane.STANDARD,
            laneShortcut = null,
            researchMode = com.aniob.app.research.FeatureSelection(),
            resolvedCriteria = SuccessCriteria(),
            startTime = System.currentTimeMillis(),
            hydration = null,
            accessibility = null,
            maxSteps = 8,
            taskContext = TaskContext(
                instruction = task.rawPrompt,
                taskId = task.id,
                condensedHistory = listOf("observed inbox")
            ),
            screenBefore = null,
            fastReflexOpen = false
        )
        pipe.currentStep = step
        pipe.parked = true
        return pipe
    }

    @Test
    fun anInstructionTypedWhileRunningJoinsTheSameTask() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)
        val task = AniobTask(id = "task_88", rawPrompt = "Read my two newest emails", status = TaskStatus.RUNNING)
        vm.activeGeneration = 1L
        vm.openTaskInputChannel(task.id)
        vm._uiState.update { it.copy(activeTask = task, isRunning = true) }

        vm.submitTask("Also tell me who sent the newest one")

        val queued = vm.taskInputs!!.takeInstructions()
        assertEquals("the instruction is queued for THIS task", 1, queued.size)
        assertEquals("Also tell me who sent the newest one", queued.single().text)
        assertEquals("the task keeps its id", "task_88", vm.uiState.value.activeTask?.id)
        assertEquals("the newest goal is not replaced", "Read my two newest emails", vm.uiState.value.activeTask?.rawPrompt)
        assertEquals(1, vm.uiState.value.pendingInstructionCount)
        assertTrue("the task is still running", vm.uiState.value.isRunning)
        assertTrue(
            "the instruction is visible in the transcript of the same task",
            vm.uiState.value.chatMessages.any { it.content == "Also tell me who sent the newest one" }
        )
        vm.closeTaskInputChannel()
    }

    @Test
    fun aNewInstructionWakesOnlyTheWaitingModelRequest() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)
        val task = AniobTask(id = "task_89", rawPrompt = "Summarize the newest emails in Gmail", status = TaskStatus.RUNNING)
        vm.activeGeneration = 1L
        vm.openTaskInputChannel(task.id)
        vm._uiState.update { it.copy(activeTask = task, isRunning = true) }
        val signal = vm.inputSignal!!

        vm.submitTask("Skip the older ones")

        assertTrue("the outstanding model request is interrupted", signal.isCompleted)
        assertEquals("Skip the older ones", vm.taskInputs!!.takeInstructions().single().text)
        vm.closeTaskInputChannel()
    }

    @Test
    fun aLateReplyToAnInterruptedRequestIsDiscarded() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)
        val task = AniobTask(id = "task_90", rawPrompt = "Read the newest messages", status = TaskStatus.RUNNING)
        vm.activeGeneration = 1L
        vm.openTaskInputChannel(task.id)
        vm._uiState.update { it.copy(activeTask = task, isRunning = true) }

        val result = vm.awaitModelRequest {
            // The user types while the model is thinking.
            vm.taskInputs!!.post(TaskInputKind.INSTRUCTION, "the newest two only")
            vm.signalNewInput()
            "reply computed from the OLD wording"
        }

        assertTrue("a late reply may not override a newer instruction", result is ModelRequestResult.Superseded)
        vm.closeTaskInputChannel()
    }

    @Test
    fun aClarificationResumesTheSameTaskFromTheSameStep() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)
        val task = AniobTask(id = "task_91", rawPrompt = "Read my two newest emails", status = TaskStatus.RUNNING)
        val pipe = parkedRun(task, step = 2)
        vm.activeGeneration = 1L
        vm.activePipeline = pipe
        vm.openTaskInputChannel(task.id)
        vm._uiState.update {
            it.copy(activeTask = task, isRunning = false, showGrillMeSheet = true, currentStep = 2)
        }

        val resumed = vm.prepareResume(task, mapOf("app_read.account" to "work@example.com"))

        assertNotNull("a parked run is resumable", resumed)
        assertEquals("same task id", "task_91", resumed!!.task.id)
        assertEquals("same step: nothing is replayed from step 0", 2, resumed.currentStep)
        assertEquals("the original goal is unchanged", "Read my two newest emails", resumed.taskContext.instruction)
        assertEquals(listOf("observed inbox"), resumed.taskContext.condensedHistory)
        assertEquals("work@example.com", resumed.taskContext.grillAnswers["app_read.account"])
        assertFalse("the run is no longer parked", resumed.parked)
        assertTrue("the task is running again", vm.uiState.value.isRunning)
        assertFalse("the question sheet is closed", vm.uiState.value.showGrillMeSheet)
        vm.closeTaskInputChannel()
    }

    @Test
    fun anInstructionCannotCloseATaskItHasNotProcessed() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)
        val task = AniobTask(id = "task_92", rawPrompt = "Read my two newest emails", status = TaskStatus.RUNNING)
        vm.activeGeneration = 1L
        vm.openTaskInputChannel(task.id)
        vm._uiState.update { it.copy(activeTask = task, isRunning = true) }

        vm.submitTask("and the third one too")

        assertFalse("closing is refused while an instruction is pending", vm.mayCloseTask())
        vm.taskInputs!!.takeInstructions()
        assertTrue("once processed, closing is allowed again", vm.mayCloseTask())
        vm.closeTaskInputChannel()
    }
}
