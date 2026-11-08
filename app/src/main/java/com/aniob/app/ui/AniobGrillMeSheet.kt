package com.aniob.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.core.grillme.AniobGrillMeResult

/**
 * Grill-Me 2.0 (UX-2 §3).
 *
 * - FlowRow chips so options never clip at 360dp + 1.3× font.
 * - "Custom…" is a separate chip that reveals the text field, so typing no longer fights the
 *   chip selection.
 * - Per-question "Remember this answer" → durable vault; remembered answers prefill with a
 *   "saved ✓" badge and are editable, un-ticking forgets.
 * - Dismiss is an explicit "Cancel task" (not a silent swipe-away).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AniobGrillMeSheet(
    grillResult: AniobGrillMeResult,
    onConfirm: (Map<String, String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    rememberedKeys: Set<String> = emptySet(),
    lookupRemembered: (String) -> String? = { null }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val userAnswers = remember {
        mutableStateMapOf<String, String>().apply {
            grillResult.questions.forEach { q ->
                put(q.id, q.defaultOption ?: q.options.firstOrNull().orEmpty())
            }
        }
    }
    val customMode = remember { mutableStateMapOf<String, Boolean>() }
    val rememberToggles = remember {
        mutableStateMapOf<String, Boolean>().apply {
            grillResult.questions.forEach { q -> put(q.id, rememberedKeys.contains(q.id)) }
        }
    }

    // Prefill from the vault so a remembered answer is honored, not re-asked.
    LaunchedEffect(grillResult) {
        grillResult.questions.forEach { q ->
            lookupRemembered(q.id)?.let { userAnswers[q.id] = it }
        }
    }

    var generalNotes by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("grill_me_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Grill me",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "A couple of quick details so I get this right.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            grillResult.questions.forEach { question ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = question.question,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Needed to pick the right app.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // FlowRow: chips wrap instead of clipping at large fonts.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            question.options.forEach { option ->
                                val selected = !customMode[question.id].orDefault(false) &&
                                    userAnswers[question.id] == option
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        customMode[question.id] = false
                                        userAnswers[question.id] = option
                                    },
                                    label = { Text(option) },
                                    modifier = Modifier.testTag("chip_${question.id}_$option")
                                )
                            }
                            if (question.allowFreeText) {
                                FilterChip(
                                    selected = customMode[question.id].orDefault(false),
                                    onClick = { customMode[question.id] = true },
                                    label = { Text("Custom…") },
                                    modifier = Modifier.testTag("chip_${question.id}_custom")
                                )
                            }
                        }

                        // Decoupled free-text: only shown after the Custom chip is chosen.
                        if (question.allowFreeText && customMode[question.id].orDefault(false)) {
                            OutlinedTextField(
                                value = userAnswers[question.id] ?: "",
                                onValueChange = { userAnswers[question.id] = it },
                                label = { Text("Type your answer") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("freetext_${question.id}"),
                                singleLine = true
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rememberToggles[question.id].orDefault(false),
                                onCheckedChange = { rememberToggles[question.id] = it },
                                modifier = Modifier.testTag("remember_${question.id}")
                            )
                            Text("Remember this answer", style = MaterialTheme.typography.bodySmall)
                            if (rememberedKeys.contains(question.id)) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "saved ✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = generalNotes,
                onValueChange = { generalNotes = it },
                label = { Text("Anything else? (optional)") },
                modifier = Modifier.fillMaxWidth().testTag("grill_general_notes"),
                maxLines = 2
            )

            Text(
                text = "${grillResult.questions.size} of ${grillResult.questions.size} · remembered ${rememberToggles.count { it.value }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("grill_me_cancel")) {
                    Text("Cancel task")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val finalAnswers = userAnswers.toMutableMap()
                        if (generalNotes.isNotBlank()) finalAnswers["notes"] = generalNotes
                        val remember = rememberToggles.filter { it.value }.keys
                        onConfirm(finalAnswers, remember)
                    },
                    modifier = Modifier.testTag("grill_me_confirm")
                ) { Text("Continue") }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun Boolean?.orDefault(default: Boolean): Boolean = this ?: default
