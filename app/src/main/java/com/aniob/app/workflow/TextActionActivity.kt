package com.aniob.app.workflow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniob.app.AniobApplication
import com.aniob.core.workflow.WorkflowLimits

/**
 * TextActionActivity: Handles PROCESS_TEXT intent from external apps.
 *
 * Invariant: Preserves caller relationship; does not set FLAG_ACTIVITY_NEW_TASK.
 * In Stage 1, provides text handoff to web/apps and safe cancellation.
 */
class TextActionActivity : ComponentActivity() {

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val boundedText = WorkflowPayloadStore.boundUtf8Bytes(rawText, WorkflowLimits.TEXT_BRIEF_MAX_BYTES)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = "Aniob Text Action",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = boundedText.ifBlank { "(No text selected)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 6
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            setResult(Activity.RESULT_CANCELED)
                                            finish()
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = {
                                            val app = application as AniobApplication
                                            app.platformTools.copyToClipboard("Selected Text", boundedText)
                                            val browserPkg = app.platformTools.findCustomTabsBrowser()
                                            val browserIntent = app.platformTools.buildCustomTabsIntent(
                                                url = "https://chatgpt.com/",
                                                browserPackage = browserPkg
                                            )
                                            try {
                                                startActivity(browserIntent)
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                android.widget.Toast.makeText(
                                                    this@TextActionActivity,
                                                    "Copied to clipboard; no browser installed",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            setResult(Activity.RESULT_CANCELED)
                                            finish()
                                        }
                                    ) {
                                        Text("Handoff to Web")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
