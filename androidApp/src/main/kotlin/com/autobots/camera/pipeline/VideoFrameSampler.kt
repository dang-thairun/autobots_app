package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.StageStats
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Decodes video samples at a fixed time interval via MediaCodec.
 */
object VideoFrameSampler {
    private const val TAG = "VideoFrameSampler"
    private const val TIMEOUT_US = 10_000L

    data class SampleStats(
        var decodeFailures: Int = 0,
        var unsupportedFormat: Int = 0,
    )

    fun sampleFrames(
        file: File,
        intervalMs: Long,
        perf: StageStats? = null,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap) -> Unit,
    ): SampleStats {
        val stats = SampleStats()
        val extractor = MediaExtractor()
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
            Log.d(
                TAG,
                "Sampling ${file.name} ${width}x$height mime=$mime " +
                    "interval=${intervalMs}ms rotation=${rotation}°",
            )

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            try {
                decodeLoop(extractor, decoder, intervalMs, stats, perf, rotation, onFrame)
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
        intervalMs: Long,
        stats: SampleStats,
        perf: StageStats?,
        rotationDegrees: Int,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastEmitUs = -intervalMs * 1_000L

        while (!outputDone) {
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
                    val image = decoder.getOutputImage(outputIndex)
                    val ptsUs = bufferInfo.presentationTimeUs
                    if (image != null &&
                        ptsUs - lastEmitUs >= intervalMs * 1_000L &&
                        bufferInfo.size > 0
                    ) {
                        val decoded = CamPerf.timed(perf, "yuv_jpeg_argb") {
                            imageToBitmap(image, stats)
                        }
                        image.close()
                        val bitmap = if (decoded != null && rotationDegrees != 0) {
                            CamPerf.timed(perf, "rotate") { rotate(decoded, rotationDegrees) }
                        } else {
                            decoded
                        }
                        if (bitmap != null) {
                            // Everything downstream runs here, blocking the decoder.
                            CamPerf.timed(perf, "decoder_blocked") {
                                runBlocking { onFrame(ptsUs, bitmap) }
                            }
                            lastEmitUs = ptsUs
                        }
                    } else {
                        image?.close()
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
    }

    /**
     * Rotation is metadata on the container, so decoded frames come out sideways for
     * phone-shot portrait video. ML Kit is handed rotation 0, so upright them here or
     * no face is ever found. Recorded chunks report 0° and skip this entirely.
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

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
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
