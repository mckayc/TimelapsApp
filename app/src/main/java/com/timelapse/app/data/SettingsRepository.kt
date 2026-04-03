package com.timelapse.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.timelapse.app.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val TAG = "SettingsRepository"

    private object PreferencesKeys {
        val CAMERA_OPTION = stringPreferencesKey("camera_option")
        val CAMERA_ID = stringPreferencesKey("camera_id")
        val INTERVAL_SECONDS = floatPreferencesKey("interval_seconds")
        val OVERLAY_TYPE = stringPreferencesKey("overlay_type")
        val OVERLAY_TEXT = stringPreferencesKey("overlay_text")
        val OVERLAY_TEXT_SIZE = floatPreferencesKey("overlay_text_size")
        val OVERLAY_POSITION = stringPreferencesKey("overlay_position")
        val OUTPUT_FPS = intPreferencesKey("output_fps")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val FOCUS_EXPOSURE_LOCKED = booleanPreferencesKey("focus_exposure_locked")
        val AUTO_STOP_TYPE = stringPreferencesKey("auto_stop_type")
        val AUTO_STOP_VALUE = intPreferencesKey("auto_stop_value")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val BATTERY_SAVER_TIMEOUT = intPreferencesKey("battery_saver_timeout")
        val ENABLE_EXTENSIONS = booleanPreferencesKey("enable_extensions")
    }

    val settingsFlow: Flow<TimelapseSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preferences.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            TimelapseSettings(
                cameraOption = safeValueOf(
                    preferences[PreferencesKeys.CAMERA_OPTION],
                    CameraOption.BACK
                ),
                cameraId = preferences[PreferencesKeys.CAMERA_ID],
                intervalSeconds = preferences[PreferencesKeys.INTERVAL_SECONDS] ?: 5f,
                overlayType = safeValueOf(
                    preferences[PreferencesKeys.OVERLAY_TYPE],
                    OverlayType.NONE
                ),
                overlayText = preferences[PreferencesKeys.OVERLAY_TEXT] ?: "Timelapse",
                overlayTextSizeSp = preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] ?: 28f,
                overlayPosition = safeValueOf(
                    preferences[PreferencesKeys.OVERLAY_POSITION],
                    OverlayPosition.BOTTOM_LEFT
                ),
                outputFps = preferences[PreferencesKeys.OUTPUT_FPS] ?: 30,
                videoQuality = safeValueOf(
                    preferences[PreferencesKeys.VIDEO_QUALITY],
                    VideoQuality.MEDIUM
                ),
                videoResolution = safeValueOf(
                    preferences[PreferencesKeys.VIDEO_RESOLUTION],
                    VideoResolution.P1080
                ),
                focusExposureLocked = preferences[PreferencesKeys.FOCUS_EXPOSURE_LOCKED] ?: false,
                autoStopType = safeValueOf(
                    preferences[PreferencesKeys.AUTO_STOP_TYPE],
                    AutoStopType.NONE
                ),
                autoStopValue = preferences[PreferencesKeys.AUTO_STOP_VALUE] ?: 0,
                showGrid = preferences[PreferencesKeys.SHOW_GRID] ?: false,
                batterySaver = preferences[PreferencesKeys.BATTERY_SAVER] ?: false,
                batterySaverTimeoutSeconds = preferences[PreferencesKeys.BATTERY_SAVER_TIMEOUT] ?: 10,
                enableExtensions = preferences[PreferencesKeys.ENABLE_EXTENSIONS] ?: true
            )
        }

    suspend fun updateSettings(settings: TimelapseSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CAMERA_OPTION] = settings.cameraOption.name
            settings.cameraId?.let { preferences[PreferencesKeys.CAMERA_ID] = it } ?: preferences.remove(PreferencesKeys.CAMERA_ID)
            preferences[PreferencesKeys.INTERVAL_SECONDS] = settings.intervalSeconds
            preferences[PreferencesKeys.OVERLAY_TYPE] = settings.overlayType.name
            preferences[PreferencesKeys.OVERLAY_TEXT] = settings.overlayText
            preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] = settings.overlayTextSizeSp
            preferences[PreferencesKeys.OVERLAY_POSITION] = settings.overlayPosition.name
            preferences[PreferencesKeys.OUTPUT_FPS] = settings.outputFps
            preferences[PreferencesKeys.VIDEO_QUALITY] = settings.videoQuality.name
            preferences[PreferencesKeys.VIDEO_RESOLUTION] = settings.videoResolution.name
            preferences[PreferencesKeys.FOCUS_EXPOSURE_LOCKED] = settings.focusExposureLocked
            preferences[PreferencesKeys.AUTO_STOP_TYPE] = settings.autoStopType.name
            preferences[PreferencesKeys.AUTO_STOP_VALUE] = settings.autoStopValue
            preferences[PreferencesKeys.SHOW_GRID] = settings.showGrid
            preferences[PreferencesKeys.BATTERY_SAVER] = settings.batterySaver
            preferences[PreferencesKeys.BATTERY_SAVER_TIMEOUT] = settings.batterySaverTimeoutSeconds
            preferences[PreferencesKeys.ENABLE_EXTENSIONS] = settings.enableExtensions
        }
    }

    private inline fun <reified T : Enum<T>> safeValueOf(name: String?, default: T): T {
        if (name == null) return default
        return try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (e: IllegalArgumentException) {
            default
        }
    }
}
