package com.aniob.app.ui.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.core.workflow.Destination
import com.aniob.core.workflow.DestinationCategory
import com.aniob.core.workflow.WorkflowResult
import com.aniob.core.workflow.WorkflowState

@Composable
fun DestinationChooserCard(
    candidates: List<Destination>,
    onSelectDestination: (Destination) -> Unit,
    onCustomUrlEntered: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var customUrl by remember { mutableStateOf("") }
    val selectingInstalledApp = candidates.isNotEmpty() && candidates.all { it.category == DestinationCategory.APP }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (selectingInstalledApp) "Choose an installed app" else "Choose a destination service",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            candidates.forEach { destination ->
                ListItem(
                    headlineContent = { Text(destination.label ?: destination.handler, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            destination.url ?: if (destination.category == DestinationCategory.APP) destination.handler else destination.category.name,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        FilledTonalButton(onClick = { onSelectDestination(destination) }) {
                            Text("Select")
                        }
                    },
                    modifier = Modifier.clickable { onSelectDestination(destination) }
                )
                HorizontalDivider()
            }

            if (!selectingInstalledApp) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Or enter another URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (customUrl.isNotBlank()) onCustomUrlEntered(customUrl)
                        },
                        enabled = customUrl.isNotBlank()
                    ) {
                        Text("Open URL")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun ContinueWorkflowCard(
    waitingState: WorkflowState.WaitingForUser,
    onContinueInWebsite: () -> Unit,
    onCopyBrief: () -> Unit,
    onMarkDone: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var userNotes by remember { mutableStateOf("") }
    var showNotesInput by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.OpenInBrowser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Task waiting on ${waitingState.destination.handler}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            val brief = waitingState.brief
            if (!brief.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Brief: \"${brief.take(120)}${if (brief.length > 120) "…" else ""}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (showNotesInput) {
                OutlinedTextField(
                    value = userNotes,
                    onValueChange = { userNotes = it },
                    label = { Text("Result notes / link (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showNotesInput = false }) {
                        Text("Back")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onMarkDone(userNotes) }) {
                        Text("Confirm Done")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        TextButton(onClick = onCopyBrief) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Brief")
                        }
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }

                    Row {
                        OutlinedButton(onClick = { showNotesInput = true }) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mark Done")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onContinueInWebsite) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}
