package com.aniob.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.core.outcome.RunOutcome
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.outcome.UserDecision

@Composable
fun OutcomeFeedbackRow(
    outcome: SealedOutcome,
    onDecision: (UserDecision) -> Unit,
    modifier: Modifier = Modifier
) {
    if (outcome.outcome == RunOutcome.STOPPED) return
    val chosen = outcome.userDecision

    val label = when {
        chosen == UserDecision.YES -> "Marked as done — saved as a reusable skill!"
        chosen == UserDecision.NO -> "Marked as incomplete. Shall I try again?"
        outcome.outcome == RunOutcome.VERIFIED -> "I have completed the task. Did it work as expected?"
        else -> "Actions completed. Is it done, or shall I try again?"
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (chosen == UserDecision.YES) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (chosen == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (outcome.outcome == RunOutcome.FAILED || outcome.outcome == RunOutcome.UNVERIFIED) {
                        Button(
                            onClick = { onDecision(UserDecision.YES) },
                            modifier = Modifier.testTag("mark_correct_btn").semantics { contentDescription = "Mark as correct" }
                        ) { Text("Mark as correct") }
                    }
                    Button(
                        onClick = { onDecision(UserDecision.YES) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("outcome_yes").semantics { contentDescription = "Yes, it worked" }
                    ) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("It's Done", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = { onDecision(UserDecision.NO) },
                        modifier = Modifier.testTag("outcome_no").semantics { contentDescription = "No, try again" }
                    ) {
                        Icon(Icons.Default.ThumbDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Try Again", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (chosen == UserDecision.YES) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Text("Skill Learned & Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
