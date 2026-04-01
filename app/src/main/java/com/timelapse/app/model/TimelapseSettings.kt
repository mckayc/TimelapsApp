package com.timelapse.app.model

data class TimelapseSettings(
    val cameraOption: CameraOption = CameraOption.BACK,
    val cameraId: String? = null, // Store specific camera ID for multiple lenses
    val intervalSeconds: Int = 5,
    val overlayType: OverlayType = OverlayType.NONE,
    val overlayText: String = "Timelapse",
    val overlayTextSizeSp: Float = 28f,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM_LEFT,
    val outputFps: Int = 30,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val videoResolution: VideoResolution = VideoResolution.P1080,
    val focusExposureLocked: Boolean = false,
    val autoStopType: AutoStopType = AutoStopType.NONE,
    val autoStopValue: Int = 0,
    val showGrid: Boolean = false,
    val batterySaver: Boolean = false,
    val batterySaverTimeoutSeconds: Int = 10,
    val enableExtensions: Boolean = true // HDR, Night mode, etc.
)

enum class CameraOption(val displayName: String) {
    BACK("Back Camera"),
    FRONT("Front Camera")
}

enum class OverlayType(val displayName: String) {
    NONE("No Overlay"),
    TIMESTAMP("Timestamp"),
    STOPWATCH("Stopwatch"),
    STATIC_TEXT("Custom Text")
}

enum class OverlayPosition(val displayName: String) {
    TOP_LEFT("Top Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right")
}

enum class VideoQuality(val displayName: String, val bitRate: Int) {
    LOW("Low (Small size)", 800_000),
    MEDIUM("Medium", 3_000_000),
    HIGH("High (Large size)", 8_000_000)
}

enum class VideoResolution(val displayName: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080)
}

enum class AutoStopType(val displayName: String) {
    NONE("No Auto-Stop"),
    DURATION("After Duration (min)"),
    FRAMES("After Frame Count")
}

val CAPTURE_INTERVALS = listOf(1, 2, 5, 10, 15, 30, 60)
val BATTERY_SAVER_TIMEOUTS = listOf(5, 10, 30, 60, 120)
