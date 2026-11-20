package com.aniob.core.domain

import com.aniob.core.external.ChatTurn

/**
 * Honest verification outcome for one executed action.
 * Missing or skipped verification must never be promoted to [VERIFIED].
 * Actions discarded before dispatch must be [NOT_EXECUTED], never [FAILED] or [UNCERTAIN].
 */
enum class ExecutionOutcomeStatus {
    VERIFIED,
    FAILED,
    UNVERIFIED,
    UNCERTAIN,
    NOT_EXECUTED
}

/**
 * Actual outcome of executing one action against the device/environment, correlated by [actionId].
 */
data class ActionExecutionRecord(
    val actionId: String = "",
    val stepIndex: Int,
    val actionSummary: String,
    val status: ExecutionOutcomeStatus,
    val detail: String,
    val screenPackage: String = "",
    val screenHash: String = "",
    val verificationEvidence: String? = null,
    val preScreenHash: String = "",
    val expectedPackage: String = "",
    val expectedText: String = ""
) {
    val success: Boolean get() = status == ExecutionOutcomeStatus.VERIFIED
}

/**
 * Per-task context carried through every step of the loop.
 *
 * Preserves the original goal, follow-up instructions, structured conversation history,
 * previously executed actions (correlated by stable [ActionExecutionRecord.actionId]), and their
 * actual execution results across steps and continuations ("Proceed" / "Continue").
 */
data class TaskContext(
    val instruction: String,
    val successCriteria: SuccessCriteria = SuccessCriteria(),
    val condensedHistory: List<String> = emptyList(),
    /** Verified-or-not actions executed so far, in order. Feeds learning + finish evidence. */
    val trajectory: List<AniobAction> = emptyList(),
    val stepIndex: Int = 0,
    val lastScreenState: AniobScreenState? = null,
    val retrievedDocs: List<String> = emptyList(),
    val grillAnswers: Map<String, String> = emptyMap(),
    val tokenBudget: TokenBudget = TokenBudget(),
    val consecutiveFailures: Int = 0,
    val rejectedFinishCount: Int = 0,
    val taskId: String = "",
    val hasFastSkippedSteps: Boolean = false,
    /**
     * Instructions the user added **while this task was running**, in their own words and in order.
     *
     * They are additive: [instruction] is still the task's original goal, so a continuation refines
     * the work instead of restarting it. Only verified progress changes the plan — a new instruction
     * never replays an action that already ran.
     */
    val additionalInstructions: List<String> = emptyList(),
    /** Structured conversation turns (user/assistant/tool/observation) across the task lifecycle. */
    val conversationHistory: List<ChatTurn> = emptyList(),
    /** Real post-execution outcomes for every attempted/planned action in order, correlated by actionId. */
    val executionResults: List<ActionExecutionRecord> = emptyList(),
    val unconfirmedTaps: List<UnconfirmedTap> = emptyList()
) {
    /** Screen and stable target for a tap that may already have taken effect. */
    data class UnconfirmedTap(val screen: AniobScreenState, val target: AniobNode)

    val hasUnverifiedActions: Boolean get() = hasFastSkippedSteps || unconfirmedTaps.isNotEmpty()

    data class TokenBudget(
        val maxTotal: Int = 32_000,
        val usedEstimate: Int = 0
    ) {
        val remaining: Int get() = (maxTotal - usedEstimate).coerceAtLeast(0)
        val isExhausted: Boolean get() = remaining == 0
    }

    fun allocateActionId(stepIndex: Int, memberIndex: Int = 0): String =
        "act_s${stepIndex}_m${memberIndex}_${executionResults.size}"

    fun withStep(index: Int, screen: AniobScreenState?, historyEntry: String?): TaskContext =
        copy(
            stepIndex = index,
            lastScreenState = screen,
            condensedHistory = if (historyEntry == null) condensedHistory else condensedHistory + historyEntry
        )

    /**
     * Appends instructions the user added mid-task, keeping the original goal first.
     *
     * Duplicates are dropped: saying "and only the unread ones" twice must not make the model
     * treat it as two different requirements.
     */
    fun withInstructions(newInstructions: List<String>): TaskContext {
        val merged = (additionalInstructions + newInstructions.map { it.trim() }.filter { it.isNotEmpty() })
            .distinct()
        return if (merged == additionalInstructions) this else copy(additionalInstructions = merged)
    }

    /** Appends the action of the step being closed so the trajectory stays in lockstep with history. */
    fun withAction(action: AniobAction): TaskContext = copy(trajectory = trajectory + action)

    /** Appends a real post-execution result from the device/action pipeline. */
    fun withExecutionResult(record: ActionExecutionRecord): TaskContext =
        copy(executionResults = executionResults + record)

    /** Appends a structured turn while keeping recent history bounded without splitting action pairs. */
    fun withConversationTurn(
        role: String,
        content: String,
        actionId: String? = null
    ): TaskContext {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return this
        val last = conversationHistory.lastOrNull()
        if (last != null && last.role == role && last.content == trimmed && last.actionId == actionId) {
            return this
        }
        val combined = conversationHistory + ChatTurn(role, trimmed, actionId)
        val bounded = ChatTurn.trimPreservingActionPairs(combined, ChatTurn.MAX_TURNS * 2)
        return copy(conversationHistory = bounded)
    }

    /** Records an assistant action and its correlated execution outcome together under [record.actionId]. */
    fun recordCorrelatedOutcome(record: ActionExecutionRecord): TaskContext =
        withExecutionResult(record)
            .withConversationTurn(ChatTurn.ASSISTANT, record.actionSummary, actionId = record.actionId)
            .withConversationTurn(
                ChatTurn.TOOL,
                ChatTurn.formatExecutionResult(
                    actionId = record.actionId,
                    stepIndex = record.stepIndex,
                    actionSummary = record.actionSummary,
                    status = record.status.name,
                    detail = record.detail,
                    evidence = record.verificationEvidence
                ),
                actionId = record.actionId
            )

    fun latestExecutionResult(): ActionExecutionRecord? = executionResults.lastOrNull()

    fun verifiedExecutionRecords(): List<ActionExecutionRecord> =
        executionResults.filter { it.status == ExecutionOutcomeStatus.VERIFIED }

    fun failedExecutionRecords(): List<ActionExecutionRecord> =
        executionResults.filter { it.status == ExecutionOutcomeStatus.FAILED }

    fun uncertainExecutionRecords(): List<ActionExecutionRecord> =
        executionResults.filter {
            it.status == ExecutionOutcomeStatus.UNVERIFIED || it.status == ExecutionOutcomeStatus.UNCERTAIN
        }

    fun notExecutedRecords(): List<ActionExecutionRecord> =
        executionResults.filter { it.status == ExecutionOutcomeStatus.NOT_EXECUTED }

    fun hasUncertainExecution(): Boolean = uncertainExecutionRecords().isNotEmpty()

    /**
     * Reconciles uncertain/unverified execution records against [screen] and available criteria,
     * updating each record's [ExecutionOutcomeStatus] to [VERIFIED], [FAILED], or keeping
     * [UNCERTAIN] while preserving the original [ActionExecutionRecord.actionId].
     */
    fun reconcileUncertainExecutions(screen: AniobScreenState): TaskContext {
        if (!hasUncertainExecution()) return this
        val screenTexts = screen.nodes.flatMap { listOf(it.text, it.contentDescription) }
            .filter { it.isNotBlank() }
        val updatedRecords = mutableListOf<ActionExecutionRecord>()
        val resolvedTurns = mutableListOf<ActionExecutionRecord>()

        for (record in executionResults) {
            if (record.status != ExecutionOutcomeStatus.UNCERTAIN &&
                record.status != ExecutionOutcomeStatus.UNVERIFIED
            ) {
                updatedRecords += record
                continue
            }

            val updated = resolveUncertainRecord(record, screen, screenTexts)
            updatedRecords += updated
            resolvedTurns += updated
        }

        var nextHistory = conversationHistory
        for (resolved in resolvedTurns) {
            val formatted = ChatTurn.formatExecutionResult(
                actionId = resolved.actionId,
                stepIndex = resolved.stepIndex,
                actionSummary = resolved.actionSummary,
                status = resolved.status.name,
                detail = resolved.detail,
                evidence = resolved.verificationEvidence
            )
            val existingToolIdx = nextHistory.indexOfLast {
                it.role == ChatTurn.TOOL && it.actionId == resolved.actionId
            }
            nextHistory = if (existingToolIdx >= 0) {
                nextHistory.toMutableList().apply {
                    set(existingToolIdx, ChatTurn(ChatTurn.TOOL, formatted, actionId = resolved.actionId))
                }
            } else {
                nextHistory + ChatTurn(ChatTurn.ASSISTANT, resolved.actionSummary, actionId = resolved.actionId) +
                    ChatTurn(ChatTurn.TOOL, formatted, actionId = resolved.actionId)
            }
        }
        return copy(
            executionResults = updatedRecords,
            conversationHistory = ChatTurn.trimPreservingActionPairs(nextHistory, ChatTurn.MAX_TURNS * 2)
        )
    }

    private fun resolveUncertainRecord(
        record: ActionExecutionRecord,
        screen: AniobScreenState,
        screenTexts: List<String>
    ): ActionExecutionRecord {
        // 1. Observable success criteria check on the fresh live screen
        if (!successCriteria.isEmpty && successCriteria.hasScreenCheck) {
            val evals = successCriteria.evaluateCriteria(screen, record.stepIndex + 1)
            if (evals.isNotEmpty() && evals.all { it.state == CriterionState.PASSED }) {
                val evidence = evals.joinToString("; ") { it.description }
                return record.copy(
                    status = ExecutionOutcomeStatus.VERIFIED,
                    detail = "Reconciled as VERIFIED on live screen (pkg=${screen.packageName}): $evidence",
                    screenPackage = screen.packageName,
                    screenHash = screen.treeHash,
                    verificationEvidence = evidence
                )
            }
        }

        // 2. Expected target package (e.g., OPEN_APP)
        if (record.expectedPackage.isNotBlank() &&
            screen.packageName.equals(record.expectedPackage, ignoreCase = true)
        ) {
            val evidence = "foreground=${screen.packageName}"
            return record.copy(
                status = ExecutionOutcomeStatus.VERIFIED,
                detail = "Reconciled as VERIFIED: foreground package is ${screen.packageName}",
                screenPackage = screen.packageName,
                screenHash = screen.treeHash,
                verificationEvidence = evidence
            )
        }

        // 3. Expected typed text (e.g., INPUT_TEXT)
        if (record.expectedText.isNotBlank()) {
            val matched = screenTexts.firstOrNull { it.contains(record.expectedText, ignoreCase = true) }
            if (matched != null) {
                return record.copy(
                    status = ExecutionOutcomeStatus.VERIFIED,
                    detail = "Reconciled as VERIFIED: expected text visible on live screen",
                    screenPackage = screen.packageName,
                    screenHash = screen.treeHash,
                    verificationEvidence = matched.take(120)
                )
            }
            if (record.preScreenHash.isNotBlank() && screen.treeHash == record.preScreenHash &&
                record.actionSummary.contains("INPUT_TEXT", ignoreCase = true)
            ) {
                return record.copy(
                    status = ExecutionOutcomeStatus.FAILED,
                    detail = "Reconciled as FAILED: expected text '${record.expectedText}' absent on unchanged screen",
                    screenPackage = screen.packageName,
                    screenHash = screen.treeHash
                )
            }
        }

        // 4. Commit/submit tap transitioned off the pre-screen and confirmation text is visible
        val isCommitTap = COMMIT_ACTION_WORDS.any { record.actionSummary.contains(it, ignoreCase = true) }
        val transitioned = record.preScreenHash.isBlank() ||
            (screen.treeHash.isNotBlank() && screen.treeHash != record.preScreenHash)
        if (isCommitTap && transitioned) {
            val confirmText = screenTexts.firstOrNull { text ->
                CONFIRMATION_WORDS.any { kw -> text.contains(kw, ignoreCase = true) }
            }
            if (confirmText != null) {
                return record.copy(
                    status = ExecutionOutcomeStatus.VERIFIED,
                    detail = "Reconciled as VERIFIED: confirmation '$confirmText' observed on live screen",
                    screenPackage = screen.packageName,
                    screenHash = screen.treeHash,
                    verificationEvidence = confirmText.take(120)
                )
            }
        }

        // 5. Insufficient evidence -> remain UNCERTAIN, preserve actionId, warn against blind retry
        return record.copy(
            status = ExecutionOutcomeStatus.UNCERTAIN,
            detail = "Still UNCERTAIN on pkg=${screen.packageName} (insufficient evidence); verify screen before retrying",
            screenPackage = screen.packageName,
            screenHash = screen.treeHash
        )
    }

    /**
     * Returns the prior structured conversation turns for the next agent LLM call, keeping the
     * original user task, added instructions, verified-vs-failed/uncertain/not-executed state,
     * and complete correlated action/result pairs without splitting any pair at the boundary.
     */
    fun priorConversationTurns(
        maxTurns: Int = ChatTurn.MAX_TURNS - 1
    ): List<ChatTurn> {
        val verified = verifiedExecutionRecords()
        val failed = failedExecutionRecords()
        val uncertain = uncertainExecutionRecords()
        val notExecuted = notExecutedRecords()
        val goalHeader = buildString {
            append("Original task: ").append(instruction)
            if (additionalInstructions.isNotEmpty()) {
                append("\nAdditional user instructions: ")
                append(additionalInstructions.joinToString("; "))
            }
            if (verified.isNotEmpty()) {
                append("\nAlready VERIFIED actions (do not repeat): ")
                append(verified.takeLast(5).joinToString("; ") { "[${it.actionId}] ${it.actionSummary}" })
            }
            if (failed.isNotEmpty()) {
                append("\nPreviously FAILED actions: ")
                append(failed.takeLast(3).joinToString("; ") { "[${it.actionId}] ${it.actionSummary}: ${it.detail}" })
            }
            if (uncertain.isNotEmpty()) {
                append("\nUNCERTAIN/UNVERIFIED actions (reconcile on live screen before retrying): ")
                append(uncertain.takeLast(3).joinToString("; ") { "[${it.actionId}] ${it.actionSummary}: ${it.detail}" })
            }
            if (notExecuted.isNotEmpty()) {
                append("\nNOT_EXECUTED actions (never dispatched — safe to plan again): ")
                append(notExecuted.takeLast(4).joinToString("; ") { "[${it.actionId}] ${it.actionSummary}" })
            }
        }
        val historyBudget = (maxTurns - 1).coerceAtLeast(0)
        // Exclude raw goal-only duplicate opening user turn and any historical OBSERVATION turns
        val historicalOnly = conversationHistory
            .filter { it.role != ChatTurn.OBSERVATION }
            .let { list ->
                if (list.firstOrNull()?.let { it.role == ChatTurn.USER && it.content == instruction } == true) {
                    list.drop(1)
                } else {
                    list
                }
            }
        val trimmedHistory = ChatTurn.trimPreservingActionPairs(historicalOnly, historyBudget)
        return listOf(ChatTurn(ChatTurn.USER, goalHeader)) + trimmedHistory
    }

    /**
     * Builds the structured conversation turns for the next agent LLM call, keeping the
     * original user task and any added instructions visible alongside recent turns.
     */
    fun buildConversationTurns(
        currentObservationPrompt: String,
        maxTurns: Int = ChatTurn.MAX_TURNS
    ): List<ChatTurn> =
        priorConversationTurns((maxTurns - 1).coerceAtLeast(1)) +
            ChatTurn(ChatTurn.OBSERVATION, currentObservationPrompt)

    fun withFailure(): TaskContext = copy(consecutiveFailures = consecutiveFailures + 1)

    fun withSuccess(): TaskContext = copy(consecutiveFailures = 0)

    fun withRejectedFinish(feedback: String): TaskContext = copy(
        rejectedFinishCount = rejectedFinishCount + 1,
        condensedHistory = condensedHistory +
            ("the finish claim was not accepted: " + com.aniob.core.memory.history.NeutralWordingContract.neutralize(feedback))
    )

    fun addTokens(estimate: Int): TaskContext =
        copy(tokenBudget = tokenBudget.copy(usedEstimate = tokenBudget.usedEstimate + estimate))

    /**
     * Planner view: goal, criteria, grill answers and condensed history.
     * Deliberately EXCLUDES the raw node dump — the planner reasons about strategy, not pixels.
     */
    fun plannerView(): String = buildString {
        appendLine("GOAL: $instruction")
        if (additionalInstructions.isNotEmpty()) {
            appendLine("ADDED BY THE USER DURING THIS TASK (same task, earlier verified steps stand):")
            additionalInstructions.forEach { appendLine("- $it") }
        }
        if (!successCriteria.isEmpty) appendLine("SUCCESS CRITERIA: $successCriteria")
        if (grillAnswers.isNotEmpty()) {
            appendLine("CLARIFICATIONS: " + grillAnswers.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (retrievedDocs.isNotEmpty()) {
            appendLine("RETRIEVED KNOWLEDGE:")
            retrievedDocs.forEach { appendLine("- $it") }
        }
        appendLine("PROGRESS (condensed):")
        if (condensedHistory.isEmpty()) {
            appendLine("- (no steps yet)")
        } else if (condensedHistory.size <= 8) {
            condensedHistory.forEach { appendLine("- $it") }
        } else {
            appendLine("- [... ${condensedHistory.size - 8} earlier steps omitted ...]")
            condensedHistory.takeLast(8).forEach { appendLine("- $it") }
        }
    }

    fun buildExecutorRequest(
        screen: AniobScreenState,
        activeSubgoal: String? = null,
        lastAction: String? = null,
        lastResult: String? = null,
        burstMode: Boolean = false,
        carriedPlanHint: String? = null
    ): ExecutorRequest {
        // A compact capture already pruned, merged and ordered its nodes; re-running the
        // optimizer would re-index them for no benefit and could break the prompt ids (BL-4).
        val alreadyCompact = screen.observationKind == "compact" || screen.nodes.any { it.markup.isNotBlank() }
        val optimizedNodes = if (alreadyCompact) {
            screen.nodes
        } else {
            com.aniob.core.optimizer.AniobTokenOptimizer.optimize(screen.nodes)
        }
        val criteriaList = if (!successCriteria.isEmpty) {
            successCriteria.describeList()
        } else emptyList()

        val latest = executionResults.lastOrNull()
        val resolvedLastAction = lastAction ?: latest?.let {
            if (it.actionId.isNotBlank()) "[action_id=${it.actionId}] ${it.actionSummary}" else it.actionSummary
        } ?: trajectory.lastOrNull()?.describeAction()
        val resolvedLastResult = lastResult ?: latest?.let { record ->
            val prefix = if (record.actionId.isNotBlank()) "[action_id=${record.actionId} | status=${record.status.name}]" else "[status=${record.status.name}]"
            val ev = record.verificationEvidence?.takeIf { it.isNotBlank() }?.let { " | evidence=$it" }.orEmpty()
            "$prefix step ${record.stepIndex} ${record.actionSummary}: ${record.detail}$ev"
        } ?: condensedHistory.lastOrNull()

        return ExecutorRequest(
            goal = instruction,
            additionalInstructions = additionalInstructions,
            clarifications = grillAnswers,
            activeSubgoal = activeSubgoal,
            requiredCriteria = criteriaList,
            packageName = screen.packageName,
            activityName = screen.activityName,
            windowId = 0,
            observationId = screen.screenHash.ifBlank { screen.treeHash },
            actionableNodes = optimizedNodes,
            lastAction = resolvedLastAction,
            lastResult = resolvedLastResult,
            consecutiveFailures = consecutiveFailures,
            burstMode = burstMode,
            carriedPlanHint = carriedPlanHint
        )
    }

    /**
     * Executor view: goal plus the current actionable screen and nodes.
     * Uses ExecutorRequest to provide full live grounding context.
     */
    fun executorView(screen: AniobScreenState): String {
        return buildExecutorRequest(screen).toPromptString()
    }

    /**
     * Reflector view: goal + criteria + the before/after pair + the action that caused it.
     * Deliberately EXCLUDES everything else.
     */
    fun reflectorView(before: AniobScreenState?, after: AniobScreenState, lastAction: AniobAction?): String = buildString {
        appendLine("GOAL: $instruction")
        if (!successCriteria.isEmpty) appendLine("SUCCESS CRITERIA: $successCriteria")
        appendLine("ACTION: ${lastAction?.describeAction() ?: "(none)"}")
        appendLine("BEFORE: package=${before?.packageName ?: "(none)"} hash=${before?.treeHash ?: "-"} nodes=${before?.nodes?.size ?: 0}")
        appendLine("AFTER:  package=${after.packageName} hash=${after.treeHash} nodes=${after.nodes.size}")
        appendLine("CONSECUTIVE FAILURES: $consecutiveFailures")
        appendLine("REJECTED FINISHES: $rejectedFinishCount")
    }

    companion object {
        private val COMMIT_ACTION_WORDS = listOf("submit", "confirm", "save", "send", "place order", "checkout", "finish")
        private val CONFIRMATION_WORDS = listOf(
            "submitted", "confirmed", "confirmation", "saved", "sent",
            "order placed", "thank you", "completed", "success", "receipt"
        )
    }
}
