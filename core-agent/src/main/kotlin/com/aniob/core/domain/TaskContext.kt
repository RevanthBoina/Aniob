package com.aniob.core.domain

import com.aniob.core.external.ChatTurn

/**
 * Actual outcome of executing one action against the device/environment.
 * Recorded only from real post-action execution and verification results.
 */
data class ActionExecutionRecord(
    val stepIndex: Int,
    val actionSummary: String,
    val success: Boolean,
    val detail: String,
    val screenPackage: String = "",
    val screenHash: String = ""
)

/**
 * Per-task context carried through every step of the loop.
 *
 * Preserves the original goal, follow-up instructions, structured conversation history,
 * previously executed actions, and their actual execution results across steps and
 * continuations ("Proceed" / "Continue").
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
    /** Structured conversation turns (user/assistant) across the task lifecycle. */
    val conversationHistory: List<ChatTurn> = emptyList(),
    /** Real post-execution outcomes for every attempted action in order. */
    val executionResults: List<ActionExecutionRecord> = emptyList()
) {
    data class TokenBudget(
        val maxTotal: Int = 32_000,
        val usedEstimate: Int = 0
    ) {
        val remaining: Int get() = (maxTotal - usedEstimate).coerceAtLeast(0)
        val isExhausted: Boolean get() = remaining == 0
    }

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

    /** Appends a structured user or assistant turn while keeping recent history bounded. */
    fun withConversationTurn(role: String, content: String): TaskContext {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return this
        val last = conversationHistory.lastOrNull()
        if (last != null && last.role == role && last.content == trimmed) return this
        return copy(
            conversationHistory = (conversationHistory + ChatTurn(role, trimmed))
                .takeLast(ChatTurn.MAX_TURNS * 2)
        )
    }

    fun latestExecutionResult(): ActionExecutionRecord? = executionResults.lastOrNull()

    /**
     * Returns the prior structured conversation turns for the next agent LLM call, keeping the
     * original user task and any added instructions visible alongside recent turns without the
     * current step observation.
     */
    fun priorConversationTurns(
        maxTurns: Int = ChatTurn.MAX_TURNS - 1
    ): List<ChatTurn> {
        val goalHeader = buildString {
            append("Original task: ").append(instruction)
            if (additionalInstructions.isNotEmpty()) {
                append("\nAdditional user instructions: ")
                append(additionalInstructions.joinToString("; "))
            }
        }
        val historyBudget = (maxTurns - 1).coerceAtLeast(0)
        val recentHistory = if (historyBudget == 0) emptyList() else conversationHistory.takeLast(historyBudget)
        val turns = mutableListOf<ChatTurn>()
        val firstIsGoal = recentHistory.firstOrNull()?.let {
            it.role == ChatTurn.USER && it.content.contains(instruction)
        } == true
        if (!firstIsGoal) {
            turns += ChatTurn(ChatTurn.USER, goalHeader)
        }
        turns += recentHistory
        return turns
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
            ChatTurn(ChatTurn.USER, currentObservationPrompt)

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

        val resolvedLastAction = lastAction ?: trajectory.lastOrNull()?.describeAction()
        val resolvedLastResult = lastResult ?: executionResults.lastOrNull()?.let { record ->
            val outcomeTag = if (record.success) "SUCCEEDED" else "FAILED"
            "step ${record.stepIndex} ${record.actionSummary} -> $outcomeTag: ${record.detail}"
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
}
