package com.aniob.core.verify

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SuccessCriteria
import kotlinx.coroutines.delay

/**
 * Two-tier post-action verification that replaces the fixed "sleep then capture" wait.
 *
 * Tier 1 samples the service's window-event counter every [POLL_INTERVAL_MS] and reads the node
 * tree only when it moved — plus one baseline read and one safety read every
 * [CAPTURE_EVERY_TICKS] ticks for apps that change without announcing it. It returns as soon as
 * - the structural identity of the screen changed (the action had an effect), or
 * - text the action/task expects appeared that was not already visible before the action.
 *
 * Tier 2 only runs when tier 1 times out: one bitmap capture, kept for the next planning step
 * because a screen whose tree did not move at all is exactly what a visual modal, a popup or a
 * no-op tap looks like. The deterministic verifier still decides whether the step counted.
 *
 * The decision half ([evaluate]) is pure, so the whole policy is unit-testable without a device.
 */
object FastVerify {

    /** Poll cadence; matches the service's window-event coalescing window. */
    const val POLL_INTERVAL_MS = 50L

    /** Hard ceiling for tier 1. After this the bitmap tier runs once. */
    const val MAX_WAIT_MS = 800L

    /**
     * Safety net for the poller: even without a window event, read the tree once every this many
     * ticks (200 ms at the default cadence). Apps that mutate the view hierarchy without emitting
     * a window event still get detected, at a quarter of the full-polling cost.
     */
    const val CAPTURE_EVERY_TICKS = 4

    enum class Tier {
        /** The screen structure changed: PASS, no pixels needed. */
        CHANGED,

        /** Expected text appeared even though the structure hash is unchanged. */
        EXPECTED_TEXT,

        /** Nothing changed within the budget: fall through to the bitmap check. */
        TIMEOUT
    }

    data class Outcome(
        val tier: Tier,
        /** Wall time spent waiting; the paper's latency figure includes this. */
        val elapsedMs: Long,
        val polls: Int,
        /** Last observation taken while polling (null when every capture failed). */
        val screen: AniobScreenState?,
        val matchedText: String? = null,
        /** True when the tier-2 bitmap was requested because tier 1 timed out. */
        val screenshotTaken: Boolean = false,
        val screenshotBase64: String? = null,
        /** Node-tree reads spent on this action; one or two on the happy path. */
        val captures: Int = 0
    ) {
        val passed: Boolean get() = tier != Tier.TIMEOUT
    }

    data class Decision(val tier: Tier, val matchedText: String? = null) {
        val passed: Boolean get() = tier != Tier.TIMEOUT
    }

    /** Identity used for change detection: the structural hash, with the tree hash as fallback. */
    fun screenIdentity(screen: AniobScreenState): String =
        screen.screenHash.ifBlank { screen.treeHash }

    /**
     * One poll's verdict.
     *
     * A blank pre-action identity cannot be compared, so the poller reports [Tier.TIMEOUT] and
     * lets the deterministic verifier judge the step from the captured screen instead of
     * inventing a pass.
     */
    fun evaluate(
        preActionIdentity: String,
        screen: AniobScreenState?,
        expectedTexts: List<String> = emptyList(),
        preActionPackage: String = ""
    ): Decision {
        if (screen == null) return Decision(Tier.TIMEOUT)
        // A foreground switch is a transition even when the new screen's tree happens to hash
        // like the old one (two launchers, two settings pages).
        if (preActionPackage.isNotBlank() && screen.packageName.isNotBlank() &&
            screen.packageName != preActionPackage
        ) {
            return Decision(Tier.CHANGED)
        }
        if (preActionIdentity.isNotBlank()) {
            val current = screenIdentity(screen)
            if (current.isNotBlank() && current != preActionIdentity) {
                return Decision(Tier.CHANGED)
            }
        }
        val matched = expectedTexts.firstOrNull { needle ->
            needle.isNotBlank() && screen.nodes.any { node ->
                node.text.contains(needle, ignoreCase = true) ||
                    node.contentDescription.contains(needle, ignoreCase = true)
            }
        }
        if (matched != null) return Decision(Tier.EXPECTED_TEXT, matched)
        return Decision(Tier.TIMEOUT)
    }

    /**
     * Texts whose appearance is enough to end the wait early: what the action itself writes, plus
     * the task's own completion evidence.
     */
    fun expectedTexts(action: AniobAction?, criteria: SuccessCriteria?): List<String> {
        val texts = buildList {
            if (action is AniobAction.InputText && action.text.isNotBlank()) add(action.text)
            criteria?.mustContainText?.filter { it.isNotBlank() }?.let(::addAll)
        }
        return texts.distinct()
    }

    /**
     * Tier 1 + tier 2 for one action.
     *
     * Window events (`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`) are the cheap
     * signal: the poller checks the service's event counter every [pollMs] and only reads the
     * node tree when something happened (plus a first read and a periodic safety read). Callers
     * without an event source pass a null counter and every tick reads the tree instead.
     *
     * @param preAction the screen the action was planned on.
     * @param windowEvents monotonic window-event counter, or null when the caller has none.
     * @param capturePoll a window read; invoked on an event, on the first tick and every
     *   [CAPTURE_EVERY_TICKS] ticks.
     * @param screenshot tier-2 bitmap read, only invoked once tier 1 has timed out.
     */
    suspend fun await(
        preAction: AniobScreenState?,
        expectedTexts: List<String> = emptyList(),
        pollMs: Long = POLL_INTERVAL_MS,
        timeoutMs: Long = MAX_WAIT_MS,
        windowEvents: () -> Long? = { null },
        capturePoll: suspend () -> AniobScreenState?,
        screenshot: suspend () -> String? = { null }
    ): Outcome {
        val preIdentity = preAction?.let(::screenIdentity).orEmpty()
        val prePackage = preAction?.packageName.orEmpty()
        // Text that was already visible before the action is not evidence that the action did
        // anything; only newly appearing text may end the wait early.
        val wantedTexts = expectedTexts.filterNot { needle ->
            needle.isBlank() || preAction?.nodes?.any { node ->
                node.text.contains(needle, ignoreCase = true) ||
                    node.contentDescription.contains(needle, ignoreCase = true)
            } == true
        }
        val startedAt = System.nanoTime()
        var polls = 0
        var captures = 0
        var last: AniobScreenState? = null
        var seenEvents = runCatching { windowEvents() }.getOrNull()
        val hasEventSource = seenEvents != null

        while (true) {
            polls++
            val currentEvents = if (hasEventSource) runCatching { windowEvents() }.getOrNull() else null
            val eventArrived = hasEventSource && currentEvents != null && currentEvents != seenEvents
            if (currentEvents != null) seenEvents = currentEvents
            // A tap that no-ops still needs one look; events cover the interesting cases and the
            // periodic read keeps apps that change without announcing it (canvas, video, toasts).
            val shouldCapture = !hasEventSource || eventArrived || polls == 1 ||
                (polls - 1) % CAPTURE_EVERY_TICKS == 0
            if (shouldCapture) {
                captures++
                val screen = runCatching { capturePoll() }.getOrNull()
                if (screen != null) last = screen
                if (screen != null) {
                    val decision = evaluate(preIdentity, screen, wantedTexts, prePackage)
                    if (decision.passed) {
                        return Outcome(
                            tier = decision.tier,
                            elapsedMs = elapsedMs(startedAt),
                            polls = polls,
                            screen = screen,
                            matchedText = decision.matchedText,
                            captures = captures
                        )
                    }
                }
            }
            if (elapsedMs(startedAt) >= timeoutMs) break
            delay(pollMs)
        }

        val bitmap = runCatching { screenshot() }.getOrNull()
        return Outcome(
            tier = Tier.TIMEOUT,
            elapsedMs = elapsedMs(startedAt),
            polls = polls,
            screen = last,
            screenshotTaken = true,
            screenshotBase64 = bitmap,
            captures = captures
        )
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
