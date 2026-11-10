package com.aniob.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aniob.core.grillme.AniobAskQueue
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.safety.TaskConsent

/**
 * The only way Aniob asks a question: ONE small card directly above the chat bar, one question at
 * a time, with tappable answers and an inline "Something else" field.
 *
 * - It appears before the task starts (clarifications first, then any payment / secret / deletion
 *   approval), so nothing is paused half-way and no notification is ever involved.
 * - Tapping an answer moves straight to the next question; "Skip" falls back to the default (and
 *   never to "Allow"); "Back" reopens the previous one; the close button cancels the task.
 * - A chosen answer can be remembered for next time, except approvals, which are asked every task.
 * - All state transitions live in [AniobAskQueue], a pure state machine with unit tests.
 */
@Composable
fun AniobAskCard(
    result: AniobGrillMeResult,
    rememberedKeys: Set<String>,
    onFinished: (answers: Map<String, String>, rememberKeys: Set<String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var queue by remember(result) { mutableStateOf(AniobAskQueue.start(result.questions)) }

    // Hand the answers back exactly once, when the last question has been answered or skipped.
    LaunchedEffect(queue.isComplete) {
        if (queue.isComplete) onFinished(queue.answers, queue.remember)
    }
    val question = queue.current ?: return

    var typing by remember(queue.index) { mutableStateOf(question.options.isEmpty()) }
    var text by remember(queue.index) { mutableStateOf("") }
    var rememberChoice by remember(queue.index) { mutableStateOf(rememberedKeys.contains(question.id)) }
    val isConsent = TaskConsent.isConsentId(question.id)
    val focus = remember(queue.index) { FocusRequester() }

    LaunchedEffect(typing, queue.index) {
        if (typing) runCatching { focus.requestFocus() }
    }

    fun submitText() {
        if (text.isNotBlank()) queue = queue.answer(text, rememberChoice)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize()
            .testTag("ask_card"),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (queue.total > 1) "Quick question · ${queue.position} of ${queue.total}" else "Quick question",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.testTag("ask_cancel")) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel task")
                }
            }

            Text(
                text = question.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            Column(
                modifier = Modifier
                    .padding(end = 12.dp, top = 4.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                question.options.forEachIndexed { index, option ->
                    OutlinedButton(
                        onClick = { queue = queue.answer(option, rememberChoice) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ask_option_$index"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (question.allowFreeText && question.options.isNotEmpty() && !typing) {
                    OutlinedButton(
                        onClick = { typing = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("ask_other"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "Something else…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (question.allowFreeText && typing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focus)
                                .testTag("ask_text"),
                            placeholder = { Text("Type your answer") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitText() })
                        )
                        FilledIconButton(
                            onClick = { submitText() },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.testTag("ask_send")
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send answer")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (queue.canGoBack) {
                    TextButton(onClick = { queue = queue.back() }, modifier = Modifier.testTag("ask_back")) {
                        Text("Back")
                    }
                }
                if (!isConsent) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                        modifier = Modifier.testTag("ask_remember")
                    )
                    Text("Remember", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { queue = queue.skip() }, modifier = Modifier.testTag("ask_skip")) {
                    Text(if (isConsent) "Don't allow" else "Skip")
                }
            }
        }
    }
}
