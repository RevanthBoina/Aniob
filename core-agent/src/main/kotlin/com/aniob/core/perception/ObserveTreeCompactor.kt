package com.aniob.core.perception

import com.aniob.core.domain.AniobRect
import com.aniob.core.optimizer.AniobTokenOptimizer

/**
 * One element of the compact observation that is handed to the model.
 *
 * Every field the executor needs is kept: [bounds] centre feeds the gesture fallback, [platformIndex]
 * lets the platform layer reach the live node for `performAction`, and [markup] is the exact
 * HTML-like token that appears in the prompt.
 */
data class CompactNode(
    val id: Int,
    val role: String,
    val label: String,
    val className: String,
    val viewId: String,
    val bounds: AniobRect,
    val contentDescription: String = "",
    val isClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false,
    val isHeading: Boolean = false,
    val errorText: String = "",
    val paneTitle: String = "",
    val tooltip: String = "",
    val platformIndex: Int = -1,
    val markup: String = ""
) {
    val isInteractive: Boolean
        get() = isClickable || isCheckable || isEditable || isScrollable

    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
}

/**
 * Result of one compact observation pass.
 *
 * [screenHash] is the deterministic identity of the screen; [structuralSignature] is the raw
 * class/viewId string it is derived from, kept for tests and diagnostics.
 */
data class CompactObservation(
    val nodes: List<CompactNode>,
    val screenHash: String,
    val structuralSignature: String,
    val labeledInteractiveCount: Int,
    val visibleNodeCount: Int,
    val mergedNodeCount: Int
)

/**
 * Prunes, merges and serialises one screen tree for the fast observe modes.
 *
 * Rules, in traversal order:
 * 1. Invisible nodes are dropped with their subtree.
 * 2. Layout containers with no text, no description and no interaction are dropped; their
 *    children are still visited, so a decorative wrapper never hides the control inside it.
 * 3. An interactive parent (clickable / checkable / editable / scrollable) absorbs the labels of
 *    its non-interactive descendants: `Settings | Wi-Fi, Bluetooth`. The absorbed descendants do
 *    not get their own ids.
 * 4. Surviving nodes get sequential 1-based ids in reading order (top-to-bottom, left-to-right),
 *    capped at [MAX_ACTIONABLE_NODES] by interaction saliency. 1-based matches the Set-of-Mark
 *    convention the prompt already used, and avoids `0` being read as "no target" by the parser.
 * 5. Each node is serialised as `<button id=1 label='…'>`, `<input id=2 text='…'>`, and so on.
 *
 * Pure JVM: no Android types, fully unit-testable.
 */
object ObserveTreeCompactor {

    /** Same hard ceiling the prompt already relied on; pruning must never grow the payload. */
    const val MAX_ACTIONABLE_NODES = AniobTokenOptimizer.MAX_ACTIONABLE_NODES

    /** Labels are prompt tokens: keep merged text short enough to stay readable. */
    private const val MAX_LABEL_CHARS = 160

    private const val MERGE_SEPARATOR = " | "

    fun compact(
        roots: List<ObserveSnapshot>,
        packageName: String,
        activityName: String = ""
    ): CompactObservation {
        // Identity, not equality: two sibling labels can be identical data classes.
        val absorbed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ObserveSnapshot, Boolean>()
        )
        val drafts = ArrayList<Draft>()

        fun markSubtree(node: ObserveSnapshot) {
            absorbed.add(node)
            node.children.forEach(::markSubtree)
        }

        fun mergeLabelsUnder(node: ObserveSnapshot, sink: MutableList<String>) {
            for (child in node.children) {
                if (!child.isVisible) continue
                // An interactive child is a target in its own right; never fold it away.
                if (child.isInteractive) continue
                if (child.hasLabel) {
                    val addition = child.label
                    if (sink.none { it == addition }) sink.add(addition)
                    markSubtree(child)
                } else {
                    mergeLabelsUnder(child, sink)
                }
            }
        }

        fun collect(node: ObserveSnapshot) {
            if (!node.isVisible) return
            if (absorbed.contains(node)) return

            if (node.isInteractive) {
                val parts = ArrayList<String>(2)
                node.label.takeIf { it.isNotBlank() }?.let(parts::add)
                mergeLabelsUnder(node, parts)
                // A container with no usable bounds cannot be tapped, but its children can.
                if (!node.bounds.isEmpty) {
                    drafts.add(Draft(node, labelOf(node, parts.joinToString(MERGE_SEPARATOR))))
                }
                node.children.forEach(::collect)
                return
            }

            if (node.hasLabel) {
                drafts.add(Draft(node, labelOf(node, node.label)))
            }
            node.children.forEach(::collect)
        }

        roots.forEach(::collect)

        val readingOrder = compareBy<Draft>({ it.snapshot.bounds.top }, { it.snapshot.bounds.left })
        val ordered = drafts.sortedWith(readingOrder)
        val selected = if (ordered.size <= MAX_ACTIONABLE_NODES) {
            ordered
        } else {
            ordered.sortedByDescending { saliency(it.snapshot) }
                .take(MAX_ACTIONABLE_NODES)
                .sortedWith(readingOrder)
        }

        val nodes = selected.mapIndexed { index, draft ->
            val id = index + 1
            val snapshot = draft.snapshot
            val role = roleOf(snapshot)
            CompactNode(
                id = id,
                role = role,
                label = draft.label,
                className = snapshot.className,
                viewId = snapshot.viewId,
                bounds = snapshot.bounds,
                contentDescription = snapshot.contentDescription,
                isClickable = snapshot.isClickable,
                isCheckable = snapshot.isCheckable,
                isChecked = snapshot.isChecked,
                isEditable = snapshot.isEditable,
                isScrollable = snapshot.isScrollable,
                isSelected = snapshot.isSelected,
                isEnabled = snapshot.isEnabled,
                isPassword = snapshot.isPassword,
                isHeading = snapshot.isHeading,
                errorText = snapshot.errorText,
                paneTitle = snapshot.paneTitle,
                tooltip = snapshot.tooltip,
                platformIndex = snapshot.platformIndex,
                markup = markupOf(id, role, snapshot, draft.label)
            )
        }

        val structuralSignature = nodes.filter { it.isInteractive }
            .joinToString("|") { "${it.className}#${it.viewId}" }

        return CompactObservation(
            nodes = nodes,
            screenHash = screenHash(packageName, activityName, structuralSignature),
            structuralSignature = structuralSignature,
            labeledInteractiveCount = nodes.count { it.isInteractive && it.label.isNotBlank() },
            visibleNodeCount = countVisible(roots),
            mergedNodeCount = absorbed.size
        )
    }

    /** 64-bit FNV-1a deterministic screen hash (F-2). */
    fun screenHash(packageName: String, activityName: String, structuralSignature: String): String =
        com.aniob.core.diff.AniobScreenDiff.computeScreenHash64(packageName, activityName, structuralSignature)

    /**
     * The signature is built from class names and view ids only: text changes repaint the screen
     * without changing its structure, so they must not invalidate the hash.
     */
    fun structuralSignature(nodes: List<CompactNode>): String =
        nodes.filter { it.isInteractive }.joinToString("|") { "${it.className}#${it.viewId}" }

    private fun countVisible(nodes: List<ObserveSnapshot>): Int {
        var count = 0
        fun walk(node: ObserveSnapshot) {
            if (!node.isVisible) return
            count++
            node.children.forEach(::walk)
        }
        nodes.forEach(::walk)
        return count
    }

    private fun labelOf(node: ObserveSnapshot, merged: String): String {
        val candidate = merged.ifBlank { node.paneTitle }
            .ifBlank { node.tooltip }
            .ifBlank { node.viewId.substringAfterLast('/').replace('_', ' ') }
            .trim()
        return candidate.take(MAX_LABEL_CHARS)
    }

    private fun roleOf(node: ObserveSnapshot): String = when {
        node.isEditable -> "input"
        node.isCheckable -> if (node.className.contains("switch", ignoreCase = true)) "switch" else "checkbox"
        node.isScrollable -> "list"
        node.isClickable -> "button"
        else -> "text"
    }

    private fun markupOf(id: Int, role: String, node: ObserveSnapshot, label: String): String {
        val attribute = if (role == "input") "text" else "label"
        val checked = if (node.isCheckable) " checked=${node.isChecked}" else ""
        val enabled = if (!node.isEnabled) " enabled=false" else ""
        return "<$role id=$id $attribute='${escape(label)}'$checked$enabled>"
    }

    /** Keeps the markup well-formed when a screen contains quotes, tags or newlines. */
    internal fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&#39;")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()

    private fun saliency(node: ObserveSnapshot): Int {
        var score = 0
        if (node.isEditable) score += 50
        if (node.isClickable) score += 40
        if (node.isScrollable) score += 20
        if (node.text.isNotBlank()) score += 15
        if (node.contentDescription.isNotBlank()) score += 15
        if (node.viewId.isNotBlank()) score += 10
        if (node.errorText.isNotBlank()) score += 25
        if (node.isHeading) score += 10
        return score
    }

    private data class Draft(val snapshot: ObserveSnapshot, val label: String)
}
