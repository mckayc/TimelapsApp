package com.timelapse.app.ui

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
            .setTargetRotation(displayRotation)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)  // match screen preview aspect ratio
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
        } catch (e: Exception) {
            android.util.Log.e("CameraScreen", "Camera binding failed", e)
            viewModel.onCameraBindingFailed("Camera unavailable: ${e.message}")
        }
    }

    // ── Screen dimming ────────────────────────────────────────────────────────
    var lastTouchMs    by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isScreenDimmed by remember { mutableStateOf(false) }
    val activity       = context as? Activity

    var showInternalPlayer by remember { mutableStateOf<String?>(null) }

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
            } else {
                // Build Version Tag
                Text(
                    text = "v0.0.2",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
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
                        showInternalPlayer = path
                    }) { Text("Watch Now") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // Trigger internal playback logic here
                        viewModel.dismissCompletion()
                    }) { Text("Close") }
                }
            )
        }

        // Fullscreen Player Overlay
        showInternalPlayer?.let { videoPath ->
            VideoPlayer(videoPath = videoPath, onDismiss = { showInternalPlayer = null })
        }
    }
}

@Composable
fun RecBadge(frameCount: Int, elapsed: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$frameCount frames • ${elapsed}s",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f))
    ) {
        Icon(icon, contentDescription, tint = Color.White)
    }
}

@Composable
fun PermissionsGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera permissions are required for this app.")
            Button(onClick = onRequest) { Text("Grant Permissions") }
        }
    }
}

@Composable
fun LiveOverlayPreview(settings: TimelapseSettings, captureStartMs: Long) {
    var overlayText by remember { mutableStateOf("") }

    LaunchedEffect(settings.overlayType, settings.overlayText) {
        while (true) {
            overlayText = when (settings.overlayType) {
                OverlayType.TIMESTAMP ->
                    SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
                OverlayType.STOPWATCH -> {
                    val elapsed = System.currentTimeMillis() - captureStartMs
                    val h = elapsed / 3_600_000L
                    val m = (elapsed % 3_600_000L) / 60_000L
                    val s = (elapsed % 60_000L) / 1_000L
                    if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
                }
                OverlayType.STATIC_TEXT -> settings.overlayText.ifBlank { "Timelapse" }
                OverlayType.NONE -> ""
            }
            // Static text never changes; timestamp/stopwatch update every second
            if (settings.overlayType == OverlayType.STATIC_TEXT ||
                settings.overlayType == OverlayType.NONE) break
            delay(1_000L)
        }
    }

    if (overlayText.isEmpty()) return

    val alignment = when (settings.overlayPosition) {
        OverlayPosition.TOP_LEFT     -> Alignment.TopStart
        OverlayPosition.TOP_CENTER   -> Alignment.TopCenter
        OverlayPosition.TOP_RIGHT    -> Alignment.TopEnd
        OverlayPosition.BOTTOM_LEFT  -> Alignment.BottomStart
        OverlayPosition.BOTTOM_CENTER -> Alignment.BottomCenter
        OverlayPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    val isBottom = settings.overlayPosition.name.startsWith("BOTTOM")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // Bottom positions need clearance above the record button area (~160dp)
            .padding(
                top    = if (!isBottom) 8.dp  else 0.dp,
                bottom = if (isBottom)  160.dp else 0.dp,
                start  = 16.dp,
                end    = 16.dp
            )
    ) {
        Text(
            text       = overlayText,
            modifier   = Modifier.align(alignment),
            color      = Color.White,
            fontSize   = settings.overlayTextSizeSp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style      = MaterialTheme.typography.bodyMedium.copy(
                shadow = Shadow(
                    color      = Color.Black,
                    offset     = Offset(2f, 2f),
                    blurRadius = 6f
                )
            )
        )
    }
}

private fun intervalLabel(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    else -> "${seconds / 60}m"
}

@Composable
fun VideoPlayer(videoPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            // Use absolute path for local files
            val uri = android.net.Uri.fromFile(java.io.File(videoPath))
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            onClick = onDismiss, 
            modifier = Modifier.padding(16.dp).align(Alignment.TopEnd).statusBarsPadding()
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}
                