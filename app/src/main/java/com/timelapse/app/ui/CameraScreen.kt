package com.timelapse.app.ui

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.timelapse.app.model.CameraOption
import com.timelapse.app.model.OverlayPosition
import com.timelapse.app.model.OverlayType
import com.timelapse.app.model.TimelapseSettings
import com.timelapse.app.viewmodel.AppScreen
import com.timelapse.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// How long before the screen auto-dims (milliseconds)
private const val DIM_TIMEOUT_MS = 3 * 60 * 1000L

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    )
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) permissionsState.launchMultiplePermissionRequest()
    }

    if (permissionsState.allPermissionsGranted) {
        CameraContent(viewModel = viewModel)
    } else {
        PermissionsGate(onRequest = { permissionsState.launchMultiplePermissionRequest() })
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CameraContent(viewModel: MainViewModel) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState        = viewModel.uiState
    val settings       = viewModel.settings

    // ── Orientation-aware display rotation (fixes landscape recording) ───────
    // LocalConfiguration recomposes whenever the device rotates, giving us
    // the correct Surface.ROTATION_* to pass to ImageCapture.
    val configuration  = LocalConfiguration.current
    val displayRotation = when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> Surface.ROTATION_90
        else                                -> Surface.ROTATION_0
    }

    // ── Camera binding ───────────────────────────────────────────────────────
    var previewViewRef  by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider  by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        cameraProvider = suspendCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).addListener(
                { cont.resume(ProcessCameraProvider.getInstance(context).get()) },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    // Re-bind whenever camera choice OR orientation changes
    LaunchedEffect(cameraProvider, previewViewRef, settings.cameraOption, displayRotation) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pv       = previewViewRef  ?: return@LaunchedEffect

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(displayRotation)   // ← KEY FIX for landscape
            .build()

        val preview = Preview.Builder()
            .setTargetRotation(displayRotation)
            .build()
            .also { it.setSurfaceProvider(pv.surfaceProvider) }

        val selector = if (settings.cameraOption == CameraOption.BACK)
            CameraSelector.DEFAULT_BACK_CAMERA
        else
            CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            viewModel.setImageCapture(imageCapture)
        } catch (_: Exception) { }
    }

    // ── Screen dimming ────────────────────────────────────────────────────────
    var lastTouchMs    by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isScreenDimmed by remember { mutableStateOf(false) }
    val activity       = context as? Activity

    // Check for inactivity only while capturing
    LaunchedEffect(uiState.isCapturing) {
        if (uiState.isCapturing) {
            while (true) {
                delay(15_000L)
                isScreenDimmed = (System.currentTimeMillis() - lastTouchMs) > DIM_TIMEOUT_MS
            }
        } else {
            isScreenDimmed = false
        }
    }

    // Apply brightness change to the window
    LaunchedEffect(isScreenDimmed) {
        activity?.window?.attributes = activity?.window?.attributes?.also {
            it.screenBrightness = if (isScreenDimmed) 0.02f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    // Restore brightness when leaving the composable
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.attributes = activity?.window?.attributes?.also {
                it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    // ── Main layout ───────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Any touch anywhere wakes the screen back up
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    lastTouchMs    = System.currentTimeMillis()
                    isScreenDimmed = false
                }
            }
    ) {
        // Camera preview
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { previewViewRef = it } },
            modifier = Modifier.fillMaxSize(),
            update   = { previewViewRef = it }
        )

        // ── Live overlay text preview ──────────────────────────────────────
        if (uiState.isCapturing && settings.overlayType != OverlayType.NONE) {
            LiveOverlayPreview(
                settings      = settings,
                captureStartMs = viewModel.captureStartMs
            )
        }

        // ── Dim overlay ────────────────────────────────────────────────────
        if (isScreenDimmed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Screen dimmed to save power\nTap to restore",
                    color     = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    style     = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            if (!uiState.isCapturing && !uiState.isEncoding) {
                // Settings button
                CircleIconButton(Icons.Default.Settings, "Settings") { viewModel.toggleSettings() }
            } else {
                Spacer(Modifier.size(48.dp))
            }

            // REC badge
            if (uiState.isCapturing) {
                RecBadge(frameCount = uiState.frameCount, elapsed = uiState.elapsedSeconds)
            }

            if (!uiState.isCapturing && !uiState.isEncoding) {
                // Right side: camera flip + gallery
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleIconButton(Icons.Default.VideoLibrary, "Gallery") {
                        viewModel.navigateTo(AppScreen.GALLERY)
                    }
                    CircleIconButton(Icons.Default.Cameraswitch, "Switch camera") {
                        val next = if (settings.cameraOption == CameraOption.BACK)
                            CameraOption.FRONT else CameraOption.BACK
                        viewModel.updateSettings(settings.copy(cameraOption = next))
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // ── Bottom controls ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.isCapturing && !uiState.isEncoding) {
                Text(
                    text     = "Every ${intervalLabel(settings.intervalSeconds)}",
                    color    = Color.White,
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
                Spacer(Modifier.height(18.dp))
            }

            when {
                uiState.isEncoding -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Encoding video…", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                uiState.isCapturing -> {
                    Button(
                        onClick        = { viewModel.stopCapture() },
                        modifier       = Modifier.size(80.dp),
                        shape          = CircleShape,
                        colors         = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Stop, "Stop", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                else -> {
                    Button(
                        onClick        = { viewModel.startCapture() },
                        modifier       = Modifier.size(80.dp),
                        shape          = CircleShape,
                        colors         = ButtonDefaults.buttonColors(containerColor = Color.White),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.FiberManualRecord, "Start", tint = Color.Red, modifier = Modifier.size(44.dp))
                    }
                }
            }
        }

        // ── Settings panel ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = uiState.showSettings,
            enter    = slideInVertically(initialOffsetY = { it }),
            exit     = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SettingsPanel(
                settings          = settings,
                onSettingsChanged = { viewModel.updateSettings(it) },
                onDismiss         = { viewModel.toggleSettings() }
            )
        }

        // ── Dialogs ────────────────────────────────────────────────────────
        uiState.errorMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                icon    = { Icon(Icons.Default.ErrorOutline, null) },
                title   = { Text("Something went wrong") },
                text    = { Text(msg) },
                confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } }
            )
        }

        uiState.outputPath?.let { path ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissCompletion() },
                icon  = { Icon(Icons.Default.CheckCircle, null) },
                title = { Text("Timelapse saved!") },
                text  = { Text("${uiState.frameCount} frames saved to:\n$path", textAlign = TextAlign.Center) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissCompletion()
                        viewModel.navigateTo(AppScreen.GALLERY)
                    }) { Text("View in Gallery") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissCompletion() }) { Text("OK") }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live overlay text shown on-screen while capturing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveOverlayPreview(settings: TimelapseSettings, captureStartMs: Long) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()) }
    var overlayText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            overlayText = when (settings.overlayType) {
                OverlayType.TIMESTAMP   -> dateFormat.format(Date())
                OverlayType.STOPWATCH   -> formatElapsedMs(System.currentTimeMillis() - captureStartMs)
                OverlayType.STATIC_TEXT -> settings.overlayText.ifBlank { "Timelapse" }
                OverlayType.NONE        -> ""
            }
            delay(500L)
        }
    }

    val alignment = when (settings.overlayPosition) {
        OverlayPosition.TOP_LEFT      -> Alignment.TopStart
        OverlayPosition.TOP_CENTER    -> Alignment.TopCenter
        OverlayPosition.TOP_RIGHT     -> Alignment.TopEnd
        OverlayPosition.BOTTOM_LEFT   -> Alignment.BottomStart
        OverlayPosition.BOTTOM_CENTER -> Alignment.BottomCenter
        OverlayPosition.BOTTOM_RIGHT  -> Alignment.BottomEnd
    }

    // Padding to avoid top bar / bottom buttons
    val verticalPad = if (settings.overlayPosition.name.startsWith("TOP")) 72.dp else 120.dp

    Box(
        modifier         = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = verticalPad),
        contentAlignment = alignment
    ) {
        Text(
            text       = overlayText,
            color      = Color.White,
            fontSize   = settings.overlayTextSizeSp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style      = LocalTextStyle.current.copy(
                shadow = Shadow(Color.Black, Offset(2f, 2f), blurRadius = 6f)
            ),
            modifier   = Modifier
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable small composables
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
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("● REC",             color = Color.Red,   fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text("Frames: $frameCount", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(formatElapsedSecs(elapsed), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionsGate(onRequest: () -> Unit) {
    Column(
        modifier              = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(72.dp), tint = Color.White)
        Spacer(Modifier.height(24.dp))
        Text("Camera permission is required.", color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant Permission") }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utility functions
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedSecs(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun formatElapsedMs(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun intervalLabel(secs: Int) = when {
    secs < 60  -> "${secs}s"
    secs == 60 -> "1 min"
    else       -> "${secs / 60} min"
}
