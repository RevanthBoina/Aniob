package com.aniob.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aniob.core.outcome.RunOutcome
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.outcome.UserDecision

/**
 * The honest result line plus a one-tap \uD83D\uDC4D / \uD83D\uDC4E. A thumbs-up on UNVERIFIED/DELEGATED is stored as
 * "confirmed by you" and never counts as machine-verified; a thumbs-down on a VERIFIED run records that the
 * probe and the user disagree.
 */
@Composable
fun OutcomeFeedbackRow(
    outcome: SealedOutcome,
    onDecision: (UserDecision) -> Unit,
    modifier: Modifier = Modifier
) {
    if (outcome.outcome == RunOutcome.STOPPED) return
    val label = when {
        outcome.outcome == RunOutcome.VERIFIED && outcome.isUserAttested -> "Confirmed by you"
        outcome.outcome == RunOutcome.VERIFIED -> "Verified: " + (outcome.evidence.firstOrNull()?.excerpt ?: "checked")
        outcome.outcome == RunOutcome.UNVERIFIED -> "I couldn't verify this. Did it work?"
        outcome.outcome == RunOutcome.DELEGATED -> outcome.reason + " Did it work?"
        outcome.outcome == RunOutcome.FAILED -> "Didn't work: " + outcome.reason
        else -> "Was this helpful?"
    }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val chosen = outcome.userDecision
            OutlinedButton(
                onClick = { onDecision(UserDecision.YES) },
                enabled = chosen == null,
                modifier = Modifier.testTag("outcome_yes").semantics { contentDescription = "Yes, it worked" }
            ) { Text(UserDecision.YES.emoji + if (chosen == UserDecision.YES) " Thanks" else "") }
            OutlinedButton(
                onClick = { onDecision(UserDecision.NO) },
                enabled = chosen == null,
                modifier = Modifier.testTag("outcome_no").semantics { contentDescription = "No, it did not work" }
            ) { Text(UserDecision.NO.emoji + if (chosen == UserDecision.NO) " Noted" else "") }
        }
    }
}
