package com.timelapse.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelapse.app.encoder.VideoEncoder
import com.timelapse.app.model.CameraOption
import com.timelapse.app.model.TimelapseSettings
import com.timelapse.app.overlay.OverlayRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Top-level screens in the app
enum class AppScreen { CAMERA, GALLERY }

data class CaptureUiState(
    val isCapturing: Boolean = false,
    val isEncoding: Boolean = false,
    val frameCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val showSettings: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

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

    private var encoder: VideoEncoder? = null
    private val encoderMutex = Mutex()

    private var currentImageCapture: ImageCapture? = null

    private val overlayRenderer = OverlayRenderer()

    // ---- Public API ----

    fun setImageCapture(imageCapture: ImageCapture) {
        currentImageCapture = imageCapture
    }

    fun updateSettings(newSettings: TimelapseSettings) {
        settings = newSettings
    }

    fun toggleSettings() {
        uiState = uiState.copy(showSettings = !uiState.showSettings)
    }

    fun navigateTo(screen: AppScreen) {
        currentScreen = screen
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
        uiState = CaptureUiState(isCapturing = true, showSettings = false)

        startElapsedTimer()

        val ctx = getApplication<Application>()
        val executor = ContextCompat.getMainExecutor(ctx)
        val intervalMs = settings.intervalSeconds * 1000L
        val snap = settings.copy() // snapshot so mid-capture settings changes don't affect this run

        captureJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val frameStart = System.currentTimeMillis()

                try {
                    val bitmap = captureFrame(capture, executor) ?: continue

                    // Apply overlay
                    val overlaid = overlayRenderer.applyOverlay(bitmap, snap, captureStartMs)
                    if (overlaid !== bitmap) bitmap.recycle()

                    // Init encoder on first frame
                    encoderMutex.withLock {
                        if (encoder == null) {
                            val tmp = File(ctx.cacheDir, "timelapse_tmp_${System.currentTimeMillis()}.mp4")
                            encoder = VideoEncoder(
                                outputFile = tmp,
                                width = overlaid.width,
                                height = overlaid.height,
                                fps = snap.outputFps
                            ).also { it.start() }
                        }
                        encoder!!.addFrame(overlaid)
                        overlaid.recycle()
                    }

                    val newCount = uiState.frameCount + 1
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(frameCount = newCount)
                    }

                } catch (e: CancellationException) {
                    throw e // let coroutine cancel normally
                } catch (e: Exception) {
                    Log.e(TAG, "Frame capture/encode error", e)
                }

                // Wait for the remainder of the interval
                val spent = System.currentTimeMillis() - frameStart
                val remaining = intervalMs - spent
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        timerJob?.cancel()
        timerJob = null

        uiState = uiState.copy(isCapturing = false, isEncoding = true)

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
                        // Return the absolute path so the internal player can find it
                        uiState = uiState.copy(isEncoding = false, outputPath = tempFile.absolutePath)
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

    override fun onCleared() {
        super.onCleared()
        captureJob?.cancel()
        timerJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            encoderMutex.withLock { encoder?.finish(); encoder = null }
        }
    }

    // ---- Private helpers ----

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

    /**
     * Suspend until CameraX delivers one captured frame as a correctly-rotated Bitmap.
     */
    private suspend fun captureFrame(
        imageCapture: ImageCapture,
        executor: java.util.concurrent.Executor
    ): Bitmap? = suspendCoroutine { cont ->
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    imageProxy.close()

                    val rotated = if (rotation != 0) {
                        val matrix = Matrix().apply {
                            postRotate(rotation.toFloat())
                            // Mirror horizontally for front camera selfie view
                            if (settings.cameraOption == CameraOption.FRONT) {
                                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                            }
                        }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            .also { bitmap.recycle() }
                    } else {
                        bitmap
                    }

                    cont.resume(rotated)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    cont.resume(null)
                }
            }
        )
    }

    private fun saveToGallery(tempFile: File): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "TIMELAPSE_$ts.mp4"
        val ctx = getApplication<Application>()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: use MediaStore (no WRITE permission needed)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TimelapsApp")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            )!!
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            tempFile.delete()
            "Movies/TimelapsApp/$fileName"
        } else {
            // API 26-28: write directly to public Movies folder
            @Suppress("DEPRECATION")
            val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MOVIES
            )
            val appDir = File(moviesDir, "TimelapsApp").also { it.mkdirs() }
