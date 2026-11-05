package com.aniob.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.PhaseSnapshot
import com.aniob.core.narration.ThinkingStatus
import kotlinx.coroutines.delay

/**
 * Always-visible answer to "is it thinking or stuck?". A spinner appears ONLY while a model call is really
 * in flight; a rules-only step says "no model used" instead of pretending to think. After
 * [ThinkingStatus.STALL_AFTER_MS] without a first token it offers Stop / Use on-device.
 */
@Composable
fun ThinkingBanner(
    snapshot: PhaseSnapshot,
    onStop: () -> Unit,
    onSwitchToOnDevice: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (snapshot.phase == AgentPhase.IDLE) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.phase, snapshot.sinceMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val headline = ThinkingStatus.headline(snapshot, now)
    val stalled = ThinkingStatus.isStalled(snapshot, now)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("thinking_banner")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = headline
            },
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ThinkingStatus.isModelBusy(snapshot)) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Text(
                ThinkingStatus.badge(snapshot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.thoughtPreview.isNotBlank()) {
                Text(
                    "\u201C${snapshot.thoughtPreview}\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (stalled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.testTag("thinking_stop")) { Text("Stop") }
                    if (onSwitchToOnDevice != null) {
                        OutlinedButton(onClick = onSwitchToOnDevice, modifier = Modifier.testTag("thinking_local")) { Text("Use on-device") }
                    }
                }
            }
        }
    }
}
