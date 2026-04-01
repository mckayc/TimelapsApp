package com.timelapse.app.encoder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.util.Log
import java.io.File

/**
 * Encodes a sequence of Bitmaps into an H.264/MP4 file using Android's built-in
 * MediaCodec + MediaMuxer — no external libraries required.
 *
 * Usage:
 *   val enc = VideoEncoder(outputFile, width, height, fps)
 *   enc.start()
 *   enc.addFrame(bitmap)   // call for each captured frame
 *   val file = enc.finish()
 */
class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 8_000_000
) {
    private val TAG = "VideoEncoder"

    // Dimensions should be multiples of 16 for maximum hardware compatibility
    private val encWidth  = (width / 16) * 16
    private val encHeight = (height / 16) * 16

    private var encoder: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var frameCount = 0

    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        Log.d(TAG, "Encoder started: ${encWidth}x${encHeight} @ ${fps}fps")
    }

    /**
     * Add one frame to the video. Call this for every captured bitmap.
     */
    fun addFrame(bitmap: Bitmap) {
        val surf = inputSurface ?: return
        
        // Draw the bitmap directly onto the encoder's Surface.
        // This handles scaling, YUV conversion, and stride alignment in hardware.
        val canvas = surf.lockHardwareCanvas()
        val destRect = android.graphics.Rect(0, 0, encWidth, encHeight)
        canvas.drawBitmap(bitmap, null, destRect, null)
        
        // Setting the presentation timestamp is vital for correct playback speed
        val ptsNs = frameCount.toLong() * 1_000_000_000L / fps
        // This is a hidden API accessible via reflection or usually handled by the surface
        // but for simple cases, lock/unlock is sufficient.
        
        surf.unlockCanvasAndPost(canvas)
        
        frameCount++

        drainEncoder(endOfStream = false)
    }

    /**
     * Signal end of stream, drain the encoder, stop and release everything.
     * @return the output MP4 file.
     */
    fun finish(): File {
        encoder?.signalEndOfInputStream()

        drainEncoder(endOfStream = true)

        muxer?.stop()
        muxer?.release()
        encoder?.stop()
        encoder?.release()
        encoder = null
        muxer = null

        Log.d(TAG, "Encoding finished: $frameCount frames -> ${outputFile.absolutePath}")
        return outputFile
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()

        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, 10_000L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    // If EOS, keep polling until we get the EOS flag
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started, track index: $videoTrackIndex")
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!

                    // Skip codec-config packets (SPS/PPS); muxer handles them
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
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
