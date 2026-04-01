package com.timelapse.app.overlay

import android.graphics.*
import com.timelapse.app.model.OverlayPosition
import com.timelapse.app.model.OverlayType
import com.timelapse.app.model.TimelapseSettings
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Renders text overlays (timestamp / stopwatch / static text) onto a Canvas.
 */
class OverlayRenderer {

    /**
     * Draws the overlay directly onto the provided Canvas.
     */
    fun drawOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        settings: TimelapseSettings,
        captureStartMs: Long
    ) {
        if (settings.overlayType == OverlayType.NONE) return

        val text = when (settings.overlayType) {
            OverlayType.TIMESTAMP -> SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
            OverlayType.STOPWATCH -> {
                if (captureStartMs == 0L) "00:00"
                else formatElapsed(System.currentTimeMillis() - captureStartMs)
            }
            OverlayType.STATIC_TEXT -> settings.overlayText.ifBlank { "Timelapse" }
            OverlayType.NONE -> return
        }

        // Scale text size relative to the SMALLER dimension to ensure it's never too big
        val minDim = minOf(width, height)
        val scaleFactor = minDim / 400f
        val textSizePx = settings.overlayTextSizeSp * scaleFactor

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            style = Paint.Style.FILL
        }

        val shadowPaint = Paint(textPaint).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = textSizePx * 0.12f // Thicker shadow for better visibility
        }

        // Increase padding to ensure text isn't cut off by rounded corners or system UI
        val padding = textSizePx * 0.8f
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent

        val (x, y) = computePosition(
            settings.overlayPosition,
            width.toFloat(),
            height.toFloat(),
            textWidth,
            textHeight,
            padding,
            metrics
        )

        canvas.drawText(text, x, y, shadowPaint)
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * Legacy method for cases where we still need a bitmap (e.g. preview).
     */
    fun applyOverlay(
        source: Bitmap,
        settings: TimelapseSettings,
        captureStartMs: Long
    ): Bitmap {
        if (settings.overlayType == OverlayType.NONE) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        drawOverlay(canvas, output.width, output.height, settings, captureStartMs)
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
        val baseline = -metrics.ascent
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
