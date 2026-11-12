package com.aniob.core.perception

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.verify.FastVerify

/**
 * Pre-observe scroll.
 *
 * The failure this removes: a valid target sits below the fold ("Battery" in Settings, "About
 * phone", a contact further down a list). The model is shown the first viewport, cannot find it,
 * emits a scroll action, is shown the second viewport, and only then taps. That is a whole
 * round trip — one LLM call and the latency that goes with it — spent on something the device
 * can decide by itself.
 *
 * So when a step fails to ground its target and the screen scrolls, the app scrolls once
 * ([SETTLE_MS] to let the hierarchy settle), captures again, and merges the two viewports for the
 * next observation. The merge keeps the *new* viewport authoritative — its ids, bounds and
 * clickable flags are exactly what a dispatch will ground against — and carries the nodes that
 * scrolled away as informational entries (non-clickable, no cached markup), so the model can see
 * it has already looked at them instead of scrolling up to check.
 *
 * Pure JVM and unit tested; the platform action itself lives in the app layer.
 */
object PreObserveScroll {

    /** Time given to the list animation and the accessibility tree before the second capture. */
    const val SETTLE_MS = 300L

    /**
     * Blind auto-scrolls allowed per screen identity. Enough to walk a long settings page; the
     * limit does not apply while a named (text) target is being searched for, which has its own
     * bounded search in `AniobScrollHelper`.
     */
    const val MAX_SCROLLS_PER_SCREEN = 8

    /** Nodes that scrolled away are context, not targets; keep the prompt bounded. */
    private const val MAX_CARRIED_NODES = 20

    private const val MIN_CONTAINER_AREA = 1000

    /** The container a forward scroll should act on: the largest one that is actually scrollable. */
    fun scrollableContainer(screen: AniobScreenState): AniobNode? =
        screen.nodes
            .filter { it.isScrollable && it.isEnabled && it.bounds.area > MIN_CONTAINER_AREA }
            .maxByOrNull { it.bounds.area }

    /**
     * Whether this screen still has an unspent auto-scroll. [scrollCounts] holds, per identity
     * ([FastVerify.screenIdentity]), how many auto-scrolls the current run has already made.
     * [textTargetPending] lifts the per-screen limit: a named target is still being looked for.
     */
    fun shouldScroll(
        screen: AniobScreenState,
        scrollCounts: Map<String, Int>,
        textTargetPending: Boolean = false
    ): Boolean {
        if (scrollableContainer(screen) == null) return false
        val identity = FastVerify.screenIdentity(screen)
        if (identity.isBlank()) return false
        return textTargetPending || (scrollCounts[identity] ?: 0) < MAX_SCROLLS_PER_SCREEN
    }

    /**
     * Union of [before] and [after] for one observation.
     *
     * The scroll changed the layout, so only [after] can be acted on; nodes unique to [before] are
     * appended with fresh ids and every actionable flag cleared. A different package means the
     * "scroll" navigated somewhere else entirely — nothing to merge in that case.
     */
    fun merge(before: AniobScreenState, after: AniobScreenState): AniobScreenState {
        if (before.packageName != after.packageName) return after

        val visibleKeys = after.nodes.mapTo(HashSet()) { keyOf(it) }
        var nextId = (after.nodes.maxOfOrNull { it.id } ?: 0) + 1
        val carried = ArrayList<AniobNode>(MAX_CARRIED_NODES)
        for (node in before.nodes) {
            if (carried.size >= MAX_CARRIED_NODES) break
            if (keyOf(node) in visibleKeys) continue
            if (node.text.isBlank() && node.contentDescription.isBlank() && node.viewId.isBlank()) continue
            carried += node.copy(
                id = nextId++,
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                isVisibleToUser = true,
                markup = ""
            )
        }
        if (carried.isEmpty()) return after

        val nodes = after.nodes + carried
        return after.copy(
            nodes = nodes,
            fusedIndex = AniobFusedIndex(nodes),
            treeHash = "${after.treeHash}+${before.treeHash.take(8)}"
        )
    }

    /**
     * The same action expressed against a *re-read* screen, or null when it cannot be re-expressed.
     *
     * Element ids are reassigned on every capture, so an action planned from the first viewport
     * points at nothing — or at the wrong widget — after the pre-observe scroll. The node the action
     * meant is still known from the planning screen, so the retry keeps the widget and swaps the
     * index for its stable identity: view id first, then exact text, then content description.
     * Nothing stable means no retry: guessing by position is how a scroll turns into a wrong tap.
     */
    fun retargetForRetry(action: AniobAction, from: AniobScreenState): AniobAction? {
        val target = action.semanticTarget() as? SemanticTarget.SomIndex ?: return null
        val node = from.nodes.firstOrNull { it.id == target.index } ?: return null

        val stable: SemanticTarget = when {
            node.viewId.isNotBlank() -> SemanticTarget.ResourceId(node.viewId)
            node.text.isNotBlank() && from.nodes.count {
                it.text.equals(node.text, ignoreCase = true)
            } == 1 -> SemanticTarget.Text(node.text, exact = true)
            node.contentDescription.isNotBlank() && from.nodes.count {
                it.contentDescription.equals(node.contentDescription, ignoreCase = true)
            } == 1 -> SemanticTarget.ContentDesc(node.contentDescription, exact = true)
            else -> return null
        }

        return when (action) {
            is AniobAction.Tap -> action.copy(target = stable)
            is AniobAction.LongPress -> action.copy(target = stable)
            is AniobAction.InputText -> action.copy(target = stable)
            else -> null
        }
    }

    /** Identity that survives a scroll: resource id when there is one, otherwise the label. */
    private fun keyOf(node: AniobNode): String {
        val id = node.viewId.trim()
        if (id.isNotEmpty()) return "$id|${node.text}"
        val label = node.text.ifBlank { node.contentDescription }
        return "${node.className}|${label.take(60)}|${node.contentDescription.take(60)}"
    }
}
