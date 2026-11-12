package com.aniob.app.ui

import com.aniob.app.background.AniobForegroundService
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepResult
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.narration.AgentPhase
import com.aniob.core.verify.FastVerify
import com.aniob.core.policy.AniobWaitForIdle
import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskPolicy
import com.aniob.core.tools.AniobScrollHelper
import com.aniob.core.verifier.DeterministicVerifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.aniob.app.ui.AniobViewModel.Companion.SWIPE_CALLBACK_TIMEOUT_SECONDS
import com.aniob.app.ui.AniobViewModel.VerifiedStep

internal fun AniobViewModel.addStepRecord(step: AniobStepRecord) {
    if (step.verifiedSuccess) verifiedActions.add(step.action)
    _uiState.update { it.copy(steps = it.steps + step) }
}

/**
 * Evidence gate for a provisional `Finish`.
 *
 * Shared by every ladder branch so none of them can declare success on an unverified screen.
 * An empty criteria set carries no evidence, so it is reported unverified — the caller then
 * treats the Finish as rejected rather than silently accepting it.
 */
internal fun AniobViewModel.verifyProvisionalFinish(
    finish: AniobAction.Finish,
    finalScreen: AniobScreenState,
    steps: Int,
    criteria: SuccessCriteria?
): DeterministicVerifier.VerificationResult =
    DeterministicVerifier.verifyFinish(criteria, finalScreen, steps)

internal suspend fun AniobViewModel.executeActionSync(accessibility: AniobAccessibilityService?, action: AniobAction, expectedTreeHash: String? = null, waitForIdle: Boolean = true): Boolean {
    // Block until the UI is quiescent so we never dispatch into a running animation.
    if (waitForIdle) AniobWaitForIdle.waitForIdle(fast = activeRunMode.fastIdleWait)
    if (accessibility == null) return false
    val deferred = CompletableDeferred<Boolean>()
    accessibility.executeAction(action, expectedTreeHash) { success ->
        deferred.complete(success)
    }
    return withTimeoutOrNull(SWIPE_CALLBACK_TIMEOUT_SECONDS * 1000L) { deferred.await() } ?: false
}

/**
 * Executes an action, verifies it, and - when the target appears to have been off-screen -
 * scrolls the scrollable container to reveal it and retries once. This is what stops the
 * long-list failure mode where a valid element below the fold is reported as not found.
 */
internal suspend fun AniobViewModel.executeVerifyAndRecover(
    accessibility: AniobAccessibilityService?,
    action: AniobAction,
    taskPrompt: String,
    currentScreen: AniobScreenState
): VerifiedStep {
    executeActionSync(accessibility, action)
    // Same two-tier wait the step pipeline uses: no fixed settle delay here either.
    val fast = FastVerify.await(
        preAction = currentScreen,
        expectedTexts = FastVerify.expectedTexts(action, null),
        windowEvents = { accessibility?.windowEventSequence() },
        capturePoll = { accessibility?.captureCurrentScreenState()?.also { app.researchTelemetry.recordTreeRead() } },
        screenshot = { captureVerifyScreenshot() },
                    finalCaptureAtTimeout = activeRunMode.fastDeltaVerify
    )
    val screenAfter = fast.screen ?: accessibility?.captureCurrentScreenState() ?: currentScreen
    val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)

    val recoverable = action.semanticTarget() != null && isTargetLikelyMissing(verification)
    if (!recoverable) return VerifiedStep(screenAfter, action, verification, false)

    return scrollRecoverAndRetry(accessibility, action, taskPrompt, currentScreen)
        ?: VerifiedStep(screenAfter, action, verification, false)
}

internal fun AniobViewModel.isTargetLikelyMissing(verification: DeterministicVerifier.VerificationResult): Boolean {
    if (verification.isSuccessful) return false
    val reason = verification.reason.lowercase()
    return reason.contains("no-effect") || reason.contains("no effect") ||
        reason.contains("not found") || reason.contains("element") ||
        reason.contains("observable effect") || reason.contains("failed")
}

internal fun AniobViewModel.targetMissKey(action: AniobAction, node: AniobNode? = null): String? {
    val target = action.semanticTarget() ?: return null
    fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
    val stableNode = node?.let {
        when {
            it.isPassword -> "protected:${normalize(it.className)}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
            it.viewId.isNotBlank() -> "id:${normalize(it.viewId)}"
            it.text.isNotBlank() -> "text:${normalize(it.text)}"
            it.contentDescription.isNotBlank() -> "desc:${normalize(it.contentDescription)}"
            else -> "node:${normalize(it.className)}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
        }
    }
    return (stableNode ?: when (target) {
        is SemanticTarget.ResourceId -> "id:${normalize(target.id)}"
        is SemanticTarget.Text -> "text:${normalize(target.text)}"
        is SemanticTarget.ContentDesc -> "desc:${normalize(target.desc)}"
        is SemanticTarget.SomIndex -> "som:${target.index}"
        is SemanticTarget.NormalizedCoordinate -> "coord:${target.normX},${target.normY}"
        is SemanticTarget.BoundingBox -> "box:${target.left},${target.top},${target.right},${target.bottom}"
    }).takeIf { it.length > 4 }
}

internal fun AniobViewModel.recordStepTargetOutcome(
    taskId: String,
    result: StepResult,
    planningScreen: AniobScreenState
): Int? {
    val groundedNode = result.resolvedNode ?: com.aniob.core.execution.AniobActionExecutor
        .resolveTarget(result.action, planningScreen)?.node
    val key = targetMissKey(result.action, groundedNode) ?: return null
    if (result.verification?.isSuccessful == true || result.terminalState == StepResult.TerminalState.SUCCESS) {
        targetMissLedger.recordSuccess(taskId, key)
        return null
    }
    val noOp = result.dispatch as? com.aniob.core.execution.DispatchCommand.NoOp ?: return null
    return if (noOp.reason.startsWith("grounding_miss:")) {
        targetMissLedger.recordMiss(taskId, key)
    } else {
        null
    }
}

internal fun AniobViewModel.recordTargetMiss(taskId: String, action: AniobAction, node: AniobNode? = null): Int? {
    val key = targetMissKey(action, node) ?: return null
    return targetMissLedger.recordMiss(taskId, key)
}

internal fun AniobViewModel.recordTargetSuccess(taskId: String, action: AniobAction, node: AniobNode? = null) {
    targetMissKey(action, node)?.let { targetMissLedger.recordSuccess(taskId, it) }
}

internal fun AniobViewModel.requestTargetClarification(task: AniobTask, action: AniobAction, node: AniobNode? = null) {
    pendingTargetClarificationKey = targetMissKey(action, node)?.let { task.id to it }
    val target = action.semanticTarget()
    val protectedTarget = node?.isPassword == true ||
        AskPolicy.category(action, node) == AskCategory.SECRET
    val label = if (protectedTarget) {
        "protected field"
    } else (
        node?.text?.takeIf { it.isNotBlank() }
            ?: node?.contentDescription?.takeIf { it.isNotBlank() }
            ?: when (target) {
                is SemanticTarget.ResourceId -> target.id.substringAfterLast('/').ifBlank { "that control" }
                is SemanticTarget.Text -> target.text
                is SemanticTarget.ContentDesc -> target.desc
                is SemanticTarget.SomIndex -> if (target.index == ActionBurst.UNNAMED_TARGET) "the text field" else "that control"
                else -> "that control"
            }
        ).replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "that control" }
    val question = com.aniob.core.grillme.GrillQuestion(
        id = "target_miss",
        question = "I couldn't find '$label' on the screen twice. What should I look for instead, or where is it?",
        options = emptyList(),
        allowFreeText = true
    )
    notificationGrillAnswers.remove(task.id)
    val result = AniobGrillMeResult(needsClarification = true, questions = listOf(question))
    setPhase(AgentPhase.ASKING)
    _uiState.update {
        it.copy(
            activeTask = task,
            grillMeResult = result,
            showGrillMeSheet = true,
            confirmRequest = null,
            isStreaming = false,
            streamingBubbleText = "",
            statusMessage = question.question
        )
    }
    AniobForegroundService.notifyGrillQuestion(
        app, task.id, question, task.clarifiedGoal.ifBlank { task.rawPrompt }
    )
}

internal suspend fun AniobViewModel.scrollRecoverAndRetry(
    accessibility: AniobAccessibilityService?,
    action: AniobAction,
    taskPrompt: String,
    currentScreen: AniobScreenState
): VerifiedStep? {
    val service = accessibility ?: return null
    val keywords = promptKeywords(taskPrompt)

    fun matches(node: AniobNode): Boolean {
        val target = action.semanticTarget()
        if (target is SemanticTarget.SomIndex && node.id == target.index) return true
        return keywords.any { kw ->
            node.text.contains(kw, ignoreCase = true) ||
                node.contentDescription.contains(kw, ignoreCase = true)
        }
    }

    val startForward = AniobScrollHelper.getSemanticDirection(taskPrompt) != com.aniob.core.domain.SwipeDirection.UP
    val scrollResult = scrollContainersUntil(
        service, currentScreen, AniobScrollHelper.textOf(action.semanticTarget()).orEmpty(), startForward, ::matches
    )
    if (!scrollResult.found) return null

    // Re-capture so node ids line up with the scrolled layout, then locate the target again.
    val revealedScreen = service.captureCurrentScreenState()
    val revealedNode = revealedScreen.nodes.firstOrNull(::matches) ?: scrollResult.node ?: return null
    val revealedTarget = SemanticTarget.SomIndex(revealedNode.id)
    val retryAction = when (action) {
        is AniobAction.InputText -> action.copy(target = revealedTarget)
        is AniobAction.LongPress -> action.copy(target = revealedTarget)
        else -> AniobAction.Tap(target = revealedTarget, thought = action.thought)
    }

    app.eventLogger.info(
        "AniobViewModel",
        "Scroll-until-found revealed node ${revealedNode.id} after ${scrollResult.swipes} swipes"
    )
    executeActionSync(accessibility, retryAction)
    val retryWait = FastVerify.await(
        preAction = revealedScreen,
        expectedTexts = FastVerify.expectedTexts(retryAction, null),
        windowEvents = { service.windowEventSequence() },
        capturePoll = { service.captureCurrentScreenState().also { app.researchTelemetry.recordTreeRead() } },
        screenshot = { captureVerifyScreenshot() },
                    finalCaptureAtTimeout = activeRunMode.fastDeltaVerify
    )
    val screenAfter = retryWait.screen ?: service.captureCurrentScreenState()
    val verification = DeterministicVerifier.verify(retryAction, revealedScreen, screenAfter)
    return VerifiedStep(screenAfter, retryAction, verification, true)
}

/** Scroll-until-visible for a target named by text. Null when nothing on the screen scrolls. */
internal suspend fun AniobViewModel.scrollUntilTargetVisible(
    service: AniobAccessibilityService,
    screen: AniobScreenState,
    target: SemanticTarget?,
    targetText: String
): com.aniob.core.tools.ScrollResult? {
    if (AniobScrollHelper.chooseContainer(screen, targetText) == null) return null
    return scrollContainersUntil(service, screen, targetText, startForward = true) {
        AniobScrollHelper.matches(it, target)
    }
}

/**
 * Searches the most specific container first (see [AniobScrollHelper.chooseContainer]); if the
 * target is not in it, the largest container on the screen it ended on gets one search too,
 * so a small carousel never hides a target that sits in the main list.
 */
internal suspend fun AniobViewModel.scrollContainersUntil(
    service: AniobAccessibilityService,
    screen: AniobScreenState,
    targetHint: String,
    startForward: Boolean,
    matches: (AniobNode) -> Boolean
): com.aniob.core.tools.ScrollResult {
    suspend fun search(from: AniobScreenState, container: AniobNode?) = AniobScrollHelper.scrollUntilFound(
        screen = from,
        targetPredicate = matches,
        container = container,
        capture = { service.captureCurrentScreenState().also { app.researchTelemetry.recordTreeRead() } },
        scrollNode = { node, forward, dyPx ->
            val quiet = windowQuiet(service, requireMovement = true)
            val moved = withContext(Dispatchers.IO) { service.scrollNode(node, forward, dyPx) }
            if (moved) until(com.aniob.core.perception.PreObserveScroll.SETTLE_MS) { quiet.settled() }
            moved
        },
        startForward = startForward
    )
    val first = search(screen, AniobScrollHelper.chooseContainer(screen, targetHint))
    if (first.found) return first
    val endedOn = first.screen ?: screen
    val largest = com.aniob.core.perception.PreObserveScroll.scrollableContainer(endedOn)
    val triedBounds = AniobScrollHelper.chooseContainer(screen, targetHint)?.bounds
    if (largest == null || largest.bounds == triedBounds) return first
    val second = search(endedOn, largest)
    return second.copy(swipes = first.swipes + second.swipes)
}
