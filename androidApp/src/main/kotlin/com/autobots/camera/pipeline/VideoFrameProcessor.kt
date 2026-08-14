package com.autobots.camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.util.Log
import com.autobots.camera.DetectorBackend
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.StreamResolution
import com.autobots.camera.detection.DetectorComparison
import com.autobots.camera.detection.FaceDetLiteDetector
import com.autobots.camera.detection.OfflineFaceDetector
import com.autobots.camera.detection.SubjectFaceDetector
import com.autobots.camera.detection.OfflinePoseDetector
import com.autobots.camera.detection.PoseDetectionResult
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.DvfsProbe
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
 * the decoder is the limit, and only producer-side work can help.
 *
 * **0.1.4 answered that question and then acted on it.** Across TC-08/09/10 `worker_idle`
 * ran at 211–220 ms per frame while the producer's `yuv_jpeg_argb` was 89–92% of wall: the
 * consumers were idle almost the whole session, and swapping ML Kit for the NPU (−34% on
 * `detect`) bought back only 4% of wall because it made an already-idle side idler.
 *
 * The fix is not to speed the producer up but to **move work off it**. Frames now cross the
 * channel compressed ([SampledFrame]); the ARGB decode happens on a worker, at detect
 * resolution first, and at full resolution only for the ~25% of frames that pass the size
 * gate. `jpeg_argb_detect` and `jpeg_argb_full` are the consumer-side cost of that, and
 * `yuv_nv21` + `nv21_jpeg` are what is left on the producer.
 */
class VideoFrameProcessor(
    private val outputDir: File,
    private val appContext: Context,
) {
    /** One detector pair per detect worker — see the class doc on why they are not shared. */
    private var detectors: List<DetectorSet> = emptyList()
    private var detectorsBackend: DetectorBackend? = null

    /** Non-null only in [DetectorBackend.CompareAll]; see [DetectorComparison]. */
    private var comparison: DetectorComparison? = null

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

        // Before the pipeline starts, so the reading is of an idle-ish core rather than of
        // this chunk's own contention — the point is to compare chunks, not to profile one.
        val cpuProbeNs = DvfsProbe.measureNs()

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

        // Bounded so the decoder cannot run ahead of detection without limit.
        // onUndeliveredElement covers cancellation: anything still in flight gets released.
        val frames = Channel<SampledFrame>(
            capacity = FRAME_QUEUE_CAPACITY,
            onUndeliveredElement = { it.release() },
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
                    VideoFrameSampler.sampleFrames(file, sampleIntervalMs, perf) { frame ->
                        scannedFrames++
                        onProgress(((scannedFrames * 100) / estimatedFrames).coerceIn(0, 99))
                        frames.send(frame)
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
                    cpuProbeNs = cpuProbeNs,
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
    /**
     * Comparison mode runs on a **single** worker. Each worker owns its detectors, so N
     * workers would mean N of every backend — several QNN contexts and GPU delegates at once,
     * for a mode whose wall time is not being measured anyway.
     */
    private fun workerCountFor(backend: DetectorBackend): Int =
        if (backend == DetectorBackend.CompareAll) 1 else DETECT_WORKERS

    private fun ensureDetectors(backend: DetectorBackend) {
        val workers = workerCountFor(backend)
        if (detectorsBackend == backend && detectors.size == workers) return
        detectors.forEach { it.close() }
        comparison?.close()

        if (backend == DetectorBackend.CompareAll) {
            // ML Kit FAST stays the decision-maker, so kept/rejected counts remain directly
            // comparable with every earlier release.
            detectors = List(workers) { DetectorSet(appContext, COMPARE_PRIMARY) }
            comparison = DetectorComparison.create(appContext, COMPARE_PRIMARY)
        } else {
            detectors = List(workers) { DetectorSet(appContext, backend) }
            comparison = null
        }
        detectorsBackend = backend
    }

    /** Rendered `detector_compare.json`, or null when the session was not in compare mode. */
    fun comparisonReport(): String? = comparison?.render()

    private suspend fun runDetectWorker(
        set: DetectorSet,
        frames: ReceiveChannel<SampledFrame>,
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
            } finally {
                // Every bitmap this frame handed out is owned by the code above, which has
                // recycled it by now; this only releases what the frame itself still holds.
                frame.release()
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
        frame: SampledFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        return when (target) {
            ExtractionTarget.Face -> evaluateFaceFrame(set, frame, rejects)
            ExtractionTarget.Pose -> evaluatePoseFrame(set, frame, rejects)
        }
    }

    private suspend fun evaluateFaceFrame(
        set: DetectorSet,
        frame: SampledFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        val timestampUs = frame.timestampUs
        val detectBmp = uprightDetectBitmap(frame) ?: run {
            logFrame(timestampUs, "decode_failed")
            return null
        }
        val faces = try {
            CamPerf.timed(perf, "detect") { set.face.detect(detectBmp) }
        } catch (t: Throwable) {
            Log.w(TAG, "Face detect failed at ${timestampUs}us", t)
            emptyList()
        }
        // Size is judged in detect space, so a rejected frame never pays for a full-res rotate.
        // Observation only, and it has to happen while the detect bitmap is still alive.
        comparison?.record(currentChunkIndex, timestampUs, detectBmp)

        val largest = faces.maxByOrNull { it.height() }
        val detectWidth = detectBmp.width
        val detectHeight = detectBmp.height
        detectBmp.recycle()

        if (largest == null) {
            rejects.noSubject.incrementAndGet()
            logFrame(timestampUs, "no_subject")
            return null
        }
        val subjectRatio = largest.height().toFloat() / detectHeight
        if (subjectRatio < profile.minFaceHeightRatio) {
            rejects.tooSmall.incrementAndGet()
            logFrame(timestampUs, "too_small", subjectRatio = subjectRatio)
            return null
        }

        // Only here, past the size gate, does the frame earn a full-resolution decode.
        val upright = uprightFullFrame(frame) ?: run {
            logFrame(timestampUs, "decode_failed", subjectRatio = subjectRatio)
            return null
        }
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
        frame: SampledFrame,
        rejects: RejectStats,
    ): FrameCandidate? {
        val timestampUs = frame.timestampUs
        val detectBmp = uprightDetectBitmap(frame) ?: run {
            logFrame(timestampUs, "decode_failed")
            return null
        }
        val detection = try {
            CamPerf.timed(perf, "detect_pose") { set.pose.detect(detectBmp) }
        } catch (t: Throwable) {
            Log.w(TAG, "Pose detect failed at ${timestampUs}us", t)
            null
        }
        val detectWidth = detectBmp.width
        val detectHeight = detectBmp.height
        detectBmp.recycle()

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

        val upright = uprightFullFrame(frame) ?: run {
            logFrame(timestampUs, "decode_failed", subjectRatio = subjectRatio)
            return null
        }
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
     *
     * Since the deferred-decode change the first reduction is done by the JPEG decoder via
     * [sampleSizeFor] rather than by a bilinear halving pass, and the caller owns every
     * bitmap involved — there is no shared full-resolution frame to protect any more, so the
     * defensive copy the rotation path used to need is gone with it.
     *
     * @return null when the frame could not be decoded at all.
     */
    private fun uprightDetectBitmap(frame: SampledFrame): Bitmap? {
        val swapsAxes = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val uprightWidth = if (swapsAxes) frame.height else frame.width
        val target = profile.detectBitmapWidth

        val scaled = if (uprightWidth <= target) {
            // Already at or below detect size — nothing to reduce, so this is the full frame.
            CamPerf.timed(perf, "jpeg_argb_detect") { frame.decode(1) } ?: return null
        } else {
            val factor = target.toFloat() / uprightWidth
            val targetWidth = max(1, (frame.width * factor).toInt())
            val targetHeight = max(1, (frame.height * factor).toInt())
            val sampleSize = sampleSizeFor(frame.width, frame.height, targetWidth, targetHeight)
            val decoded = CamPerf.timed(perf, "jpeg_argb_detect") {
                frame.decode(sampleSize)
            } ?: return null
            CamPerf.timed(perf, "scale_for_detect") {
                downscale(decoded, targetWidth, targetHeight).also {
                    if (it !== decoded) decoded.recycle()
                }
            }
        }
        if (frame.rotationDegrees == 0) return scaled
        return VideoFrameSampler.rotate(scaled, frame.rotationDegrees)
    }

    /**
     * The JPEG decoder's share of the reduction to detect size.
     *
     * `BitmapFactory.inSampleSize` reduces by a power of two during decode, so those passes
     * cost nothing beyond the decode that had to happen anyway — where [downscale]'s halving
     * loop had to materialise a full-resolution ARGB bitmap first and then halve it.
     *
     * The factor returned is **exactly the one the halving loop would have applied**: the
     * largest power of two that still leaves the result at or above the target in both axes.
     * At UHD portrait (3840×2160 → 1137×640) both give 1920×1080, and the remaining 1.69×
     * step runs through the same `createScaledBitmap` as before.
     *
     * What is *not* identical is the pixels. libjpeg reduces in the DCT domain, which is a
     * different filter from bilinear halving — comparable quality, not the same output. So
     * detect verdicts can shift by a frame or two on this change alone, and a run that wants
     * to attribute a `kept` delta has to hold this constant. `downscaleMode` in
     * `perf_report.json` records which path produced a given report.
     *
     * Returns 1 when [MULTISTEP_DOWNSCALE] is off, so TC-05 still measures what it was
     * written to measure: one bilinear step from the full-resolution frame.
     */
    private fun sampleSizeFor(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (!MULTISTEP_DOWNSCALE) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
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

    /**
     * Upright full-resolution frame — only frames that passed the size gate pay for this.
     *
     * `jpeg_argb_full` is the cost the deferred decode is trying to avoid: measured on
     * run4mins it should fire on ~25% of sampled frames rather than 100%, and on a detect
     * worker rather than on the decoder thread.
     */
    private fun uprightFullFrame(frame: SampledFrame): Bitmap? {
        val full = CamPerf.timed(perf, "jpeg_argb_full") { frame.decode(1) } ?: return null
        if (frame.rotationDegrees == 0) return full
        return CamPerf.timed(perf, "rotate") {
            VideoFrameSampler.rotate(full, frame.rotationDegrees)
        }
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
        comparison?.close()
        comparison = null
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
        /** Smallest face, as a fraction of the detect bitmap's height, worth keeping. */
        val minFaceHeightRatio: Float,
    ) {
        companion object {
            fun forResolution(resolution: StreamResolution): ProcessProfile {
                return when (resolution) {
                    StreamResolution.Fhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS,
                        minFaceHeightRatio = MIN_FACE_HEIGHT_RATIO_FHD,
                    )
                    StreamResolution.Uhd -> ProcessProfile(
                        detectBitmapWidth = 640,
                        minSharpness = MIN_SHARPNESS_UHD,
                        minFaceHeightRatio = MIN_FACE_HEIGHT_RATIO_UHD,
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

        /** Whose verdict the pipeline follows in [DetectorBackend.CompareAll]. */
        private val COMPARE_PRIMARY = DetectorBackend.MlKitFast

        /**
         * Frames buffered between the decoder and the workers.
         *
         * Was 2, and had to be: each slot held a full-resolution ARGB_8888 bitmap — ~33 MB at
         * UHD — which made this the pipeline's memory ceiling rather than a throughput knob.
         *
         * Since frames cross the channel as JPEG ([SampledFrame]) a slot costs ~2 MB, and the
         * reason to keep it at 2 is gone. It has to grow, too: the workers now do the ARGB
         * decodes, and the full-resolution one fires on the ~25% of frames that pass the size
         * gate — bursty work. With two slots the decoder stalls behind a burst instead of
         * running ahead through it. Six slots is ~12 MB and covers a run of consecutive
         * keepers, which is exactly what a subject walking past the lens produces.
         *
         * This is the second variable in one change; `frameQueueCapacity` is recorded per
         * chunk in `perf_report.json` so a run can be attributed, and setting it back to 2
         * isolates the deferred decode on its own.
         */
        private const val FRAME_QUEUE_CAPACITY = 6

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
        /**
         * Smallest face worth a photo, as a fraction of the **detect bitmap's** height — so the
         * same number means different pixel counts in the delivered JPEG depending on the source,
         * which is why it is per-resolution like [MIN_SHARPNESS].
         *
         * 0.1.4 measured the whole distribution (`detector_compare.json`, 812 frames with a face
         * at UHD) and found 0.035 sitting on the **steepest slope of it**: 49 frames per 0.001,
         * against 28–35 anywhere else. A threshold there is maximally sensitive to how a given
         * model draws its box, and that is not a hypothetical — ML Kit ACCURATE draws boxes 7.1%
         * taller than FAST, which at that slope is worth ~120 frames and was misread as ACCURATE
         * "seeing more". Only 5 of its extra frames were faces FAST genuinely missed.
         *
         * UHD moves to 0.030: off the cliff (36 frames per 0.001), and the swing from swapping
         * detectors drops from ~30% of passing frames to ~12%. Costs nothing in quality that
         * matters — 0.030 x 1137 px detect x (2160/640) is still a **115 px face** in the saved
         * 4K frame. It takes frames past the size gate from 404 to 623 (+54%).
         *
         * FHD is left at 0.035 because it was **not measured**. The same ratio there is only ~58 px
         * in a 1080p frame, so the UHD number cannot simply be copied across.
         */
            private const val MIN_FACE_HEIGHT_RATIO_UHD = 0.030f
        private const val MIN_FACE_HEIGHT_RATIO_FHD = 0.035f
        private const val MIN_TORSO_HEIGHT_RATIO = 0.25f
        const val MIN_SHARPNESS = 80.0
        const val MIN_SHARPNESS_UHD = 65.0
    }
}
