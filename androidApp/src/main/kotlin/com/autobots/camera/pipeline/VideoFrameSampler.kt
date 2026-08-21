package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.autobots.BuildConfig
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
 *  - **yuv** (fallback) — the pre-0.1.3 path: YUV_420_888 → NV21 → JPEG. Kept because the
 *    surface path depends on device support for RGBA ImageReader output.
 *
 * The YUV path stops at the **JPEG**. Decoding those bytes to ARGB used to happen here too,
 * for every sampled frame, and was the single largest cost in the pipeline; it now happens
 * on a detect worker, at detect resolution, and at full resolution only for frames that earn
 * it. See [SampledFrame]. The two halves are timed separately (`yuv_nv21`, `nv21_jpeg`) so
 * what is left on this thread can be attributed rather than guessed at.
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
     * How many frames per session get checked against the old per-pixel conversion.
     *
     * A handful is enough: the plane layout is a property of the decoder and the format, not
     * of the picture, so if it holds for the first frames it holds for the session. Checking
     * every frame would cost more than the optimisation saves.
     */
    private const val FAST_PATH_VERIFY_FRAMES = 3

    /** Pixel pairs compared when working out the chroma layout. One row's worth is plenty. */
    private const val PROBE_PAIRS = 64

    /**
     * Quality of the intermediate JPEG the YUV path produces.
     *
     * Left at 92, where 0.1.4 had it as an unnamed implementation detail on the way to a
     * bitmap. It is named now because those bytes became the thing that crosses the thread
     * boundary and gets decoded twice — so this, not the 95 used for delivery, is what
     * actually bounds the quality of a saved photo. That was equally true before (the chain
     * was already YUV → 92 → ARGB → 95) and is left alone deliberately: changing it would
     * move image quality in the same release that moves timings.
     */
    private const val JPEG_QUALITY = 92

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
     * @param onFrame receives the sampled frame **unrotated** and, on the YUV path, still
     *   compressed — the callback decides what resolution it needs. See [SampledFrame].
     */
    fun sampleFrames(
        file: File,
        intervalMs: Long,
        perf: StageStats? = null,
        onFrame: suspend (frame: SampledFrame) -> Unit,
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
        onFrame: suspend (frame: SampledFrame) -> Unit,
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
        onFrame: suspend (frame: SampledFrame) -> Unit,
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

                    val frame = if (reader != null) {
                        // Surface path: skipped frames are never converted at all.
                        decoder.releaseOutputBuffer(outputIndex, wanted)
                        if (wanted) {
                            surfaceAttempts++
                            CamPerf.timed(perf, "surface_rgba") { readSurfaceBitmap(reader, stats) }
                                ?.let { SampledFrame.ofBitmap(ptsUs, rotationDegrees, it) }
                        } else {
                            null
                        }
                    } else {
                        val image = decoder.getOutputImage(outputIndex)
                        // Note there is no ARGB decode here any more — the bytes go to the
                        // worker as they are. See the class doc and [SampledFrame].
                        val encoded = if (wanted && image != null) {
                            imageToJpeg(image, stats, perf)
                        } else {
                            null
                        }
                        image?.close()
                        decoder.releaseOutputBuffer(outputIndex, false)
                        encoded?.let {
                            SampledFrame.ofJpeg(ptsUs, rotationDegrees, it.width, it.height, it.bytes)
                        }
                    }

                    if (frame != null) {
                        // Handing the frame to Worker 2. Since 0.1.4 the callback only
                        // enqueues, so this measures backpressure — how long the decoder
                        // waited for a detect worker to free a slot — not detection itself.
                        CamPerf.timed(perf, "queue_wait") {
                            runBlocking { onFrame(frame) }
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

    /** A frame the decoder produced, compressed and ready to hand to a worker. */
    private class EncodedFrame(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * YUV_420_888 → NV21 → JPEG, and stop there.
     *
     * The two halves are timed apart because they behave differently and only one of them
     * has anywhere left to go: `yuv_nv21` is a per-pixel loop in Kotlin over the chroma
     * planes, `nv21_jpeg` is platform code. Together they are what remains of the old
     * `yuv_jpeg_argb` on this thread once the ARGB decode moved to the workers, so the next
     * run can say which of the two is now the producer's real cost.
     */
    private fun imageToJpeg(image: Image, stats: SampleStats, perf: StageStats?): EncodedFrame? {
        if (image.format != ImageFormat.YUV_420_888) {
            stats.unsupportedFormat++
            if (stats.unsupportedFormat <= 3) {
                Log.w(TAG, "Unsupported decode format=${image.format} ${image.width}x${image.height}")
            }
            return null
        }
        val nv21 = CamPerf.timed(perf, "yuv_nv21") { yuv420ToNv21(image) } ?: run {
            stats.decodeFailures++
            return null
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        return try {
            CamPerf.timed(perf, "nv21_jpeg") {
                yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            }
            EncodedFrame(out.toByteArray(), image.width, image.height)
        } catch (t: Throwable) {
            if (stats.decodeFailures < 3) {
                Log.w(TAG, "imageToJpeg failed ${image.width}x${image.height}", t)
            }
            stats.decodeFailures++
            null
        }
    }

    /**
     * YUV_420_888 → NV21.
     *
     * Measured at **63.9 ms/frame and 61.7 % of the whole import** on 4K (session
     * `ext_v0_1_5_20082026_1725`), which made it the single largest cost in the pipeline and
     * the reason the producer thread, not the detector, set the pace — the workers sat idle
     * 109 ms per frame waiting for it.
     *
     * The cost was never the copying; it was doing it one byte at a time. At 4K the chroma
     * loop ran 2,073,600 times per frame with two bounds-checked `ByteBuffer.get(int)` calls
     * each. The fast paths do the same work in ~1,080 bulk reads.
     *
     * **Which fast path is right depends on the device**, and guessing was wrong: this phone
     * hands back a U-first (NV12) buffer, so reading forward from the V plane yields
     * `V0 U1 V1 U2 …` — the chroma shifted by one sample, which is a colour bug that no test
     * would fail and no crash would announce. [pickChromaCopy] therefore *measures* the
     * layout on every frame instead of assuming it, and falls back to the per-pixel loop when
     * neither shape matches.
     */
    private fun yuv420ToNv21(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val yBuffer = planes[0].buffer
        val ySize = yBuffer.remaining()
        val uSize = planes[1].buffer.remaining()
        val vSize = planes[2].buffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val chromaHeight = image.height / 2
        val chromaWidth = image.width / 2

        when (pickChromaCopy(planes, chromaWidth)) {
            ChromaCopy.FromV -> {
                copyChromaRows(planes[2].buffer, nv21, ySize, planes[2].rowStride, chromaWidth, chromaHeight, swap = false)
                patchLastPair(planes, nv21, ySize, chromaWidth, chromaHeight)
            }
            ChromaCopy.FromUSwapped -> {
                copyChromaRows(planes[1].buffer, nv21, ySize, planes[1].rowStride, chromaWidth, chromaHeight, swap = true)
                patchLastPair(planes, nv21, ySize, chromaWidth, chromaHeight)
            }
            ChromaCopy.Slow ->
                copyChromaPlanar(planes, nv21, ySize, chromaWidth, chromaHeight)
        }

        if (BuildConfig.DEBUG && verifiedFastFrames < FAST_PATH_VERIFY_FRAMES) {
            verifyFastPath(planes, nv21, ySize, chromaWidth, chromaHeight)
        }
        return nv21
    }

    private enum class ChromaCopy { FromV, FromUSwapped, Slow }

    /**
     * Work out how this device lays out chroma, by trying each shape on the first few pixels
     * and comparing against the per-pixel loop.
     *
     * Done per frame rather than cached per session: the probe is [PROBE_PAIRS] iterations of
     * the slow loop — microseconds — and caching would mean a stale answer the first time a
     * different decoder or resolution comes through. Cheap enough that correctness wins.
     */
    private fun pickChromaCopy(planes: Array<out Image.Plane>, chromaWidth: Int): ChromaCopy {
        if (planes[1].pixelStride != 2 || planes[2].pixelStride != 2) return ChromaCopy.Slow
        val pairs = minOf(PROBE_PAIRS, chromaWidth)
        val bytes = pairs * 2

        val expected = ByteArray(bytes)
        copyChromaPlanar(planes, expected, 0, pairs, 1)

        val candidate = ByteArray(bytes)
        if (readRow(planes[2].buffer, candidate, 0, 0, bytes, swap = false) &&
            candidate.contentEquals(expected)
        ) {
            return ChromaCopy.FromV
        }
        if (readRow(planes[1].buffer, candidate, 0, 0, bytes, swap = true) &&
            candidate.contentEquals(expected)
        ) {
            return ChromaCopy.FromUSwapped
        }
        return ChromaCopy.Slow
    }

    /**
     * Copy the chroma plane a row at a time.
     *
     * @param swap true when the source is U-first: the bytes arrive as `U V U V` and NV21
     *   wants `V U V U`, so each pair is exchanged in place — still an array-local loop, which
     *   costs a fraction of the bounds-checked buffer reads it replaces.
     */
    private fun copyChromaRows(
        source: java.nio.ByteBuffer,
        dest: ByteArray,
        offset: Int,
        rowStride: Int,
        chromaWidth: Int,
        chromaHeight: Int,
        swap: Boolean,
    ) {
        val rowBytes = chromaWidth * 2
        var out = offset
        for (row in 0 until chromaHeight) {
            if (!readRow(source, dest, out, row * rowStride, rowBytes, swap)) break
            out += rowBytes
        }
    }

    /**
     * One bulk read into [dest], optionally exchanging byte pairs.
     *
     * @return false when the source has nothing left to give — the last row of a semi-planar
     *   buffer is one byte short of a full pair, and that final byte is the second chroma
     *   sample of the last pixel, which no decoder reads.
     */
    private fun readRow(
        source: java.nio.ByteBuffer,
        dest: ByteArray,
        destOffset: Int,
        sourceStart: Int,
        length: Int,
        swap: Boolean,
    ): Boolean {
        val view = source.duplicate()
        if (sourceStart >= view.limit()) return false
        val len = minOf(length, view.limit() - sourceStart, dest.size - destOffset)
        if (len <= 0) return false
        view.position(sourceStart)
        view.get(dest, destOffset, len)
        if (swap) {
            var i = destOffset
            val end = destOffset + len - 1
            while (i < end) {
                val first = dest[i]
                dest[i] = dest[i + 1]
                dest[i + 1] = first
                i += 2
            }
        }
        return len == length
    }

    /**
     * Write the final chroma pair the way the per-pixel loop would.
     *
     * A semi-planar buffer ends one byte before its last pair is complete: whichever plane the
     * bulk copy reads from, the very last sample lives in the *other* plane's view. Two direct
     * reads settle it, and doing so lets the verification compare every byte with no
     * exceptions — an excluded byte is a byte nothing checks.
     */
    private fun patchLastPair(
        planes: Array<out Image.Plane>,
        dest: ByteArray,
        offset: Int,
        chromaWidth: Int,
        chromaHeight: Int,
    ) {
        val out = offset + (chromaHeight * chromaWidth * 2) - 2
        if (out < offset || out + 1 >= dest.size) return
        val vIndex = (chromaHeight - 1) * planes[2].rowStride + (chromaWidth - 1) * planes[2].pixelStride
        val uIndex = (chromaHeight - 1) * planes[1].rowStride + (chromaWidth - 1) * planes[1].pixelStride
        if (vIndex < planes[2].buffer.limit()) dest[out] = planes[2].buffer.get(vIndex)
        if (uIndex < planes[1].buffer.limit()) dest[out + 1] = planes[1].buffer.get(uIndex)
    }

    /** The original per-pixel path. Correct for any layout, and slow enough to prove it. */
    private fun copyChromaPlanar(
        planes: Array<out Image.Plane>,
        dest: ByteArray,
        offset: Int,
        chromaWidth: Int,
        chromaHeight: Int,
    ) {
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        var out = offset
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                dest[out++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                dest[out++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }
    }

    /**
     * Debug builds only: run the slow path over the same frame and compare byte for byte.
     *
     * A colour bug here would not crash and would not fail a test — it would quietly ship
     * green faces. Comparing against the implementation this replaces is the only check that
     * actually proves the result on a device, and it has already earned its place: it caught
     * the U-first layout on the first run.
     */
    private fun verifyFastPath(
        planes: Array<out Image.Plane>,
        produced: ByteArray,
        offset: Int,
        chromaWidth: Int,
        chromaHeight: Int,
    ) {
        verifiedFastFrames++
        val reference = ByteArray(produced.size)
        copyChromaPlanar(planes, reference, offset, chromaWidth, chromaHeight)
        val end = minOf(offset + chromaWidth * chromaHeight * 2, produced.size)
        val mismatch = (offset until end).firstOrNull { produced[it] != reference[it] }
        if (mismatch == null) {
            Log.i(
                TAG,
                "yuv_nv21 verified byte-exact via ${pickChromaCopy(planes, chromaWidth)} " +
                    "(frame $verifiedFastFrames)",
            )
        } else {
            val rel = mismatch - offset
            fun dump(a: ByteArray) = (0 until 16).joinToString(" ") { "%02x".format(a[offset + it]) }
            Log.e(
                TAG,
                "yuv_nv21 MISMATCH at chroma[$rel]\n  fast  ${dump(produced)}\n  slow  ${dump(reference)}",
            )
        }
    }

    private var verifiedFastFrames = 0
}
