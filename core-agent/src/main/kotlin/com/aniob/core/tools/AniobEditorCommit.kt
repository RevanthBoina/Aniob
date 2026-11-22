package com.aniob.core.tools

import com.aniob.core.domain.AniobNode

/**
 * Save-class completion, as one reusable capability.
 *
 * A large class of tasks ends in the same shape: an editor or composer is left open holding a
 * pending change (a calendar event, a saved image, a posted comment, a sent message, a new alarm).
 * The task is not done until the editor's own commit control is tapped and the screen settles.
 * This object gives every such task the same two things, and nothing more:
 *
 *  - [findCommitControls]: the visible, enabled, clickable controls on this screen whose label
 *    says "commit" (Save, Done, Post, Send, …) — discovery only, never an action;
 *  - [promptHint]: a one-line prompt section naming them, so the model (and only the model) can
 *    finish the task. Nothing here ever taps, dispatches or verifies on its own.
 *
 * Deliberately generic: it names no app, no package and no button-by-id list. Any app that follows
 * the editor/composer pattern (Google Calendar, Clock, Messages, WhatsApp, Photos, Files, Notes,
 * Samsung variants, …) is covered by the same code, and any future editor is covered the same day.
 *
 * Safety notes:
 *  - discovery never makes a candidate tappable or auto-tapped; disambiguation between several
 *    plausible commit controls stays with the model + AskPolicy, exactly like any other control;
 *  - destructive-looking labels are excluded (Discard / Delete / Cancel / Remove are not commits);
 *  - editable fields and password fields are never listed.
 */
object AniobEditorCommit {

    /**
     * Commit-like label tokens. Matched as whole words over the node's visible text / accessibility
     * label, so "Save now" and "Save event" qualify but "Saved" or "saving" spelled differently is
     * not required — callers should treat multi-candidate screens as ambiguous, not as a lock-on.
     */
    private val COMMIT_TOKENS = setOf(
        "save", "done", "send", "post", "publish", "apply", "update",
        "create", "add", "submit", "keep", "confirm", "finish", "complete", "ok"
    )

    /** A control on this screen that could commit the pending change. */
    data class CommitControl(val node: AniobNode, val label: String)

    /** Visible, enabled, non-editable, clickable/checkable nodes whose label names a commit. */
    fun findCommitControls(nodes: List<AniobNode>): List<CommitControl> =
        nodes.filter { node ->
            node.isVisibleToUser && node.isEnabled && !node.isEditable &&
                (node.isClickable || node.isCheckable) &&
                labelOf(node) != null
        }.mapNotNull { node -> labelOf(node)?.let { CommitControl(node, it) } }
            .distinctBy { it.node.id }
            .take(MAX_CONTROLS)

    /** The label to show for a commit control, normalized for the prompt; null when not a commit. */
    private fun labelOf(node: AniobNode): String? {
        val raw = listOf(node.text, node.contentDescription)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val tokens = raw.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        if (tokens.any { it in COMMIT_TOKENS }) return raw.take(60)
        return null
    }

    /**
     * The one line added to the model's prompt when commit-like controls are present, e.g.
     * `COMMIT-LIKE CONTROLS ON THIS SCREEN: "Save" (node 4), "Create" (node 6). If this screen holds a`
     * plus the rule. Null when no candidate exists (nothing added to the prompt).
     */
    fun promptHint(nodes: List<AniobNode>): String? {
        val controls = findCommitControls(nodes)
        if (controls.isEmpty()) return null
        val listed = controls.joinToString(", ") { "\"${it.label}\" (node ${it.node.id})" }
        return "COMMIT-LIKE CONTROLS ON THIS SCREEN: $listed. If this screen holds a pending change, " +
            "the task is not complete until the matching control is tapped and the screen confirms it; " +
            "with several plausible commit controls, ask instead of choosing."
    }

    private const val MAX_CONTROLS = 5
}
