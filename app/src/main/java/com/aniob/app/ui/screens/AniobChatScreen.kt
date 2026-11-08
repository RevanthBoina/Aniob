package com.aniob.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.system.SystemState
import com.aniob.app.ui.components.BannerKind
import com.aniob.app.ui.components.StatusBanner
import com.aniob.app.ui.theme.AniobTheme
import com.aniob.app.ui.AniobUiState
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.chat.FormattedMessageView
import com.aniob.app.ui.model.*
import com.aniob.core.knowledge.AniobAppCatalog
import com.aniob.core.narration.FailureReasonCopy
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobChatScreen(
    uiState: AniobUiState,
    systemState: SystemState? = null,
    onSubmitTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onOutcomeDecision: (com.aniob.core.outcome.UserDecision) -> Unit = {},
    onPauseToggle: () -> Unit = {},
    onShowTrackerSheet: (Boolean) -> Unit,
    onFilterChanged: (String) -> Unit,
    onManageModels: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onDismissInterrupted: () -> Unit = {},
    onDismissRecovery: () -> Unit = {},
    onRunAgain: (String) -> Unit = {},
    onViewSteps: () -> Unit = {},
    onMomentDismiss: () -> Unit = {},
    onQueueFollowUp: (String) -> Unit = {},
    onCancelQueuedTask: () -> Unit = {},
    onStartQueuedTask: () -> Unit = {},
    onWorkflowMarkComplete: (String, String) -> Unit = { _, _ -> },
    onWorkflowCancel: (String) -> Unit = {},
    onContinueInWebsite: (String) -> Unit = {},
    onConsumePendingPrefill: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    renderWindow: () -> List<ChatMessage> = { emptyList() }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val app = context.applicationContext as? com.aniob.app.AniobApplication
    val workflowState by (app?.workflowCoordinator?.workflowState?.collectAsState() ?: remember { mutableStateOf(null) })
    var promptInput by rememberSaveable { mutableStateOf("") }
    val isConnected = systemState?.accessibilityConnected ?: AniobAccessibilityService.isServiceConnected
    var bannerDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.pendingInputPrefill) {
        val prefill = uiState.pendingInputPrefill
        if (!prefill.isNullOrBlank()) {
            promptInput = prefill
            onConsumePendingPrefill()
        }
    }

    val windowMessages = renderWindow()
    val displayMessages: List<ChatMessage> = if (windowMessages.isNotEmpty()) windowMessages else uiState.chatMessages

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                promptInput = spokenText
            }
        }
    }
    val speechAvailable = remember(context) {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    val listState = rememberLazyListState()

    // Keep the newest message visible when the keyboard opens (the list shrinks above the composer).
    val imeBottomPx = androidx.compose.foundation.layout.WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottomPx > 0) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (imeBottomPx > 0 && last >= 0) listState.scrollToItem(last)
    }

    // Auto-scroll to latest message
    LaunchedEffect(displayMessages.size, uiState.isStreaming, uiState.streamingBubbleText, uiState.lastSummary) {
        val lastItem = listState.layoutInfo.totalItemsCount - 1
        if (lastItem >= 0) listState.animateScrollToItem(lastItem)
    }

    // Tracker Bottom Sheet (0 model calls, 0 captures)
    if (uiState.showTrackerSheet) {
        ModalBottomSheet(
            onDismissRequest = { onShowTrackerSheet(false) },
            modifier = Modifier.testTag("tracker_bottom_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Execution Steps",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { onShowTrackerSheet(false) },
                        modifier = Modifier.testTag("close_tracker_sheet")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                AniobTrackerScreen(
                    uiState = uiState,
                    onFilterChanged = onFilterChanged
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Permission Health Banner (Blocking card only when disconnected, not shown everyday)
        if (!isConnected && !bannerDismissed) {
            StatusBanner(
                kind = BannerKind.ERROR,
                title = "Accessibility Service Disabled",
                body = "Enable Aniob in Android Settings to automate apps.",
                actionLabel = "Enable",
                onAction = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                },
                onDismiss = { bannerDismissed = true },
                modifier = Modifier.testTag("service_status_card")
            )
        }

        // 2. Interrupted recovery banner (UX-6)
        if (uiState.interruptedSummary != null) {
            StatusBanner(
                kind = BannerKind.WARNING,
                title = "Task was interrupted",
                body = "\"${uiState.interruptedSummary.prompt}\" stopped when Aniob restarted.",
                actionLabel = "Resume",
                onAction = {
                    val p = uiState.interruptedSummary.prompt
                    onDismissInterrupted()
                    onSubmitTask(p)
                },
                onDismiss = onDismissInterrupted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 3. Setup reminder banner if skipped (PROMPT 4)
        if (systemState != null && (!systemState.accessibilityConnected || !systemState.overlayGranted) && isConnected && !bannerDismissed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpenSetup() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Finish setup to unlock all features →", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onOpenSetup, contentPadding = PaddingValues(0.dp)) {
                        Text("Finish setup", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3b. Waiting workflow card (Return to website / Continue workflow)
        val ws = workflowState
        if (ws is com.aniob.core.workflow.WorkflowState.WaitingForUser) {
            com.aniob.app.ui.workflow.ContinueWorkflowCard(
                waitingState = ws,
                onContinueInWebsite = {
                    onContinueInWebsite(ws.identity.taskId)
                },
                onCopyBrief = {
                    val brief = ws.brief
                    if (!brief.isNullOrBlank()) {
                        app?.platformTools?.copyToClipboard("Aniob Brief", brief)
                    }
                },
                onMarkDone = { notes ->
                    onWorkflowMarkComplete(ws.identity.taskId, notes)
                },
                onCancel = {
                    onWorkflowCancel(ws.identity.taskId)
                }
            )
        }

        // 4. Live Cockpit Narration Ticker (PROMPT 3)
        AnimatedVisibility(visible = uiState.isRunning) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .testTag("live_execution_ticker")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PulsingDot()
                        val narration = uiState.lastNarration.ifBlank {
                            if (uiState.isPaused) "Paused — do this step yourself, then Resume"
                            else "Step ${uiState.currentStep} · ${FailureReasonCopy.providerLabel(uiState.lastProviderUsed)}"
                        }
                        Text(
                            text = narration,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPauseToggle,
                            modifier = Modifier.size(48.dp).testTag("ticker_pause_btn")
                        ) {
                            Icon(
                                if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (uiState.isPaused) "Resume" else "Pause"
                            )
                        }
                        TextButton(
                            onClick = { onShowTrackerSheet(true) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("open_tracker_sheet_btn")
                        ) {
                            Text("Details", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(
                            onClick = onStopTask,
                            modifier = Modifier.size(48.dp).testTag("ticker_stop_btn")
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Conversation History or Empty State
        if (displayMessages.isEmpty() && uiState.lastSummary == null && !uiState.isStreaming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "How can I help you today?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tell Aniob what to do, or tap a starter task below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                ExampleTaskRow(title = "Open Settings", subtitle = "Safe system navigation") {
                    promptInput = "Open Settings"
                    onSubmitTask("Open Settings")
                }
                ExampleTaskRow(title = "Check battery status", subtitle = "Reads system power info") {
                    promptInput = "Check battery status"
                    onSubmitTask("Check battery status")
                }
                ExampleTaskRow(title = "Book a cab to airport", subtitle = "Shows Grill-Me clarification") {
                    promptInput = "Book a cab to airport"
                    onSubmitTask("Book a cab to airport")
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("chat_timeline"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = displayMessages,
                    key = { it.id }
                ) { message ->
                    WhatsAppChatBubble(
                        message = message,
                        onOpenDetails = { onShowTrackerSheet(true) },
                        onManageModels = onManageModels,
                        onOpenSettings = onOpenSettings,
                        onRetry = {
                            if (!message.retryPrompt.isNullOrBlank()) {
                                onSubmitTask(message.retryPrompt)
                            }
                        }
                    )
                }

                if (uiState.isStreaming) {
                    item(key = "streaming_bubble") {
                        StreamingChatBubble(uiState.streamingBubbleText)
                    }
                }

                // "Is it thinking or stuck?": visible while a run is active, honest about rules-only steps.
                if (uiState.isRunning && uiState.agentPhase.phase != com.aniob.core.narration.AgentPhase.IDLE) {
                    item(key = "thinking_banner") {
                        com.aniob.app.ui.chat.ThinkingBanner(
                            snapshot = uiState.agentPhase,
                            onStop = onStopTask,
                            onSwitchToOnDevice = null
                        )
                    }
                }

                if (uiState.lastSummary != null) {
                    item(key = "task_summary_card") {
                        TaskSummaryCard(
                            summary = uiState.lastSummary,
                            onRunAgain = { onRunAgain(uiState.lastSummary.prompt) },
                            onViewSteps = onViewSteps,
                            onMakeSkill = { onOpenSkills() },
                            onRetry = { onSubmitTask(uiState.lastSummary.prompt) }
                        )
                    }
                }

                // Honest closed-set result + the user's \uD83D\uDC4D / \uD83D\uDC4E.
                uiState.lastSealedOutcome?.let { sealed ->
                    if (!uiState.isRunning) {
                        item(key = "outcome_feedback") {
                            com.aniob.app.ui.chat.OutcomeFeedbackRow(sealed, onDecision = onOutcomeDecision)
                        }
                    }
                }
            }
        }

        // 6. Queued task chip (running) & persistent next task card (idle)
        if (uiState.isRunning && uiState.queuedPrompt != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "After this task: \"${uiState.queuedPrompt}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onCancelQueuedTask,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel queued task")
                    }
                }
            }
        } else if (!uiState.isRunning && uiState.queuedPrompt != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("queued_task_card")
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Queued next task: \"${uiState.queuedPrompt}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartQueuedTask,
                            modifier = Modifier.testTag("btn_start_queued_task")
                        ) {
                            Text("Start next task")
                        }
                        OutlinedButton(
                            onClick = onCancelQueuedTask,
                            modifier = Modifier.testTag("btn_discard_queued_task")
                        ) {
                            Text("Discard")
                        }
                    }
                }
            }
        }

        // 7. Horizontal Suggestion Rail (PROMPT 2)
        val catalogApps: List<com.aniob.core.knowledge.AppCatalogEntry> = remember {
            runCatching {
                AniobAppCatalog.getInstance().getAll().take(3)
            }.getOrDefault(emptyList())
        }
        val recentPrompts = remember(uiState.sessionScores) {
            uiState.sessionScores.map { it.userPrompt }.filter { it.isNotBlank() }.distinct().take(5)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            catalogApps.forEach { app ->
                val appPrompt = "Open ${app.appName}"
                SuggestionChip(
                    onClick = {
                        if (promptInput == appPrompt) {
                            onSubmitTask(appPrompt)
                            promptInput = ""
                        } else {
                            promptInput = appPrompt
                        }
                    },
                    label = { Text(appPrompt, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("suggestion_app_${app.packageName}")
                )
            }

            listOf("Search in YouTube", "Send message to", "Set alarm for").forEach { template ->
                SuggestionChip(
                    onClick = {
                        if (promptInput != "$template ") {
                            promptInput = "$template "
                        }
                    },
                    label = { Text(template, style = MaterialTheme.typography.labelSmall) }
                )
            }

            recentPrompts.forEach { recent ->
                SuggestionChip(
                    onClick = {
                        if (promptInput == recent) {
                            onSubmitTask(recent)
                            promptInput = ""
                        } else {
                            promptInput = recent
                        }
                    },
                    label = { Text(recent.take(24), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // 8. Input Composer (PROMPT 2)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                placeholder = {
                    Text(if (uiState.isRunning) "Queue next task..." else "Message or command...")
                },
                shape = RoundedCornerShape(24.dp),
                enabled = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (promptInput.isNotBlank()) {
                            val cmd = promptInput.trim()
                            promptInput = ""
                            if (uiState.isRunning) {
                                onQueueFollowUp(cmd)
                            } else {
                                onSubmitTask(cmd)
                            }
                        }
                    }
                )
            )

            if (speechAvailable) {
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your task to Aniob")
                        }
                        runCatching { speechLauncher.launch(intent) }
                    },
                    modifier = Modifier.testTag("mic_button")
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (uiState.isRunning && promptInput.isBlank()) {
                FilledIconButton(
                    onClick = onStopTask,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("stop_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Task",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            } else {
                FilledIconButton(
                    onClick = {
                        if (promptInput.isNotBlank()) {
                            val cmd = promptInput.trim()
                            promptInput = ""
                            if (uiState.isRunning) {
                                onQueueFollowUp(cmd)
                            } else {
                                onSubmitTask(cmd)
                            }
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("submit_button")
                ) {
                    Icon(
                        imageVector = if (uiState.isRunning) Icons.Default.Queue else Icons.Default.Send,
                        contentDescription = if (uiState.isRunning) "Queue" else "Send",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun ExampleTaskRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun WhatsAppChatBubble(
    message: ChatMessage,
    onOpenDetails: () -> Unit,
    onManageModels: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val timeString = remember(message.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
    }
    val isPreflightFailure = message.badge?.let {
        it.contains("Pre-flight") || it.contains("Check Failed")
    } == true
    val needsProviderAction = isPreflightFailure || message.retryPrompt != null ||
        message.content.contains("configure an API key in Settings", ignoreCase = true) ||
        message.content.contains("API key is not configured", ignoreCase = true) ||
        message.content.contains("cloud API key is missing", ignoreCase = true)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .testTag("chat_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                FormattedMessageView(
                    content = message.content,
                    thoughtText = message.action?.thought,
                    textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (needsProviderAction) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.provider == "LOCAL_SLM" && message.retryPrompt != null ||
                            message.content.contains("local model is missing", ignoreCase = true) ||
                            message.content.contains("install an on-device model", ignoreCase = true) ||
                            message.content.contains("Local model file missing", ignoreCase = true) ||
                            message.content.contains("Download an on-device model", ignoreCase = true)
                        ) {
                            Button(
                                onClick = onManageModels,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_manage_models_btn")
                            ) {
                                Text("Manage Models", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (message.content.contains("settings", ignoreCase = true) ||
                            message.content.contains("API key", ignoreCase = true) ||
                            message.content.contains("Local model file missing", ignoreCase = true)
                        ) {
                            OutlinedButton(
                                onClick = onOpenSettings,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_open_settings_btn")
                            ) {
                                Text("Cloud API", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (message.content.contains("Accessibility Service Disabled", ignoreCase = true)) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_enable_accessibility_btn")
                            ) {
                                Text("Enable", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (!message.retryPrompt.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("doctor_retry_btn")
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (message.badge != null) {
                        Text(
                            text = message.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (message.stepIndex != null) {
                        Text(
                            text = "${message.stepIndex} steps · ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (!isUser && message.role == "assistant" && message.stepIndex != null) {
                        Text(
                            text = "[Details]",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpenDetails() }
                        )
                    }

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingChatBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 320.dp).testTag("chat_generating_bubble")
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (text.isNotBlank()) {
                    FormattedMessageView(
                        content = text,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulsingDot()
                    Text(if (text.isBlank()) "Waiting for an answer..." else "Generating...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(AniobTheme.state.working.accent.copy(alpha = alpha))
    )
}

/**
 * Task completion proof card (PROMPT 5).
 * Renders checklist of what was verified with real checkmarks (✓/✗), honest stats, and action buttons.
 */
@Composable
fun TaskSummaryCard(
    summary: TaskSummary,
    onRunAgain: () -> Unit,
    onViewSteps: () -> Unit,
    onMakeSkill: () -> Unit,
    onRetry: () -> Unit
) {
    val isSuccess = summary.outcome == Outcome.SUCCESS
    val isStopped = summary.outcome == Outcome.STOPPED
    val containerColor = when {
        isSuccess -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        isStopped -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("task_summary_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = when {
                        isSuccess -> Icons.Default.CheckCircle
                        isStopped -> Icons.Default.StopCircle
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = when {
                        isSuccess -> MaterialTheme.colorScheme.primary
                        isStopped -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = when {
                        isSuccess -> "Task Completed"
                        isStopped -> "Stopped by you at step ${summary.steps}"
                        else -> "Task Failed"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (summary.evidence.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    summary.evidence.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (item.met) "✓" else "✗",
                                fontWeight = FontWeight.Bold,
                                color = if (item.met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            val providerLabel = FailureReasonCopy.providerLabel(summary.provider)
            val durationSec = summary.durationMs / 1000.0
            Text(
                text = "${summary.steps} steps · ${String.format(Locale.US, "%.1fs", durationSec)} · $providerLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isSuccess && !isStopped && !summary.reason.isNullOrBlank()) {
                Text(
                    text = FailureReasonCopy.humanize(summary.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSuccess) {
                    Button(
                        onClick = onRunAgain,
                        modifier = Modifier.weight(1f).testTag("summary_run_again")
                    ) {
                        Text("Run again", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onViewSteps,
                        modifier = Modifier.weight(1f).testTag("summary_view_steps")
                    ) {
                        Text("View steps", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (!isStopped) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f).testTag("summary_retry")
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onViewSteps,
                        modifier = Modifier.weight(1f).testTag("summary_view_steps")
                    ) {
                        Text("View steps", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
