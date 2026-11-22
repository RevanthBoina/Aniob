package com.aniob.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.ui.AniobUiState
import java.text.SimpleDateFormat
import java.util.*

/**
 * The marker appended to [SessionScoreEntity.decisionReason] when the user confirmed a run with a
 * thumbs-up (see SessionScoreDao.markUserAttestedSuccess). Such a SUCCESS was not machine-verified;
 * the UI says so instead of dressing it up as a verified one.
 */
private fun SessionScoreEntity.isUserAttested(): Boolean =
    decisionReason.contains(USER_ATTESTED_MARKER)

private const val USER_ATTESTED_MARKER = "[user-attested]"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobHistoryScreen(
    uiState: AniobUiState,
    onBack: () -> Unit,
    onNavigateToStats: () -> Unit,
    onRunAgain: (String) -> Unit = {},
    onMakeSkill: (String) -> Unit = {},
    onSelectSession: (SessionScoreEntity) -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedSessionForDetails by remember { mutableStateOf<SessionScoreEntity?>(null) }

    val filteredSessions = remember(uiState.sessionScores, selectedFilter) {
        when (selectedFilter) {
            "SUCCESS" -> uiState.sessionScores.filter { it.status == "SUCCESS" }
            "FAILED" -> uiState.sessionScores.filter { it.status != "SUCCESS" }
            else -> uiState.sessionScores
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("history_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToStats,
                        modifier = Modifier.testTag("history_stats_action")
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = "View Stats")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Filter Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("All (${uiState.sessionScores.size})") },
                    modifier = Modifier.testTag("history_filter_all")
                )
                FilterChip(
                    selected = selectedFilter == "SUCCESS",
                    onClick = { selectedFilter = "SUCCESS" },
                    label = { Text("Success (${uiState.sessionScores.count { it.status == "SUCCESS" }})") },
                    modifier = Modifier.testTag("history_filter_success")
                )
                FilterChip(
                    selected = selectedFilter == "FAILED",
                    onClick = { selectedFilter = "FAILED" },
                    label = { Text("Failed (${uiState.sessionScores.count { it.status != "SUCCESS" }})") },
                    modifier = Modifier.testTag("history_filter_failed")
                )
            }

            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.sessionScores.isEmpty()) "No history recorded yet.\nRun a task from the chat to see execution history." else "No sessions match filter '$selectedFilter'.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        HistorySessionCard(
                            session = session,
                            onClick = {
                                selectedSessionForDetails = session
                                onSelectSession(session)
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Details Sheet when tapping a history session
    if (selectedSessionForDetails != null) {
        val s = selectedSessionForDetails!!
        ModalBottomSheet(
            onDismissRequest = { selectedSessionForDetails = null },
            modifier = Modifier.testTag("history_detail_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Session Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = s.userPrompt,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Outcome:")
                    Text(
                        text = if (s.isUserAttested() && s.status == "SUCCESS") "SUCCESS · you confirmed" else s.status,
                        fontWeight = FontWeight.Bold,
                        color = if (s.status == "SUCCESS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (s.isUserAttested()) {
                    Text(
                        text = "Counted as successful because you confirmed it — not machine-verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Primary Provider:")
                    Text(s.providerUsed, fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Steps:")
                    Text("${s.totalSteps} steps", fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Duration:")
                    Text("${s.durationMs / 1000.0}s", fontWeight = FontWeight.Medium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tokens:")
                    Text("${s.tokensUsed}", fontWeight = FontWeight.Medium)
                }
                if (s.decisionReason.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Decision & Details:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text(s.decisionReason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            selectedSessionForDetails = null
                            onRunAgain(s.userPrompt)
                        },
                        modifier = Modifier.weight(1f).testTag("history_run_again_btn")
                    ) {
                        Text("Run again")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(s.userPrompt))
                        },
                        modifier = Modifier.weight(1f).testTag("history_copy_prompt_btn")
                    ) {
                        Text("Copy prompt")
                    }
                }
                OutlinedButton(
                    onClick = {
                        selectedSessionForDetails = null
                        onMakeSkill(s.userPrompt)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("history_make_skill_btn")
                ) {
                    Text("Make it a skill")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { selectedSessionForDetails = null },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().testTag("history_detail_close")
                ) {
                    Text("Close")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun HistorySessionCard(
    session: SessionScoreEntity,
    onClick: () -> Unit
) {
    val isSuccess = session.status == "SUCCESS"
    // A thumbs-up on an unverified run flips the row to SUCCESS at the DAO layer; say why, here and
    // in the details sheet, instead of dressing it up as a verified completion.
    val attested = session.isUserAttested() && isSuccess
    val dateFormatted = remember(session.timestamp) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(session.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("history_card_${session.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when {
                            attested -> Icons.Default.ThumbUp
                            isSuccess -> Icons.Default.CheckCircle
                            else -> Icons.Default.Error
                        },
                        contentDescription = null,
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when {
                            attested -> "Confirmed by you"
                            isSuccess -> "Completed"
                            else -> "Failed"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = session.userPrompt,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("${session.totalSteps} steps") }
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text("${session.durationMs / 1000}s") }
                    )
                }

                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = session.providerUsed,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
