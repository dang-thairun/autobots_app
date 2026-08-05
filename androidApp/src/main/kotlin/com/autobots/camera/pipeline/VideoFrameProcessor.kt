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
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.StageStats
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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

    /** Per-chunk stage timings; safe as a field because chunks are processed serially. */
    private var perf: StageStats? = null
    private val sharpnessSamples = ArrayList<Double>()
    private var currentChunkIndex = 0

    suspend fun process(
        file: File,
        chunkIndex: Int,
        resolution: StreamResolution,
        extractionTarget: ExtractionTarget,
        sampleIntervalMs: Long,
        onProgress: (Int) -> Unit = {},
    ): VideoProcessResult {
        currentChunkIndex = chunkIndex
        profile = ProcessProfile.forResolution(resolution)
        target = extractionTarget
        if (profile.accurateDetect) {
            faceDetector.close()
            faceDetector = OfflineFaceDetector(accurate = true)
        }

        val started = System.currentTimeMillis()
        perf = CamPerf.stageStats()
        sharpnessSamples.clear()
        outputDir.mkdirs()
        var kept = 0
        var skipped = 0
        val savedFiles = mutableListOf<File>()
        var bestInWindow: FrameCandidate? = null
        var windowStartUs = -1L
        val estimatedFrames = estimateFrameCount(file, sampleIntervalMs)
        var scannedFrames = 0
        val rejects = RejectStats()

        val sampleStats = VideoFrameSampler.sampleFrames(file, sampleIntervalMs, perf) { timestampUs, bitmap ->
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
        perf?.takeIf { !it.isEmpty() }?.let { stats ->
            CamPerf.log { stats.table("Worker2 ${file.name} (${resolution.label}, ${target.label})") }
        }
        CamPerf.log { sharpnessReport(file.name) }
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
        val scaled = CamPerf.timed(perf, "scale_for_detect") { scaleForDetect(bitmap) }
        val faces = try {
            CamPerf.timed(perf, "mlkit_face") { faceDetector.detect(scaled) }
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

        val sharpness = scoreSharpness(bitmap, largest)
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
        val scaled = CamPerf.timed(perf, "scale_for_detect") { scaleForDetect(bitmap) }
        val detection = try {
            CamPerf.timed(perf, "mlkit_pose") { detectPose(scaled) }
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

        val sharpness = scoreSharpness(bitmap, torso)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            return null
        }

        return FrameCandidate(timestampUs, bitmap, sharpness, subjectRatio)
    }

    private suspend fun detectPose(bitmap: Bitmap): PoseDetectionResult? {
        return poseDetector.detect(bitmap)
    }

    /**
     * Scores the subject ROI and records the raw value. The distribution is what
     * Phase 1 needs to re-tune [MIN_SHARPNESS] — the current threshold was picked
     * against JPEG-softened, long-exposure frames.
     */
    private fun scoreSharpness(bitmap: Bitmap, roi: Rect): Double {
        val score = CamPerf.timed(perf, "sharpness") {
            FaceSharpnessScorer.scoreNormalized(bitmap, roi)
        }
        if (CamPerf.enabled) sharpnessSamples.add(score)
        return score
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
        // Chunk index is part of the name because presentation timestamps restart at ~0
        // in every chunk — without it, frames from different chunks overwrite each other.
        val name = "${prefix}_c${currentChunkIndex.toString().padStart(3, '0')}_${candidate.timestampUs}.jpg"
        val outFile = File(outputDir, name)
        return try {
            CamPerf.timed(perf, "save_jpeg") {
                FileOutputStream(outFile).use { stream ->
                    candidate.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
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

    /** Sharpness distribution vs. the current cut-off — the input for re-tuning it. */
    private fun sharpnessReport(name: String): String {
        if (sharpnessSamples.isEmpty()) {
            return "┌─ sharpness $name\n└ no frames reached the scorer (all rejected earlier)"
        }
        val sorted = sharpnessSamples.sorted()
        fun percentile(p: Int) = sorted[((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)]
        val below = sorted.count { it < profile.minSharpness }
        return buildString {
            append("┌─ sharpness $name (n=${sorted.size}, cutoff=${profile.minSharpness})\n")
            append(
                String.format(
                    Locale.US,
                    "│ min %.1f  p25 %.1f  p50 %.1f  p75 %.1f  max %.1f\n",
                    sorted.first(),
                    percentile(25),
                    percentile(50),
                    percentile(75),
                    sorted.last(),
                ),
            )
            append("└ below cutoff ${below * 100 / sorted.size}% ($below/${sorted.size})")
        }
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
