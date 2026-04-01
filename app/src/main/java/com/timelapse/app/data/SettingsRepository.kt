package com.timelapse.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.timelapse.app.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val CAMERA_OPTION = stringPreferenceKey("camera_option")
        val INTERVAL_SECONDS = intPreferenceKey("interval_seconds")
        val OVERLAY_TYPE = stringPreferenceKey("overlay_type")
        val OVERLAY_TEXT = stringPreferenceKey("overlay_text")
        val OVERLAY_TEXT_SIZE = floatPreferenceKey("overlay_text_size")
        val OVERLAY_POSITION = stringPreferenceKey("overlay_position")
        val OUTPUT_FPS = intPreferenceKey("output_fps")
        val VIDEO_QUALITY = stringPreferenceKey("video_quality")
    }

    val settingsFlow: Flow<TimelapseSettings> = context.dataStore.data
        .map { preferences ->
            TimelapseSettings(
                cameraOption = CameraOption.valueOf(
                    preferences[PreferencesKeys.CAMERA_OPTION] ?: CameraOption.BACK.name
                ),
                intervalSeconds = preferences[PreferencesKeys.INTERVAL_SECONDS] ?: 5,
                overlayType = OverlayType.valueOf(
                    preferences[PreferencesKeys.OVERLAY_TYPE] ?: OverlayType.NONE.name
                ),
                overlayText = preferences[PreferencesKeys.OVERLAY_TEXT] ?: "Timelapse",
                overlayTextSizeSp = preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] ?: 28f,
                overlayPosition = OverlayPosition.valueOf(
                    preferences[PreferencesKeys.OVERLAY_POSITION] ?: OverlayPosition.BOTTOM_LEFT.name
                ),
                outputFps = preferences[PreferencesKeys.OUTPUT_FPS] ?: 30,
                videoQuality = VideoQuality.valueOf(
                    preferences[PreferencesKeys.VIDEO_QUALITY] ?: VideoQuality.MEDIUM.name
                )
            )
        }

    suspend fun updateSettings(settings: TimelapseSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CAMERA_OPTION] = settings.cameraOption.name
            preferences[PreferencesKeys.INTERVAL_SECONDS] = settings.intervalSeconds
            preferences[PreferencesKeys.OVERLAY_TYPE] = settings.overlayType.name
            preferences[PreferencesKeys.OVERLAY_TEXT] = settings.overlayText
            preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] = settings.overlayTextSizeSp
            preferences[PreferencesKeys.OVERLAY_POSITION] = settings.overlayPosition.name
            preferences[PreferencesKeys.OUTPUT_FPS] = settings.outputFps
            preferences[PreferencesKeys.VIDEO_QUALITY] = settings.videoQuality.name
        }
    }
}
