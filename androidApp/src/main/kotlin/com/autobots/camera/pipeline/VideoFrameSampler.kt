package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.StageStats
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Decodes video samples at a fixed time interval via **hardware-accelerated** MediaCodec.
 *
 * Two output paths:
 *  - **surface** (preferred) — the decoder renders straight into an [ImageReader] in
 *    RGBA_8888, so the YUV→RGB conversion runs on the display pipeline instead of the CPU.
 *    Frames between samples are dropped with `releaseOutputBuffer(false)` and cost nothing.
 *  - **yuv** (fallback) — the pre-0.1.3 path: YUV_420_888 → NV21 → JPEG → Bitmap. Kept
 *    because the surface path depends on device support for RGBA ImageReader output.
 *
 * Frames are emitted **unrotated**, with the container rotation passed alongside. Rotating
 * a 4K frame costs ~52 ms, and only the handful of frames that survive detection need it.
 *
 * Since 0.1.4 `onFrame` is expected to **hand the frame off and return**, not to process it.
 * This loop is the producer half of the two-stage pipeline described on [VideoFrameProcessor];
 * blocking it is blocking the decoder.
 */
object VideoFrameSampler {
    private const val TAG = "VideoFrameSampler"
    private const val TIMEOUT_US = 10_000L

    /**
     * Surface decode is **off**.
     *
     * Measured on Xiaomi peridot / Android 16 / `c2.qti.avc.decoder`: a hardware video
     * decoder does not render into an `ImageReader` configured as `RGBA_8888`. All 216
     * frames of chunk 1 timed out — 54 s of dead waiting per chunk before the fallback
     * fired, taking realtimeRatio from 2.35× to 8.62×.
     *
     * Making it work needs `ImageFormat.PRIVATE` + `HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE`
     * + `Bitmap.wrapHardwareBuffer()` (API 29+), plus care so the `Image` outlives every
     * Bitmap derived from it. That is a different design and must be verified on a device
     * before it ships. See docs/RELEASE_0_1_3.md.
     */
    private const val SURFACE_DECODE_ENABLED = false

    /** Enough slack for the decoder to stay ahead without holding many 4K buffers. */
    private const val IMAGE_READER_BUFFERS = 3
    private const val IMAGE_WAIT_MS = 120L

    /**
     * Sampled frames to try before declaring the surface path unsupported. Being wrong
     * must cost milliseconds, not a minute per chunk.
     */
    private const val SURFACE_PROBE_FRAMES = 3

    /** Remembered per process: once the surface path fails, never pay for it again. */
    @Volatile
    private var surfaceUnsupported = false

    data class SampleStats(
        var decodeFailures: Int = 0,
        var unsupportedFormat: Int = 0,
        var emitted: Int = 0,
        var usedSurfacePath: Boolean = false,
    )

    /**
     * Prefer a hardware decoder. On API 29+ the platform answers directly; below that,
     * fall back to the name heuristic (Codec2 names like `c2.qti.*` carry no `omx`/`hw`).
     */
    private fun findHardwareDecoder(mime: String): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                info.name.contains("omx", ignoreCase = true) ||
                    info.name.contains("hw", ignoreCase = true)
            }
            if (isHardware) return info.name
        }
        return null
    }

    /**
     * @param onFrame receives the decoded frame **unrotated** plus the container rotation.
     */
    fun sampleFrames(
        file: File,
        intervalMs: Long,
        perf: StageStats? = null,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap, rotationDegrees: Int) -> Unit,
    ): SampleStats {
        val trySurface = SURFACE_DECODE_ENABLED && !surfaceUnsupported
        if (!trySurface) {
            return runSampler(file, intervalMs, perf, useSurface = false, onFrame = onFrame)
        }

        val surfaceStats = runSampler(file, intervalMs, perf, useSurface = true, onFrame = onFrame)
        if (surfaceStats.emitted > 0 || !surfaceStats.usedSurfacePath) {
            return surfaceStats
        }
        // Configured but produced nothing — this device cannot feed decoder output into the
        // ImageReader. The probe aborts after SURFACE_PROBE_FRAMES, so nothing was emitted
        // and the retry is side-effect free. Remember it so no later chunk pays again.
        surfaceUnsupported = true
        Log.w(TAG, "Surface path unsupported on this device; using YUV for the rest of the session")
        val fallback = runSampler(file, intervalMs, perf, useSurface = false, onFrame = onFrame)
        fallback.decodeFailures += surfaceStats.decodeFailures
        return fallback
    }

    private fun runSampler(
        file: File,
        intervalMs: Long,
        perf: StageStats?,
        useSurface: Boolean,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap, rotationDegrees: Int) -> Unit,
    ): SampleStats {
        val stats = SampleStats()
        val extractor = MediaExtractor()
        var reader: ImageReader? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) {
                Log.w(TAG, "No video track in ${file.name}")
                return stats
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return stats
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = readRotationDegrees(format, file)

            val hwDecoder = findHardwareDecoder(mime)
            val decoder = if (hwDecoder != null) {
                MediaCodec.createByCodecName(hwDecoder)
            } else {
                MediaCodec.createDecoderByType(mime)
            }

            val surface = if (useSurface) {
                runCatching {
                    ImageReader.newInstance(
                        width,
                        height,
                        PixelFormat.RGBA_8888,
                        IMAGE_READER_BUFFERS,
                    ).also { reader = it }.surface
                }.getOrNull()
            } else {
                null
            }
            stats.usedSurfacePath = surface != null

            val configured = runCatching {
                decoder.configure(format, surface, null, 0)
                decoder.start()
            }.isSuccess

            if (!configured) {
                runCatching { decoder.release() }
                if (surface != null) {
                    Log.w(TAG, "Surface configure failed for ${file.name}; caller will retry YUV")
                    return stats
                }
                Log.e(TAG, "Decoder configure failed for ${file.name}")
                return stats
            }

            Log.i(
                TAG,
                "Sampling ${file.name} ${width}x$height mime=$mime interval=${intervalMs}ms " +
                    "rotation=${rotation}° decoder=${hwDecoder ?: "default"} " +
                    "path=${if (surface != null) "surface" else "yuv"}",
            )

            try {
                decodeLoop(
                    extractor = extractor,
                    decoder = decoder,
                    reader = reader,
                    intervalMs = intervalMs,
                    stats = stats,
                    perf = perf,
                    rotationDegrees = rotation,
                    onFrame = onFrame,
                )
            } finally {
                runCatching {
                    decoder.stop()
                    decoder.release()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sample ${file.name}", t)
        } finally {
            runCatching { extractor.release() }
            runCatching { reader?.close() }
        }
        if (stats.decodeFailures > 0 || stats.unsupportedFormat > 0) {
            Log.w(
                TAG,
                "${file.name}: decodeFailures=${stats.decodeFailures} " +
                    "unsupportedFormat=${stats.unsupportedFormat}",
            )
        }
        return stats
    }

    private fun decodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        reader: ImageReader?,
        intervalMs: Long,
        stats: SampleStats,
        perf: StageStats?,
        rotationDegrees: Int,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap, rotationDegrees: Int) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastEmitUs = -intervalMs * 1_000L
        var surfaceAttempts = 0

        while (!outputDone) {
            // Probe: if the surface produced nothing in its first few tries, it never will.
            if (reader != null && stats.emitted == 0 && surfaceAttempts >= SURFACE_PROBE_FRAMES) {
                Log.w(TAG, "Surface probe failed after $surfaceAttempts frames; aborting")
                return
            }
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        val presentationUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationUs, 0)
                        extractor.advance()
                    }
                }
            }

            val dequeueStartNs = if (perf != null) System.nanoTime() else 0L
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    perf?.add("decode", System.nanoTime() - dequeueStartNs)
                    val ptsUs = bufferInfo.presentationTimeUs
                    val wanted = ptsUs - lastEmitUs >= intervalMs * 1_000L && bufferInfo.size > 0

                    val bitmap = if (reader != null) {
                        // Surface path: skipped frames are never converted at all.
                        decoder.releaseOutputBuffer(outputIndex, wanted)
                        if (wanted) {
                            surfaceAttempts++
                            CamPerf.timed(perf, "surface_rgba") { readSurfaceBitmap(reader, stats) }
                        } else {
                            null
                        }
                    } else {
                        val image = decoder.getOutputImage(outputIndex)
                        val decoded = if (wanted && image != null) {
                            CamPerf.timed(perf, "yuv_jpeg_argb") { imageToBitmap(image, stats) }
                        } else {
                            null
                        }
                        image?.close()
                        decoder.releaseOutputBuffer(outputIndex, false)
                        decoded
                    }

                    if (bitmap != null) {
                        // Handing the frame to Worker 2. Since 0.1.4 the callback only
                        // enqueues, so this measures backpressure — how long the decoder
                        // waited for a detect worker to free a slot — not detection itself.
                        CamPerf.timed(perf, "queue_wait") {
                            runBlocking { onFrame(ptsUs, bitmap, rotationDegrees) }
                        }
                        lastEmitUs = ptsUs
                        stats.emitted++
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
    }

    /**
     * The decoder renders asynchronously, so the rendered frame may not be queued the
     * instant `releaseOutputBuffer` returns — poll briefly rather than dropping it.
     */
    private fun readSurfaceBitmap(reader: ImageReader, stats: SampleStats): Bitmap? {
        val deadline = SystemClock.uptimeMillis() + IMAGE_WAIT_MS
        while (true) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull()
            if (image != null) {
                return try {
                    rgbaImageToBitmap(image, stats)
                } finally {
                    image.close()
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                stats.decodeFailures++
                return null
            }
            Thread.sleep(1)
        }
    }

    private fun rgbaImageToBitmap(image: Image, stats: SampleStats): Bitmap? {
        val plane = image.planes.firstOrNull() ?: run {
            stats.decodeFailures++
            return null
        }
        if (plane.pixelStride != 4) {
            stats.unsupportedFormat++
            return null
        }
        // rowStride can exceed width*4; decode at the padded width, then crop it off.
        val paddedWidth = plane.rowStride / plane.pixelStride
        return try {
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                    if (it !== padded) padded.recycle()
                }
            }
        } catch (t: Throwable) {
            if (stats.decodeFailures < 3) {
                Log.w(TAG, "rgbaImageToBitmap failed ${image.width}x${image.height}", t)
            }
            stats.decodeFailures++
            null
        }
    }

    /**
     * Rotation is metadata on the container, so decoded frames come out sideways for
     * phone-shot portrait video. Frames are emitted unrotated and [VideoFrameProcessor]
     * decides where rotation is cheapest. Recorded chunks report 0° and skip it entirely.
     */
    private fun readRotationDegrees(format: MediaFormat, file: File): Int {
        runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }
            .getOrNull()
            ?.let { return normalizeRotation(it) }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            normalizeRotation(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0,
            )
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Recycles [bitmap] when a rotated copy is produced. */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            Log.w(TAG, "Rotate by $degrees failed; using unrotated frame", t)
            bitmap
        }
    }

    private fun imageToBitmap(image: Image, stats: SampleStats): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            stats.unsupportedFormat++
            if (stats.unsupportedFormat <= 3) {
                Log.w(TAG, "Unsupported decode format=${image.format} ${image.width}x${image.height}")
            }
            return null
        }
        val nv21 = yuv420ToNv21(image) ?: run {
            stats.decodeFailures++
            return null
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        return try {
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 92, out)
            val bytes = out.toByteArray()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            if (stats.decodeFailures < 3) {
                Log.w(TAG, "imageToBitmap failed ${image.width}x${image.height}", t)
            }
            stats.decodeFailures++
            null
        } ?: run {
            stats.decodeFailures++
            null
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        var offset = ySize
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[offset++] = vBuffer.get(vIndex)
                nv21[offset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }
}
