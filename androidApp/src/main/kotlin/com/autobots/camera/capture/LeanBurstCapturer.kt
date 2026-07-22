package com.autobots.camera.capture

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 5 Lean Burst — Keep-All (no scoring). Writes JPEG to [outputDir].
 */
class LeanBurstCapturer(
    private val imageCapture: ImageCapture,
    private val executor: Executor,
    private val outputDir: File,
) {
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    fun capture(
        shotCount: Int = DEFAULT_SHOTS,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        onComplete: (savedCount: Int, files: List<File>) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "Burst skipped — already in progress")
            return
        }
        if (!outputDir.exists()) outputDir.mkdirs()

        val total = shotCount.coerceIn(1, 5)
        val saved = mutableListOf<File>()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        fun finish() {
            busy.set(false)
            onComplete(saved.size, saved.toList())
        }

        fun takeAt(index: Int) {
            val file = File(outputDir, "burst_${stamp}_${index + 1}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        saved += file
                        Log.i(TAG, "Saved ${file.name} (${saved.size}/$total)")
                        if (index + 1 >= total) {
                            finish()
                        } else {
                            handler.postDelayed({ takeAt(index + 1) }, intervalMs)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "takePicture failed at $index", exception)
                        if (index + 1 >= total) {
                            finish()
                        } else {
                            handler.postDelayed({ takeAt(index + 1) }, intervalMs)
                        }
                    }
                },
            )
        }

        takeAt(0)
    }

    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        busy.set(false)
    }

    companion object {
        private const val TAG = "LeanBurst"
        const val DEFAULT_SHOTS = 3
        /** Gap between stills — 150 ms @ 1080p on most devices; 200 ms if drops occur. */
        const val DEFAULT_INTERVAL_MS = 150L
    }
}
