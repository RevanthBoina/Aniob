package com.aniob.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.research.AblationMode
import com.aniob.app.research.StudyPass
import com.aniob.app.ui.AniobUiState
import com.aniob.app.ui.CloudTestResult
import com.aniob.app.ui.components.BannerKind
import com.aniob.app.ui.components.StatusBanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobSettingsScreen(
    uiState: AniobUiState,
    onSaveSettings: (String, String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onCloudInputChanged: () -> Unit = {},
    onAutoRouterModeChanged: (String) -> Unit = {},
    onAblationModeChanged: (AblationMode) -> Unit = {},
    onStudyPassChanged: (StudyPass) -> Unit = {},
    onSeedRememberCache: () -> Unit = {},
    onFastPathChanged: (Boolean) -> Unit = {},
    onSafetyGateChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToOnboarding: () -> Unit = {},
    onNavigateToTrust: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var apiKeyInput by remember { mutableStateOf(uiState.omnirouteApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var baseUrlInput by remember { mutableStateOf(uiState.omnirouteBaseUrl) }
    var selectedModel by remember { mutableStateOf(uiState.omnirouteModel) }
    var autoRouterMode by remember { mutableStateOf(uiState.autoRouterMode) }
    var showAdvancedDetails by remember { mutableStateOf(false) }

    // A save can normalise the address/model (trim, blank → auto); mirror that back into the fields.
    LaunchedEffect(uiState.omnirouteBaseUrl) { baseUrlInput = uiState.omnirouteBaseUrl }
    LaunchedEffect(uiState.omnirouteModel) { selectedModel = uiState.omnirouteModel }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // AutoRouter Mode Section (Phase 3.3)
            Card(modifier = Modifier.fillMaxWidth().testTag("card_autorouter_mode")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "AutoRouter Execution Policy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Controls whether tasks execute locally on-device or escalate to OmniRoute cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val modes = listOf(
                        Triple("auto", "Auto (Recommended)", "Smart local-first, cloud when battery/vision requires"),
                        Triple("local-first", "Local-First", "Prefer on-device, cloud only for vision/complex"),
                        Triple("local-only", "Local-Only", "Never use cloud, offline mode, fail if impossible"),
                        Triple("cloud-only", "Cloud-Only", "Always use cloud (OmniRoute)"),
                        Triple("balanced", "Balanced", "Local for simple tasks, cloud for research/creative")
                    )

                    modes.forEach { (modeKey, modeTitle, modeDesc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autoRouterMode = modeKey
                                    onAutoRouterModeChanged(modeKey)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (autoRouterMode == modeKey),
                                onClick = {
                                    autoRouterMode = modeKey
                                    onAutoRouterModeChanged(modeKey)
                                },
                                modifier = Modifier.testTag("radio_mode_$modeKey")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(modeTitle, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(modeDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // Research ablation selector: the condition the next measured task runs under.
            Card(modifier = Modifier.fillMaxWidth().testTag("card_ablation_mode")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Research mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Pick the condition before a test batch. It changes how the agent works, not what it can do.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    AblationMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAblationModeChanged(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.ablationMode == mode,
                                onClick = { onAblationModeChanged(mode) },
                                modifier = Modifier.testTag("radio_ablation_${mode.id}")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.label,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "Study pass",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StudyPass.entries.forEach { pass ->
                            FilterChip(
                                selected = uiState.studyPass == pass,
                                onClick = { onStudyPassChanged(pass) },
                                label = { Text(pass.label) },
                                modifier = Modifier.testTag("radio_study_pass_${pass.id.lowercase()}")
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onSeedRememberCache,
                        modifier = Modifier.fillMaxWidth().testTag("btn_seed_remember_cache")
                    ) {
                        Text("Seed Remember Cache from Presets")
                    }

                    Text(
                        text = "Warm replay needs a filled cache. Seeding writes the first hop of each of the 15 benchmark tasks, so FULL_HUMAN_SPEED can be measured without a cold batch first; the steps after it are still learned from verified runs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Each finished task is appended to research_runs.csv; averages are kept in research_summary_table.csv inside the app's files folder.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Navigation Shortcuts Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("System & Performance", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                    OutlinedButton(
                        onClick = onNavigateToModels,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_models")
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("On-Device AI Models (Phi-4, Gemma, Llama)")
                    }

                    OutlinedButton(
                        onClick = onNavigateToStats,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_stats")
                    ) {
                        Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Performance & Diagnostics Audit")
                    }

                    OutlinedButton(
                        onClick = onNavigateToOnboarding,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_onboarding")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Setup & Permissions Checklist")
                    }
                }
            }

            // Sign in with an account (hidden unless this build is configured for it).
            AniobAccountSignInCard()

            // Cloud AI (self-hosted OmniRoute gateway) setup.
            val cloudTest = uiState.cloudTest
            val addressError = if (baseUrlInput.isBlank()) null
                else AniobProviderConfig.validateBaseUrl(baseUrlInput)
            Card(modifier = Modifier.fillMaxWidth().testTag("omniroute_config_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Cloud AI (OmniRoute)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Add the key from your OmniRoute dashboard for fast answers and cloud automation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when (cloudTest.result) {
                        CloudTestResult.SAVED -> StatusBanner(
                            kind = BannerKind.SUCCESS,
                            title = "Saved",
                            body = cloudTest.message,
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                        CloudTestResult.SUCCESS -> StatusBanner(
                            kind = BannerKind.SUCCESS,
                            title = "Connected",
                            body = cloudTest.message,
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                        CloudTestResult.BAD_KEY -> StatusBanner(
                            kind = BannerKind.ERROR,
                            title = "Key rejected",
                            body = cloudTest.message,
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                        CloudTestResult.UNREACHABLE -> StatusBanner(
                            kind = BannerKind.ERROR,
                            title = "Gateway unreachable",
                            body = cloudTest.message,
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                        CloudTestResult.INVALID_SETUP -> StatusBanner(
                            kind = BannerKind.WARNING,
                            title = "Check these details",
                            body = cloudTest.message,
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                        CloudTestResult.NONE -> Unit
                    }
                    if (cloudTest.testing) {
                        StatusBanner(
                            kind = BannerKind.INFO,
                            title = "Testing connection…",
                            body = "Asking the gateway for one short reply.",
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                    }
                    if (cloudTest.result == CloudTestResult.NONE &&
                        apiKeyInput.isNotBlank() &&
                        baseUrlInput.isBlank()
                    ) {
                        // Migration path: an older build could store a key without an address.
                        StatusBanner(
                            kind = BannerKind.WARNING,
                            title = "One more step",
                            body = "Add the gateway address to finish cloud setup.",
                            modifier = Modifier.testTag("banner_cloud_status")
                        )
                    }

                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = {
                            baseUrlInput = it
                            onCloudInputChanged()
                        },
                        label = { Text("Gateway address") },
                        placeholder = { Text("http://192.168.1.20:20128/v1") },
                        supportingText = {
                            Text(
                                addressError
                                    ?: "Where OmniRoute runs. Plain http is accepted on your local network only."
                            )
                        },
                        isError = addressError != null,
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_base_url"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            onCloudInputChanged()
                        },
                        label = { Text("OmniRoute key") },
                        placeholder = { Text("Paste the key from your dashboard") },
                        visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (apiKeyVisible) "Hide secret key" else "Reveal secret key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_key"),
                        singleLine = true
                    )

                    Text(
                        text = "Routing model",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AniobProviderConfig.OMNIROUTE_MODEL_PRESETS.chunked(2).forEach { rowPresets ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowPresets.forEach { preset ->
                                    FilterChip(
                                        selected = selectedModel.trim() == preset,
                                        onClick = {
                                            selectedModel = preset
                                            onCloudInputChanged()
                                        },
                                        label = { Text(modelPresetLabel(preset)) },
                                        modifier = Modifier.testTag("chip_${preset.replace('/', '_')}")
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {
                            selectedModel = it
                            onCloudInputChanged()
                        },
                        label = { Text("Model name") },
                        supportingText = {
                            Text("auto lets OmniRoute choose. Any provider/model name from the dashboard also works.")
                        },
                        modifier = Modifier.fillMaxWidth().testTag("input_omniroute_model"),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onTestConnection(apiKeyInput, baseUrlInput) },
                            enabled = !cloudTest.testing,
                            modifier = Modifier.weight(1f).testTag("btn_test_omniroute")
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (cloudTest.testing) "Testing…" else "Test")
                        }
                        Button(
                            onClick = { onSaveSettings(apiKeyInput, selectedModel, baseUrlInput) },
                            enabled = !cloudTest.testing,
                            modifier = Modifier.weight(1f).testTag("save_settings_button")
                        ) {
                            Text("Save")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showAdvancedDetails = !showAdvancedDetails }) {
                            Text(if (showAdvancedDetails) "Hide details" else "Details")
                        }
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(OMNIROUTE_HELP_URL)).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.testTag("btn_omniroute_help")
                        ) {
                            Text("Setup guide")
                        }
                    }

                    if (showAdvancedDetails) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Protocol: OpenAI-compatible chat completions", style = MaterialTheme.typography.labelSmall)
                                Text("Transport: HTTPS anywhere, or plain http on your own network", style = MaterialTheme.typography.labelSmall)
                                Text("Key storage: encrypted with the Android Keystore; sent only to the address above", style = MaterialTheme.typography.labelSmall)
                                Text("Vision: a screenshot is attached only when a task needs it, never when a secret field is on screen", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // On-Device SLM & Optimization Settings
            Card(modifier = Modifier.fillMaxWidth().testTag("ondevice_config_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "On-Device SLM & Automation Policy",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (showAdvancedDetails) {
                        Text(
                            text = "Model Engine: On-device LiteRT-LM (.litertlm, in-process)\nNo Ollama or localhost server required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Reuse verified tasks", fontWeight = FontWeight.SemiBold)
                            Text("0-token instant execution for previously verified workflows", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.fastPathEnabled,
                            onCheckedChange = { onFastPathChanged(it) },
                            modifier = Modifier.testTag("switch_fastpath")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ask before actions", fontWeight = FontWeight.SemiBold)
                            Text("Require approval before sensitive or high-risk actions", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = uiState.safetyGateEnabled,
                            onCheckedChange = { onSafetyGateChanged(it) },
                            modifier = Modifier.testTag("switch_safety")
                        )
                    }

                    OutlinedButton(
                        onClick = onNavigateToTrust,
                        modifier = Modifier.fillMaxWidth().testTag("btn_goto_trust")
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Authoritative Safety & Policy Controls")
                    }
                }
            }

            // OEM Battery Optimization deep-link (Phase 1.7)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Battery & Background Resilience", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Disable battery optimization so Xiaomi, Samsung, OnePlus do not terminate Aniob mid-execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.align(Alignment.End).testTag("battery_deep_link_btn")
                    ) {
                        Text("Open Battery Settings")
                    }
                }
            }

            // Preferred Destinations & Browser
            val app = context.applicationContext as? com.aniob.app.AniobApplication
            Card(modifier = Modifier.fillMaxWidth().testTag("card_preferred_destinations")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Preferred Destinations & Browser", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Customize default website builder and preferred Custom Tabs browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                app?.destinationPreferences?.clearAll()
                            }
                        },
                        modifier = Modifier.testTag("btn_reset_destinations")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset destination preferences")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Human labels for the OmniRoute model presets; the value sent on the wire is the raw id. */
private fun modelPresetLabel(preset: String): String = when (preset) {
    "auto" -> "Auto"
    "auto/coding" -> "Coding"
    "auto/fast" -> "Fast"
    "auto/cheap" -> "Cheap"
    else -> preset
}

/** Setup instructions live with the project the key comes from. */
private const val OMNIROUTE_HELP_URL = "https://github.com/diegosouzapw/OmniRoute"
