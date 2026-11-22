package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.research.ResearchRun
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.research.ResearchSummaryRow
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.*
import com.aniob.core.narration.ThinkSource
import com.aniob.core.exec.ExecOutcome
import com.aniob.core.exec.ExecReport
import com.aniob.core.execution.StepResult
import com.aniob.core.narration.AgentPhase
import com.aniob.core.run.RunLane
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.CompletedLearningSnapshot

/**
 * Close: everything [executeTaskPipeline] does after the step loop has ended. Final verification
 * against the criteria, the outcome gate, bookkeeping and the completion message. The statements
 * are unchanged.
 */
internal suspend fun AniobViewModel.pipelineComplete(pipe: PipelineRun) {
    with(pipe) {
        // PART 4: a final response may not close a task that still has an unprocessed instruction.
        // The instruction is folded into the SAME context and the loop keeps working; only when no
        // instruction is left does this run get to report an outcome.
        var pendingAdded = drainInstructionsInto(pipe)
        while (pendingAdded > 0 && _uiState.value.isRunning) {
            // New instructions invalidate any earlier terminal state from before they arrived.
            finalOutcome = null
            terminalReason = ""
            completedByIntent = false
            intentVerdict = null
            fastLaunchProof = null
            stepResult = null
            screenBefore = accessibility?.captureCurrentScreenState() ?: screenBefore
            setPhase(AgentPhase.PLANNING, ThinkSource.RULES, "Continuing with your new instruction")
            _uiState.update { it.copy(isRunning = true) }
            // The step loop's own cleanup already ran when it ended. This continuation is still the
            // SAME task, so it re-takes the accessibility task flag while it works and gives it back
            // afterwards; nothing about ownership or generation changes.
            AniobAccessibilityService.isTaskActive = true
            try {
                runStepLoop(pipe)
            } finally {
                AniobAccessibilityService.isTaskActive = false
            }
            if (pipe.parked) return
            pendingAdded = drainInstructionsInto(pipe)
        }
        if (!mayCloseTask(taskContext)) {
            // Input that landed between the last drain and this report. The task may not present a
            // result it has not processed, so it says what is true instead.
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "The task still has unresolved or unprocessed user input."
        }
        // The loop has ended; nothing assigns the last step result any more.
        val stepResult = pipe.stepResult
        // A confirmed SUCCESS was checked against the screen the pipeline verified. A fresh capture
        // here is the user's *next* screen (or Aniob's own window) and used to flip good runs to
        // "not done". Every other outcome still reports against the live screen.
        // A fast-lane close was judged on evidence that is not a tree (the foreground package, or
        // the dispatch itself); walking the tree now would only delay the reported completion.
        val fastLaneFinalScreen: AniobScreenState? = fastLaunchProof ?: if (
            lane == RunLane.FAST_REFLEX && completedByIntent && finalOutcome == Outcome.SUCCESS
        ) {
            screenBefore ?: AniobScreenState(
                packageName = accessibility?.currentForegroundPackage() ?: "com.aniob.app",
                observationKind = "fast-lane"
            )
        } else {
            null
        }
        val finalScreen = fastLaneFinalScreen ?: if (stepResult?.terminalState == StepResult.TerminalState.SUCCESS) {
            stepResult.nextCtx.lastScreenState ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
        } else {
            accessibility?.captureCurrentScreenState() ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
        }
        // A run closed by a shortcut keeps the verdict it was actually judged by. Otherwise the
        // common path re-checks the final screen; a completion with nothing checkable has nothing
        // to re-check at all.
        // Device evidence is merged into EVERY closing path (intent, pipeline, model Finish).
        // v1 only wired it into the intent branch, leaving the common ladder path unguarded.
        setPhase(AgentPhase.VERIFYING)
        val finalDeviceState = if (resolvedCriteria?.hasDeviceChecks == true) probeDeviceState(resolvedCriteria) else DeviceStateSnapshot.UNKNOWN
        val priorEvaluations = when {
            intentVerdict != null -> intentVerdict!!
            completedByIntent -> emptyList()
            else -> resolvedCriteria?.evaluateCriteria(finalScreen, currentStep, finalDeviceState) ?: emptyList()
        }
        // A shortcut's earlier hardware verdict can become stale before closure. Replace it with
        // the final live probe, rather than distinctBy keeping an older PASSED value.
        val criteriaEvaluations = resolvedCriteria?.withLatestDeviceEvaluations(priorEvaluations, finalDeviceState)
            ?: priorEvaluations
        val evidenceItems = criteriaEvaluations.map { ev ->
            EvidenceItem(
                label = ev.description,
                met = ev.state == com.aniob.core.domain.CriterionState.PASSED,
                detail = ev.detail,
                state = ev.state
            )
        }

        if (finalOutcome == null) {
            finalOutcome = when {
                !_uiState.value.isRunning -> Outcome.STOPPED
                currentStep >= maxSteps -> {
                    terminalReason = "Maximum steps ($maxSteps) reached"
                    Outcome.FAILED
                }
                stepResult?.terminalState == StepResult.TerminalState.SUCCESS ->
                    if (resolvedCriteria?.hasObservableCheck == true) Outcome.SUCCESS else Outcome.UNVERIFIED
                stepResult?.terminalState == StepResult.TerminalState.UNVERIFIED -> Outcome.UNVERIFIED
                stepResult?.terminalState == StepResult.TerminalState.FAIL -> Outcome.FAILED
                else -> Outcome.FAILED
            }
        } else if (!completedByIntent && finalOutcome == Outcome.SUCCESS &&
            resolvedCriteria?.hasObservableCheck != true
        ) {
            // Confirmed with no machine-checkable criterion: honest "unverified", never a failure.
            finalOutcome = Outcome.UNVERIFIED
        }

        if (finalOutcome == Outcome.SUCCESS && taskPlan != null && !hasAuthoritativeCompletionCriteria) {
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "The observed state alone does not establish the requested outcome."
        }

        // PART 2/7: a read request is judged against what was OBSERVED inside the app. Opening the
        // inbox, tapping a row, a successful dispatch and a model that says "read it" all satisfy
        // nothing here: only distinct content captured from the target app does.
        val readVerdict = com.aniob.core.execution.AppReadCompletionGate.evaluate(readEvidence)
        if (readVerdict.blocksCompletion) {
            val rejected = readVerdict as com.aniob.core.execution.AppReadCompletionGate.Verdict.Rejected
            if (finalOutcome == null || finalOutcome == Outcome.SUCCESS) {
                finalOutcome = Outcome.UNVERIFIED
                terminalReason = rejected.reason
            }
            readEvidence?.evidenceLines()?.takeIf { it.isNotEmpty() }?.let { seen ->
                // Only observed text is quoted; when nothing was seen this says so instead.
                terminalReason = terminalReason + " Observed: " + seen.joinToString("; ")
            }
        }

        // FINAL GATE (BUG-01/06): SUCCESS may not survive an unmet or unprobed criterion — even
        // when a pipeline step or a model Finish claimed it. This is the single place every run
        // passes through, so no future code path can quietly opt out of real completion checks.
        if (finalOutcome == Outcome.SUCCESS &&
            (resolvedCriteria?.hasObservableCheck != true || criteriaEvaluations.isEmpty())
        ) {
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "The outcome could not be independently verified."
        }
        if (finalOutcome == Outcome.SUCCESS) {
            val failedCriteria = criteriaEvaluations.filter {
                it.state == com.aniob.core.domain.CriterionState.FAILED
            }
            val inconclusiveCriteria = criteriaEvaluations.filter {
                it.state == com.aniob.core.domain.CriterionState.INCONCLUSIVE ||
                    it.state == com.aniob.core.domain.CriterionState.UNCHECKED
            }
            if (failedCriteria.isNotEmpty()) {
                finalOutcome = Outcome.FAILED
                terminalReason = "Task not completed — unmet criteria: " +
                    failedCriteria.joinToString("; ") { "${it.description} (${it.detail})" }
            } else if (inconclusiveCriteria.isNotEmpty()) {
                finalOutcome = Outcome.UNVERIFIED
                terminalReason = "Completion not supported by observable evidence: " +
                    inconclusiveCriteria.joinToString("; ") { "${it.description} — ${it.detail}" }
            }
        }
        if (finalOutcome == Outcome.SUCCESS &&
            resolvedCriteria?.requireInstallConfirmation == true &&
            resolvedCriteria.mustBeInstalled.isEmpty()
        ) {
            // "install <unknown app>" only reached a store SEARCH. There is no package id to query,
            // so the run cannot prove an install happened — honest UNVERIFIED, never SUCCESS.
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "Store search opened, but the install target is unresolved; " +
                "PackageManager cannot confirm completion."
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val closedOutcome = finalOutcome ?: Outcome.FAILED
        val isSuccess = (closedOutcome == Outcome.SUCCESS)
        val outcomeCopy = com.aniob.app.outcome.AniobOutcomeCopy.forTask(
            outcome = closedOutcome ?: Outcome.FAILED,
            reason = terminalReason.ifBlank { "Unmet criteria" },
            taskLabel = task.rawPrompt,
            completedChecks = evidenceItems.filter { it.met }.map { it.label },
            outstandingChecks = evidenceItems.filter { !it.met }.map { it.label }
        )
        val resultSummary = outcomeCopy.message

        // Research metric closure: latency comes from elapsedRealtime inside the manager, LLM calls
        // and steps were counted as they happened, and success is the deterministic verdict above.
        // endTask appends the row, rewrites the summary table and logs RESEARCH_METRICS.
        val researchFailureReason = when {
            isSuccess -> ""
            closedOutcome == Outcome.STOPPED -> "stopped"
            closedOutcome == Outcome.INTERRUPTED -> "interrupted"
            closedOutcome == Outcome.UNVERIFIED -> terminalReason.ifBlank { "unverified" }
            else -> terminalReason.ifBlank { "unmet_criteria" }
        }
        val episodeSnapshot = hippocampusTracker.getSteps()
        val actionsSnapshot = verifiedActions.toList()
        val transitionsSnapshot = rememberTransitions.toList()
        val stateTraceSnapshot = episodeSnapshot.map { it.screenHash }
        val stepCountSnapshot = currentStep + 1
        val routingReasonSnapshot = _uiState.value.lastRoutingReason
        val skipTaskLearning = taskContext.hasUnverifiedActions
        lastCompletedLearningSnapshot = if (taskContext.unconfirmedTaps.isNotEmpty()) null else CompletedLearningSnapshot(task.id,
            task.clarifiedGoal.ifBlank { task.rawPrompt }, resolvedCriteria, finalScreen,
            episodeSnapshot.map { it.action }, transitionsSnapshot, researchMode, episodeSnapshot)
        // Clear only this episode, synchronously. Background consolidation uses the captured list.
        hippocampusTracker.onFailure()
        var researchRun: com.aniob.app.research.ResearchRun? = null
        var researchLine: String? = null
        var researchSummaryRow: com.aniob.app.research.ResearchSummaryRow? = null

        suspend fun bookkeeping() = kotlinx.coroutines.supervisorScope {
            val research = async {
                runCatching {
                    if (researchMode.immediateCompletion) app.researchTelemetry.endTask(
                        com.aniob.app.research.ResearchOutcome.valueOf(closedOutcome.name), researchFailureReason, runId = task.id)
                    else null
                }.getOrNull()
            }
            launch { runCatching {
                if (isSuccess) {
                    // The cache file load no longer blocks dispatch, so it is awaited here, after
                    // the run: recording into (and saving) the not-yet-loaded empty cache would
                    // overwrite the persisted one.
                    if (researchMode.rememberReplay) rememberCacheLoadJob?.join()
                    persistRememberTransitions(researchMode,
                        task.clarifiedGoal.ifBlank { task.rawPrompt }, transitionsSnapshot)
                }
            } }
            launch { runCatching {
                app.metricsCollector.recordSession(taskId = task.id, prompt = task.rawPrompt, isSuccess = isSuccess,
                    steps = stepCountSnapshot, durationMs = totalDuration, tokensUsed = totalTokens,
                    providerUsed = primaryProvider, decisionReason = decisionReason)
            } }
            launch { try { runCatching {
                if (isSuccess && !completedByIntent && !skipTaskLearning &&
                    (researchMode.immediateCompletion || stepResult?.terminalState != StepResult.TerminalState.SUCCESS)) {
                    learningPipeline.onTaskSuccess(TaskContext(instruction = task.rawPrompt,
                        successCriteria = resolvedCriteria, taskId = task.id, lastScreenState = finalScreen), actionsSnapshot)
                }
            } } finally { learningPipeline.onTaskEnd(task.id) } }
            launch { runCatching {
                if (isSuccess && researchMode.skillsEnabled) {
                    val name = hippocampusTracker.consolidateEpisode(task.id,
                        task.clarifiedGoal.ifBlank { task.rawPrompt }, episodeSnapshot, elapsedMs = totalDuration)
                    if (name != null) {
                        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
                        semanticSkillMatcher.loadAndIndexSingleSkill(File(dir, "$name.yaml"))
                    }
                }
            } }
            launch { runCatching {
                app.eventLogger.recordSessionCounters(screenReads = screenReads, actions = actionsDispatched,
                    escalations = escalations, elapsedMs = totalDuration, stateTrace = stateTraceSnapshot,
                    providerDecisionReason = decisionReason, lastRoutingReason = routingReasonSnapshot)
            } }
            researchRun = research.await()
            researchLine = researchRun?.let { app.researchTelemetry.hudLine(it) }
            researchSummaryRow = researchRun?.let { app.researchTelemetry.summaryFor(it.mode) }
            if (_uiState.value.lastSealedTaskId == task.id) _uiState.update {
                it.copy(researchLastRun = researchRun ?: it.researchLastRun,
                    researchStatusLine = researchLine ?: it.researchStatusLine,
                    researchSummary = researchSummaryRow ?: it.researchSummary)
            }
        }

        val execOutcome = when (closedOutcome) {
            Outcome.SUCCESS -> ExecOutcome.SUCCESS
            Outcome.STOPPED -> ExecOutcome.STOPPED
            Outcome.INTERRUPTED -> ExecOutcome.INTERRUPTED
            Outcome.UNVERIFIED -> ExecOutcome.UNVERIFIED
            Outcome.FAILED -> ExecOutcome.FAILED_VERIFICATION
        }
        fun recordExecSnapshot() {
            ExecReport(
                outcome = execOutcome,
                reason = resultSummary,
                screenReads = screenReads,
                actions = actionsDispatched,
                escalations = escalations,
                elapsedMs = totalDuration,
                stateTrace = stateTraceSnapshot,
                providerUsed = primaryProvider,
                decisionReason = decisionReason
            ).toMetricSnapshot(taskId = task.id, stepIndex = currentStep + 1).also { snapshot ->
                app.eventLogger.info(
                    "AniobMetrics",
                    "SNAPSHOT{task=${snapshot.taskId},provider=${snapshot.provider}," +
                        "screenReads=${snapshot.screenReads},actions=${snapshot.actions}," +
                        "escalations=${snapshot.escalations},routing=${snapshot.lastRoutingReason}}"
                )
            }

        }

        fun publishCompletion() {
            val closedResult = closedOutcome ?: Outcome.FAILED
            val isDelegated = terminalReason.trim().startsWith("USER_ACTION:", ignoreCase = true)
            val sealed = AniobOutcomeBridge.seal(
                outcome = closedResult,
                reason = terminalReason.ifBlank { resultSummary },
                evaluations = criteriaEvaluations
            )
            val taskVerification = when (sealed.outcome) {
                com.aniob.core.outcome.RunOutcome.VERIFIED -> TaskVerificationStatus.VERIFIED
                com.aniob.core.outcome.RunOutcome.FAILED -> TaskVerificationStatus.FAILED
                else -> TaskVerificationStatus.UNVERIFIED
            }
            val finalTaskStatus = when {
                isDelegated -> TaskStatus.WAITING_FOR_USER
                closedResult == Outcome.SUCCESS && sealed.outcome == com.aniob.core.outcome.RunOutcome.VERIFIED -> TaskStatus.SUCCESS
                closedResult == Outcome.UNVERIFIED -> TaskStatus.UNVERIFIED
                closedResult == Outcome.STOPPED || closedResult == Outcome.INTERRUPTED -> TaskStatus.ABORTED
                else -> TaskStatus.FAILED
            }
            pipe.task = pipe.task.copy(
                status = finalTaskStatus,
                verificationStatus = taskVerification,
                lastExecutionResult = terminalReason.ifBlank { resultSummary },
                finishedAt = System.currentTimeMillis()
            )
            // If the run ended unverified/stopped/interrupted and the last trajectory action has
            // no correlated VERIFIED execution record yet, record it as UNCERTAIN so a
            // "Proceed" / "Continue" follow-up reconciles it against the live screen first.
            if (finalTaskStatus != TaskStatus.SUCCESS) {
                val lastTrajectoryAction = pipe.taskContext.trajectory.lastOrNull()
                val lastRecorded = pipe.taskContext.latestExecutionResult()
                if (lastTrajectoryAction != null &&
                    (lastRecorded == null || lastRecorded.actionSummary != lastTrajectoryAction.describeAction())
                ) {
                    pipe.taskContext = pipe.taskContext.recordCorrelatedOutcome(
                        ActionExecutionRecord(
                            actionId = pipe.taskContext.allocateActionId(currentStep, 0),
                            stepIndex = currentStep,
                            actionSummary = lastTrajectoryAction.describeAction(),
                            status = ExecutionOutcomeStatus.UNCERTAIN,
                            detail = terminalReason.ifBlank { "Task ended before verification (${finalTaskStatus.name})" },
                            screenPackage = finalScreen.packageName,
                            screenHash = finalScreen.treeHash
                        )
                    )
                }
            }
            // Keep incomplete/delegated/paused/stopped runs resumable in-memory in the same process
            // so "Proceed" or "Continue" resumes the same task without losing its context.
            // (Process-death recovery is handled separately via TaskRecoverySnapshot.)
            lastResumablePipeline = if (finalTaskStatus == TaskStatus.SUCCESS) null else pipe

            val summaryCard = buildSummary(
                prompt = task.rawPrompt,
                outcome = closedOutcome,
                steps = currentStep + 1,
                durationMs = totalDuration,
                provider = primaryProvider,
                evidence = evidenceItems,
                reason = terminalReason.ifBlank { null }
            )
            postSummaryCard(summaryCard)
            // Step 1 invariant: SUCCESS survives only with observer-made evidence; otherwise UNVERIFIED.
            recordSealedOutcome(task.id, sealed)
            setPhase(AgentPhase.IDLE)
            clearRunningMarker()

            AniobBackgroundController.onTaskFinished(
                app,
                resultSummary,
                isSuccess = isSuccess,
                unverified = closedOutcome == Outcome.UNVERIFIED,
                openChat = closedOutcome == Outcome.FAILED || closedOutcome == Outcome.UNVERIFIED,
                wasStopped = closedOutcome == Outcome.STOPPED,
                resultTitle = outcomeCopy.title
            )

            val chatBadge = when (closedOutcome) {
                Outcome.SUCCESS -> "Success"
                Outcome.UNVERIFIED -> "Unverified"
                Outcome.STOPPED -> "Stopped"
                Outcome.INTERRUPTED -> "Interrupted"
                Outcome.FAILED -> "Failed"
            }
            val assistantMessage = ChatMessage(
                id = "msg_asst_${java.util.UUID.randomUUID()}",
                role = "assistant",
                content = outcomeCopy.message,
                badge = chatBadge,
                stepIndex = currentStep + 1
            )

            if (researchMode.immediateCompletion) app.researchTelemetry.prepareBackgroundClosure(task.id)
            updateToIdle {
                it.copy(
                    activeTask = pipe.task,
                    statusMessage = resultSummary,
                    lastProviderUsed = primaryProvider,
                    chatMessages = it.chatMessages + assistantMessage,
                    researchStatusLine = researchLine ?: it.researchStatusLine,
                    researchLastRun = researchRun ?: it.researchLastRun,
                    researchSummary = researchSummaryRow ?: it.researchSummary
                )
            }
            app.researchTelemetry.recordCompletionMessageShown(task.id)
        }
        com.aniob.core.execution.CompletionPublication.complete(
            immediate = researchMode.immediateCompletion,
            publish = ::publishCompletion,
            bookkeeping = {
                if (researchMode.immediateCompletion) {
                    viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.supervisorScope {
                            launch { runCatching { recordExecSnapshot() } }
                            bookkeeping()
                        }
                    }
                } else {
                    bookkeeping()
                    recordExecSnapshot()
                }
            }
        )
        if (!researchMode.immediateCompletion) {
            val row = app.researchTelemetry.endTask(com.aniob.app.research.ResearchOutcome.valueOf(closedOutcome.name),
                researchFailureReason, runId = task.id)
            if (row != null) {
                val line = app.researchTelemetry.hudLine(row)
                val summary = app.researchTelemetry.summaryFor(row.mode)
                _uiState.update { it.copy(researchLastRun = row, researchStatusLine = line, researchSummary = summary) }
            }
        }
    }
}
