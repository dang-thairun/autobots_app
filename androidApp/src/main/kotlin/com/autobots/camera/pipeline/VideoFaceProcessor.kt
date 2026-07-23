package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.autobots.camera.detection.OfflineFaceDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

data class VideoProcessResult(
    val kept: Int,
    val skipped: Int,
    val durationMs: Long,
    val savedFiles: List<File> = emptyList(),
)

/**
 * Worker 2 — sample video chunks, keep sharp full-frame JPEGs with visible faces.
 */
class VideoFaceProcessor(
    private val facesDir: File,
    private val detectBitmapWidth: Int = 640,
) {
    private val detector = OfflineFaceDetector()

    suspend fun process(file: File): VideoProcessResult {
        val started = System.currentTimeMillis()
        facesDir.mkdirs()
        var kept = 0
        var skipped = 0
        val savedFiles = mutableListOf<File>()
        var bestInWindow: FrameCandidate? = null
        var windowStartUs = -1L

        VideoFrameSampler.sampleFrames(file, SAMPLE_INTERVAL_MS) { timestampUs, bitmap ->
            val candidate = evaluateFrame(bitmap, timestampUs)
            if (candidate == null) {
                skipped++
                bitmap.recycle()
                return@sampleFrames
            }

            val windowUs = DEDUP_WINDOW_US
            if (windowStartUs < 0 || timestampUs - windowStartUs >= windowUs) {
                bestInWindow?.let { candidate ->
                    saveFrame(candidate)?.let { saved ->
                        kept++
                        savedFiles.add(saved)
                    } ?: run { skipped++ }
                }
                bestInWindow = candidate
                windowStartUs = timestampUs
            } else if (candidate.sharpness > (bestInWindow?.sharpness ?: 0.0)) {
                bestInWindow?.bitmap?.recycle()
                bestInWindow = candidate
            } else {
                candidate.bitmap.recycle()
                skipped++
            }
        }

        bestInWindow?.let { candidate ->
            saveFrame(candidate)?.let { saved ->
                kept++
                savedFiles.add(saved)
            } ?: run { skipped++ }
        }

        val durationMs = System.currentTimeMillis() - started
        Log.i(TAG, "Processed ${file.name}: kept=$kept skipped=$skipped ${durationMs}ms")
        return VideoProcessResult(
            kept = kept,
            skipped = skipped,
            durationMs = durationMs,
            savedFiles = savedFiles,
        )
    }

    private suspend fun evaluateFrame(bitmap: Bitmap, timestampUs: Long): FrameCandidate? {
        val scaled = scaleForDetect(bitmap)
        val faces = try {
            detector.detect(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        if (faces.isEmpty()) return null

        val scaleX = bitmap.width.toFloat() / detectBitmapWidth
        val scaleY = scaleX
        val mapped = faces.map { face ->
            Rect(
                (face.left * scaleX).toInt(),
                (face.top * scaleY).toInt(),
                (face.right * scaleX).toInt(),
                (face.bottom * scaleY).toInt(),
            )
        }
        val largest = mapped.maxByOrNull { it.height() } ?: return null
        val faceRatio = largest.height().toFloat() / bitmap.height
        if (faceRatio < MIN_FACE_HEIGHT_RATIO) return null

        val sharpness = FaceSharpnessScorer.score(bitmap, largest)
        if (sharpness < MIN_SHARPNESS) return null

        return FrameCandidate(timestampUs, bitmap, sharpness, faceRatio)
    }

    private fun scaleForDetect(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= detectBitmapWidth) return bitmap
        val height = (bitmap.height * (detectBitmapWidth.toFloat() / bitmap.width)).toInt()
        return Bitmap.createScaledBitmap(bitmap, detectBitmapWidth, max(1, height), true)
    }

    private fun saveFrame(candidate: FrameCandidate): File? {
        val name = "face_${candidate.timestampUs}.jpg"
        val outFile = File(facesDir, name)
        return try {
            FileOutputStream(outFile).use { stream ->
                candidate.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            if (outFile.length() > 0L) outFile else null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save $name", t)
            null
        } finally {
            candidate.bitmap.recycle()
        }
    }

    fun close() {
        detector.close()
    }

    private data class FrameCandidate(
        val timestampUs: Long,
        val bitmap: Bitmap,
        val sharpness: Double,
        val faceRatio: Float,
    )

    companion object {
        private const val TAG = "VideoFaceProcessor"
        private const val SAMPLE_INTERVAL_MS = 300L
        private const val DEDUP_WINDOW_US = 1_000_000L
        private const val MIN_FACE_HEIGHT_RATIO = 0.05f
        private const val MIN_SHARPNESS = 80.0
    }
}
