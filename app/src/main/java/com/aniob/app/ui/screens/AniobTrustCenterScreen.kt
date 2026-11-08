package com.aniob.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.app.ui.model.SafetyLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniobTrustCenterScreen(
    onBack: () -> Unit,
    safetyLevel: SafetyLevel,
    onSafetyLevelChanged: (SafetyLevel) -> Unit,
    confirmationHistory: List<ConfirmationRecord>,
    vaultEntries: () -> Map<String, String>,
    onForgetVault: (String) -> Unit,
    onEditVault: ((key: String, newAnswer: String) -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trust Center", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("trust_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Safety level radio
            item {
                Card(modifier = Modifier.fillMaxWidth().testTag("trust_safety_level_card")) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Safety Level", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        listOf(SafetyLevel.OFF, SafetyLevel.STANDARD, SafetyLevel.STRICT).forEach { level ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = safetyLevel == level,
                                    onClick = { onSafetyLevelChanged(level) },
                                    modifier = Modifier.testTag("trust_radio_${level.name.lowercase()}")
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(level.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (level) {
                                            SafetyLevel.OFF -> "Skip ordinary-action prompts; still ask before money, secrets, and deletion."
                                            SafetyLevel.STANDARD -> "Also ask before sending, posting, sharing, or calling."
                                            SafetyLevel.STRICT -> "Also ask before other consequential, hard-to-reverse actions."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Confirmation history
            item {
                Text(
                    "Approval History",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (confirmationHistory.isEmpty()) {
                item {
                    Text(
                        "No approvals yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(confirmationHistory, key = { "${it.what}_${it.at}" }) { record ->
                    ConfirmHistoryRow(record)
                }
            }

            // Stored Memory / Vault items (Workstream B / Finding F3)
            item {
                Text(
                    "Stored Preferences & Secrets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val entries = vaultEntries()
            if (entries.isEmpty()) {
                item {
                    Text(
                        "No saved credentials or answers stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(entries.entries.toList(), key = { it.key }) { (key, value) ->
                    Card(modifier = Modifier.fillMaxWidth().testTag("vault_row_$key")) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    key.removePrefix("grill.").removePrefix("vault.").replace('_', ' ').replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (key.contains("key") || key.contains("secret") || key.contains("token")) "••••••••" else value,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onForgetVault(key) },
                                modifier = Modifier.testTag("vault_forget_$key")
                            ) {
                                Text("Forget", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmHistoryRow(record: ConfirmationRecord) {
    val decisionColor = when (record.decision) {
        ConfirmDecision.APPROVE_ONCE, ConfirmDecision.APPROVE_FOR_TASK -> MaterialTheme.colorScheme.primary
        ConfirmDecision.DENY, ConfirmDecision.TIMEOUT_DENY -> MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.what, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("${record.risk.name} · ${formatTime(record.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                record.decision.name.replace('_', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = decisionColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
