package com.timelapse.app.ui

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.timelapse.app.model.CameraOption
import com.timelapse.app.viewmodel.MainViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: MainViewModel) {

    // Request camera + storage permissions upfront
    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        CameraContent(viewModel = viewModel)
    } else {
        PermissionsGate(onRequest = { permissionsState.launchMultiplePermissionRequest() })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main camera UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CameraContent(viewModel: MainViewModel) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState       = viewModel.uiState
    val settings      = viewModel.settings

    // We store the PreviewView ref so the camera re-binds when settings change
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // Fetch CameraProvider once
    LaunchedEffect(Unit) {
        cameraProvider = suspendCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).addListener(
                { cont.resume(ProcessCameraProvider.getInstance(context).get()) },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    // Re-bind camera whenever provider, preview view, or camera choice changes
    LaunchedEffect(cameraProvider, previewViewRef, settings.cameraOption) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pv       = previewViewRef ?: return@LaunchedEffect

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pv.surfaceProvider)
        }

        val selector = if (settings.cameraOption == CameraOption.BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            viewModel.setImageCapture(imageCapture)
        } catch (e: Exception) {
            // front camera may not exist on all devices
        }
    }

    // Main layout — camera preview fills the screen
    Box(modifier = Modifier.fillMaxSize()) {

        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewViewRef = it }
            },
            modifier = Modifier.fillMaxSize(),
            update   = { previewViewRef = it }
        )

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings button (hidden while capturing)
            if (!uiState.isCapturing && !uiState.isEncoding) {
                CircleIconButton(
                    icon        = Icons.Default.Settings,
                    description = "Open settings",
                    onClick     = { viewModel.toggleSettings() }
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }

            // REC badge
            if (uiState.isCapturing) {
                RecBadge(frameCount = uiState.frameCount, elapsed = uiState.elapsedSeconds)
            }

            // Camera flip button
            if (!uiState.isCapturing && !uiState.isEncoding) {
                CircleIconButton(
                    icon        = Icons.Default.Cameraswitch,
                    description = "Switch camera",
                    onClick     = {
                        val next = if (settings.cameraOption == CameraOption.BACK)
                            CameraOption.FRONT else CameraOption.BACK
                        viewModel.updateSettings(settings.copy(cameraOption = next))
                    }
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.isCapturing && !uiState.isEncoding) {
                // Interval pill
                Text(
                    text = "Every ${intervalLabel(settings.intervalSeconds)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
                Spacer(Modifier.height(18.dp))
            }

            // Main capture / stop button
            when {
                uiState.isEncoding -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Encoding video…",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                uiState.isCapturing -> {
                    // STOP button
                    Button(
                        onClick = { viewModel.stopCapture() },
                        modifier = Modifier.size(80.dp),
                        shape    = CircleShape,
                        colors   = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop capture",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                else -> {
                    // START button
                    Button(
                        onClick = { viewModel.startCapture() },
                        modifier = Modifier.size(80.dp),
                        shape    = CircleShape,
                        colors   = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = "Start capture",
                            tint = Color.Red,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }

        // ── Settings panel (slide up from bottom) ────────────────────────────
        AnimatedVisibility(
            visible = uiState.showSettings,
            enter   = slideInVertically(initialOffsetY = { it }),
            exit    = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SettingsPanel(
                settings         = settings,
                onSettingsChanged = { viewModel.updateSettings(it) },
                onDismiss        = { viewModel.toggleSettings() }
            )
        }

        // ── Dialogs ──────────────────────────────────────────────────────────

        uiState.errorMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                icon    = { Icon(Icons.Default.ErrorOutline, contentDescription = null) },
                title   = { Text("Something went wrong") },
                text    = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                }
            )
        }

        uiState.outputPath?.let { path ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissCompletion() },
                icon  = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                title = { Text("Timelapse saved!") },
                text  = {
                    Text(
                        "Saved ${uiState.frameCount} frames to:\n$path",
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissCompletion() }) { Text("OK") }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small reusable composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick  = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
    ) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun RecBadge(frameCount: Int, elapsed: Long) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "● REC",
            color = Color.Red,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontSize = 13.sp
        )
        Text(
            "Frames: $frameCount",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontSize = 13.sp
        )
        Text(
            formatElapsedHms(elapsed),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PermissionsGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color.White
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Camera permission is required to capture timelapse videos.",
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Permission") }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure utility functions
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedHms(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private fun intervalLabel(secs: Int) = when {
    secs < 60  -> "${secs}s"
    secs == 60 -> "1 min"
    else       -> "${secs / 60} min"
}
