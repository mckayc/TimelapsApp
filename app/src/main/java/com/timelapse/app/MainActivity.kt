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

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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
                        AppScreen.CAMERA  -> CameraScreen(viewModel = viewModel)
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
            }
        }
    }
}
