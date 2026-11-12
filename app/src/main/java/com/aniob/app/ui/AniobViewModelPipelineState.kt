package com.aniob.app.ui

import com.aniob.app.research.FeatureSelection
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.model.Outcome
import com.aniob.core.domain.*
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import com.aniob.core.ladder.ResolvedIntentShortcut
import com.aniob.core.ladder.TaskProgress
import com.aniob.core.run.RunLane
import kotlinx.coroutines.launch
import com.aniob.app.ui.AniobViewModel.RememberTransition

/** How one phase of a step tells the step loop to go on. */
internal sealed class PipelineFlow<out T> {
    /** The phase finished; the step carries on with [value]. */
    class Next<T>(val value: T) : PipelineFlow<T>()
    /** `continue`: start the next loop iteration. */
    object Continue : PipelineFlow<Nothing>()
    /** `break`: leave the step loop and close the run. */
    object Break : PipelineFlow<Nothing>()
    /** `return`: leave the run without the closing phase (a question was put to the user). */
    object Return : PipelineFlow<Nothing>()
}

/**
 * State of one task run. These were the local variables of [executeTaskPipeline]; they moved here
 * so the function can be split over several files. One instance per run, used by the run's own
 * coroutine exactly as the locals were.
 */
internal class PipelineRun(
    val task: AniobTask,
    val taskGeneration: Long,
    val lane: RunLane,
    val laneShortcut: ResolvedIntentShortcut?,
    val researchMode: FeatureSelection,
    val resolvedCriteria: SuccessCriteria,
    val startTime: Long,
    val hydration: kotlinx.coroutines.Deferred<Any?>?,
    val accessibility: AniobAccessibilityService?,
    val maxSteps: Int,
    var taskContext: TaskContext,
    var screenBefore: AniobScreenState?,
    var fastReflexOpen: Boolean
) {
    var currentStep = 0
    var totalTokens = 0
    var primaryProvider = "INTENT"
    var decisionReason = "Direct intent shortcut"
    var screenReads = 0
    var actionsDispatched = 0
    var escalations = 0

    // Prefrontal-cortex condensation: TaskProgress summary (not full history) is
    // threaded through the ladder so the LLM sees a pure-text progress summary.
    var taskProgress: TaskProgress? = null

    // Set once a fast-lane launch is confirmed in the foreground; the run closes on it.
    var fastLaunchProof: AniobScreenState? = null

    var settleTransition: (suspend (AniobAction) -> AniobScreenState?)? = null
    lateinit var stepPipe: StepPipeline

    // A bitmap taken by the tier-2 verify is reused by the next Reason step instead of being
    // captured twice. The structural identity it belongs to is stored with it, so a bitmap
    // can never leak onto a different screen; cleared once consumed.
    var pendingVerifyScreenshot: Pair<String, String>? = null
    // Verified transitions of this run, persisted only if the task ends successfully.
    val rememberTransitions = mutableListOf<RememberTransition>()
    // Screen on which the last replay changed nothing; the cache is skipped there next time.
    val stalledReplayIdentities = mutableSetOf<String>()
    var finalOutcome: Outcome? = null
    var terminalReason: String = ""
    var stepResult: StepResult? = null
    // An intent shortcut that carried the whole task is a finished run: the criteria re-check
    // must not turn "Wi-Fi settings opened" into an unverified or failed run.
    var completedByIntent = false
    // The verdict the shortcut was judged by, so a success carries the evidence it was
    // actually checked against instead of being re-evaluated on a later screen.
    var intentVerdict: List<com.aniob.core.domain.CriterionEvaluation>? = null
    // Model routing for this task: one on-device attempt, then cloud, and cloud stays chosen.
    var cloudEscalationAllowed = false
    lateinit var executionLlm: com.aniob.core.ladder.ExecutionLlmGateway
    // Pre-observe scrolls already made in this run, per screen identity.
    val preObservedScreens = HashMap<String, Int>()
    // Viewport merged after that scroll, handed to the next planning pass.
    var preObservedScreen: AniobScreenState? = null
    // Set once a burst's target vanished mid-burst. The screen in front is dynamic (search
    // results re-indexing, animated lists), so the rest of the run plans one action at a time
    // from a fresh observation instead of trusting a multi-action plan again.
    var burstsSuspended = false
    // The part of the last plan that named elements the current screen did not show yet.
    // Rendered into the next planning request as context, cleared as soon as it is used.
    var deferredPlanHint: String? = null
    // Consecutive plans that could not execute even their first member: the screen is not
    // settling, so the rest of the run plans one action at a time.
    var burstZeroProgressReplans = 0
}
