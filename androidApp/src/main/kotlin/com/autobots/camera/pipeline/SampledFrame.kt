package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.max

/**
 * One sampled frame in flight between the decoder thread and a detect worker.
 *
 * Up to 0.1.4 this was a decoded ARGB_8888 bitmap — ~33 MB at UHD, produced on the decoder
 * thread for **every** sampled frame. Measured on run4mins, only 579 of 2281 frames (25%)
 * ever needed those pixels: the rest were rejected on a 640 px detect bitmap and the 4K
 * decode was thrown away. `yuv_jpeg_argb` was 89–92% of the session's wall clock.
 *
 * So the frame travels **compressed** and decodes lazily, on the worker:
 *  - [decode] with a sample size > 1 for the detect pass — 1/s² of the pixels;
 *  - [decode] with 1 only once a frame has passed the size gate.
 *
 * Two consequences, and both are the point:
 *  - the full-resolution decode moves **off** the decoder thread and onto the detect workers,
 *    which `worker_idle` measured as idle ~92% of the time. Work moved there is nearly free.
 *  - the frame queue's memory ceiling drops from `capacity × ~33 MB` to `capacity × ~2 MB`,
 *    which is what allows [VideoFrameProcessor.FRAME_QUEUE_CAPACITY] to grow.
 *
 * The bytes are the same JPEG the YUV path already produced on its way to a bitmap, and the
 * full-size decode uses the same options, so a **saved photo is unchanged**. The detect
 * bitmap is not: see [VideoFrameProcessor.sampleSizeFor].
 */
class SampledFrame private constructor(
    val timestampUs: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    private val jpeg: ByteArray?,
    /** Surface path only — already-decoded pixels this frame owns and must recycle. */
    private val owned: Bitmap?,
) {

    /**
     * Decode at 1/[sampleSize] in each axis. The caller **owns** the result in every case,
     * including the surface path, so there is one ownership rule rather than two.
     *
     * @return null when the bytes cannot be decoded; the caller counts the frame as skipped.
     */
    fun decode(sampleSize: Int): Bitmap? {
        val ready = owned
        if (ready != null) {
            // The surface path is disabled (see VideoFrameSampler.SURFACE_DECODE_ENABLED), so
            // this copy costs nothing today. It is here so that reviving that path cannot
            // hand two consumers the same bitmap.
            return runCatching {
                if (sampleSize <= 1) {
                    ready.copy(ready.config ?: Bitmap.Config.ARGB_8888, false)
                } else {
                    Bitmap.createScaledBitmap(
                        ready,
                        max(1, ready.width / sampleSize),
                        max(1, ready.height / sampleSize),
                        true,
                    )
                }
            }.getOrNull()
        }
        val bytes = jpeg ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = max(1, sampleSize)
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            Log.w(TAG, "decode(sampleSize=$sampleSize) failed at ${timestampUs}us", t)
            null
        }
    }

    /**
     * Release what this frame holds. JPEG-backed frames own nothing the GC cannot take, so
     * this is a no-op for them — it exists so the queue's `onUndeliveredElement` and the
     * worker's reject path have one thing to call.
     */
    fun release() {
        runCatching { owned?.recycle() }
    }

    companion object {
        private const val TAG = "SampledFrame"

        /** YUV path: the frame the decoder produced, still compressed. */
        fun ofJpeg(
            timestampUs: Long,
            rotationDegrees: Int,
            width: Int,
            height: Int,
            jpeg: ByteArray,
        ) = SampledFrame(timestampUs, rotationDegrees, width, height, jpeg, null)

        /** Surface path: pixels are already decoded, so there is nothing to defer. */
        fun ofBitmap(timestampUs: Long, rotationDegrees: Int, bitmap: Bitmap) =
            SampledFrame(timestampUs, rotationDegrees, bitmap.width, bitmap.height, null, bitmap)
    }
}
