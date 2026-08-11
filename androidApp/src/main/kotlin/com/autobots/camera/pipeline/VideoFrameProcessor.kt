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
import com.autobots.camera.perf.PerfReport
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
    /** Populated only when [CamPerf.enabled]; feeds `perf_report.json`. */
    val diag: PerfReport.ChunkDiag? = null,
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
    /** Per-frame verdicts for `perf_report.json`; same serial-chunk assumption as [perf]. */
    private val frameLog = ArrayList<PerfReport.FrameDiag>()
    private var currentChunkIndex = 0

    private fun logFrame(
        timestampUs: Long,
        outcome: String,
        sharpness: Double? = null,
        subjectRatio: Float? = null,
    ) {
        if (!CamPerf.enabled) return
        frameLog.add(PerfReport.FrameDiag(timestampUs, outcome, sharpness, subjectRatio))
    }

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
        frameLog.clear()
        outputDir.mkdirs()
        var kept = 0
        var skipped = 0
        val savedFiles = mutableListOf<File>()
        // Candidates are written to disk as they arrive and ranked afterwards. Ranking them
        // in memory would mean holding several full-resolution frames at once — ~33 MB each
        // at UHD — while a JPEG write costs ~83 ms on a frame that already passed two gates.
        val windowFrames = mutableListOf<SavedCandidate>()
        var windowStartUs = -1L
        val estimatedFrames = estimateFrameCount(file, sampleIntervalMs)
        var scannedFrames = 0
        val rejects = RejectStats()

        fun closeWindow() {
            if (windowFrames.isEmpty()) return
            val ranked = windowFrames.sortedByDescending { it.sharpness }
            for ((index, entry) in ranked.withIndex()) {
                if (index < MAX_KEEP_PER_WINDOW) {
                    kept++
                    savedFiles.add(entry.file)
                } else {
                    skipped++
                    entry.file.delete()
                }
            }
            windowFrames.clear()
        }

        val sampleStats = VideoFrameSampler.sampleFrames(
            file,
            sampleIntervalMs,
            perf,
        ) { timestampUs, bitmap, rotationDegrees ->
            scannedFrames++
            val percent = ((scannedFrames * 100) / estimatedFrames).coerceIn(0, 99)
            onProgress(percent)

            val candidate = evaluateFrame(bitmap, timestampUs, rotationDegrees, rejects)
            if (candidate == null) {
                skipped++
                bitmap.recycle()
                return@sampleFrames
            }

            // saveFrame recycles the bitmap, so memory stays flat regardless of window size.
            val saved = saveFrame(candidate)
            if (saved == null) {
                skipped++
                return@sampleFrames
            }
            if (windowStartUs < 0 || timestampUs - windowStartUs >= DEDUP_WINDOW_US) {
                closeWindow()
                windowStartUs = timestampUs
            }
            windowFrames.add(SavedCandidate(saved, candidate.sharpness))
        }

        closeWindow()
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
            diag = if (!CamPerf.enabled) {
                null
            } else {
                PerfReport.ChunkDiag(
                    framesSampled = scannedFrames,
                    kept = kept,
                    skipped = skipped,
                    decodeFailures = sampleStats.decodeFailures,
                    processDurationMs = durationMs,
                    noSubject = rejects.noSubject,
                    tooSmall = rejects.tooSmall,
                    tooSoft = rejects.tooSoft,
                    roiInvalid = rejects.roiInvalid,
                    sharpnessCutoff = profile.minSharpness,
                    detectWidth = profile.detectBitmapWidth,
                    decodePath = if (sampleStats.usedSurfacePath) "surface" else "yuv",
                    stages = perf?.snapshot().orEmpty(),
                    frames = frameLog.toList(),
                    // Dedup picks the winner after the fact; the saved file name carries its PTS.
                    keptPtsUs = savedFiles.mapNotNull {
                        it.nameWithoutExtension.substringAfterLast('_').toLongOrNull()
                    },
                )
            },
        )
    }

    private suspend fun evaluateFrame(
        raw: Bitmap,
        timestampUs: Long,
        rotationDegrees: Int,
        rejects: RejectStats,
    ): FrameCandidate? {
        return when (target) {
            ExtractionTarget.Face -> evaluateFaceFrame(raw, timestampUs, rotationDegrees, rejects)
            ExtractionTarget.Pose -> evaluatePoseFrame(raw, timestampUs, rotationDegrees, rejects)
        }
    }

    private suspend fun evaluateFaceFrame(
        raw: Bitmap,
        timestampUs: Long,
        rotationDegrees: Int,
        rejects: RejectStats,
    ): FrameCandidate? {
        val detectBmp = CamPerf.timed(perf, "scale_for_detect") {
            uprightDetectBitmap(raw, rotationDegrees)
        }
        val faces = try {
            CamPerf.timed(perf, "mlkit_face") { faceDetector.detect(detectBmp) }
        } catch (t: Throwable) {
            Log.w(TAG, "Face detect failed at ${timestampUs}us", t)
            emptyList()
        }
        // Size is judged in detect space, so a rejected frame never pays for a full-res rotate.
        val largest = faces.maxByOrNull { it.height() }
        val detectWidth = detectBmp.width
        val detectHeight = detectBmp.height
        if (detectBmp !== raw) detectBmp.recycle()

        if (largest == null) {
            rejects.noSubject++
            logFrame(timestampUs, "no_subject")
            return null
        }
        val subjectRatio = largest.height().toFloat() / detectHeight
        if (subjectRatio < MIN_FACE_HEIGHT_RATIO) {
            rejects.tooSmall++
            logFrame(timestampUs, "too_small", subjectRatio = subjectRatio)
            return null
        }

        val upright = uprightFullFrame(raw, rotationDegrees)
        val roi = mapRect(largest, upright, detectWidth, detectHeight)
        if (!isUsableRoi(roi)) {
            rejects.roiInvalid++
            logFrame(timestampUs, "roi_invalid", subjectRatio = subjectRatio)
            upright.recycle()
            return null
        }
        val sharpness = scoreSharpness(upright, roi)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            logFrame(timestampUs, "too_soft", sharpness, subjectRatio)
            upright.recycle()
            return null
        }

        logFrame(timestampUs, "candidate", sharpness, subjectRatio)
        return FrameCandidate(timestampUs, upright, sharpness, subjectRatio)
    }

    private suspend fun evaluatePoseFrame(
        raw: Bitmap,
        timestampUs: Long,
        rotationDegrees: Int,
        rejects: RejectStats,
    ): FrameCandidate? {
        val detectBmp = CamPerf.timed(perf, "scale_for_detect") {
            uprightDetectBitmap(raw, rotationDegrees)
        }
        val detection = try {
            CamPerf.timed(perf, "mlkit_pose") { detectPose(detectBmp) }
        } catch (t: Throwable) {
            Log.w(TAG, "Pose detect failed at ${timestampUs}us", t)
            null
        }
        val detectWidth = detectBmp.width
        val detectHeight = detectBmp.height
        if (detectBmp !== raw) detectBmp.recycle()

        if (detection == null) {
            rejects.noSubject++
            logFrame(timestampUs, "no_subject")
            return null
        }
        val subjectRatio = detection.torsoBounds.height().toFloat() / detectHeight
        if (subjectRatio < MIN_TORSO_HEIGHT_RATIO) {
            rejects.tooSmall++
            logFrame(timestampUs, "too_small", subjectRatio = subjectRatio)
            return null
        }

        val upright = uprightFullFrame(raw, rotationDegrees)
        val roi = mapRect(detection.torsoBounds, upright, detectWidth, detectHeight)
        if (!isUsableRoi(roi)) {
            rejects.roiInvalid++
            logFrame(timestampUs, "roi_invalid", subjectRatio = subjectRatio)
            upright.recycle()
            return null
        }
        val sharpness = scoreSharpness(upright, roi)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft++
            logFrame(timestampUs, "too_soft", sharpness, subjectRatio)
            upright.recycle()
            return null
        }

        logFrame(timestampUs, "candidate", sharpness, subjectRatio)
        return FrameCandidate(timestampUs, upright, sharpness, subjectRatio)
    }

    /**
     * Detect input, upright and ~[ProcessProfile.detectBitmapWidth] wide.
     *
     * Scaling happens **before** rotation so the rotate runs on a 640px frame rather than a
     * 4K one (~52 ms → ~5 ms at UHD). The result is the same size as the pre-0.1.3
     * rotate-then-scale order, so detection sees an identical image.
     */
    private fun uprightDetectBitmap(raw: Bitmap, rotationDegrees: Int): Bitmap {
        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (swapsAxes) raw.height else raw.width
        val target = profile.detectBitmapWidth

        val scaled = if (uprightWidth <= target) {
            raw
        } else {
            val factor = target.toFloat() / uprightWidth
            Bitmap.createScaledBitmap(
                raw,
                max(1, (raw.width * factor).toInt()),
                max(1, (raw.height * factor).toInt()),
                true,
            )
        }
        if (rotationDegrees == 0) return scaled
        if (scaled === raw) {
            // rotate() recycles its input, and the caller still owns the raw frame.
            val copy = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, false) ?: return raw
            return VideoFrameSampler.rotate(copy, rotationDegrees)
        }
        return VideoFrameSampler.rotate(scaled, rotationDegrees)
    }

    /** Upright full-resolution frame — only frames that passed the size gate pay for this. */
    private fun uprightFullFrame(raw: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return raw
        return CamPerf.timed(perf, "rotate") { VideoFrameSampler.rotate(raw, rotationDegrees) }
    }

    /**
     * Detect-space rect → full-frame rect. Both are upright, so this is a pure scale.
     *
     * ML Kit with `enableTracking()` can report a box that runs past the frame edge for a
     * subject at the border. Unclamped, [FaceSharpnessScorer] sees an empty region and
     * returns 0.0 — which the caller would then read as "too blurry" rather than
     * "not measurable". Clamp here and let the caller check the result.
     */
    private fun mapRect(rect: Rect, target: Bitmap, detectWidth: Int, detectHeight: Int): Rect {
        val scaleX = target.width.toFloat() / detectWidth
        val scaleY = target.height.toFloat() / detectHeight
        return Rect(
            (rect.left * scaleX).toInt().coerceIn(0, target.width),
            (rect.top * scaleY).toInt().coerceIn(0, target.height),
            (rect.right * scaleX).toInt().coerceIn(0, target.width),
            (rect.bottom * scaleY).toInt().coerceIn(0, target.height),
        )
    }

    /** Matches [FaceSharpnessScorer]'s own floor, so a usable ROI always yields a real score. */
    private fun isUsableRoi(roi: Rect): Boolean =
        roi.width() >= MIN_ROI_PX && roi.height() >= MIN_ROI_PX

    private suspend fun detectPose(bitmap: Bitmap): PoseDetectionResult? {
        return poseDetector.detect(bitmap)
    }

    /**
     * Laplacian-variance sharpness on the subject ROI.
     * The distribution is what Phase 1 needs to re-tune [MIN_SHARPNESS].
     */
    private fun scoreSharpness(bitmap: Bitmap, roi: Rect): Double {
        val score = CamPerf.timed(perf, "sharpness") {
            FaceSharpnessScorer.scoreNormalized(bitmap, roi)
        }
        if (CamPerf.enabled) sharpnessSamples.add(score)
        return score
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

    /** A candidate already on disk, waiting to be ranked against the rest of its window. */
    private data class SavedCandidate(
        val file: File,
        val sharpness: Double,
    )

    private data class RejectStats(
        var noSubject: Int = 0,
        var tooSmall: Int = 0,
        var tooSoft: Int = 0,
        /** Subject found, but its box mapped outside the frame — not a quality verdict. */
        var roiInvalid: Int = 0,
    ) {
        override fun toString(): String =
            "noSubject=$noSubject,small=$tooSmall,soft=$tooSoft,roiInvalid=$roiInvalid"
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
                        minSharpness = MIN_SHARPNESS_UHD,
                        accurateDetect = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoFrameProcessor"
        private const val DEDUP_WINDOW_US = 1_000_000L

        /**
         * Photos kept per dedup window.
         *
         * 0.1.2–0.1.3 kept the single sharpest frame, which turned a runner's whole pass in
         * front of the lens into one photo — 42 candidates became 7 in the UHD test. Keeping
         * three matches the Passage Outcome in CONTEXT.md (Keep-All Policy, ~3 per passage).
         */
        private const val MAX_KEEP_PER_WINDOW = 3

        /** Smallest ROI the sharpness scorer can work with. */
        private const val MIN_ROI_PX = 8

        /**
         * Minimum subject height as a fraction of frame height.
         *
         * 0.1.2 used 0.05 and threw away 96 of 507 UHD frames whose faces measured
         * 0.023–0.048 — runners approaching the lens, rejected one step short of the gate.
         * Must stay above [OfflineFaceDetector]'s own `setMinFaceSize`, or ML Kit filters
         * the face out before this check ever sees it.
         */
        private const val MIN_FACE_HEIGHT_RATIO = 0.035f
        private const val MIN_TORSO_HEIGHT_RATIO = 0.25f
        const val MIN_SHARPNESS = 80.0
        const val MIN_SHARPNESS_UHD = 65.0
    }
}
