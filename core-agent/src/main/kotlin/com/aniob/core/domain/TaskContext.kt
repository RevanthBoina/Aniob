package com.aniob.core.domain

/**
 * Per-task context carried through every step of the loop.
 *
 * The role-specific view builders exist to keep each LLM call's prompt minimal and on-task:
 * a planner never sees a raw screen dump (it gets a condensed history and the criteria), an
 * executor never sees the full history (it gets the goal and the current screen), and a
 * reflector sees exactly the before/after delta plus the action that caused it.
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
    val hasFastSkippedSteps: Boolean = false
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

    /** Appends the action of the step being closed so the trajectory stays in lockstep with history. */
    fun withAction(action: AniobAction): TaskContext = copy(trajectory = trajectory + action)

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

        return ExecutorRequest(
            goal = instruction,
            clarifications = grillAnswers,
            activeSubgoal = activeSubgoal,
            requiredCriteria = criteriaList,
            packageName = screen.packageName,
            activityName = screen.activityName,
            windowId = 0,
            observationId = screen.screenHash.ifBlank { screen.treeHash },
            actionableNodes = optimizedNodes,
            lastAction = lastAction,
            lastResult = lastResult ?: condensedHistory.lastOrNull(),
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