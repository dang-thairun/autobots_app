package com.autobots.camera.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.StreamResolution
import com.autobots.camera.detection.OfflineFaceDetector
import com.autobots.camera.detection.OfflinePoseDetector
import com.autobots.camera.detection.PoseDetectionResult
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
 * Worker 2 — sample video chunks, keep sharp full-frame JPEGs with visible faces or poses.
 */
class VideoFrameProcessor(
    private val outputDir: File,
) {
    private var faceDetector = OfflineFaceDetector(accurate = false)
    private val poseDetector = OfflinePoseDetector()
    private var profile = ProcessProfile.forResolution(StreamResolution.Fhd)
    private var target = ExtractionTarget.Face

    suspend fun process(
        file: File,
        resolution: StreamResolution,
        extractionTarget: ExtractionTarget,
        sampleIntervalMs: Long,
        onProgress: (Int) -> Unit = {},
    ): VideoProcessResult {
        profile = ProcessProfile.forResolution(resolution)
        target = extractionTarget
        if (profile.accurateDetect) {
            faceDetector.close()
            faceDetector = OfflineFaceDetector(accurate = true)
        }

        val started = System.currentTimeMillis()
        outputDir.mkdirs()
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
                bestInWindow?.let { previous ->
                    saveFrame(previous)?.let { saved ->
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

        bestInWindow?.let { previous ->
            saveFrame(previous)?.let { saved ->
                kept++
                savedFiles.add(saved)
            } ?: run { skipped++ }
        }

        onProgress(100)

        val durationMs = System.currentTimeMillis() - started
        Log.i(
            TAG,
            "Processed ${file.name} (${resolution.label}, ${target.label}): kept=$kept skipped=$skipped " +
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
        return when (target) {
            ExtractionTarget.Face -> evaluateFaceFrame(bitmap, timestampUs, rejects)
            ExtractionTarget.Pose -> evaluatePoseFrame(bitmap, timestampUs, rejects)
        }
    }

    private suspend fun evaluateFaceFrame(
        bitmap: Bitmap,
        timestampUs: Long,
        rejects: RejectStats,
    ): FrameCandidate? {
        val scaled = scaleForDetect(bitmap)
        val faces = try {
            faceDetector.detect(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        if (faces.isEmpty()) {
            rejects.noSubject++
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
            rejects.noSubject++
            return null
        }
        val subjectRatio = largest.height().toFloat() / bitmap.height
        if (subjectRatio < MIN_FACE_HEIGHT_RATIO) {
            rejects.tooSmall++
            return null
        }

        val sharpness = FaceSharpnessScorer.scoreNormalized(bitmap, largest)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            return null
        }

        return FrameCandidate(timestampUs, bitmap, sharpness, subjectRatio)
    }

    private suspend fun evaluatePoseFrame(
        bitmap: Bitmap,
        timestampUs: Long,
        rejects: RejectStats,
    ): FrameCandidate? {
        val scaled = scaleForDetect(bitmap)
        val detection = try {
            detectPose(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
        if (detection == null) {
            rejects.noSubject++
            return null
        }

        val scaleX = bitmap.width.toFloat() / scaled.width
        val scaleY = bitmap.height.toFloat() / scaled.height
        val torso = Rect(
            (detection.torsoBounds.left * scaleX).toInt(),
            (detection.torsoBounds.top * scaleY).toInt(),
            (detection.torsoBounds.right * scaleX).toInt(),
            (detection.torsoBounds.bottom * scaleY).toInt(),
        )
        val subjectRatio = torso.height().toFloat() / bitmap.height
        if (subjectRatio < MIN_TORSO_HEIGHT_RATIO) {
            rejects.tooSmall++
            return null
        }

        val sharpness = FaceSharpnessScorer.scoreNormalized(bitmap, torso)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            return null
        }

        return FrameCandidate(timestampUs, bitmap, sharpness, subjectRatio)
    }

    private suspend fun detectPose(bitmap: Bitmap): PoseDetectionResult? {
        return poseDetector.detect(bitmap)
    }

    private fun scaleForDetect(bitmap: Bitmap): Bitmap {
        val targetW = profile.detectBitmapWidth
        if (bitmap.width <= targetW) return bitmap
        val height = (bitmap.height * (targetW.toFloat() / bitmap.width)).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetW, max(1, height), true)
    }

    private fun saveFrame(candidate: FrameCandidate): File? {
        val prefix = when (target) {
            ExtractionTarget.Face -> "face"
            ExtractionTarget.Pose -> "pose"
        }
        val name = "${prefix}_${candidate.timestampUs}.jpg"
        val outFile = File(outputDir, name)
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
        faceDetector.close()
        poseDetector.close()
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
        val subjectRatio: Float,
    )

    private data class RejectStats(
        var noSubject: Int = 0,
        var tooSmall: Int = 0,
        var tooSoft: Int = 0,
    ) {
        override fun toString(): String =
            "noSubject=$noSubject,small=$tooSmall,soft=$tooSoft"
    }

    private data class ProcessProfile(
        val detectBitmapWidth: Int,
        val minSharpness: Double,
        val accurateDetect: Boolean,
    ) {
        companion object {
            fun forResolution(resolution: StreamResolution): ProcessProfile {
                return when (resolution) {
                    StreamResolution.Fhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                        accurateDetect = false,
                    )
                    StreamResolution.Uhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                        accurateDetect = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoFrameProcessor"
        private const val DEDUP_WINDOW_US = 1_000_000L
        private const val MIN_FACE_HEIGHT_RATIO = 0.05f
        private const val MIN_TORSO_HEIGHT_RATIO = 0.25f
        private const val MIN_SHARPNESS = 80.0
    }
}
