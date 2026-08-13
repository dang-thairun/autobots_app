package com.autobots.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
import com.autobots.camera.DetectorBackend
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.StreamResolution
import com.autobots.camera.detection.FaceDetLiteDetector
import com.autobots.camera.detection.OfflineFaceDetector
import com.autobots.camera.detection.SubjectFaceDetector
import com.autobots.camera.detection.OfflinePoseDetector
import com.autobots.camera.detection.PoseDetectionResult
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.PerfReport
import com.autobots.camera.perf.StageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * **Two stages, not one.** Up to and including 0.1.3 everything ran inside the decode loop:
 * [VideoFrameSampler] called back with a bitmap and blocked until detection, scoring and the
 * JPEG write had finished. Decode and detect were therefore perfectly serial, and the chunk's
 * wall time was their *sum*. Measured on UHD (v0.1.3, TC-04) that sum was
 * `yuv_jpeg_argb 49.5% + mlkit 30.9% + rotate 5.4% + save_jpeg 4.3% + scale 2.8%`.
 *
 * 0.1.4 splits it across a bounded channel:
 *
 * ```
 * sampler thread ──decode + YUV→Bitmap──▶ Channel(2) ──▶ detect worker ×N
 *   (producer, ~50% of the old wall)                      (consumer, ~43%, spread over N)
 * ```
 *
 * Wall time becomes `max(producer, consumer / N)` instead of `producer + consumer`.
 *
 * Two consequences follow from parallelising, and both are load-bearing:
 *  - **Each worker owns its detectors.** One ML Kit detector shared by N threads serialises
 *    inside the SDK, which is the exact cost being removed.
 *  - **Selection moved to the end of the chunk.** Workers finish out of order, so the 1 s
 *    dedup window can no longer be applied as frames stream past. Candidates are written to
 *    JPEG on arrival (as in 0.1.3) and the whole chunk is sorted by PTS and windowed once
 *    everything has landed. Same rule, same result, order-independent.
 *
 * `queue_wait` and `worker_idle` in `perf_report.json` say which side is the limit:
 * `queue_wait` high → consumers are behind, raise [DETECT_WORKERS]. `worker_idle` high →
 * the decoder is the limit, and only `yuv_jpeg_argb` work can help.
 */
class VideoFrameProcessor(
    private val outputDir: File,
    private val appContext: Context,
) {
    /** One detector pair per detect worker — see the class doc on why they are not shared. */
    private var detectors: List<DetectorSet> = emptyList()
    private var detectorsBackend: DetectorBackend? = null

    private var profile = ProcessProfile.forResolution(StreamResolution.Fhd)
    private var target = ExtractionTarget.Face
    private var backend = DetectorBackend.DEFAULT

    /** Per-chunk stage timings. Chunks are serial, but workers within one are not — see [StageStats]. */
    private var perf: StageStats? = null
    private val sharpnessSamples = Collections.synchronizedList(ArrayList<Double>())
    /** Per-frame verdicts for `perf_report.json`; written from every detect worker. */
    private val frameLog = Collections.synchronizedList(ArrayList<PerfReport.FrameDiag>())
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
        detectorBackend: DetectorBackend = DetectorBackend.DEFAULT,
        onProgress: (Int) -> Unit = {},
    ): VideoProcessResult {
        currentChunkIndex = chunkIndex
        profile = ProcessProfile.forResolution(resolution)
        target = extractionTarget
        backend = detectorBackend
        ensureDetectors(detectorBackend)

        val started = System.currentTimeMillis()
        perf = CamPerf.stageStats()
        sharpnessSamples.clear()
        frameLog.clear()
        outputDir.mkdirs()

        val skipped = AtomicInteger(0)
        val rejects = RejectStats()
        // Candidates are written to disk as they arrive and ranked afterwards. Ranking them
        // in memory would mean holding several full-resolution frames at once — ~33 MB each
        // at UHD — while a JPEG write costs ~83 ms on a frame that already passed two gates.
        val candidates = Collections.synchronizedList(ArrayList<SavedCandidate>())
        val estimatedFrames = estimateFrameCount(file, sampleIntervalMs)
        var scannedFrames = 0

        // Bounded so the decoder cannot run ahead of detection and pile up 4K bitmaps.
        // onUndeliveredElement covers cancellation: anything still in flight gets recycled.
        val frames = Channel<DecodedFrame>(
            capacity = FRAME_QUEUE_CAPACITY,
            onUndeliveredElement = { runCatching { it.bitmap.recycle() } },
        )

        val sampleStats = coroutineScope {
            val workers = detectors.map { set ->
                launch(Dispatchers.Default) {
                    runDetectWorker(set, frames, candidates, rejects, skipped)
                }
            }
            // The sampler's decode loop is blocking, so it gets a thread of its own rather
            // than occupying one of Default's cores that the workers need.
            val stats = try {
                withContext(Dispatchers.IO) {
                    VideoFrameSampler.sampleFrames(file, sampleIntervalMs, perf) { ts, bitmap, rotation ->
                        scannedFrames++
                        onProgress(((scannedFrames * 100) / estimatedFrames).coerceIn(0, 99))
                        frames.send(DecodedFrame(ts, bitmap, rotation))
                    }
                }
            } finally {
                frames.close()
            }
            workers.joinAll()
            stats
        }

        // Selection runs once, over the whole chunk, in PTS order — see the class doc.
        val (kept, savedFiles, keptPtsUs) = selectKeepers(candidates, skipped)
        onProgress(100)

        val durationMs = System.currentTimeMillis() - started
        Log.i(
            TAG,
            "Processed ${file.name} (${resolution.label}, ${target.label}): kept=$kept " +
                "skipped=${skipped.get()} sampled=$scannedFrames " +
                "decodeFail=${sampleStats.decodeFailures} rejects=$rejects " +
                "workers=${detectors.size} ${durationMs}ms",
        )
        perf?.takeIf { !it.isEmpty() }?.let { stats ->
            CamPerf.log { stats.table("Worker2 ${file.name} (${resolution.label}, ${target.label})") }
        }
        CamPerf.log { sharpnessReport(file.name) }
        return VideoProcessResult(
            kept = kept,
            skipped = skipped.get(),
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
                    skipped = skipped.get(),
                    decodeFailures = sampleStats.decodeFailures,
                    processDurationMs = durationMs,
                    noSubject = rejects.noSubject.get(),
                    tooSmall = rejects.tooSmall.get(),
                    tooSoft = rejects.tooSoft.get(),
                    roiInvalid = rejects.roiInvalid.get(),
                    sharpnessCutoff = profile.minSharpness,
                    detectWidth = profile.detectBitmapWidth,
                    decodePath = if (sampleStats.usedSurfacePath) "surface" else "yuv",
                    detectWorkers = detectors.size,
                    frameQueueCapacity = FRAME_QUEUE_CAPACITY,
                    downscaleMode = if (MULTISTEP_DOWNSCALE) "halving" else "single",
                    detector = detectorDiagnostics(),
                    stages = perf?.snapshot().orEmpty(),
                    frames = synchronized(frameLog) { ArrayList(frameLog) },
                    keptPtsUs = keptPtsUs,
                )
            },
        )
    }

    /**
     * What actually ran, as opposed to what was asked for — see [PerfReport.ChunkDiag.detector].
     * Read from the first worker; they are all built the same way.
     */
    private fun detectorDiagnostics(): Map<String, Any> =
        mapOf("backend" to backend.slug) + (detectors.firstOrNull()?.face?.diagnostics ?: emptyMap())

    /**
     * Detect workers each need their own ML Kit clients, and loading those models is not
     * cheap — build them once and keep them until the accuracy mode actually changes.
     */
    private fun ensureDetectors(backend: DetectorBackend) {
        if (detectorsBackend == backend && detectors.size == DETECT_WORKERS) return
        detectors.forEach { it.close() }
        detectors = List(DETECT_WORKERS) { DetectorSet(appContext, backend) }
        detectorsBackend = backend
    }

    private suspend fun runDetectWorker(
        set: DetectorSet,
        frames: ReceiveChannel<DecodedFrame>,
        candidates: MutableList<SavedCandidate>,
        rejects: RejectStats,
        skipped: AtomicInteger,
    ) {
        while (true) {
            // Idle time here is the headline diagnostic: a worker with nothing to do means
            // the decoder, not detection, is what the chunk is waiting on.
            val idleStartNs = System.nanoTime()
            val frame = frames.receiveCatching().getOrNull() ?: return
            perf?.add("worker_idle", System.nanoTime() - idleStartNs)

            try {
                val candidate = evaluateFrame(set, frame, rejects)
                if (candidate == null) {
                    skipped.incrementAndGet()
                    frame.bitmap.recycle()
                    continue
                }
                // saveFrame recycles the bitmap, so memory stays flat regardless of yield.
                val saved = saveFrame(candidate)
                if (saved == null) {
                    skipped.incrementAndGet()
                    continue
                }
                candidates.add(SavedCandidate(saved, candidate.sharpness, candidate.timestampUs))
            } catch (t: Throwable) {
                // One bad frame must not take the worker — and therefore the chunk — down.
                Log.w(TAG, "Frame ${frame.timestampUs}us failed", t)
                skipped.incrementAndGet()
                runCatching { frame.bitmap.recycle() }
            }
        }
    }

    /**
     * Dedup, applied once per chunk instead of once per streaming frame.
     *
     * Identical rule to 0.1.3 — windows are anchored on the first candidate and closed after
     * [DEDUP_WINDOW_US], keeping the [MAX_KEEP_PER_WINDOW] sharpest. Because the input is
     * sorted by PTS first, the outcome matches the streaming version exactly; sorting is what
     * makes it independent of the order workers happened to finish in.
     */
    private fun selectKeepers(
        candidates: List<SavedCandidate>,
        skipped: AtomicInteger,
    ): Triple<Int, List<File>, List<Long>> {
        val byPts = synchronized(candidates) { ArrayList(candidates) }.sortedBy { it.timestampUs }
        val keepers = mutableListOf<SavedCandidate>()
        val window = mutableListOf<SavedCandidate>()
        var windowStartUs = -1L

        fun closeWindow() {
            if (window.isEmpty()) return
            for ((index, entry) in window.sortedByDescending { it.sharpness }.withIndex()) {
                if (index < MAX_KEEP_PER_WINDOW) {
                    keepers.add(entry)
                } else {
                    skipped.incrementAndGet()
                    entry.file.delete()
                }
            }
            window.clear()
        }

        for (entry in byPts) {
            if (windowStartUs < 0 || entry.timestampUs - windowStartUs >= DEDUP_WINDOW_US) {
                closeWindow()
                windowStartUs = entry.timestampUs
            }
            window.add(entry)
        }
        closeWindow()
        // Deliver in capture order, not in the sharpness order the windows ranked them by.
        // Sorting on the file name would be wrong: PTS values have differing digit counts,
        // so "..._1200000.jpg" sorts before "..._960000.jpg" lexicographically.
        val ordered = keepers.sortedBy { it.timestampUs }
        return Triple(ordered.size, ordered.map { it.file }, ordered.map { it.timestampUs })
    }

    private suspend fun evaluateFrame(
        set: DetectorSet,
        frame: DecodedFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        return when (target) {
            ExtractionTarget.Face -> evaluateFaceFrame(set, frame, rejects)
            ExtractionTarget.Pose -> evaluatePoseFrame(set, frame, rejects)
        }
    }

    private suspend fun evaluateFaceFrame(
        set: DetectorSet,
        frame: DecodedFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        val raw = frame.bitmap
        val timestampUs = frame.timestampUs
        val detectBmp = CamPerf.timed(perf, "scale_for_detect") {
            uprightDetectBitmap(raw, frame.rotationDegrees)
        }
        val faces = try {
            CamPerf.timed(perf, "detect") { set.face.detect(detectBmp) }
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
            rejects.noSubject.incrementAndGet()
            logFrame(timestampUs, "no_subject")
            return null
        }
        val subjectRatio = largest.height().toFloat() / detectHeight
        if (subjectRatio < MIN_FACE_HEIGHT_RATIO) {
            rejects.tooSmall.incrementAndGet()
            logFrame(timestampUs, "too_small", subjectRatio = subjectRatio)
            return null
        }

        val upright = uprightFullFrame(raw, frame.rotationDegrees)
        val roi = mapRect(largest, upright, detectWidth, detectHeight)
        if (!isUsableRoi(roi)) {
            rejects.roiInvalid.incrementAndGet()
            logFrame(timestampUs, "roi_invalid", subjectRatio = subjectRatio)
            upright.recycle()
            return null
        }
        val sharpness = scoreSharpness(upright, roi)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft.incrementAndGet()
            logFrame(timestampUs, "too_soft", sharpness, subjectRatio)
            upright.recycle()
            return null
        }

        logFrame(timestampUs, "candidate", sharpness, subjectRatio)
        return FrameCandidate(timestampUs, upright, sharpness, subjectRatio)
    }

    private suspend fun evaluatePoseFrame(
        set: DetectorSet,
        frame: DecodedFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        val raw = frame.bitmap
        val timestampUs = frame.timestampUs
        val detectBmp = CamPerf.timed(perf, "scale_for_detect") {
            uprightDetectBitmap(raw, frame.rotationDegrees)
        }
        val detection = try {
            CamPerf.timed(perf, "detect_pose") { set.pose.detect(detectBmp) }
        } catch (t: Throwable) {
            Log.w(TAG, "Pose detect failed at ${timestampUs}us", t)
            null
        }
        val detectWidth = detectBmp.width
        val detectHeight = detectBmp.height
        if (detectBmp !== raw) detectBmp.recycle()

        if (detection == null) {
            rejects.noSubject.incrementAndGet()
            logFrame(timestampUs, "no_subject")
            return null
        }
        val subjectRatio = detection.torsoBounds.height().toFloat() / detectHeight
        if (subjectRatio < MIN_TORSO_HEIGHT_RATIO) {
            rejects.tooSmall.incrementAndGet()
            logFrame(timestampUs, "too_small", subjectRatio = subjectRatio)
            return null
        }

        val upright = uprightFullFrame(raw, frame.rotationDegrees)
        val roi = mapRect(detection.torsoBounds, upright, detectWidth, detectHeight)
        if (!isUsableRoi(roi)) {
            rejects.roiInvalid.incrementAndGet()
            logFrame(timestampUs, "roi_invalid", subjectRatio = subjectRatio)
            upright.recycle()
            return null
        }
        val sharpness = scoreSharpness(upright, roi)
        if (sharpness < profile.minSharpness) {
            rejects.tooSoft.incrementAndGet()
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
     * rotate-then-scale order, so detection sees an image of identical dimensions.
     */
    private fun uprightDetectBitmap(raw: Bitmap, rotationDegrees: Int): Bitmap {
        val swapsAxes = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (swapsAxes) raw.height else raw.width
        val target = profile.detectBitmapWidth

        val scaled = if (uprightWidth <= target) {
            raw
        } else {
            val factor = target.toFloat() / uprightWidth
            downscale(
                raw,
                max(1, (raw.width * factor).toInt()),
                max(1, (raw.height * factor).toInt()),
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

    /**
     * Downscale by repeated halving rather than in one step.
     *
     * `createScaledBitmap(filter = true)` is bilinear: it reads a 2×2 neighbourhood per output
     * pixel. Reducing by more than 2× in one pass therefore *skips* source pixels, and the
     * detail it drops is exactly the fine texture a face detector keys on. UHD portrait is a
     * 3.4× reduction (2160 → 640), and a runner at the size gate is only ~40 px tall in the
     * result — right at the edge of what ML Kit FAST can see, before any aliasing.
     *
     * Halving first (3840×2160 → 1920×1080) keeps every pass at or under 2×, so each output
     * pixel is an average of real neighbours instead of a point sample. Cost is one extra
     * intermediate bitmap on a thread that, since the two-stage split, has headroom.
     *
     * Guarded by [MULTISTEP_DOWNSCALE] and recorded as `downscaleMode` in `perf_report.json`,
     * so a run can be attributed rather than assumed.
     */
    private fun downscale(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (!MULTISTEP_DOWNSCALE) {
            return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        }
        var current = src
        var width = src.width
        var height = src.height
        while (width / 2 >= targetWidth && height / 2 >= targetHeight) {
            width /= 2
            height /= 2
            val next = Bitmap.createScaledBitmap(current, width, height, true)
            if (current !== src) current.recycle()
            current = next
        }
        if (width == targetWidth && height == targetHeight) return current
        val out = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
        if (current !== src && current !== out) current.recycle()
        return out
    }

    /** Upright full-resolution frame — only frames that passed the size gate pay for this. */
    private fun uprightFullFrame(raw: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return raw
        return CamPerf.timed(perf, "rotate") { VideoFrameSampler.rotate(raw, rotationDegrees) }
    }

    /**
     * Detect-space rect → full-frame rect. Both are upright, so this is a pure scale.
     *
     * A detector can report a box that runs past the frame edge for a subject at the border.
     * Unclamped, [FaceSharpnessScorer] sees an empty region and returns 0.0 — which the
     * caller would then read as "too blurry" rather than "not measurable". Clamp here and
     * let the caller check the result.
     *
     * The known source of out-of-bounds boxes was `enableTracking()`, removed in 0.1.4, so
     * `roiInvalid` should now be ~0. The clamp stays: it costs nothing, and a count that is
     * *supposed* to be zero is a free regression detector.
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
        detectors.forEach { it.close() }
        detectors = emptyList()
        detectorsBackend = null
    }

    /** Sharpness distribution vs. the current cut-off — the input for re-tuning it. */
    private fun sharpnessReport(name: String): String {
        val sorted = synchronized(sharpnessSamples) { ArrayList(sharpnessSamples) }.sorted()
        if (sorted.isEmpty()) {
            return "┌─ sharpness $name\n└ no frames reached the scorer (all rejected earlier)"
        }
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

    /** A decoded frame in flight between the sampler thread and a detect worker. */
    private data class DecodedFrame(
        val timestampUs: Long,
        val bitmap: Bitmap,
        val rotationDegrees: Int,
    )

    /**
     * ML Kit clients for one detect worker. Both are lazy: a Face session never pays to load
     * the pose model, and vice versa.
     */
    private class DetectorSet(private val context: Context, private val backend: DetectorBackend) {
        private val faceDelegate = lazy { createFace() }
        private val poseDelegate = lazy { OfflinePoseDetector() }
        val face: SubjectFaceDetector by faceDelegate
        val pose: OfflinePoseDetector by poseDelegate

        /**
         * Falls back to ML Kit FAST when the requested backend cannot start — a missing model
         * file, or QNN libraries absent from the build. The session still produces photos, and
         * `detector` in `perf_report.json` records what actually ran, so a fallback can never
         * be mistaken for a result.
         */
        private fun createFace(): SubjectFaceDetector = when {
            backend.usesLiteRt -> FaceDetLiteDetector.create(context, backend)
                ?: OfflineFaceDetector(accurate = false).also {
                    Log.e(TAG, "\${backend.slug} unavailable — fell back to ML Kit FAST")
                }
            backend == DetectorBackend.MlKitAccurate -> OfflineFaceDetector(accurate = true)
            else -> OfflineFaceDetector(accurate = false)
        }

        fun close() {
            if (faceDelegate.isInitialized()) face.close()
            if (poseDelegate.isInitialized()) pose.close()
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
        val timestampUs: Long,
    )

    /** Atomic because every detect worker reports into the same tally. */
    private class RejectStats {
        val noSubject = AtomicInteger(0)
        val tooSmall = AtomicInteger(0)
        val tooSoft = AtomicInteger(0)
        /** Subject found, but its box mapped outside the frame — not a quality verdict. */
        val roiInvalid = AtomicInteger(0)

        override fun toString(): String =
            "noSubject=${noSubject.get()},small=${tooSmall.get()}," +
                "soft=${tooSoft.get()},roiInvalid=${roiInvalid.get()}"
    }

    private data class ProcessProfile(
        val detectBitmapWidth: Int,
        val minSharpness: Double,
    ) {
        companion object {
            fun forResolution(resolution: StreamResolution): ProcessProfile {
                return when (resolution) {
                    StreamResolution.Fhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                    )
                    StreamResolution.Uhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS_UHD,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoFrameProcessor"
        private const val DEDUP_WINDOW_US = 1_000_000L

        /**
         * Detect workers running alongside the decoder.
         *
         * From the v0.1.3 UHD profile the consumer side is ~43% of the old serial wall and
         * the producer ~50%, so two workers already make the chunk producer-bound — which is
         * the point: further detection cost (a wider detect bitmap, a heavier model) is then
         * absorbed for free. More than two only spends memory until `yuv_jpeg_argb` is fixed.
         */
        private const val DETECT_WORKERS = 2

        /**
         * Frames buffered between the decoder and the workers.
         *
         * Each one is a full-resolution ARGB_8888 bitmap — ~33 MB at UHD — so this is the
         * knob that decides the pipeline's memory ceiling, not a throughput knob. Two is
         * enough to keep the workers fed across a slow frame without holding a queue of 4K
         * buffers the decoder also needs.
         */
        private const val FRAME_QUEUE_CAPACITY = 2

        /** Halve-then-scale for the detect bitmap. See [downscale]. */
        private const val MULTISTEP_DOWNSCALE = true

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
