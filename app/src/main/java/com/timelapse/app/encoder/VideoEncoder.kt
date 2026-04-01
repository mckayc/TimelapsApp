package com.timelapse.app.encoder

import android.graphics.Bitmap
import android.media.*
import android.util.Log
import com.timelapse.app.model.TimelapseSettings
import com.timelapse.app.overlay.OverlayRenderer
import java.io.File

/**
 * Encodes a sequence of Bitmaps into an H.264/MP4 using MediaCodec with an input Surface.
 */
class VideoEncoder(
    private val outputFile: File,
    private val encWidth: Int,
    private val encHeight: Int,
    private val settings: TimelapseSettings,
    private val captureStartMs: Long,
    private val rotation: Int = 0
) {
    private val TAG = "VideoEncoder"

    private var encoder: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var outputFrameCount = 0
    private val overlayRenderer = OverlayRenderer()

    fun start() {
        val bitRate = settings.videoQuality.bitRate
        val fps = settings.outputFps

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Use a 2-second I-frame interval. Too long (like 10s) can confuse some hardware encoders 
            // and cause them to default to a very high bitrate.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            
            // Default to Main Profile for better compatibility and more consistent bitrate control
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)

            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            
            // Try setting a max bitrate if the device supports it (API 21+)
            setInteger("max-bitrate", bitRate)
        }

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(rotation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            // Fallback to Baseline if Main fails
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(rotation)
            }
        }

        Log.d(TAG, "Encoder started: ${encWidth}x${encHeight} @ ${fps}fps, bitrate=$bitRate")
    }

    fun addFrame(bitmap: Bitmap) {
        val surf = inputSurface ?: return
        
        val canvas = surf.lockHardwareCanvas()
        try {
            // 1. Draw the bitmap with Center Crop
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = maxOf(encWidth.toFloat() / bitmap.width, encHeight.toFloat() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (encWidth - drawWidth) / 2f
            val top = (encHeight - drawHeight) / 2f
            val dest = android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, dest, null)

            // 2. Draw the overlay ON TOP of the cropped frame
            overlayRenderer.drawOverlay(canvas, encWidth, encHeight, settings, captureStartMs)
            
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
        drainEncoder(endOfStream = false)
    }

    fun finish(): File {
        val enc = encoder ?: return outputFile
        try {
            enc.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling EOS", e)
        }

        muxer?.let {
            if (muxerStarted) try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        muxer = null
        muxerStarted = false

        enc.stop()
        enc.release()
        encoder = null
        inputSurface?.release()
        inputSurface = null

        return outputFile
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer   ?: return
        val info = MediaCodec.BufferInfo()

        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    if (muxerStarted && info.size > 0 && 
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)) {
                        
                        info.presentationTimeUs = outputFrameCount * (1_000_000L / settings.outputFps)
                        outputFrameCount++
                        
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(videoTrackIndex, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }
}
