package com.aniob.core.ladder

import com.aniob.core.domain.AniobScreenState

/**
 * PART 3 — recognises an app's *account chooser* on the live screen.
 *
 * Why this needs to be conservative. Reading an inbox must not stop to ask "which account?" when
 * one account is signed in (the agent reads it), and it must not stop when the user is simply
 * looking at a mailbox. So a screen only counts as a chooser when it shows **two or more account
 * rows** and **no mailbox chrome at all** — a mailbox always renders at least one of its folders or
 * the compose control in the tree, while a chooser renders account rows only.
 *
 * Everything is read from the captured accessibility tree; nothing is inferred from the prompt, and
 * an unrecognised screen returns null (no question, the model continues normally).
 */
object AniobAccountChooserScreen {

    /** Rows that mean "this is a mailbox/settings page", not an account chooser. */
    private val MAILBOX_CHROME = setOf(
        "inbox", "primary", "promotions", "social", "compose", "sent", "drafts", "trash", "spam",
        "search", "search in mail", "settings", "starred", "snoozed", "important", "all mail",
        "new message", "chats", "calls", "contacts", "new chat", "start chat", "messages"
    )

    /**
     * @param targetPackages packages that belong to the app being read; empty means "any".
     * @return the signed-in account rows when the screen really is a chooser, else null.
     */
    fun detect(screen: AniobScreenState, targetPackages: Set<String>): List<AccountCandidate>? {
        if (targetPackages.isNotEmpty() &&
            targetPackages.none { it.equals(screen.packageName, ignoreCase = true) }
        ) {
            return null
        }
        val texts = screen.nodes.mapNotNull { node ->
            node.text.trim().ifBlank { node.contentDescription.trim() }.ifBlank { null }
        }
        val lower = texts.map { it.lowercase() }
        // A mailbox renders one of its own folders/controls: this is not a chooser.
        if (lower.any { it in MAILBOX_CHROME }) return null
        // A chooser is a short list of accounts. A long tree, a message body or a date/time means
        // the user is looking at mail, and a read must not stop there to ask anything.
        if (screen.nodes.size > MAX_NODES_FOR_CHOOSER) return null
        if (texts.any { it.length > MAX_PLAIN_ROW }) return null
        if (texts.any { MESSAGE_HINT.containsMatchIn(it) }) return null
        val accountRows = texts.filter { it.contains('@') && it.length <= MAX_ACCOUNT_LABEL }
        val candidates = AniobAppReadAccountPolicy.candidatesFrom(accountRows)
        return candidates.takeIf { it.size >= 2 }
    }

    private const val MAX_ACCOUNT_LABEL = 80

    /** Above this many nodes the screen is a mailbox or a settings page, not a chooser. */
    private const val MAX_NODES_FOR_CHOOSER = 14

    /** A row longer than this is a subject line or a message body, not an account label. */
    private const val MAX_PLAIN_ROW = 60

    /** Dates and times only appear on mail rows, never on an account chooser. */
    private val MESSAGE_HINT = Regex(
        """\d{1,2}:\d{2}|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\s+\d{1,2}\b""",
        RegexOption.IGNORE_CASE
    )
}
