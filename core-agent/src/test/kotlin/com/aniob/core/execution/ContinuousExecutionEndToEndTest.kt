package com.aniob.core.execution

import com.aniob.core.capability.AniobCapabilityManifest
import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.ActionExecutionRecord
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.ExecutionOutcomeStatus
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.domain.TaskContext
import com.aniob.core.external.AniobActionStepProvider
import com.aniob.core.external.ChatTurn
import com.aniob.core.providers.AniobStructuredOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM tests covering the real model -> parse -> StepPipeline/ActionBurstRunner ->
 * TaskContext correlation -> next LLM request loop across all core continuity guarantees.
 */
class ContinuousExecutionEndToEndTest {

    private fun screen(pkg: String, hash: String, vararg nodes: AniobNode) = AniobScreenState(
        packageName = pkg,
        activityName = "$pkg.MainActivity",
        treeHash = hash,
        screenHash = hash,
        nodes = nodes.toList()
    )

    private fun node(id: Int, text: String, clickable: Boolean = true) = AniobNode(
        id = id,
        className = "android.widget.Button",
        text = text,
        isClickable = clickable,
        isEnabled = true,
        isVisibleToUser = true,
        bounds = AniobRect(10, id * 100, 300, id * 100 + 80)
    )

    @Test
    fun `multi-step UI flow feeds correlated action ids and real verification results into next LLM call`() = runBlocking {
        val capturedPrompts = mutableListOf<String>()
        val responses = ArrayDeque(
            listOf(
                """{"thought":"Open settings","tool":"OPEN_APP","package":"com.android.settings"}""",
                """{"thought":"Tap Display","tool":"TAP","target":{"type":"text","value":"Display"}}""",
                """{"thought":"Display is open","tool":"FINISH","summary":"Opened Display settings"}"""
            )
        )
        val provider = object : AniobActionStepProvider {
            override suspend fun getNextActionRawStreaming(
                systemPrompt: String,
                userPrompt: String,
                screenshotBase64: String?,
                maxTokens: Int,
                onFirstToken: () -> Unit
            ): Result<String> {
                capturedPrompts += userPrompt
                onFirstToken()
                return Result.success(responses.removeFirst())
            }

            override suspend fun getNextActionRaw(
                systemPrompt: String,
                userPrompt: String,
                screenshotBase64: String?,
                maxTokens: Int
            ): Result<String> = Result.success(responses.removeFirst())
        }

        val s0 = screen("com.android.launcher", "h0")
        val s1 = screen("com.android.settings", "h1", node(1, "Display"))
        val s2 = screen("com.android.settings", "h2", node(1, "Brightness"))
        val screens = listOf(s0, s1, s2)

        val stepPipeline = StepPipeline()
        var ctx = TaskContext(
            instruction = "Open Display settings",
            successCriteria = SuccessCriteria(mustContainText = listOf("Brightness"))
        )

        for (step in 0..2) {
            val currentScreen = screens[step]
            val obs = ctx.buildExecutorRequest(currentScreen).toPromptString()
            val raw = provider.getNextActionRawStreaming(
                systemPrompt = "System",
                history = ctx.priorConversationTurns(),
                userPrompt = obs
            ).getOrThrow()
            val parsed = AniobStructuredOutput.parseOrRepair(raw, currentScreen)
            val nextScreen = screens[(step + 1).coerceAtMost(screens.lastIndex)]
            val stepResult = stepPipeline.executeStep(
                ctx = ctx,
                planningScreen = currentScreen,
                proposal = StepPipeline.Proposal.Model(parsed.action, "OMNIROUTE_CLOUD"),
                capture = { currentScreen },
                dispatch = {
                    StepPipeline.DispatchOutcome(
                        dispatched = true,
                        reason = it.describeAction(),
                        screenAfter = nextScreen
                    )
                },
                targetNode = parsed.action.semanticTarget()?.let {
                    AniobActionExecutor.resolveTarget(parsed.action, currentScreen)?.node
                }
            )
            val actionId = stepResult.nextCtx.allocateActionId(step, 0)
            val status = if (stepResult.terminalState == StepResult.TerminalState.FAIL) {
                ExecutionOutcomeStatus.FAILED
            } else if (stepResult.verificationSkippedFast || stepResult.verification == null) {
                ExecutionOutcomeStatus.UNVERIFIED
            } else if (stepResult.verification?.isSuccessful == true ||
                stepResult.terminalState == StepResult.TerminalState.SUCCESS
            ) {
                ExecutionOutcomeStatus.VERIFIED
            } else {
                ExecutionOutcomeStatus.FAILED
            }
            ctx = stepResult.nextCtx.recordCorrelatedOutcome(
                ActionExecutionRecord(
                    actionId = actionId,
                    stepIndex = step,
                    actionSummary = stepResult.action.describeAction(),
                    status = status,
                    detail = stepResult.verification?.reason ?: "ok",
                    screenPackage = nextScreen.packageName,
                    screenHash = nextScreen.treeHash,
                    verificationEvidence = stepResult.verification?.textEvidence
                )
            )
        }

        assertEquals(3, capturedPrompts.size)
        // Turn 2 must see step 0's correlated action & verified result, NOT disguised as a user prompt
        assertTrue(capturedPrompts[1].contains("Assistant [action_id=act_s0_m0_0]:"))
        assertTrue(capturedPrompts[1].contains("ExecutionResult [action_id=act_s0_m0_0]:"))
        assertTrue(capturedPrompts[1].contains("status=VERIFIED"))
        assertFalse(capturedPrompts[1].contains("User: step=0"))
        assertTrue(capturedPrompts[1].contains("ScreenObservation:"))
        // Turn 3 must see both prior correlated action IDs
        assertTrue(capturedPrompts[2].contains("act_s0_m0_0"))
        assertTrue(capturedPrompts[2].contains("act_s1_m0_1"))
    }

    @Test
    fun `device command output feeds real execution result into next LLM reasoning request`() = runBlocking {
        val capturedPrompts = mutableListOf<String>()
        val responses = ArrayDeque(
            listOf(
                """{"thought":"Read media volume","tool":"DEVICE_CONTROL","command":"volume_get","args":{"stream":"music"}}""",
                """{"thought":"Volume is 40%","tool":"FINISH","summary":"Media volume is 40%"}"""
            )
        )
        val provider = object : AniobActionStepProvider {
            override suspend fun getNextActionRawStreaming(
                systemPrompt: String,
                userPrompt: String,
                screenshotBase64: String?,
                maxTokens: Int,
                onFirstToken: () -> Unit
            ): Result<String> {
                capturedPrompts += userPrompt
                onFirstToken()
                return Result.success(responses.removeFirst())
            }

            override suspend fun getNextActionRaw(
                systemPrompt: String,
                userPrompt: String,
                screenshotBase64: String?,
                maxTokens: Int
            ): Result<String> = Result.success(responses.removeFirst())
        }

        val currentScreen = screen("com.aniob.app", "h0")
        val stepPipeline = StepPipeline()
        var ctx = TaskContext(instruction = "Check my media volume")

        val obs0 = ctx.buildExecutorRequest(currentScreen).toPromptString()
        val raw0 = provider.getNextActionRawStreaming("System", ctx.priorConversationTurns(), obs0).getOrThrow()
        val act0 = AniobStructuredOutput.parseOrRepair(raw0, currentScreen).action
        assertTrue(act0 is AniobAction.DeviceControl && act0.command == DeviceCommand.VOLUME_GET)

        val step0 = stepPipeline.executeStep(
            ctx = ctx,
            planningScreen = currentScreen,
            proposal = StepPipeline.Proposal.Model(act0, "OMNIROUTE_CLOUD"),
            capture = { currentScreen },
            dispatch = {
                StepPipeline.DispatchOutcome(
                    dispatched = true,
                    reason = "music volume is 40%",
                    screenAfter = currentScreen
                )
            }
        )
        val id0 = step0.nextCtx.allocateActionId(0, 0)
        ctx = step0.nextCtx.recordCorrelatedOutcome(
            ActionExecutionRecord(
                actionId = id0,
                stepIndex = 0,
                actionSummary = step0.action.describeAction(),
                status = ExecutionOutcomeStatus.VERIFIED,
                detail = "music volume is 40%",
                screenPackage = currentScreen.packageName
            )
        )

        val obs1 = ctx.buildExecutorRequest(currentScreen).toPromptString()
        provider.getNextActionRawStreaming("System", ctx.priorConversationTurns(), obs1).getOrThrow()

        assertTrue(capturedPrompts[1].contains("music volume is 40%"))
        assertTrue(capturedPrompts[1].contains("ExecutionResult [action_id=$id0]:"))
        assertTrue(capturedPrompts[1].contains("LAST RESULT / INCIDENT: [action_id=$id0 | status=VERIFIED]"))
    }

    @Test
    fun `burst runner preserves individual member results and never treats unverified or failed burst as success`() = runBlocking {
        val origin = screen("com.example.app", "h0", node(1, "Next"), node(2, "Proceed"))
        val afterFirst = screen("com.example.app", "h1", node(1, "Next"), node(2, "Proceed"))
        val pipeline = StepPipeline()
        var currentLive = origin

        val runner = ActionBurstRunner(
            capture = { currentLive },
            executeMember = { action, ctx, live, target ->
                pipeline.executeStep(
                    ctx = ctx,
                    planningScreen = live,
                    proposal = StepPipeline.Proposal.Model(action, "OMNIROUTE_CLOUD"),
                    capture = { live },
                    dispatch = { act ->
                        // StepPipeline retargets SomIndex -> Text("Next"), so inspect the target text.
                        if (act.describeAction().contains("Next")) {
                            currentLive = afterFirst
                            StepPipeline.DispatchOutcome(dispatched = true, reason = "tapped Next", screenAfter = afterFirst)
                        } else {
                            // Action 2 fails dispatch -> verification missing, terminalState FAIL
                            StepPipeline.DispatchOutcome(dispatched = false, reason = "target disappeared", screenAfter = afterFirst)
                        }
                    },
                    targetNode = target
                )
            }
        )

        val burst = ActionBurst(
            thought = "Tap Next then Proceed",
            actions = listOf(
                AniobAction.Tap(SemanticTarget.SomIndex(1)),
                AniobAction.Tap(SemanticTarget.SomIndex(2))
            ),
            isComplete = true
        )

        val outcome = runner.run(burst, origin, TaskContext(instruction = "Complete two-step wizard"))
        assertEquals(2, outcome.memberResults.size)
        assertEquals(ActionBurstRunner.Stop.BLOCKED, outcome.stop)
        assertTrue(outcome.memberResults[0].verification?.isSuccessful == true)
        assertEquals(StepResult.TerminalState.FAIL, outcome.memberResults[1].terminalState)
        assertFalse(outcome.memberResults[1].verification?.isSuccessful == true)
    }

    @Test
    fun `uncertain Submit is reconciled to VERIFIED via screen evidence and preserves actionId`() {
        val ctx = TaskContext(instruction = "Submit form")
            .recordCorrelatedOutcome(
                ActionExecutionRecord(
                    actionId = "act_s1_m0_0",
                    stepIndex = 1,
                    actionSummary = "tap(Submit)",
                    status = ExecutionOutcomeStatus.UNCERTAIN,
                    detail = "Stopped before verification",
                    preScreenHash = "h4"
                )
            )

        assertTrue(ctx.hasUncertainExecution())
        val reconciled = ctx.reconcileUncertainExecutions(
            screen("com.example.app", "h5", node(1, "Confirmation #123"))
        )

        assertFalse(reconciled.hasUncertainExecution())
        val resolvedRecord = reconciled.verifiedExecutionRecords().single()
        assertEquals("act_s1_m0_0", resolvedRecord.actionId)
        assertEquals(ExecutionOutcomeStatus.VERIFIED, resolvedRecord.status)
        assertEquals("Confirmation #123", resolvedRecord.verificationEvidence)

        val turns = reconciled.priorConversationTurns()
        assertTrue(turns.any { it.role == ChatTurn.TOOL && it.actionId == "act_s1_m0_0" && it.content.contains("status=VERIFIED") })
        assertTrue(turns.first().content.contains("Already VERIFIED actions"))
    }

    @Test
    fun `uncertain action stays UNCERTAIN when live screen evidence is insufficient`() {
        val ctx = TaskContext(instruction = "Check payment status")
            .recordCorrelatedOutcome(
                ActionExecutionRecord(
                    actionId = "act_s2_m0_0",
                    stepIndex = 2,
                    actionSummary = "tap(Pay)",
                    status = ExecutionOutcomeStatus.UNCERTAIN,
                    detail = "Process died during dispatch",
                    preScreenHash = "h10"
                )
            )

        val reconciled = ctx.reconcileUncertainExecutions(
            screen("com.example.app", "h10", node(1, "Loading..."))
        )

        assertTrue(reconciled.hasUncertainExecution())
        val kept = reconciled.uncertainExecutionRecords().single()
        assertEquals("act_s2_m0_0", kept.actionId)
        assertEquals(ExecutionOutcomeStatus.UNCERTAIN, kept.status)
        assertTrue(kept.detail.contains("insufficient evidence"))
    }

    @Test
    fun `history truncation at exact boundary never splits assistant and tool action pairs`() {
        var ctx = TaskContext(instruction = "Original goal")
            .withInstructions(listOf("Latest instruction: use dark mode"))
        for (i in 0 until 10) {
            ctx = ctx.recordCorrelatedOutcome(
                ActionExecutionRecord(
                    actionId = "act_s${i}_m0_$i",
                    stepIndex = i,
                    actionSummary = "tap(Step$i)",
                    status = ExecutionOutcomeStatus.VERIFIED,
                    detail = "Tapped Step$i",
                    screenPackage = "com.example.app",
                    screenHash = "h$i"
                )
            )
        }

        // Request an odd turn budget (e.g., maxTurns=6 -> 1 goalHeader + 5 history slots).
        // With 2 turns per (ASSISTANT, TOOL) pair, 5 slots must fit 2 complete pairs (4 turns),
        // NEVER 2 pairs + an orphaned TOOL turn!
        val prior = ctx.priorConversationTurns(maxTurns = 6)
        assertTrue(prior.first().role == ChatTurn.USER)
        assertTrue(prior.first().content.contains("Original task: Original goal"))
        assertTrue(prior.first().content.contains("Latest instruction: use dark mode"))

        val historyOnly = prior.drop(1)
        assertEquals(4, historyOnly.size)
        for (idx in historyOnly.indices step 2) {
            assertEquals(ChatTurn.ASSISTANT, historyOnly[idx].role)
            assertEquals(ChatTurn.TOOL, historyOnly[idx + 1].role)
            assertEquals(historyOnly[idx].actionId, historyOnly[idx + 1].actionId)
        }

        // Verify ChatTurn.flatten preserves goal header + pairs + final ScreenObservation
        val fullTurns = ctx.buildConversationTurns("ScreenObservation: pkg=com.example.app")
        val flattened = ChatTurn.flatten(fullTurns)
        assertTrue(flattened.contains("Original task: Original goal"))
        assertTrue(flattened.contains("Latest instruction: use dark mode"))
        assertTrue(flattened.endsWith("ScreenObservation: pkg=com.example.app"))
    }

    @Test
    fun `missing callPhone contacts and allFilesAccess exclude unavailable commands from executable capabilities`() {
        val restricted = AniobCapabilityManifest.DeviceState(
            accessibility = true,
            notificationAccess = true,
            dndAccess = true,
            location = true,
            callPhone = false,
            contacts = false,
            allFilesAccess = false
        )

        val available = AniobCapabilityManifest.availableCommands(restricted)
        assertFalse(available.contains(DeviceCommand.CALL))
        assertFalse(available.contains(DeviceCommand.MESSAGE_WHATSAPP))
        assertFalse(available.contains(DeviceCommand.MESSAGE_TELEGRAM))
        assertFalse(available.contains(DeviceCommand.MESSAGE_SMS))
        assertFalse(available.contains(DeviceCommand.FILE_FIND))
        assertFalse(available.contains(DeviceCommand.FILE_OPEN))
        assertFalse(available.contains(DeviceCommand.FILE_READ))

        assertTrue(AniobCapabilityManifest.blockedReason(DeviceCommand.CALL, restricted)!!.contains("Phone permission"))
        assertTrue(AniobCapabilityManifest.blockedReason(DeviceCommand.MESSAGE_WHATSAPP, restricted)!!.contains("Contacts permission"))
        assertTrue(AniobCapabilityManifest.blockedReason(DeviceCommand.FILE_FIND, restricted)!!.contains("All files access"))

        // Explicit direct number / SAF URI still respects per-argument exceptions when callPhone is granted
        val callPhoneOnly = restricted.copy(callPhone = true)
        assertEquals(
            null,
            AniobCapabilityManifest.blockedReason(DeviceCommand.CALL, callPhoneOnly, mapOf("number" to "5551234"))
        )
        assertEquals(
            null,
            AniobCapabilityManifest.blockedReason(DeviceCommand.FILE_READ, restricted, mapOf("uri" to "content://doc/1"))
        )

        val rendered = AniobCapabilityManifest.render(restricted, compact = false, executableOnly = true)
        assertFalse(rendered.contains("- call ("))
        assertFalse(rendered.contains("- file_find"))
        assertFalse(rendered.contains("- message_whatsapp"))
    }

    @Test
    fun `burst early stop records dropped actions as NOT_EXECUTED rather than FAILED or UNCERTAIN`() = runBlocking {
        val origin = screen("com.example.app", "h0", node(1, "Next"), node(2, "Submit"))
        val navigatedAway = screen("com.other.app", "h1", node(1, "Details"))
        val pipeline = StepPipeline()
        var currentLive = origin

        val runner = ActionBurstRunner(
            capture = { currentLive },
            executeMember = { action, ctx, live, target ->
                pipeline.executeStep(
                    ctx = ctx,
                    planningScreen = live,
                    proposal = StepPipeline.Proposal.Model(action, "OMNIROUTE_CLOUD"),
                    capture = { live },
                    dispatch = {
                        currentLive = navigatedAway
                        StepPipeline.DispatchOutcome(dispatched = true, reason = "tapped Next", screenAfter = navigatedAway)
                    },
                    targetNode = target
                )
            }
        )

        val burst = ActionBurst(
            thought = "Tap Next then Submit",
            actions = listOf(
                AniobAction.Tap(SemanticTarget.SomIndex(1)),
                AniobAction.Tap(SemanticTarget.SomIndex(2))
            ),
            isComplete = true
        )

        val run = runner.run(burst, origin, TaskContext(instruction = "Wizard"))
        assertEquals(1, run.memberResults.size)
        assertEquals(ActionBurstRunner.Stop.NAVIGATED, run.stop)

        var ctx = run.ctx
        ctx = ctx.recordCorrelatedOutcome(
            ActionExecutionRecord(
                actionId = ctx.allocateActionId(0, 0),
                stepIndex = 0,
                actionSummary = run.memberResults[0].action.describeAction(),
                status = ExecutionOutcomeStatus.VERIFIED,
                detail = "Verified"
            )
        )
        burst.actions.drop(run.memberResults.size).forEachIndexed { offset, skipped ->
            ctx = ctx.recordCorrelatedOutcome(
                ActionExecutionRecord(
                    actionId = ctx.allocateActionId(0, run.memberResults.size + offset),
                    stepIndex = 0,
                    actionSummary = skipped.describeAction(),
                    status = ExecutionOutcomeStatus.NOT_EXECUTED,
                    detail = "Not executed (${run.stop.name}; safe to plan again)"
                )
            )
        }

        assertEquals(1, ctx.verifiedExecutionRecords().size)
        assertEquals(1, ctx.notExecutedRecords().size)
        assertTrue(ctx.failedExecutionRecords().isEmpty())
        assertFalse(ctx.hasUncertainExecution())
        val promptHeader = ctx.priorConversationTurns().first().content
        assertTrue(promptHeader.contains("NOT_EXECUTED actions (never dispatched — safe to plan again)"))
    }

    @Test
    fun `task recovery snapshot restores interrupted context and reconciles uncertain dispatch without replaying`() {
        val snapshot = com.aniob.core.run.TaskRecoverySnapshot(
            taskId = "task_recovery_1",
            originalInstruction = "Submit expense report",
            lastKnownTaskStatus = "RUNNING",
            currentStep = 2,
            pendingInstructions = listOf("Use corporate card"),
            executionResults = listOf(
                ActionExecutionRecord(
                    actionId = "act_s0_m0_0",
                    stepIndex = 0,
                    actionSummary = "open_app(com.example.expense)",
                    status = ExecutionOutcomeStatus.VERIFIED,
                    detail = "Opened expense app"
                ),
                ActionExecutionRecord(
                    actionId = "act_s1_m0_1",
                    stepIndex = 1,
                    actionSummary = "tap(Submit)",
                    status = ExecutionOutcomeStatus.UNCERTAIN,
                    detail = "Process died after dispatch",
                    preScreenHash = "h_form"
                )
            )
        )

        val decoded = com.aniob.core.run.TaskRecoverySnapshot.fromJson(snapshot.toJson())!!
        assertEquals("task_recovery_1", decoded.taskId)
        assertEquals(listOf("Use corporate card"), decoded.pendingInstructions)

        val restored = decoded.restoreAsInterrupted(
            liveScreen = screen("com.example.expense", "h_done", node(1, "Expense submitted"))
        )
        assertEquals("Submit expense report", restored.instruction)
        assertEquals(listOf("Use corporate card"), restored.additionalInstructions)
        assertEquals(2, restored.verifiedExecutionRecords().size)
        assertFalse(restored.hasUncertainExecution())
        assertEquals("act_s1_m0_1", restored.verifiedExecutionRecords()[1].actionId)
    }

    @Test
    fun `dynamic capability manifest omits unavailable UI tools and blocked device commands when executableOnly is true`() {
        val restricted = AniobCapabilityManifest.DeviceState(
            accessibility = false,
            notificationAccess = false,
            dndAccess = false,
            location = false
        )

        val tools = AniobCapabilityManifest.availableActionTools(restricted)
        assertFalse(tools.contains("TAP"))
        assertFalse(tools.contains("INPUT_TEXT"))
        assertFalse(tools.contains("SWIPE"))
        assertTrue(tools.contains("DEVICE_CONTROL"))
        assertTrue(tools.contains("OPEN_APP"))

        val rendered = AniobCapabilityManifest.render(restricted, compact = false, executableOnly = true)
        assertTrue(rendered.contains("UI gestures (TAP, LONG_PRESS, INPUT_TEXT, SWIPE, SYSTEM_KEY) are NOT executable right now"))
        assertFalse(rendered.contains("- screenshot"))
        assertFalse(rendered.contains("- otp_copy"))
        assertFalse(rendered.contains("- dnd_on"))
        assertTrue(rendered.contains("torch_on"))
        assertTrue(rendered.contains("volume_set"))
    }
}
