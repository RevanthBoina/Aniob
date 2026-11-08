package com.aniob.app.outcome

import com.aniob.app.ui.model.Outcome
import com.aniob.core.narration.FailureReasonCopy

/** The single user-facing copy policy for task outcomes across chat and result notifications. */
object AniobOutcomeCopy {
    data class Copy(val title: String, val message: String)

    fun forTask(
        outcome: Outcome,
        reason: String?,
        taskLabel: String = "",
        completedChecks: List<String> = emptyList(),
        outstandingChecks: List<String> = emptyList()
    ): Copy {
        val completed = completedChecks.filter { it.isNotBlank() }.distinct().take(4)
        val outstanding = outstandingChecks.filter { it.isNotBlank() }.distinct().take(4)
        val partial = completed.isNotEmpty() && outstanding.isNotEmpty()

        return when (outcome) {
            Outcome.SUCCESS -> Copy(
                title = "Done",
                message = if (completed.isEmpty()) {
                    "Done. I verified the result."
                } else {
                    "Done. Verified: ${completed.joinToString(", ")}."
                }
            )
            Outcome.UNVERIFIED -> Copy(
                title = "Done, not verified",
                message = "The steps ran, but I couldn't verify the result. Please check the screen; I won't mark it done."
            )
            Outcome.FAILED -> if (partial) {
                Copy(
                    title = "Needs attention",
                    message = buildString {
                        val task = com.aniob.core.safety.AniobDlpSanitizer.sanitize(taskLabel)
                            .replace(Regex("\\s+"), " ").trim().take(100).replace('\'', '’')
                        if (task.isNotBlank()) append("FAILED '").append(task).append("'. ")
                        else append("FAILED. ")
                        append("Done except: ").append(outstanding.joinToString(", "))
                            .append(". Want me to try again?")
                    }
                )
            } else {
                val explanation = FailureReasonCopy.humanize(reason)
                Copy(
                    title = "Needs attention",
                    message = "I couldn't finish. $explanation. Want me to try again?"
                )
            }
            Outcome.STOPPED -> Copy(
                title = "Stopped",
                message = "Stopped. Earlier changes were not undone."
            )
            Outcome.INTERRUPTED -> Copy(
                title = "Interrupted",
                message = "The run was interrupted before I could verify the result. Earlier changes were not undone."
            )
        }
    }
}
