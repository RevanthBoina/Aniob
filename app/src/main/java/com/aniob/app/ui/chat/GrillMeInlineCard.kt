package com.aniob.app.ui.chat

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
import com.aniob.core.grillme.GrillQuestion

/**
 * The answers to send, or null while any question is still unanswered. Nothing is ever sent on
 * the user's behalf: the only caller is the Continue button.
 */
internal fun grillAnswersToSubmit(
    questions: List<GrillQuestion>,
    answers: Map<String, String>,
    notes: String
): Map<String, String>? {
    if (questions.any { answers[it.id].isNullOrBlank() }) return null
    val result = answers.filterValues { it.isNotBlank() }.toMutableMap()
    if (notes.isNotBlank()) result["notes"] = notes
    return result
}

/**
 * Clarifying questions, shown as a compact card in the chat column directly above the composer
 * instead of a modal sheet, so the conversation stays visible while the user answers.
 *
 * - Options are chips that wrap; the last chip is "Type it yourself…" and reveals a text field.
 *   A closed-choice question (`allowFreeText = false`) has no such chip.
 * - "Remember this answer" stores the answer so the question is skipped next time.
 * - Nothing is submitted automatically; the user presses Continue or Cancel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrillMeInlineCard(
    grillResult: AniobGrillMeResult,
    onConfirm: (Map<String, String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    rememberedKeys: Set<String> = emptySet(),
    lookupRemembered: (String) -> String? = { null }
) {
    val userAnswers = remember(grillResult) {
        mutableStateMapOf<String, String>().apply {
            grillResult.questions.forEach { q ->
                put(q.id, q.defaultOption.orEmpty())
            }
        }
    }
    val customMode = remember(grillResult) { mutableStateMapOf<String, Boolean>() }
    val rememberToggles = remember(grillResult) {
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

    var generalNotes by remember(grillResult) { mutableStateOf("") }
    var showNotes by remember(grillResult) { mutableStateOf(false) }
    var submitted by remember(grillResult) { mutableStateOf(false) }
    fun submitOnce() {
        if (submitted) return
        val answers = grillAnswersToSubmit(grillResult.questions, userAnswers, generalNotes) ?: return
        submitted = true
        onConfirm(answers, rememberToggles.filterValues { it }.keys)
    }

    ElevatedCard(modifier = modifier.fillMaxWidth().testTag("grill_me_card")) {
        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            grillResult.questions.forEach { question ->
                Text(
                    text = question.question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // FlowRow: chips wrap instead of clipping at large fonts.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        AssistChip(
                            onClick = {
                                if (!customMode[question.id].orDefault(false)) {
                                    customMode[question.id] = true
                                    // Start from an empty field, not the previously tapped option.
                                    if (userAnswers[question.id] in question.options) userAnswers[question.id] = ""
                                }
                            },
                            label = { Text("Type it yourself…") },
                            modifier = Modifier.testTag("chip_${question.id}_custom")
                        )
                    }
                }

                // Decoupled free-text: only shown after the "Type it yourself…" chip is chosen.
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

            if (showNotes) {
                OutlinedTextField(
                    value = generalNotes,
                    onValueChange = { generalNotes = it },
                    label = { Text("Anything else? (optional)") },
                    modifier = Modifier.fillMaxWidth().testTag("grill_general_notes"),
                    maxLines = 2
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!showNotes) {
                    TextButton(onClick = { showNotes = true }, modifier = Modifier.testTag("grill_add_note")) {
                        Text("Add a note")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("grill_me_cancel")) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { submitOnce() },
                    enabled = !submitted && grillResult.questions.all { !userAnswers[it.id].isNullOrBlank() },
                    modifier = Modifier.testTag("grill_me_confirm")
                ) { Text("Continue") }
            }
        }
    }
}

private fun Boolean?.orDefault(default: Boolean): Boolean = this ?: default
