package com.timelapse.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Timelapse is always dark-themed — it's a camera app
private val DarkColors = darkColorScheme(
    primary          = TimelapsePrimary,
    secondary        = TimelapseSecondary,
    tertiary         = TimelapseTertiary,
    background       = TimelapseBackground,
    surface          = TimelapseSurface,
    onSurface        = TimelapseOnSurface,
    onSurfaceVariant = TimelapseOnSurface.copy(alpha = 0.7f)
)

@Composable
fun TimelapsAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography  = Typography,
        content     = content
    )
}
