package com.timelapse.app.ui

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.extensions.ExtensionMode
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.timelapse.app.model.CameraOption
import com.timelapse.app.model.OverlayType
import com.timelapse.app.overlay.OverlayRenderer
import com.timelapse.app.viewmodel.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val uiState = viewModel.uiState

    // --- Camera Lifecycle Management ---
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { 
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build() 
    }

    LaunchedEffect(Unit) {
        cameraProviderFuture.addListener({
            val provider = try { cameraProviderFuture.get() } catch (e: Exception) { null }
            cameraProvider = provider
            
            if (provider != null) {
                // Initialize ExtensionsManager for HDR/Night Mode
                val extensionsFuture = ExtensionsManager.getInstanceAsync(context, provider)
                extensionsFuture.addListener({
                    extensionsManager = try { extensionsFuture.get() } catch (e: Exception) { null }
                }, ContextCompat.getMainExecutor(context))
            }
            
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(viewModel.settings.cameraOption, viewModel.settings.enableExtensions, cameraProvider, extensionsManager) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val extManager = extensionsManager
        
        var cameraSelector = if (viewModel.settings.cameraOption == CameraOption.BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // Apply HDR/Auto extension if enabled and supported
        if (viewModel.settings.enableExtensions && extManager != null) {
            try {
                if (extManager.isExtensionAvailable(cameraSelector, ExtensionMode.HDR)) {
                    cameraSelector = extManager.getExtensionEnabledCameraSelector(cameraSelector, ExtensionMode.HDR)
                } else if (extManager.isExtensionAvailable(cameraSelector, ExtensionMode.AUTO)) {
                    cameraSelector = extManager.getExtensionEnabledCameraSelector(cameraSelector, ExtensionMode.AUTO)
                }
            } catch (e: Exception) {
                Log.e("CameraScreen", "Extensions error", e)
            }
        }

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageCapture
            )
            viewModel.setImageCapture(imageCapture)
            viewModel.setCameraControl(camera.cameraControl)
            
            camera.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                viewModel.updateZoomState(state)
            }
        } catch (exc: Exception) {
            Log.e("CameraScreen", "Use case binding failed", exc)
            viewModel.onCameraBindingFailed(exc.message ?: "Unknown camera error")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- 1. Camera Preview Layer ---
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    preview.setSurfaceProvider(this.surfaceProvider)
                    
                    // Tap to Focus implementation
                    setOnTouchListener { view, event ->
                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                            val factory = view.display.let { 
                                SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat()) 
                            }
                            val point = factory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point).build()
                            viewModel.tapToFocus(action)
                            view.performClick()
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // --- 2. Live Overlay Layer (Text/Grid) ---
        LiveOverlay(viewModel)

        // --- 3. UI Controls Layer ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { 
                        viewModel.toggleFocusLock()
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        if (viewModel.settings.focusExposureLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock AE/AF",
                        tint = if (viewModel.settings.focusExposureLocked) Color.Yellow else Color.White
                    )
                }

                IconButton(
                    onClick = { viewModel.toggleSettings() },
                    enabled = !uiState.isCapturing,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Zoom Controls
            if (!uiState.isCapturing && uiState.zoomState != null) {
                ZoomControls(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                    currentZoom = uiState.zoomState.zoomRatio,
                    minZoom = uiState.zoomState.minZoomRatio,
                    maxZoom = uiState.zoomState.maxZoomRatio,
                    onZoomChanged = { viewModel.setZoom(it) }
                )
            }

            // Capture Status Info
            AnimatedVisibility(
                visible = uiState.isCapturing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Column(
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "RECORDING",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${uiState.frameCount} frames • ${formatSeconds(uiState.elapsedSeconds)}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Bottom Control Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, start = 32.dp, end = 32.dp)
            ) {
                CameraControlButton(
                    modifier = Modifier.align(Alignment.CenterStart),
                    icon = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Flip Camera",
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        val next = if (viewModel.settings.cameraOption == CameraOption.BACK) CameraOption.FRONT else CameraOption.BACK
                        viewModel.updateSettings(viewModel.settings.copy(cameraOption = next))
                    },
                    enabled = !uiState.isCapturing
                )

                CaptureButton(
                    modifier = Modifier.align(Alignment.Center),
                    isCapturing = uiState.isCapturing,
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        if (uiState.isCapturing) viewModel.stopCapture() else viewModel.startCapture()
                    }
                )

                CameraControlButton(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    icon = Icons.Outlined.PhotoLibrary,
                    contentDescription = "Gallery",
                    onClick = { viewModel.navigateTo(AppScreen.GALLERY) },
                    enabled = !uiState.isCapturing
                )
            }
        }

        // Toast-like message for AE/AF Lock
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible = uiState.toastMessage != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = uiState.toastMessage ?: "",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // --- 4. Battery Saver / Blackout Layer ---
        if (uiState.batterySaverActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.wakeUp() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BatteryChargingFull, "Battery Saver", tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                    Text("Tap to wake", color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // --- 5. Dialogs & Overlays ---
        if (uiState.isEncoding) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("Encoding Timelapse...", color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (uiState.showSettings) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable { viewModel.toggleSettings() }
            ) {
                Box(modifier = Modifier.align(Alignment.BottomCenter).clickable(enabled = false) {}) {
                    SettingsPanel(
                        settings = viewModel.settings,
                        onSettingsChanged = { viewModel.updateSettings(it) },
                        onDismiss = { viewModel.toggleSettings() }
                    )
                }
            }
        }
    }
}

@Composable
fun ZoomControls(
    modifier: Modifier = Modifier,
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChanged: (Float) -> Unit
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val zoomLevels = listOf(1f, 2f, 5f).filter { it in minZoom..maxZoom }
        
        zoomLevels.forEach { level ->
            val isSelected = (currentZoom - level).let { it > -0.1f && it < 0.1f }
            Surface(
                onClick = { onZoomChanged(level) },
                shape = CircleShape,
                color = if (isSelected) Color.White else Color.Transparent,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${level.toInt()}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Slider for fine-grained control if range is large
        if (maxZoom > 1f) {
            Slider(
                value = currentZoom,
                onValueChange = onZoomChanged,
                valueRange = minZoom..maxZoom.coerceAtMost(10f),
                modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
fun LiveOverlay(viewModel: MainViewModel) {
    val overlayRenderer = remember { OverlayRenderer() }
    val settings = viewModel.settings
    
    // Grid layer
    if (settings.showGrid) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.dp.toPx()
            val color = Color.White.copy(alpha = 0.3f)
            drawLine(color, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), strokeWidth)
            drawLine(color, Offset(2 * size.width / 3, 0f), Offset(2 * size.width / 3, size.height), strokeWidth)
            drawLine(color, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), strokeWidth)
            drawLine(color, Offset(0f, 2 * size.height / 3), Offset(size.width, 2 * size.height / 3), strokeWidth)
        }
    }

    // Text overlay layer
    if (settings.overlayType != OverlayType.NONE) {
        // Tick every second to update timestamp/stopwatch
        var tick by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(settings.overlayType, viewModel.uiState.isCapturing) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(1000)
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // We use the tick value implicitly by reading it here to trigger recomposition
            val t = tick 
            val canvas = drawContext.canvas.nativeCanvas
            overlayRenderer.drawOverlay(
                canvas,
                size.width.toInt(),
                size.height.toInt(),
                settings,
                if (viewModel.uiState.isCapturing) viewModel.captureStartMs else 0L
            )
        }
    }
}

@Composable
fun CameraControlButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.Gray,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
fun CaptureButton(
    modifier: Modifier = Modifier,
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val size by animateDpAsState(targetValue = if (isCapturing) 80.dp else 90.dp, label = "size")
    val innerSize by animateDpAsState(targetValue = if (isCapturing) 32.dp else 74.dp, label = "innerSize")
    val shape = if (isCapturing) RoundedCornerShape(12.dp) else CircleShape

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .padding(6.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false, radius = 45.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(shape)
                .background(if (isCapturing) Color.Red else Color.White)
        )
    }
}

private fun formatSeconds(totalSecs: Long): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
