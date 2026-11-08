package com.aniob.core.policy

import com.aniob.core.domain.AniobAction

/**
 * Reuse policy for the last verified accessibility observation during short, predictable runs.
 *
 * This is not the multi-action plan limit and it is not a dispatch shortcut. The action-array
 * contract is independently bounded at 2–8; ordinary steps still re-capture in [StepPipeline], and
 * [com.aniob.core.execution.ActionBurstRunner] revalidates a targeted first member plus every later
 * member against a live tree. This policy only decides whether the planning loop needs an additional
 * pre-planning hierarchy read after a recent verified result.
 *
 * A planning snapshot may be reused after distinct successful actions in the same package when no
 * scroll occurred. Any actual dispatch must still pass the shared fresh-grounding, safety and
 * verification path.
 */
class AniobObservationPolicy(
    maxBurstSteps: Int = 3
) {

    /**
     * Maximum consecutive planning passes allowed to reuse verified observations in an ablation.
     * The default 3 is a research setting, not the 2–8 action-plan limit.
     */
    @Volatile
    private var burstLimit: Int = maxBurstSteps.coerceAtLeast(1)

    /** Changes the observation-reuse ceiling without recreating the policy. */
    fun setBurstLimit(maxBurstSteps: Int) {
        burstLimit = maxBurstSteps.coerceAtLeast(1)
    }

    data class ActionHistoryItem(
        val action: AniobAction,
        val packageName: String,
        val verifiedSuccess: Boolean,
        val wasScroll: Boolean
    )

    private val recentActions = mutableListOf<ActionHistoryItem>()
    private var currentBurstCount = 0

    @Synchronized
    fun recordActionOutcome(
        action: AniobAction,
        packageName: String,
        verifiedSuccess: Boolean
    ) {
        val isScroll = action is AniobAction.Swipe
        recentActions.add(
            ActionHistoryItem(
                action = action,
                packageName = packageName,
                verifiedSuccess = verifiedSuccess,
                wasScroll = isScroll
            )
        )
        if (recentActions.size > 10) {
            recentActions.removeAt(0)
        }

        if (verifiedSuccess && !isScroll) {
            currentBurstCount++
        } else {
            currentBurstCount = 0
        }
    }

    /** True when the planning loop needs a separate observation before the next model pass. */
    @Synchronized
    fun shouldObserve(): Boolean {
        if (recentActions.size < 2 || currentBurstCount >= burstLimit) {
            currentBurstCount = 0
            return true
        }

        val lastTwo = recentActions.takeLast(2)
        val sameApp = lastTwo[0].packageName == lastTwo[1].packageName
        val bothConfirmed = lastTwo.all { it.verifiedSuccess }
        val noScroll = lastTwo.none { it.wasScroll }

        // Repeated identical action likely landed mid-animation or made no visible change. Require
        // a new planning observation instead of extrapolating from the previous result.
        if (lastTwo[0].action.describeAction() == lastTwo[1].action.describeAction()) {
            currentBurstCount = 0
            return true
        }

        val canReuseVerifiedObservation = sameApp && bothConfirmed && noScroll
        if (canReuseVerifiedObservation) return false

        currentBurstCount = 0
        return true
    }

    @Synchronized
    fun reset() {
        recentActions.clear()
        currentBurstCount = 0
    }
}
