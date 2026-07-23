package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes video samples at a fixed time interval via MediaCodec.
 */
object VideoFrameSampler {
    private const val TAG = "VideoFrameSampler"
    private const val TIMEOUT_US = 10_000L

    fun sampleFrames(
        file: File,
        intervalMs: Long,
        onFrame: suspend (timestampUs: Long, bitmap: Bitmap) -> Unit,
    ) {
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
                return
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            try {
                decodeLoop(extractor, decoder, intervalMs, onFrame)
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
    }

    private fun decodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        intervalMs: Long,
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

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    val image = decoder.getOutputImage(outputIndex)
                    val ptsUs = bufferInfo.presentationTimeUs
                    if (image != null &&
                        ptsUs - lastEmitUs >= intervalMs * 1_000L &&
                        bufferInfo.size > 0
                    ) {
                        imageToBitmap(image)?.let { bitmap ->
                            runBlocking { onFrame(ptsUs, bitmap) }
                            lastEmitUs = ptsUs
                        }
                        image.close()
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

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        return try {
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
            android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (_: Throwable) {
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
