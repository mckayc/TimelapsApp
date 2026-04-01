package com.timelapse.app.overlay

import android.graphics.*
import com.timelapse.app.model.OverlayPosition
import com.timelapse.app.model.OverlayType
import com.timelapse.app.model.TimelapseSettings
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Renders text overlays (timestamp / stopwatch / static text) onto a Bitmap.
 * Returns the same bitmap if overlay type is NONE, otherwise a new mutable copy.
 */
class OverlayRenderer {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault())

    fun applyOverlay(
        source: Bitmap,
        settings: TimelapseSettings,
        captureStartMs: Long
    ): Bitmap {
        if (settings.overlayType == OverlayType.NONE) return source

        val text = when (settings.overlayType) {
            OverlayType.TIMESTAMP -> dateFormat.format(Date())
            OverlayType.STOPWATCH -> formatElapsed(System.currentTimeMillis() - captureStartMs)
            OverlayType.STATIC_TEXT -> settings.overlayText.ifBlank { "Timelapse" }
            OverlayType.NONE -> return source
        }

        // Work on a mutable copy so we don't mutate the decoded JPEG bitmap
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        // Scale text size relative to image width so it looks good at any resolution
        val scaleFactor = output.width / 400f
        val textSizePx = settings.overlayTextSizeSp * scaleFactor

        // --- Text paint (white) ---
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            style = Paint.Style.FILL
        }

        // --- Shadow / stroke paint for readability on any background ---
        val shadowPaint = Paint(textPaint).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = textSizePx * 0.08f
        }

        val padding = textSizePx * 0.6f
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent

        val (x, y) = computePosition(
            settings.overlayPosition,
            output.width.toFloat(),
            output.height.toFloat(),
            textWidth,
            textHeight,
            padding,
            metrics
        )

        canvas.drawText(text, x, y, shadowPaint)
        canvas.drawText(text, x, y, textPaint)

        return output
    }

    private fun computePosition(
        position: OverlayPosition,
        imgW: Float,
        imgH: Float,
        textW: Float,
        textH: Float,
        padding: Float,
        metrics: Paint.FontMetrics
    ): Pair<Float, Float> {
        val baseline = -metrics.ascent  // distance from ascent to baseline
        return when (position) {
            OverlayPosition.TOP_LEFT ->
                Pair(padding, padding + baseline)
            OverlayPosition.TOP_CENTER ->
                Pair((imgW - textW) / 2f, padding + baseline)
            OverlayPosition.TOP_RIGHT ->
                Pair(imgW - textW - padding, padding + baseline)
            OverlayPosition.BOTTOM_LEFT ->
                Pair(padding, imgH - padding - metrics.descent)
            OverlayPosition.BOTTOM_CENTER ->
                Pair((imgW - textW) / 2f, imgH - padding - metrics.descent)
            OverlayPosition.BOTTOM_RIGHT ->
                Pair(imgW - textW - padding, imgH - padding - metrics.descent)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
