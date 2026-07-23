package com.autobots.camera.capture

import android.util.Log
import com.autobots.camera.delivery.PhotoDeliveryService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves JPEG bytes from the analysis stream to cache, then enqueues gallery delivery.
 */
class StreamFrameCapturer(
    private val executor: Executor,
    private val outputDir: File,
    private val deliveryService: PhotoDeliveryService,
) {
    private val sequence = AtomicInteger(0)
    private val sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun saveJpeg(jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        executor.execute {
            if (!outputDir.exists()) outputDir.mkdirs()
            val index = sequence.incrementAndGet()
            val file = File(outputDir, "stream_${sessionStamp}_${index}.jpg")
            try {
                file.writeBytes(jpeg)
                val enqueued = deliveryService.enqueueAll(listOf(file))
                Log.i(TAG, "Stream frame #$index saved (${jpeg.size} bytes) enqueued=$enqueued")
            } catch (t: Throwable) {
                Log.e(TAG, "Stream frame save failed", t)
            }
        }
    }

    fun resetSession() {
        sequence.set(0)
    }

    companion object {
        private const val TAG = "StreamFrameGrab"
    }
}
