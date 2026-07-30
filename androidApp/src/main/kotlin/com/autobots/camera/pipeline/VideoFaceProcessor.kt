package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
import com.autobots.camera.StreamResolution
import com.autobots.camera.detection.OfflineFaceDetector
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

data class VideoProcessResult(
    val kept: Int,
    val skipped: Int,
    val durationMs: Long,
    val savedFiles: List<File> = emptyList(),
    val framesSampled: Int = 0,
    val decodeFailures: Int = 0,
)

/**
 * Worker 2 — sample video chunks, keep sharp full-frame JPEGs with visible faces.
 */
class VideoFaceProcessor(
    private val facesDir: File,
) {
    private var detector = OfflineFaceDetector(accurate = false)
    private var profile = FaceProcessProfile.forResolution(StreamResolution.Fhd)

    suspend fun process(
        file: File,
        resolution: StreamResolution,
        sampleIntervalMs: Long,
        onProgress: (Int) -> Unit = {},
    ): VideoProcessResult {
        profile = FaceProcessProfile.forResolution(resolution)
        if (profile.accurateDetect) {
            detector.close()
            detector = OfflineFaceDetector(accurate = true)
        }

        val started = System.currentTimeMillis()
        facesDir.mkdirs()
        var kept = 0
        var skipped = 0
        val savedFiles = mutableListOf<File>()
        var bestInWindow: FrameCandidate? = null
        var windowStartUs = -1L
        val estimatedFrames = estimateFrameCount(file, sampleIntervalMs)
        var scannedFrames = 0
        val rejects = RejectStats()

        val sampleStats = VideoFrameSampler.sampleFrames(file, sampleIntervalMs) { timestampUs, bitmap ->
            scannedFrames++
            val percent = ((scannedFrames * 100) / estimatedFrames).coerceIn(0, 99)
            onProgress(percent)

            val candidate = evaluateFrame(bitmap, timestampUs, rejects)
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

        onProgress(100)

        val durationMs = System.currentTimeMillis() - started
        Log.i(
            TAG,
            "Processed ${file.name} (${resolution.label}): kept=$kept skipped=$skipped " +
                "sampled=$scannedFrames decodeFail=${sampleStats.decodeFailures} " +
                "rejects=$rejects ${durationMs}ms",
        )
        return VideoProcessResult(
            kept = kept,
            skipped = skipped,
            durationMs = durationMs,
            savedFiles = savedFiles,
            framesSampled = scannedFrames,
            decodeFailures = sampleStats.decodeFailures,
        )
    }

    private suspend fun evaluateFrame(
        bitmap: Bitmap,
        timestampUs: Long,
        rejects: RejectStats,
    ): FrameCandidate? {
        val scaled = scaleForDetect(bitmap)
        val faces = try {
            detector.detect(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        if (faces.isEmpty()) {
            rejects.noFace++
            return null
        }

        val scaleX = bitmap.width.toFloat() / scaled.width
        val scaleY = bitmap.height.toFloat() / scaled.height
        val mapped = faces.map { face ->
            Rect(
                (face.left * scaleX).toInt(),
                (face.top * scaleY).toInt(),
                (face.right * scaleX).toInt(),
                (face.bottom * scaleY).toInt(),
            )
        }
        val largest = mapped.maxByOrNull { it.height() } ?: run {
            rejects.noFace++
            return null
        }
        val faceRatio = largest.height().toFloat() / bitmap.height
        if (faceRatio < MIN_FACE_HEIGHT_RATIO) {
            rejects.tooSmall++
            return null
        }

        val sharpness = FaceSharpnessScorer.scoreNormalized(bitmap, largest)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            return null
        }

        return FrameCandidate(timestampUs, bitmap, sharpness, faceRatio)
    }

    private fun scaleForDetect(bitmap: Bitmap): Bitmap {
        val targetW = profile.detectBitmapWidth
        if (bitmap.width <= targetW) return bitmap
        val height = (bitmap.height * (targetW.toFloat() / bitmap.width)).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetW, max(1, height), true)
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

    private fun estimateFrameCount(file: File, sampleIntervalMs: Long): Int {
        val durationMs = readDurationMs(file) ?: return 1
        return maxOf(1, (durationMs / sampleIntervalMs).toInt())
    }

    private fun readDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class FrameCandidate(
        val timestampUs: Long,
        val bitmap: Bitmap,
        val sharpness: Double,
        val faceRatio: Float,
    )

    private data class RejectStats(
        var noFace: Int = 0,
        var tooSmall: Int = 0,
        var tooSoft: Int = 0,
    ) {
        override fun toString(): String =
            "noFace=$noFace,small=$tooSmall,soft=$tooSoft"
    }

    private data class FaceProcessProfile(
        val detectBitmapWidth: Int,
        val minSharpness: Double,
        val accurateDetect: Boolean,
    ) {
        companion object {
            fun forResolution(resolution: StreamResolution): FaceProcessProfile {
                return when (resolution) {
                    StreamResolution.Fhd -> FaceProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                        accurateDetect = false,
                    )
                    StreamResolution.Uhd -> FaceProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                        accurateDetect = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoFaceProcessor"
        private const val DEDUP_WINDOW_US = 1_000_000L
        private const val MIN_FACE_HEIGHT_RATIO = 0.05f
        private const val MIN_SHARPNESS = 80.0
    }
}
