package com.timelapse.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.timelapse.app.ui.CameraScreen
import com.timelapse.app.ui.VideoGalleryScreen
import com.timelapse.app.ui.VideoPlayerScreen
import com.timelapse.app.ui.theme.TimelapsAppTheme
import com.timelapse.app.viewmodel.AppScreen
import com.timelapse.app.viewmodel.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while the app is open (important for long captures)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Draw behind system bars for full-screen camera view
        enableEdgeToEdge()

        setContent {
            TimelapsAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

                    if (cameraPermissionState.status.isGranted) {
                        MainContent(viewModel)
                    } else {
                        PermissionRequestScreen {
                            cameraPermissionState.launchPermissionRequest()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MainContent(viewModel: MainViewModel) {
        // Back button handling: Navigate between screens or exit
        BackHandler(enabled = viewModel.currentScreen != AppScreen.CAMERA || viewModel.uiState.showSettings) {
            when {
                viewModel.uiState.showSettings -> viewModel.toggleSettings()
                viewModel.currentScreen == AppScreen.PLAYER -> viewModel.navigateTo(AppScreen.GALLERY)
                viewModel.currentScreen == AppScreen.GALLERY -> viewModel.navigateTo(AppScreen.CAMERA)
                else -> finish()
            }
        }

        // Simple screen router — no Navigation library needed
        when (viewModel.currentScreen) {
            AppScreen.CAMERA -> CameraScreen(viewModel = viewModel)
            AppScreen.GALLERY -> VideoGalleryScreen(
                onBack = { viewModel.navigateTo(AppScreen.CAMERA) },
                onPlayVideo = { uri -> viewModel.playVideo(uri) }
            )
            AppScreen.PLAYER -> {
                val uri = viewModel.uiState.selectedVideoUri
                if (uri != null) {
                    VideoPlayerScreen(
                        uri = uri,
                        onBack = { viewModel.navigateTo(AppScreen.GALLERY) }
                    )
                } else {
                    viewModel.navigateTo(AppScreen.GALLERY)
                }
            }
        }
    }

    @Composable
    private fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Camera Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "This app needs camera access to record timelapses.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}
