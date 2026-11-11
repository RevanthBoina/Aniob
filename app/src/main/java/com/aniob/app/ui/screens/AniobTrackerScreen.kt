package com.aniob.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.AniobUiState
import com.aniob.app.ui.theme.AniobTheme
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobStepRecord

/**
 * Expert Tracker Sheet / Screen (AIM Phase 1.3).
 * Zero model calls, zero screen captures on open.
 * Features:
 * - Segmented progress bar: green=done, red=failed, blue-pulsing=running, gray=pending
 * - Stats header: Elapsed, Retries, Model calls, Provider
 * - FilterBar: All, Correct, Failed
 * - SubTask rows with icons (✅, ❌, ⏳, ⬜), duration, retry count
 * - Expandable failed rows with errorClass, errorMessage, Reflector reasoning, [Copy JSON]
 */
@Composable
fun AniobTrackerScreen(
    uiState: AniobUiState,
    onFilterChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val filteredSteps = when (uiState.trackerFilter) {
        "VERIFIED", "CORRECT" -> uiState.steps.filter { it.verifiedSuccess }
        "NEEDS_ATTENTION", "FAILED" -> uiState.steps.filter { !it.verifiedSuccess && it.failureReason != null }
        "NOT_CHECKED" -> uiState.steps.filter { !it.verifiedSuccess && it.failureReason == null }
        else -> uiState.steps
    }

    val totalSteps = uiState.steps.size
    val verifiedCount = uiState.steps.count { it.verifiedSuccess }
    val needsAttentionCount = uiState.steps.count { !it.verifiedSuccess && it.failureReason != null }
    val notCheckedCount = uiState.steps.count { !it.verifiedSuccess && it.failureReason == null }
    val totalLatency = uiState.steps.sumOf { it.latencyMs }
    val totalTokens = uiState.steps.sumOf { it.tokensUsed }
    val modelCalls = uiState.steps.count { it.provider == "OMNIROUTE_CLOUD" || it.provider == "LOCAL_SLM" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Task Header & Segmented Progress Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("tracker_header_card")
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = uiState.activeTask?.rawPrompt ?: "Current Execution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                        Text(uiState.lastProviderUsed)
                    }
                }

                // Segmented Progress Bar
                SegmentedProgressBar(steps = uiState.steps, isRunning = uiState.isRunning)

                // Quick Telemetry Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Time: ${totalLatency / 1000.0}s", style = MaterialTheme.typography.labelSmall)
                    Text("Model calls: $modelCalls", style = MaterialTheme.typography.labelSmall)
                    Text("Tokens: $totalTokens", style = MaterialTheme.typography.labelSmall)
                    Text("Status: ${if (uiState.isRunning) "Running" else "Idle"}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // 2. Filter Bar: All / Verified / Needs attention / Not checked
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.trackerFilter == "ALL",
                onClick = { onFilterChanged("ALL") },
                label = { Text("All ($totalSteps)") },
                modifier = Modifier.testTag("filter_all")
            )
            FilterChip(
                selected = uiState.trackerFilter == "VERIFIED" || uiState.trackerFilter == "CORRECT",
                onClick = { onFilterChanged("VERIFIED") },
                label = { Text("Verified ($verifiedCount)") },
                modifier = Modifier.testTag("filter_verified")
            )
            FilterChip(
                selected = uiState.trackerFilter == "NEEDS_ATTENTION" || uiState.trackerFilter == "FAILED",
                onClick = { onFilterChanged("NEEDS_ATTENTION") },
                label = { Text("Needs attention ($needsAttentionCount)") },
                modifier = Modifier.testTag("filter_needs_attention")
            )
            if (notCheckedCount > 0) {
                FilterChip(
                    selected = uiState.trackerFilter == "NOT_CHECKED",
                    onClick = { onFilterChanged("NOT_CHECKED") },
                    label = { Text("Not checked ($notCheckedCount)") },
                    modifier = Modifier.testTag("filter_not_checked")
                )
            }
        }

        // 3. SubTask & Step List
        if (filteredSteps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.steps.isEmpty()) "No steps recorded. Start a task to monitor." else "No steps in filter '${uiState.trackerFilter}'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("tracker_step_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSteps, key = { it.stepIndex }) { step ->
                    ExpandableStepRow(
                        step = step,
                        autoExpand = !step.verifiedSuccess,
                        onCopyJson = {
                            val escapedAction = step.action.toolName.replace("\\", "\\\\").replace("\"", "\\\"")
                            val escapedFailure = (step.failureReason ?: "none").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                            val json = """{"step":${step.stepIndex},"action":"$escapedAction","success":${step.verifiedSuccess},"latencyMs":${step.latencyMs},"failure":"$escapedFailure"}"""
                            clipboardManager.setText(AnnotatedString(json))
                            Toast.makeText(context, "Copied step JSON to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SegmentedProgressBar(steps: List<AniobStepRecord>, isRunning: Boolean) {
    val totalSegments = steps.size.coerceAtLeast(4)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (steps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.LightGray)
            )
        } else {
            steps.forEachIndexed { index, step ->
                val color = if (step.verificationSkippedFast) {
                    MaterialTheme.colorScheme.outline
                } else if (step.verifiedSuccess) {
                    AniobTheme.state.verified.accent
                } else {
                    AniobTheme.state.blocked.accent
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(color)
                )
            }
            if (isRunning) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500), repeatMode = RepeatMode.Reverse),
                    label = "alpha"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(AniobTheme.state.working.accent.copy(alpha = alpha))
                )
            }
        }
    }
}

@Composable
fun ExpandableStepRow(
    step: AniobStepRecord,
    autoExpand: Boolean,
    onCopyJson: () -> Unit
) {
    var expanded by remember(step.stepIndex, autoExpand) { mutableStateOf(autoExpand) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (step.verifiedSuccess || step.verificationSkippedFast) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("step_card_${step.stepIndex}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (step.verificationSkippedFast) "—" else if (step.verifiedSuccess) "✅" else "❌",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column {
                        val failure = step.failureReason
                        val title = when {
                            !step.verifiedSuccess && failure != null -> {
                                val f = failure.lowercase()
                                when {
                                    f.contains("approval") || f.contains("declined") -> "Waiting for approval"
                                    f.contains("not found") -> "Could not find element"
                                    f.contains("unverified") || f.contains("cannot verify") -> "Could not verify"
                                    else -> step.action.describeAction().ifBlank { "Step ${step.stepIndex} failed" }
                                }
                            }
                            step.action.describeAction().isNotBlank() -> step.action.describeAction()
                            else -> "Step ${step.stepIndex}"
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (step.verificationSkippedFast) "Verification skipped for speed" else if (step.verifiedSuccess) "Verified" else (step.failureReason ?: "Needs attention"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.verificationSkippedFast) MaterialTheme.colorScheme.onSurfaceVariant else if (step.verifiedSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider()

                    Text(
                        text = "Provider & Latency: ${step.provider} · ${step.latencyMs}ms · ${step.tokensUsed} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Action: ${formatActionSummary(step.action)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = "Screen Hash: ${step.screenHash}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!step.textEvidence.isNullOrBlank()) {
                        Text(
                            text = "Evidence: ${step.textEvidence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    val bounds = step.targetBounds
                    if (bounds != null && !bounds.isEmpty) {
                        Text(
                            text = "Target Geometry: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!step.failureReason.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Failure Reason:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = step.failureReason.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (step.reflectorInvoked) {
                                    Text(
                                        text = "Reflector: Loop detected (watchdog N=3) -> Recovery backtracking triggered.",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onCopyJson,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("copy_json_${step.stepIndex}")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy JSON", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun formatActionSummary(action: AniobAction): String = when (action) {
    is AniobAction.Tap -> "TAP(${action.target.describe()})"
    is AniobAction.LongPress -> "LONG_PRESS(${action.target.describe()}, ${action.durationMs}ms)"
    is AniobAction.OpenApp -> "OPEN_APP(${action.packageName})"
    is AniobAction.InputText -> "TYPE(\"${action.text}\" -> ${action.target.describe()})"
    is AniobAction.Swipe -> "SWIPE(${action.direction})"
    is AniobAction.PressKey -> "KEY(${action.key})"
    is AniobAction.SystemKey -> "SYS_KEY(${action.key})"
    is AniobAction.Wait -> "WAIT(${action.durationMs}ms)"
    is AniobAction.Finish -> "FINISH: ${action.summary}"
    is AniobAction.Fail -> "FAIL: ${action.reason}"
    is AniobAction.ConfirmWithUser -> "CONFIRM: ${action.message}"
    else -> "${action.toolName}"
}
