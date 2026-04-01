package com.timelapse.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelapse.app.data.SettingsRepository
import com.timelapse.app.encoder.VideoEncoder
import com.timelapse.app.model.*
import com.timelapse.app.overlay.OverlayRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*

// Top-level screens in the app
enum class AppScreen { CAMERA, GALLERY, PLAYER }

data class CaptureUiState(
    val isCapturing: Boolean = false,
    val isEncoding: Boolean = false,
    val frameCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val showSettings: Boolean = false,
    val batterySaverActive: Boolean = false,
    val selectedVideoUri: Uri? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val repository = SettingsRepository(application)

    // ---- UI-observable state ----
    var settings by mutableStateOf(TimelapseSettings())
        private set

    var uiState by mutableStateOf(CaptureUiState())
        private set

    var currentScreen by mutableStateOf(AppScreen.CAMERA)
        private set

    /** Exposed so the live overlay composable can compute its running stopwatch. */
    var captureStartMs: Long = 0L
        private set

    // ---- Internal capture state ----
    private var captureJob: Job? = null
    private var timerJob: Job? = null
    private var batterySaverJob: Job? = null

    private var encoder: VideoEncoder? = null
    private val encoderMutex = Mutex()

    private var currentImageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null

    private val overlayRenderer = OverlayRenderer()

    init {
        // Load persisted settings
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { persistedSettings ->
                settings = persistedSettings
                applyCameraSettings()
            }
        }
    }

    // ---- Public API ----

    fun setImageCapture(imageCapture: ImageCapture) {
        currentImageCapture = imageCapture
    }

    fun setCameraControl(control: CameraControl?) {
        cameraControl = control
        applyCameraSettings()
    }

    private fun applyCameraSettings() {
        val control = cameraControl ?: return
        if (settings.focusExposureLocked) {
            control.cancelFocusAndMetering()
        }
    }

    fun toggleFocusLock() {
        updateSettings(settings.copy(focusExposureLocked = !settings.focusExposureLocked))
    }

    fun updateSettings(newSettings: TimelapseSettings) {
        settings = newSettings
        viewModelScope.launch {
            repository.updateSettings(newSettings)
        }
    }

    fun toggleSettings() {
        uiState = uiState.copy(showSettings = !uiState.showSettings)
    }

    fun navigateTo(screen: AppScreen) {
        currentScreen = screen
    }

    fun playVideo(uri: Uri) {
        uiState = uiState.copy(selectedVideoUri = uri)
        currentScreen = AppScreen.PLAYER
    }

    fun onCameraBindingFailed(message: String) {
        uiState = uiState.copy(errorMessage = message)
    }

    fun dismissError() {
        uiState = uiState.copy(errorMessage = null)
    }

    fun dismissCompletion() {
        uiState = uiState.copy(outputPath = null)
    }

    fun startCapture() {
        val capture = currentImageCapture ?: run {
            uiState = uiState.copy(errorMessage = "Camera not ready. Please wait.")
            return
        }

        captureStartMs = System.currentTimeMillis()
        uiState = uiState.copy(
            isCapturing = true,
            showSettings = false,
            frameCount = 0,
            elapsedSeconds = 0,
            batterySaverActive = false
        )

        startElapsedTimer()
        if (settings.batterySaver) {
            startBatterySaverTimer()
        }

        val ctx = getApplication<Application>()
        val executor = ContextCompat.getMainExecutor(ctx)
        val intervalMs = settings.intervalSeconds * 1000L
        val snap = settings.copy() // snapshot

        captureJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val frameStart = System.currentTimeMillis()

                try {
                    val (bitmap, _) = captureFrame(capture, executor) ?: continue

                    // Init encoder on first frame
                    encoderMutex.withLock {
                        if (encoder == null) {
                            val tmp = File(ctx.cacheDir, "timelapse_tmp_${System.currentTimeMillis()}.mp4")
                            
                            val isPortrait = bitmap.height > bitmap.width
                            val targetWidth = if (isPortrait) snap.videoResolution.height else snap.videoResolution.width
                            val targetHeight = if (isPortrait) snap.videoResolution.width else snap.videoResolution.height

                            encoder = VideoEncoder(
                                outputFile = tmp,
                                encWidth = targetWidth,
                                encHeight = targetHeight,
                                settings = snap,
                                captureStartMs = captureStartMs,
                                rotation = 0
                            ).also { it.start() }
                        }
                        encoder!!.addFrame(bitmap)
                        bitmap.recycle()
                    }

                    val newCount = uiState.frameCount + 1
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(frameCount = newCount)
                        checkAutoStop(newCount, System.currentTimeMillis() - captureStartMs)
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Frame capture/encode error", e)
                }

                val spent = System.currentTimeMillis() - frameStart
                val remaining = intervalMs - spent
                if (remaining > 0) delay(remaining)
            }
        }
    }

    private fun checkAutoStop(frameCount: Int, elapsedMs: Long) {
        when (settings.autoStopType) {
            AutoStopType.FRAMES -> {
                if (frameCount >= settings.autoStopValue && settings.autoStopValue > 0) {
                    stopCapture()
                }
            }
            AutoStopType.DURATION -> {
                if (elapsedMs >= settings.autoStopValue * 60 * 1000L && settings.autoStopValue > 0) {
                    stopCapture()
                }
            }
            AutoStopType.NONE -> {}
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        timerJob?.cancel()
        timerJob = null
        batterySaverJob?.cancel()
        batterySaverJob = null

        uiState = uiState.copy(isCapturing = false, isEncoding = true, batterySaverActive = false)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = encoderMutex.withLock {
                    val f = encoder?.finish()
                    encoder = null
                    f
                }

                if (tempFile != null && uiState.frameCount > 0) {
                    val saved = saveToGallery(tempFile)
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(isEncoding = false, outputPath = saved)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(
                            isEncoding = false,
                            errorMessage = "No frames were captured."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing video", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(
                        isEncoding = false,
                        errorMessage = "Could not save video: ${e.message}"
                    )
                }
            }
        }
    }

    private fun startBatterySaverTimer() {
        batterySaverJob?.cancel()
        batterySaverJob = viewModelScope.launch {
            delay(settings.batterySaverTimeoutSeconds * 1000L)
            if (uiState.isCapturing) {
                uiState = uiState.copy(batterySaverActive = true)
            }
        }
    }

    fun wakeUp() {
        if (uiState.batterySaverActive) {
            uiState = uiState.copy(batterySaverActive = false)
            if (uiState.isCapturing && settings.batterySaver) {
                startBatterySaverTimer()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()
        timerJob?.cancel()
        batterySaverJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            encoderMutex.withLock { encoder?.finish(); encoder = null }
        }
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        val start = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (isActive) {
                uiState = uiState.copy(elapsedSeconds = (System.currentTimeMillis() - start) / 1000L)
                delay(1000)
            }
        }
    }

    private suspend fun captureFrame(
        imageCapture: ImageCapture,
        executor: java.util.concurrent.Executor
    ): Pair<Bitmap, Int>? = suspendCoroutine { cont ->
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()

                    val processed = if (settings.cameraOption == CameraOption.FRONT) {
                        val matrix = Matrix().apply {
                            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                        }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            .also { bitmap.recycle() }
                    } else {
                        bitmap
                    }

                    cont.resume(processed to 0)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    cont.resume(null)
                }
            }
        )
    }

    private fun saveToGallery(file: File): String? {
        val ctx = getApplication<Application>()
        val filename = "Timelapse_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TimelapsApp/")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = ctx.contentResolver.insert(collection, values) ?: return null

        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            }

            file.delete()
            return uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
            return null
        }
    }
}
