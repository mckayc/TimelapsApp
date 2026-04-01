package com.timelapse.app.model

data class TimelapseSettings(
    val cameraOption: CameraOption = CameraOption.BACK,
    val intervalSeconds: Int = 5,
    val overlayType: OverlayType = OverlayType.NONE,
    val overlayText: String = "Timelapse",
    val overlayTextSizeSp: Float = 28f,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM_LEFT,
    val outputFps: Int = 30
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

/** Selectable capture intervals in seconds */
val CAPTURE_INTERVALS = listOf(1, 2, 5, 10, 15, 30, 60)
