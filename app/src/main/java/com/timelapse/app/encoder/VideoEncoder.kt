package com.timelapse.app.encoder

import android.graphics.Bitmap
import android.media.*
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a sequence of Bitmaps into an H.264/MP4 using MediaCodec in buffer mode.
 *
 * Frames are converted from ARGB to YUV420 and written directly into the encoder's
 * input ByteBuffers via getInputBuffer() — avoiding the getInputImage() API that
 * returned null on some devices, and avoiding Surface/Canvas which produced
 * uncompressed-sized output on others.
 *
 * At startup we query the device's H.264 encoder to find whether it prefers
 * NV12 (COLOR_FormatYUV420SemiPlanar) or I420 (COLOR_FormatYUV420Planar) and
 * write the appropriate layout. Both share the same BT.601 maths; only the
 * chroma plane arrangement differs.
 */
class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 4_000_000   // 4 Mbps VBR
) {
    private val TAG = "VideoEncoder"

    // Force 1080p: portrait → 1080×1920, landscape → 1920×1080
    private val encWidth:  Int = if (width <= height) 1080 else 1920
    private val encHeight: Int = if (width <= height) 1920 else 1080

    // Pre-allocated scratch buffer — avoids a new IntArray allocation every frame.
    private val argbPixels = IntArray(encWidth * encHeight)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var frameCount = 0
    private var ptsUs = 0L

    // Detected at start() — NV12 on most hardware, I420 on some software encoders.
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    // -------------------------------------------------------------------------

    fun start() {
        colorFormat = findSupportedColorFormat()

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        Log.d(TAG, "Encoder started: ${encWidth}×${encHeight} @ ${fps}fps " +
                   "colorFormat=${colorFormatName(colorFormat)} bitRate=${bitRate/1000}kbps")
    }

    /**
     * Convert one bitmap to YUV420 and hand it to the encoder.
     * Scales to encoder dimensions if needed.
     */
    fun addFrame(bitmap: Bitmap) {
        val enc = encoder ?: return

        val inputIdx = enc.dequeueInputBuffer(10_000L)
        if (inputIdx < 0) {
            Log.w(TAG, "No input buffer — dropping frame $frameCount")
            return
        }

        val buffer = enc.getInputBuffer(inputIdx)
        if (buffer == null) {
            Log.w(TAG, "getInputBuffer returned null — dropping frame $frameCount")
            enc.queueInputBuffer(inputIdx, 0, 0, ptsUs, 0)
            return
        }

        buffer.clear()
        writeYuv(bitmap, buffer)

        // YUV420 = W*H luma + W/2*H/2*2 chroma = W*H*3/2 bytes total
        enc.queueInputBuffer(inputIdx, 0, encWidth * encHeight * 3 / 2, ptsUs, 0)
        ptsUs += 1_000_000L / fps

        frameCount++
        drainEncoder(endOfStream = false)
    }

    /**
     * Flush the encoder, write the final MP4, and release all resources.
     */
    fun finish(): File {
        val enc = encoder ?: return outputFile

        // Signal end-of-stream by queueing an empty buffer with the EOS flag
        val inputIdx = enc.dequeueInputBuffer(10_000L)
        if (inputIdx >= 0) {
            enc.queueInputBuffer(inputIdx, 0, 0, ptsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        drainEncoder(endOfStream = true)

        muxer?.stop()
        muxer?.release()
        muxer = null
        enc.stop()
        enc.release()
        encoder = null

        Log.d(TAG, "Finished: $frameCount frames → ${outputFile.absolutePath}")
        return outputFile
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Walk all H.264 encoders on the device and return the first supported
     * YUV format in preference order: NV12 > I420.
     * NV12 is natively preferred by most Qualcomm/MediaTek hardware encoders.
     * I420 is common on older or software encoders.
     */
    private fun findSupportedColorFormat(): Int {
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, // NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar      // I420
        )
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (MediaFormat.MIMETYPE_VIDEO_AVC !in info.supportedTypes) continue
            val supported = info
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .colorFormats.toSet()
            for (fmt in preferred) {
                if (fmt in supported) {
                    Log.d(TAG, "Color format ${colorFormatName(fmt)} from ${info.name}")
                    return fmt
                }
            }
        }
        Log.w(TAG, "No preferred format found — defaulting to NV12")
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    /**
     * Convert bitmap pixels to YUV420 and write into [buffer].
     *
     * BT.601 limited-range coefficients:
     *   Y =  16 + (  66R + 129G +  25B) / 256
     *   U = 128 + ( -38R -  74G + 112B) / 256
     *   V = 128 + ( 112R -  94G -  18B) / 256
     *
     * NV12 layout  (SemiPlanar):  [Y…] [U0 V0 U1 V1 …]
     * I420 layout  (Planar):      [Y…] [U…] [V…]
     */
    private fun writeYuv(bitmap: Bitmap, buffer: ByteBuffer) {
        val src = if (bitmap.width != encWidth || bitmap.height != encHeight)
            Bitmap.createScaledBitmap(bitmap, encWidth, encHeight, true)
        else
            bitmap

        src.getPixels(argbPixels, 0, encWidth, 0, 0, encWidth, encHeight)
        if (src !== bitmap) src.recycle()

        // ── Y plane ──────────────────────────────────────────────────────────
        for (i in argbPixels.indices) {
            val p = argbPixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            buffer.put(((66 * r + 129 * g + 25 * b + 128) ushr 8 + 16).toByte())
        }

        val isNv12 = colorFormat ==
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        if (isNv12) {
            // ── NV12 UV plane: interleaved U,V per 2×2 block ─────────────────
            for (row in 0 until encHeight step 2) {
                for (col in 0 until encWidth step 2) {
                    val p = argbPixels[row * encWidth + col]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8)  and 0xFF
                    val b =  p         and 0xFF
                    buffer.put(((-38 * r -  74 * g + 112 * b + 128) ushr 8 + 128).toByte())
                    buffer.put(((112 * r -  94 * g -  18 * b + 128) ushr 8 + 128).toByte())
                }
            }
        } else {
            // ── I420 U plane, then V plane ────────────────────────────────────
            for (row in 0 until encHeight step 2) {
                for (col in 0 until encWidth step 2) {
                    val p = argbPixels[row * encWidth + col]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8)  and 0xFF
                    val b =  p         and 0xFF
                    buffer.put(((-38 * r -  74 * g + 112 * b + 128) ushr 8 + 128).toByte())
                }
            }
            for (row in 0 until encHeight step 2) {
                for (col in 0 until encWidth step 2) {
                    val p = argbPixels[row * encWidth + col]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8)  and 0xFF
                    val b =  p         and 0xFF
                    buffer.put(((112 * r -  94 * g -  18 * b + 128) ushr 8 + 128).toByte())
                }
            }
        }
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
                    // Keep polling until the EOS output buffer arrives
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer started, track: $videoTrackIndex")
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && muxerStarted) {
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

    private fun colorFormatName(fmt: Int) = when (fmt) {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "NV12"
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar     -> "I420"
        else -> fmt.toString()
    }
}
