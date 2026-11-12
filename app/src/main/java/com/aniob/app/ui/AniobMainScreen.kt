package com.aniob.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.aniob.app.system.SystemState
import com.aniob.app.ui.screens.*
import com.aniob.app.ui.theme.AniobMotion
import com.aniob.core.narration.FailureReasonCopy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Root screen. Navigation is owned by the ViewModel's back-stack (UX-0 §2), so rotation keeps the
 * screen and Back never exits from a sub-screen. CHAT uses double-back-to-exit with a toast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobMainScreen(viewModel: AniobViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack by viewModel.navBackStack.collectAsStateWithLifecycle()
    val route = backStack.lastOrNull() ?: AniobRoute.CHAT
    val app = LocalContext.current.applicationContext as com.aniob.app.AniobApplication
    val systemState by app.systemState.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showWhyModelDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showPreviousChats by remember { mutableStateOf(false) }
    var lastBackPress by remember { mutableStateOf(0L) }
    var reducedMotion by remember { mutableStateOf(false) }

    // UX-7: honour the platform reduced-motion accessibility setting (static variants).
    LaunchedEffect(Unit) {
        reducedMotion = AniobMotion.isReducedMotionEnabled(context)
    }

    // UX-0: BackHandler only pops the stack; double-back exits only when already at root CHAT.
    BackHandler {
        if (viewModel.navigateBack()) {
            Unit
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackPress < DOUBLE_BACK_MS) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackPress = now
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(animationSpec = AniobMotion.instant()) togetherWith fadeOut(animationSpec = AniobMotion.instant())
            } else {
                (fadeIn(AniobMotion.navSpec()) + slideInHorizontally(AniobMotion.navSpec()) { it / 8 }) togetherWith
                    (fadeOut(AniobMotion.navSpec()) + slideOutHorizontally(AniobMotion.navSpec()) { -it / 8 })
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "nav"
    ) { current ->
        when (current) {
            AniobRoute.CHAT -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "Aniob",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when {
                                        uiState.isPaused -> "Paused"
                                        uiState.isRunning -> "Working · step ${uiState.currentStep}"
                                        else -> "Ready"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isRunning) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            val activeBrainLabel = viewModel.getActiveBrainLabel()
                            val modeLabel = when (uiState.autoRouterMode.lowercase()) {
                                "local-first" -> "Local-first"
                                "local-only" -> "On-device"
                                "cloud-only" -> "Cloud"
                                "balanced" -> "Balanced"
                                else -> "Auto"
                            }
                            AssistChip(
                                onClick = { showWhyModelDialog = true },
                                label = { Text("$modeLabel · $activeBrainLabel") },
                                leadingIcon = {
                                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("model_selector_chip")
                            )

                            IconButton(
                                onClick = { showPreviousChats = true },
                                modifier = Modifier.testTag("history_button")
                            ) {
                                Icon(Icons.Default.History, contentDescription = "Previous chats")
                            }

                            IconButton(
                                onClick = { showClearChatDialog = true },
                                modifier = Modifier.testTag("clear_chat_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear chat")
                            }

                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier.testTag("main_overflow_menu_btn")
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    MenuEntry("History", Icons.Default.History, "menu_history") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.HISTORY)
                                    }
                                    MenuEntry("Stats & progress", Icons.Default.BarChart, "menu_stats") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.STATS)
                                    }
                                    MenuEntry("Skills", Icons.Default.AutoAwesome, "menu_skills") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.SKILLS)
                                    }
                                    MenuEntry("Trust Center", Icons.Default.Shield, "menu_trust") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.TRUST)
                                    }
                                    MenuEntry("On-device models", Icons.Default.Memory, "menu_models") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.MODELS)
                                    }
                                    MenuEntry("Settings", Icons.Default.Settings, "menu_settings") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.SETTINGS)
                                    }
                                    MenuEntry("Setup & Permissions", Icons.Default.VerifiedUser, "menu_onboarding") {
                                        showOverflowMenu = false; viewModel.navigateTo(AniobRoute.ONBOARDING)
                                    }
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    AniobChatScreen(
                        uiState = uiState,
                        systemState = systemState,
                        onSubmitTask = { viewModel.submitTask(it) },
                        onStopTask = { viewModel.stopCurrentTask() },
                        onOutcomeDecision = { viewModel.onOutcomeDecision(it) },
                        onPauseToggle = { viewModel.togglePause() },
                        onShowTrackerSheet = { viewModel.setShowTrackerSheet(it) },
                        onFilterChanged = { viewModel.setTrackerFilter(it) },
                        onManageModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                        onOpenSetup = { viewModel.navigateTo(AniobRoute.ONBOARDING) },
                        onOpenSkills = { viewModel.navigateTo(AniobRoute.SKILLS) },
                        onDismissInterrupted = { viewModel.dismissInterruptedSummary() },
                        onDismissRecovery = { viewModel.dismissRecoveryBanner() },
                        onRunAgain = { prompt -> viewModel.submitTask(prompt) },
                        onViewSteps = { viewModel.setShowTrackerSheet(true) },
                        onMomentDismiss = { viewModel.clearMomentChips() },
                        onQueueFollowUp = { viewModel.queueFollowUp(it) },
                        onCancelQueuedTask = { viewModel.cancelQueuedFollowUp() },
                        onStartQueuedTask = { viewModel.startQueuedTask() },
                        onWorkflowMarkComplete = { taskId, notes -> viewModel.onWorkflowMarkComplete(taskId, notes) },
                        onWorkflowCancel = { taskId -> viewModel.onWorkflowCancel(taskId) },
                        onContinueInWebsite = { taskId -> viewModel.onContinueInWebsite(taskId) },
                        onConsumePendingPrefill = { viewModel.consumePendingInputPrefill() },
                        onOpenSettings = { viewModel.navigateTo(AniobRoute.SETTINGS) },
                        onGrillConfirm = { answers, rememberKeys -> viewModel.onGrillAnswersSubmitted(answers, rememberKeys) },
                        onGrillCancel = { viewModel.cancelGrillMe() },
                        renderWindow = { viewModel.renderWindow() }
                    )
                }
            }

            AniobRoute.HISTORY -> AniobHistoryScreen(
                uiState = uiState,
                onBack = { viewModel.navigateBack() },
                onNavigateToStats = { viewModel.navigateTo(AniobRoute.STATS) },
                onRunAgain = { prompt -> viewModel.resetToChat(); viewModel.submitTask(prompt) },
                onMakeSkill = { prompt ->
                    viewModel.startTeachFlow(prompt)
                    viewModel.navigateTo(AniobRoute.SKILLS)
                },
                onSelectSession = { session ->
                    viewModel.setPromptPrefill(session.userPrompt)
                }
            )

            AniobRoute.STATS -> AniobStatsScreen(
                uiState = uiState,
                onBack = { viewModel.navigateBack() },
                onResearchOverride = { success -> viewModel.overrideResearchOutcome(success) },
                onClearResearch = { viewModel.clearResearchData() }
            )

            AniobRoute.MODELS -> AniobModelDownloadScreen(
                onBack = { viewModel.navigateBack() },
                uiState = uiState,
                onSaveCloudSettings = { key, model, url -> viewModel.updateSettings(key, model, url) },
                onTestCloudConnection = { key, url, model -> viewModel.testOmniRouteSettings(key, url, model) },
                onOpenSettings = { viewModel.navigateTo(AniobRoute.SETTINGS) }
            )

            AniobRoute.SETTINGS -> AniobSettingsScreen(
                uiState = uiState,
                onSaveSettings = { key, model, address -> viewModel.saveCloudSettings(key, model, address) },
                onTestConnection = { key, address -> viewModel.testCloudConnection(key, address) },
                onCloudInputChanged = { viewModel.clearCloudTestResult() },
                onAutoRouterModeChanged = { mode -> viewModel.setAutoRouterMode(mode) },
                onAblationModeChanged = { mode -> viewModel.setAblationMode(mode) },
                onFeaturesChanged = { viewModel.setFeatureSelection(it) },
                onStudyPassChanged = { pass -> viewModel.setStudyPass(pass) },
                onSeedRememberCache = { viewModel.seedRememberPresets() },
                onFastPathChanged = { viewModel.setFastPathEnabled(it) },
                onBack = { viewModel.navigateBack() },
                onNavigateToModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                onNavigateToStats = { viewModel.navigateTo(AniobRoute.STATS) },
                onNavigateToOnboarding = { viewModel.navigateTo(AniobRoute.ONBOARDING) },
                onNavigateToTrust = { viewModel.navigateTo(AniobRoute.TRUST) }
            )

            AniobRoute.ONBOARDING -> AniobSetupConciergeScreen(
                systemState = systemState,
                cloudReady = systemState.cloudReady,
                onBack = { viewModel.navigateBack() },
                onNavigateToModels = { viewModel.navigateTo(AniobRoute.MODELS) },
                onRefresh = { app.systemState.refresh() },
                onRunDemoTask = {
                    viewModel.resetToChat()
                    viewModel.submitTask("Open Settings")
                },
                onNavigateToSettings = { viewModel.navigateTo(AniobRoute.SETTINGS) }
            )

            AniobRoute.SKILLS -> AniobSkillsScreen(
                uiState = uiState,
                skills = viewModel.getLoadedSkills(),
                onBack = { viewModel.navigateBack() },
                onTeach = { viewModel.startTeachFlow(null) },
                onReplay = { prompt -> viewModel.resetToChat(); viewModel.submitTask(prompt) },
                onToggleSkill = { name, enabled -> viewModel.setSkillEnabled(name, enabled) },
                onDeleteSkill = { name -> viewModel.deleteSkill(name) },
                vaultEntries = { viewModel.vaultEntries() },
                onForgetVault = { key -> viewModel.forgetVaultEntry(key) }
            )

            AniobRoute.TRUST -> AniobTrustCenterScreen(
                onBack = { viewModel.navigateBack() },
                safetyLevel = uiState.safetyLevel,
                onSafetyLevelChanged = { viewModel.setSafetyLevel(it) },
                confirmationHistory = uiState.confirmationHistory,
                vaultEntries = { viewModel.vaultEntries() },
                onForgetVault = { key -> viewModel.forgetVaultEntry(key) },
                onEditVault = { key, newAnswer -> viewModel.editVaultEntry(key, newAnswer) }
            )
        }
    }

    // UX-2/UX-5: Remembered Moments + skill replay chips surface in chat, not logs.
    if (uiState.momentChips.isNotEmpty()) {
        MomentChipRow(chips = uiState.momentChips, onDismiss = { viewModel.clearMomentChips() })
    }

    // Clarifying questions render as an inline card on the chat screen (above the composer).
    // Bring that screen forward so a question raised while another screen is open is not missed.
    LaunchedEffect(uiState.showGrillMeSheet, uiState.grillMeResult) {
        if (uiState.showGrillMeSheet && uiState.grillMeResult != null && route != AniobRoute.CHAT) {
            viewModel.resetToChat()
        }
    }

    val confirmRequest = uiState.confirmRequest
    if (confirmRequest != null) {
        ConfirmSheet(
            request = confirmRequest,
            onDecision = { decision -> viewModel.resolveConfirmation(decision, confirmRequest.taskId, confirmRequest.id) }
        )
    }

    val workflowState by app.workflowCoordinator.workflowState.collectAsState()

    when (val state = workflowState) {
        is com.aniob.core.workflow.WorkflowState.NeedsChoice -> {
            androidx.compose.ui.window.Dialog(onDismissRequest = {
                app.workflowCoordinator.updateState(com.aniob.core.workflow.WorkflowState.Cancelled(null, "User dismissed chooser"))
            }) {
                com.aniob.app.ui.workflow.DestinationChooserCard(
                    candidates = state.candidates,
                    onSelectDestination = { dest ->
                        viewModel.onDestinationChosen(dest, state.prompt)
                    },
                    onCustomUrlEntered = { url ->
                        viewModel.onCustomUrlChosen(url, state.prompt)
                    },
                    onCancel = {
                        app.workflowCoordinator.updateState(com.aniob.core.workflow.WorkflowState.Cancelled(null, "User cancelled chooser"))
                    }
                )
            }
        }
        else -> {}
    }

    if (showPreviousChats) {
        val savedChats by viewModel.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
        com.aniob.app.ui.chat.PreviousChatsSheet(
            conversations = savedChats,
            canSwitch = !uiState.isRunning,
            onOpen = { id -> showPreviousChats = false; viewModel.openConversation(id) },
            onDelete = { id -> viewModel.deleteConversation(id) },
            onDismiss = { showPreviousChats = false }
        )
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            modifier = Modifier.testTag("clear_chat_dialog"),
            title = { Text("Clear this chat?") },
            text = {
                Text(if (uiState.isRunning) {
                    "This will stop the current task and clear the conversation. Changes already made on your device will not be undone."
                } else {
                    "This clears the conversation from this screen. Saved history is not deleted."
                })
            },
            confirmButton = {
                TextButton(onClick = { showClearChatDialog = false; viewModel.clearChat() }, modifier = Modifier.testTag("clear_chat_confirm")) {
                    Text(if (uiState.isRunning) "Stop and clear" else "Clear chat")
                }
            },
            dismissButton = { TextButton(onClick = { showClearChatDialog = false }) { Text("Cancel") } }
        )
    }

    if (showWhyModelDialog) {
        val installedModel = viewModel.getInstalledModelName() ?: "No local model installed"
        val cloudReady = systemState.cloudReady
        val reason = viewModel.getModelChoiceReason()
        AlertDialog(
            onDismissRequest = { showWhyModelDialog = false },
            title = { Text("How Aniob is thinking") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode: ${FailureReasonCopy.providerLabel(uiState.autoRouterMode)}", fontWeight = FontWeight.Bold)
                    Text("Brain: $installedModel")
                    Text("Cloud: " + if (cloudReady) "configured (${viewModel.getActiveBrainLabel()})" else "not set up")
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showWhyModelDialog = false
                            viewModel.navigateTo(AniobRoute.SETTINGS)
                        },
                        modifier = Modifier.testTag("why_model_cloud_settings_btn")
                    ) {
                        Text("Cloud setup")
                    }
                    Button(onClick = {
                        showWhyModelDialog = false
                        viewModel.navigateTo(AniobRoute.MODELS)
                    }) {
                        Text("Manage brains")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showWhyModelDialog = false }) { Text("Dismiss") }
            }
        )
    }
}

@Composable
private fun MenuEntry(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag)
    )
}

/** Compact chip row for Remembered Moments / skill replay (UX-5 §2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentChipRow(chips: List<com.aniob.app.ui.model.MomentChip>, onDismiss: () -> Unit) {
    // Rendered as a transient overlay row above the chat input.
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp)
                .testTag("moment_chip_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { chip ->
                AssistChip(
                    onClick = onDismiss,
                    label = { Text(chip.label) },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("moment_chip_${chip.id}")
                )
            }
        }
    }
}

private const val DOUBLE_BACK_MS = 2000L

