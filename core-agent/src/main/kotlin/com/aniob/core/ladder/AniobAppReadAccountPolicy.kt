package com.aniob.core.ladder

import com.aniob.core.grillme.GrillQuestion

/**
 * One account row an app shows on its account chooser/picker.
 *
 * [label] is the text the app renders (an address, a display name); [signedIn] means the row is a
 * real, already-signed-in account rather than "Add another account".
 */
data class AccountCandidate(val label: String, val signedIn: Boolean = true, val topmost: Boolean = false)

/** What Aniob should do about account choice before it starts reading. */
sealed class AccountHandover {
    /** Read with [label] and ask nothing: exactly one signed-in account, or the question was asked. */
    data class Proceed(val label: String, val askedAlready: Boolean, val reason: String) : AccountHandover()

    /** Two or more real accounts: ask ONCE, with the accounts as options. */
    data class AskOnce(val question: GrillQuestion, val candidates: List<String>) : AccountHandover()

    /** Nothing to read: no signed-in account was found on the screen. */
    data class NeedsSignIn(val reason: String) : AccountHandover()
}

/**
 * PART 3 — when may an app-data read stop and ask the user about an account?
 *
 * The failure this replaces is a task that kept interrupting the user: "which account?", then again
 * on the next step, and sometimes "please open the app first" for an app it could open itself. The
 * rules are deliberately one-way:
 *
 *  - the agent opens the app itself; it never asks the user to open it (that is `Launched` work in
 *    [AniobAppDataReadPlanner], not a question);
 *  - one signed-in account is not ambiguous: read it, ask nothing;
 *  - two or more signed-in accounts are genuinely ambiguous: ask exactly ONCE, and never repeat the
 *    question in the same task — after the answer, or after asking, the topmost/answer account wins
 *    and the task continues;
 *  - a screen with no signed-in account is a real authentication handover ([NeedsSignIn]); it is not
 *    completion, and the active task is kept so reading can continue after the user signs in.
 *
 * Pure Kotlin so every rule is pinned by a test; the caller only supplies what the accessibility
 * tree actually showed.
 */
object AniobAppReadAccountPolicy {

    /** Stable id so the answer is filed under the same question in the task context. */
    const val QUESTION_ID = "app_read.account"

    /** Text patterns that are chooser chrome, not an account. */
    private val CHROME = setOf(
        "add another account", "add account", "manage accounts", "use another account",
        "remove account", "switch account", "accounts", "google", "cancel", "back"
    )

    /**
     * Turns the visible rows into [AccountCandidate]s. Only rows that really look like a signed-in
     * account (an address, or a row marked as signed in by the caller) are returned, in screen
     * order, with the first one marked [AccountCandidate.topmost].
     */
    fun candidatesFrom(labels: List<String>): List<AccountCandidate> {
        val cleaned = labels.map { it.trim() }.filter { it.isNotEmpty() }
        val accounts = cleaned.filter { label ->
            val lower = label.lowercase()
            !lower.contains("add another") && lower !in CHROME && (lower.contains('@') || label.length > 2)
        }
        return accounts.distinctBy { it.lowercase() }.mapIndexed { index, label ->
            AccountCandidate(label = label, signedIn = true, topmost = index == 0)
        }
    }

    /**
     * The decision, given the accounts the app showed and whether this task already asked.
     *
     * @param askedAlready the task's own ledger: true once [QUESTION_ID] was answered or shown.
     */
    fun decide(candidates: List<AccountCandidate>, askedAlready: Boolean): AccountHandover {
        val usable = candidates.filter { it.signedIn }
        return when {
            usable.isEmpty() -> AccountHandover.NeedsSignIn(
                "This app has no signed-in account on screen, so there is nothing I can read yet. " +
                    "Sign in once and I will continue this same task."
            )
            usable.size == 1 -> AccountHandover.Proceed(
                label = usable.first().label,
                askedAlready = askedAlready,
                reason = "Only one account is signed in, so I am reading it without asking."
            )
            !askedAlready -> AccountHandover.AskOnce(
                question = GrillQuestion(
                    id = QUESTION_ID,
                    question = "This app is signed in to ${usable.size} accounts (" +
                        usable.joinToString(", ") { it.label } + "). Which one should I read?",
                    options = usable.map { it.label },
                    allowFreeText = false,
                    defaultOption = usable.first().label
                ),
                candidates = usable.map { it.label }
            )
            else -> AccountHandover.Proceed(
                label = usable.first().label,
                askedAlready = true,
                reason = "I already asked which account to use, so I am continuing with the one on " +
                    "top instead of asking again."
            )
        }
    }

    /**
     * The account the user picked, matched against the candidates; falls back to the topmost one so
     * a free-text answer that names nothing recognisable cannot stall the task.
     */
    fun resolveAnswer(candidates: List<AccountCandidate>, answer: String?): String? {
        if (candidates.isEmpty()) return null
        val chosen = answer?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { text -> candidates.firstOrNull { it.label.equals(text, ignoreCase = true) } }
            ?: answer?.let { text ->
                val lower = text.lowercase()
                candidates.firstOrNull { lower.contains(it.label.lowercase()) }
            }
        return (chosen ?: candidates.firstOrNull { it.topmost } ?: candidates.first()).label
    }
}
