package com.timelapse.app.encoder

import android.graphics.Bitmap
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

    // Dimensions must be multiples of 2 for H.264
    private val encWidth  = if (width  % 2 == 0) width  else width  - 1
    private val encHeight = if (height % 2 == 0) height else height - 1

    private var encoder: MediaCodec? = null
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
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
     * The bitmap will be scaled to [encWidth × encHeight] if needed.
     */
    fun addFrame(bitmap: Bitmap) {
        val enc = encoder ?: return

        val scaled = if (bitmap.width != encWidth || bitmap.height != encHeight) {
            Bitmap.createScaledBitmap(bitmap, encWidth, encHeight, true)
        } else {
            bitmap
        }

        val yuv = argbToNv12(scaled)
        if (scaled !== bitmap) scaled.recycle()

        // Feed YUV data to the encoder
        var attempts = 0
        var inputIdx = -1
        while (inputIdx < 0 && attempts < 20) {
            inputIdx = enc.dequeueInputBuffer(5_000L)
            attempts++
        }

        if (inputIdx >= 0) {
            val buf = enc.getInputBuffer(inputIdx)!!
            buf.clear()
            buf.put(yuv)
            val ptsUs = frameCount.toLong() * 1_000_000L / fps
            enc.queueInputBuffer(inputIdx, 0, yuv.size, ptsUs, 0)
            frameCount++
        } else {
            Log.w(TAG, "Could not dequeue input buffer, dropping frame")
        }

        drainEncoder(endOfStream = false)
    }

    /**
     * Signal end of stream, drain the encoder, stop and release everything.
     * @return the output MP4 file.
     */
    fun finish(): File {
        val enc = encoder ?: return outputFile

        // Signal EOS
        val inputIdx = enc.dequeueInputBuffer(10_000L)
        if (inputIdx >= 0) {
            enc.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        drainEncoder(endOfStream = true)

        muxer?.stop()
        muxer?.release()
        enc.stop()
        enc.release()
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

    /**
     * Convert ARGB_8888 Bitmap to NV12 (YUV420SemiPlanar).
     * Layout: [Y plane: w*h bytes] [UV plane: w*h/2 bytes, interleaved U,V pairs]
     */
    private fun argbToNv12(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)

        val nv12 = ByteArray(w * h * 3 / 2)
        var yIdx = 0
        var uvIdx = w * h

        for (row in 0 until h) {
            for (col in 0 until w) {
                val px = argb[row * w + col]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8)  and 0xFF
                val b =  px         and 0xFF

                // BT.601 limited-range conversion
                val y = ((66 * r + 129 * g +  25 * b + 128) shr 8) + 16
                nv12[yIdx++] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r -  74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r -  94 * g -  18 * b + 128) shr 8) + 128
                    nv12[uvIdx++] = u.coerceIn(0, 255).toByte()  // Cb
                    nv12[uvIdx++] = v.coerceIn(0, 255).toByte()  // Cr
                }
            }
        }
        return nv12
    }
}
