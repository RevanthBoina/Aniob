package com.aniob.core.execution

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.intent.AppDataReadRequest

/**
 * One piece of app data that was really on the screen: a message row, a sender, a subject line.
 *
 * [text] is copied from a node of a captured accessibility tree — never composed by a model and
 * never guessed. [stepIndex] is the step whose observation produced it, so a report can say when
 * the item was seen, and [screenPackage] proves it was seen inside the app the user asked about.
 */
data class ObservedAppDataItem(
    val text: String,
    val screenPackage: String,
    val stepIndex: Int,
    val kind: String
)

/**
 * Task-scoped evidence that the data an app-data read request asked for was **actually observed**.
 *
 * Why this exists (the defect it fixes). A run could open Gmail, take two taps, propose `Finish`,
 * and be closed as a success while no message content had ever been read — and no criterion could
 * disprove it, because "read the newest email" has no text to assert on beforehand. The honest
 * criterion is quantitative and observable: *this many distinct pieces of data were on screen
 * inside the app the user named*, so a claim to have read them is supported, and a claim to have
 * read more than that is not.
 *
 * Rules that keep it honest:
 *
 *  - only captures from the **target app** count (`targetPackages`); a browser tab, the launcher or
 *    Aniob's own chat window contributes nothing;
 *  - only *row-like* nodes count — clickable or long-clickable list entries, or non-clickable text
 *    that carries a sender address/date — so a toolbar label ("Inbox") is not a message;
 *  - items are deduplicated by normalized text across steps, which is what makes "scroll and
 *    re-observe" add new evidence instead of inflating the count with the same three rows;
 *  - a task that asked for N items is satisfied only at N distinct items. Unspecified requests need
 *    at least one observed item, because "I read your inbox" with nothing seen is the failure mode.
 *
 * Pure Kotlin/JVM so every one of those rules is pinned by a unit test.
 */
class AppReadEvidence(
    private val request: AppDataReadRequest? = null,
    /** Packages that count as "inside the app the user asked about". Empty means "any app". */
    private val targetPackages: Set<String> = emptySet()
) {
    private val items = LinkedHashMap<String, ObservedAppDataItem>()
    private var sawTargetScreen = false

    /** Observations recorded so far, distinct by normalized text. */
    fun items(): List<ObservedAppDataItem> = items.values.toList()

    fun distinctCount(): Int = items.size

    /** True once any capture came from the target app, so a report can say what was reached. */
    fun reachedTargetApp(): Boolean = sawTargetScreen

    /** What the user asked for, when the caller knows. */
    fun requestedCount(): Int? = request?.requestedCount

    /** How many items would satisfy the request: the named count, else 1. */
    val requiredCount: Int get() = request?.requestedCount ?: if (request == null) 0 else 1

    val isReadRequest: Boolean get() = request != null

    fun isSatisfied(): Boolean = request == null || distinctCount() >= requiredCount

    /**
     * Records the data visible on [screen].
     *
     * @param expectedKind the data kind in [AppDataReadRequest]; used to name the evidence and to
     *        keep the row heuristic focused. Never used to invent an item.
     */
    fun observe(screen: AniobScreenState, stepIndex: Int) {
        if (request == null) return
        val pkg = screen.packageName
        if (targetPackages.isNotEmpty() && targetPackages.none { it.equals(pkg, ignoreCase = true) }) {
            return
        }
        if (targetPackages.isNotEmpty()) sawTargetScreen = true
        for (node in screen.nodes) {
            val text = rowText(node) ?: continue
            val key = normalize(text)
            if (key.length < MIN_ITEM_CHARS) continue
            if (items.containsKey(key)) continue
            items[key] = ObservedAppDataItem(
                text = text,
                screenPackage = pkg,
                stepIndex = stepIndex,
                kind = request.kind.name.lowercase()
            )
        }
    }

    /**
     * Why the request is not yet satisfied, in words a user can act on — or null when it is.
     *
     * This string is shown in the transcript and in the sealed outcome, so it says what was seen,
     * what is still missing, and never implies that unseen content was read.
     */
    fun unmetReason(): String? {
        if (request == null || isSatisfied()) return null
        val needed = requiredCount
        val seen = distinctCount()
        val where = when {
            targetPackages.isEmpty() -> "the app"
            !sawTargetScreen -> "the app that owns this data (it never came to the foreground)"
            else -> "the ${request.noun} list"
        }
        return "I opened $where but only observed $seen of the $needed " +
            "${if (needed == 1) request.noun else request.noun + "s"} you asked for, so I cannot " +
            "report their contents. Scroll the list or open the message and ask again."
    }

    /**
     * The evidence lines a report may quote. Only observed text appears here; when nothing was
     * observed the list is empty and the report has nothing to quote.
     */
    fun evidenceLines(max: Int = 5): List<String> =
        items.values.take(max).map { it.text }

    private fun rowText(node: AniobNode): String? {
        val text = node.text.trim().ifBlank { node.contentDescription.trim() }
        if (text.length < MIN_ITEM_CHARS) return null
        if (text.length > MAX_ITEM_CHARS) return null
        if (text.lowercase() in CHROME_LABELS) return null
        val rowLike = node.isClickable || node.isEditable ||
            SENDER_OR_DATE.containsMatchIn(text)
        return text.takeIf { rowLike }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")

    companion object {
        const val MIN_ITEM_CHARS = 3
        const val MAX_ITEM_CHARS = 160

        /** Navigation chrome that is never a message: counting it would fake the evidence. */
        private val CHROME_LABELS = setOf(
            "inbox", "search", "compose", "menu", "settings", "back", "more options", "refresh",
            "star", "archive", "delete", "send", "primary", "promotions", "social", "sent",
            "drafts", "spam", "trash", "chats", "calls", "contacts", "new chat", "start chat"
        )

        /** A sender address or a date/time is row text even when the row itself is not clickable. */
        private val SENDER_OR_DATE = Regex(
            """(?:[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,})|(?:\b\d{1,2}:\d{2}\s*(?:am|pm)?\b)|
               (?:\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\s+\d{1,2}\b)""".trimIndent(),
            RegexOption.IGNORE_CASE
        )
    }
}

/**
 * The one place that decides whether an app-data read may be reported as done.
 *
 * It is deliberately separate from [AppReadEvidence] (which only knows what was observed) so the
 * completion rules can be tested — and read — on their own:
 *
 *  - a task that is not a read request is unaffected ([Verdict.NotAReadRequest]);
 *  - a read request with enough observed items may complete, and its verdict carries the count;
 *  - a read request without them may **not** complete, and the reason names what was observed and
 *    what is missing. Opening the app, tapping a message and a successful dispatch are not reading;
 *    only observed content is.
 */
object AppReadCompletionGate {

    sealed class Verdict {
        data object NotAReadRequest : Verdict()
        data class Allowed(val observed: Int, val requested: Int?) : Verdict()
        data class Rejected(val reason: String, val observed: Int, val requested: Int?) : Verdict()

        val blocksCompletion: Boolean get() = this is Rejected
    }

    fun evaluate(evidence: AppReadEvidence?): Verdict {
        if (evidence == null || !evidence.isReadRequest) return Verdict.NotAReadRequest
        val observed = evidence.distinctCount()
        val requested = evidence.requestedCount()
        return if (evidence.isSatisfied()) {
            Verdict.Allowed(observed, requested)
        } else {
            Verdict.Rejected(
                reason = evidence.unmetReason() ?: "The requested app data was not observed.",
                observed = observed,
                requested = requested
            )
        }
    }
}
