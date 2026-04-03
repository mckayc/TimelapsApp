package com.timelapse.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timelapse.app.model.*

@Composable
fun SettingsPanel(
    settings: TimelapseSettings,
    onSettingsChanged: (TimelapseSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "1.0.0"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(fraction = 0.9f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Persistent Header ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Version $versionName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider()

            // ── Scrollable Content ──────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // ── Camera & Extensions ────────────────────────────────────────
                SectionLabel("Camera & Extensions")
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoResolution.entries.forEach { res ->
                        FilterChip(
                            selected = settings.videoResolution == res,
                            onClick = { onSettingsChanged(settings.copy(videoResolution = res)) },
                            label = { Text(res.displayName) }
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.showGrid,
                        onCheckedChange = { onSettingsChanged(settings.copy(showGrid = it)) }
                    )
                    Text("Show Grid Lines", style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.enableExtensions,
                        onCheckedChange = { onSettingsChanged(settings.copy(enableExtensions = it)) }
                    )
                    Text("Enable Camera Extensions (HDR/Night)", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(16.dp))

                // ── Capture interval ────────────────────────────────────────────
                SectionLabel("Capture Interval")
                Spacer(Modifier.height(8.dp))
                
                // Display intervals in a flow/grid-like layout
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val rows = CAPTURE_INTERVALS.chunked(5)
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { secs ->
                                val label = when {
                                    secs < 1f -> "${secs}s"
                                    secs >= 60f -> "${(secs / 60).toInt()}m"
                                    else -> "${secs.toInt()}s"
                                }
                                FilterChip(
                                    selected = settings.intervalSeconds == secs,
                                    onClick  = { onSettingsChanged(settings.copy(intervalSeconds = secs)) },
                                    label    = { Text(label) }
                                )
                            }
                        }
                    }
                }

                // Speed calculation and explanation
                Spacer(Modifier.height(8.dp))
                IntervalExplanation(settings.intervalSeconds, settings.outputFps)

                Spacer(Modifier.height(20.dp))

                // ── Video Quality (Bitrate) ─────────────────────────────────────
                SectionLabel("Video Quality")
                Spacer(Modifier.height(4.dp))
                VideoQuality.entries.forEach { quality ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = settings.videoQuality == quality,
                            onClick  = { onSettingsChanged(settings.copy(videoQuality = quality)) }
                        )
                        Text(
                            text = quality.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Auto-Stop ───────────────────────────────────────────────────
                SectionLabel("Auto-Stop")
                Spacer(Modifier.height(4.dp))
                AutoStopType.entries.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.autoStopType == type,
                            onClick = { onSettingsChanged(settings.copy(autoStopType = type)) }
                        )
                        Text(type.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (settings.autoStopType != AutoStopType.NONE) {
                    OutlinedTextField(
                        value = if (settings.autoStopValue == 0) "" else settings.autoStopValue.toString(),
                        onValueChange = { 
                            val value = it.toIntOrNull() ?: 0
                            onSettingsChanged(settings.copy(autoStopValue = value))
                        },
                        label = { Text("Value") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))
                
                // ── Power & Battery ─────────────────────────────────────────────
                SectionLabel("Power & Screen")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = settings.batterySaver,
                        onCheckedChange = { onSettingsChanged(settings.copy(batterySaver = it)) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Battery Saver (Blackout screen)", style = MaterialTheme.typography.bodyMedium)
                }

                if (settings.batterySaver) {
                    Spacer(Modifier.height(8.dp))
                    Text("Blackout timeout:", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BATTERY_SAVER_TIMEOUTS.forEach { secs ->
                            FilterChip(
                                selected = settings.batterySaverTimeoutSeconds == secs,
                                onClick  = { onSettingsChanged(settings.copy(batterySaverTimeoutSeconds = secs)) },
                                label    = { Text("${secs}s") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Overlay type ────────────────────────────────────────────────
                SectionLabel("Text Overlay")
                Spacer(Modifier.height(4.dp))
                OverlayType.entries.forEach { type ->
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
                        label = { Text("Custom Text") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (settings.overlayType != OverlayType.NONE) {
                    Spacer(Modifier.height(16.dp))
                    
                    // Text Size
                    SectionLabel("Text Size: ${settings.overlayTextSizeSp.toInt()} sp")
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

                // Add padding to avoid being covered by system navigation bars
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun IntervalExplanation(intervalSeconds: Float, fps: Int) {
    val speedFactor = (intervalSeconds * fps).toInt()
    val recordingTimeMinutes = 10
    val finalDurationSeconds = (recordingTimeMinutes * 60) / (intervalSeconds * fps)
    
    val exampleText = when {
        intervalSeconds < 1f -> "Ideal for very fast action like sports or busy traffic."
        intervalSeconds <= 2f -> "Great for moving clouds, people walking, or city streets."
        intervalSeconds <= 5f -> "Perfect for slow moving clouds, sunsets, or shadows."
        intervalSeconds <= 15f -> "Best for stars, growing plants, or construction."
        else -> "Used for long-term projects like building or changing seasons."
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Recording 10m → Final video: ${finalDurationSeconds.toInt()}s",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Speed: ${speedFactor}x real time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = exampleText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
