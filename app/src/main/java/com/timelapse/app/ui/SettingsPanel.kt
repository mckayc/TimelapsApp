package com.timelapse.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timelapse.app.model.*

@Composable
fun SettingsPanel(
    settings: TimelapseSettings,
    onSettingsChanged: (TimelapseSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(fraction = 0.75f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

            // ── Capture interval ────────────────────────────────────────────
            SectionLabel("Capture Interval")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CAPTURE_INTERVALS.forEach { secs ->
                    val label = if (secs >= 60) "${secs / 60}m" else "${secs}s"
                    FilterChip(
                        selected = settings.intervalSeconds == secs,
                        onClick  = { onSettingsChanged(settings.copy(intervalSeconds = secs)) },
                        label    = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Overlay type ────────────────────────────────────────────────
            SectionLabel("Text Overlay")
            Spacer(Modifier.height(4.dp))
            OverlayType.values().forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(
                        selected = settings.overlayType == type,
                        onClick  = { onSettingsChanged(settings.copy(overlayType = type)) }
                    )
                    Text(
                        text = type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Custom text field — only when STATIC_TEXT is selected
            if (settings.overlayType == OverlayType.STATIC_TEXT) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.overlayText,
                    onValueChange = { onSettingsChanged(settings.copy(overlayText = it)) },
                    label = { Text("Your text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Overlay sub-options — only when overlay is active
            if (settings.overlayType != OverlayType.NONE) {
                Spacer(Modifier.height(20.dp))

                // Text size slider
                SectionLabel("Text Size: ${settings.overlayTextSizeSp.toInt()}sp")
                Slider(
                    value = settings.overlayTextSizeSp,
                    onValueChange = { onSettingsChanged(settings.copy(overlayTextSizeSp = it)) },
                    valueRange = 14f..80f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Position
                SectionLabel("Text Position")
                Spacer(Modifier.height(4.dp))

                // Render positions in a visual 3-column grid matching screen corners
                val topRow    = listOf(OverlayPosition.TOP_LEFT, OverlayPosition.TOP_CENTER,    OverlayPosition.TOP_RIGHT)
                val bottomRow = listOf(OverlayPosition.BOTTOM_LEFT, OverlayPosition.BOTTOM_CENTER, OverlayPosition.BOTTOM_RIGHT)

                listOf(topRow, bottomRow).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { pos ->
                            FilterChip(
                                selected = settings.overlayPosition == pos,
                                onClick  = { onSettingsChanged(settings.copy(overlayPosition = pos)) },
                                label    = { Text(pos.displayName, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Output FPS ──────────────────────────────────────────────────
            SectionLabel("Output Video FPS")
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(24, 30, 60).forEach { fps ->
                    FilterChip(
                        selected = settings.outputFps == fps,
                        onClick  = { onSettingsChanged(settings.copy(outputFps = fps)) },
                        label    = { Text("${fps} fps") }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}
