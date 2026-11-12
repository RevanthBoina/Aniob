package com.aniob.core.run

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.CriterionEvaluation
import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.ResolvedIntentShortcut
import kotlinx.coroutines.delay

/** Which path a task takes from intake to dispatch. */
enum class RunLane {
    /** A reflex whose dispatch *is* the outcome (torch, timer with SKIP_UI, dial, URL). */
    FAST_REFLEX,

    /** "Open <installed app>" and nothing else; proven by the foreground package. */
    FAST_LAUNCH,

    /** Everything else: understanding echo, feasibility, Grill-Me, Doctor, full observation. */
    STANDARD;

    val isFast: Boolean get() = this != STANDARD
}

/**
 * The intent fast lane.
 *
 * A request that is already solved by a resolved shortcut does not need the pre-execution gates
 * (understanding echo, feasibility, Grill-Me, Doctor preflight), a before-image of the screen, or
 * a settle-and-recapture afterwards. This object owns the three decisions that make skipping them
 * safe, as pure JVM code so each one is pinned by a test:
 *
 *  1. [decide] — is this prompt *entirely* answered by the shortcut?
 *  2. [confirmLaunched] — did the target really take the foreground? (event-driven, bounded)
 *  3. [launchVerdict] — does that proof satisfy the task's success criteria?
 *
 * Safety is not decided here: AskPolicy still evaluates the action at dispatch time.
 */
object AniobFastLane {

    const val LAUNCH_CONFIRM_DEADLINE_MS = 1_500L
    const val LAUNCH_CONFIRM_POLL_MS = 40L

    /**
     * @param hasGatedPlanSteps true when the feasibility court would have something to rule on
     *   (APK install, uninstall). Those keep the standard path so its verdict is never skipped.
     */
    fun decide(
        prompt: String,
        shortcut: ResolvedIntentShortcut?,
        accessibilityConnected: Boolean,
        hasGatedPlanSteps: Boolean = false,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance()
    ): RunLane {
        if (shortcut == null || hasGatedPlanSteps) return RunLane.STANDARD
        if (shortcut.completesOnDispatch) return RunLane.FAST_REFLEX

        // A launch is only "solved" when it is the whole request. `AniobIntentResolver` also
        // returns an app shortcut for the first hop of a longer goal ("open WhatsApp and send hi
        // to Mom"); that must keep Grill-Me, feasibility and the observed step loop.
        val target = shortcut.targetPackage ?: return RunLane.STANDARD
        val plainLaunch = shortcut.requiresObservation &&
            shortcut.action == AniobIntentResolver.ACTION_MAIN &&
            shortcut.uriString == null &&
            shortcut.extras.isEmpty()
        if (!plainLaunch) return RunLane.STANDARD
        // The foreground package is read through the accessibility service. Without it the
        // standard path runs, so the Doctor advisory can say why the launch cannot be confirmed.
        if (!accessibilityConnected) return RunLane.STANDARD
        return if (AniobLaunchHub.isSoleLaunch(prompt, target, registry)) RunLane.FAST_LAUNCH else RunLane.STANDARD
    }

    /**
     * Admission to execution. A fast-lane task is admitted without running [gates] at all; a
     * standard task is admitted only if its gates say so.
     */
    suspend fun admit(lane: RunLane, gates: suspend () -> Boolean): Boolean = lane.isFast || gates()

    /**
     * Event-driven launch proof: returns the instant [target] owns the foreground, and gives up
     * at [deadlineMs]. A `false` is not a failure verdict — the caller falls back to the full
     * observation path.
     */
    suspend fun confirmLaunched(
        target: String,
        deadlineMs: Long = LAUNCH_CONFIRM_DEADLINE_MS,
        stepMs: Long = LAUNCH_CONFIRM_POLL_MS,
        now: () -> Long = System::currentTimeMillis,
        foreground: () -> String?
    ): Boolean {
        val end = now() + deadlineMs
        while (true) {
            val observed = runCatching(foreground).getOrNull()
            if (AniobLaunchHub.verifyForeground(target, observed)) return true
            val left = end - now()
            if (left <= 0L) return false
            delay(stepMs.coerceAtMost(left).coerceAtLeast(1L))
        }
    }

    /**
     * The verdict a confirmed launch is closed with, or null when the foreground package alone
     * cannot satisfy the task's criteria (a text or activity expectation) and the full
     * observation path has to judge it instead.
     */
    fun launchVerdict(
        criteria: SuccessCriteria?,
        targetPackage: String,
        stepsTaken: Int = 1
    ): List<CriterionEvaluation>? {
        val observed = CriterionEvaluation(
            description = "Package '$targetPackage'",
            state = CriterionState.PASSED,
            detail = "Foreground package matches",
            textEvidence = targetPackage
        )
        if (criteria == null || !criteria.hasObservableCheck) return listOf(observed)
        if (criteria.hasDeviceChecks) return null
        val evaluations = criteria.evaluateCriteria(AniobScreenState(packageName = targetPackage), stepsTaken)
        if (evaluations.isEmpty() || evaluations.any { it.state != CriterionState.PASSED }) return null
        return (evaluations + observed).distinctBy { it.description }
    }
}
