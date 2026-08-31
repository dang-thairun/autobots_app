package com.autobots.camera.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autobots.camera.DetectZone
import com.autobots.camera.DetectorBackend
import com.autobots.camera.FrameQuality
import com.autobots.camera.SubjectCount
import com.autobots.camera.TrackSummary
import com.autobots.camera.detection.DetectorComparison
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.ChunkProcessStatus
import com.autobots.camera.ChunkRecord
import com.autobots.camera.ExtractedFaceImage
import com.autobots.camera.PipelineStats
import com.autobots.camera.StreamResolution
import com.autobots.camera.VideoPreviewController
import com.autobots.camera.capture.ChunkCaptureMeta
import com.autobots.camera.capture.ImportSplitResult
import com.autobots.camera.capture.ImportedVideoSplitter
import com.autobots.camera.detection.FaceDetLiteDetector
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.network.RemoteVideoFetcher
import com.autobots.camera.network.VideoHttp
import com.autobots.camera.delivery.SessionAlbumNaming
import com.autobots.camera.delivery.WriteQueue
import com.autobots.camera.upload.UploadCandidate
import com.autobots.camera.upload.UploadRepository
import com.autobots.camera.upload.UploadScheduler
import com.autobots.camera.toLogText
import com.autobots.camera.load.DeviceLoadReader
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.PerfReport
import com.autobots.camera.perf.PerfStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.autobots.camera.PipelineSessionRecord
import com.autobots.camera.SessionSource
import com.autobots.camera.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates queueVideo → face extract → queueImage → local gallery delivery.
 */
class CapturePipelineCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val onStats: (PipelineStats) -> Unit,
    private val onPhotoDelivered: (Uri) -> Unit,
    private val onDrainComplete: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val sessionId = System.currentTimeMillis().toString()
    private val sessionDir = File(appContext.cacheDir, "autobots/$sessionId")
    private val facesDir = File(sessionDir, "faces")
    private val videoQueue = Channel<ChunkWorkItem>(VIDEO_QUEUE_CAPACITY)
    private val videoPending = AtomicInteger(0)
    private val workerBusy = AtomicBoolean(false)
    private val historyLock = Mutex()
    private val chunkHistory = mutableListOf<ChunkRecord>()

    /**
     * Set when a session begins and read from every thread that reports on it — the worker,
     * the drain watchdog and the stats publisher. The *fields inside* [SessionMeta] are
     * mutable and still unsynchronised; they are written during the split and read once the
     * split has finished, which the drain check orders for us.
     */
    @Volatile
    private var sessionMeta: SessionMeta? = null

    private val frameProcessor = VideoFrameProcessor(facesDir, appContext)
    private val deliveryWriter = LocalDeliveryWriter(appContext)
    private val perfReport = PerfReport()
    private val loadReader = if (CamPerf.enabled) {
        // The detailed reader — this is the one whose samples reach `perf_report.json`.
        DeviceLoadReader(appContext, detailed = true) { it.run() }
    } else {
        null
    }
    /**
     * Where delivered photos are recorded for later upload. Local-only for now — nothing
     * reads this queue yet (B3a in docs/PHASES.md); the worker arrives in B3c.
     */
    private val uploadQueue: UploadRepository = UploadRepository.create(appContext)

    private val imageDelivery = WriteQueue(
        writer = deliveryWriter,
        capacity = IMAGE_QUEUE_CAPACITY,
        onDelivered = { uri ->
            onPhotoDelivered(uri)
            publishStats()
            maybeNotifyDrainComplete()
        },
        onDeliveredFile = ::recordMomentToGallery,
        onPublished = ::enqueueForUpload,
    )

    init {
        // Rows left in Uploading by a process that died mid-transfer belong back in the
        // queue; nothing else would ever claim them again.
        scope.launch { runCatching { uploadQueue.resetInterrupted() } }
    }

    /** chunk index → wall-clock instant that chunk started / finished recording. */
    private val chunkStartWallMs = ConcurrentHashMap<Int, Long>()
    private val chunkQueuedWallMs = ConcurrentHashMap<Int, Long>()
    /** chunk index → footage length, so the perf report can stand alone. */
    private val chunkRecordDurationMs = ConcurrentHashMap<Int, Long>()
    private var photoLatencySumMs = 0L
    private var photoLatencyCount = 0
    private var photoLatencyMaxMs = 0L
    private var photoLatencyMinMs = Long.MAX_VALUE

    /** Written on the worker thread, read when publishing stats. */
    @Volatile
    private var lastRealtimeRatio = 0f

    /**
     * State that decides whether a session may finish — see [isBusy].
     *
     * These four are written from the caller's thread (the ViewModel's scope, so the main
     * thread) and read from [startDrainWatchdog]'s coroutine on a different dispatcher. As
     * plain fields there is no happens-before edge between those two, so the watchdog is not
     * guaranteed to ever observe `importing` going false — and a watchdog that never sees the
     * pipeline go idle is a session that writes neither `session_log.txt` nor
     * `perf_report.json`. That is exactly the symptom behind NA-05, which has bitten twice
     * and whose cause is still unproven; `@Volatile` cannot confirm it was this, but it does
     * remove it from the list of candidates for the price of four words.
     *
     * The rest of [isBusy] was already safe: `workerBusy`, `videoPending` and
     * `WriteQueue.pending` are atomics.
     */
    @Volatile
    private var recording = false

    @Volatile
    private var awaitingRecorderFinalize = false

    @Volatile
    private var closed = false

    @Volatile
    private var importing = false

    /**
     * Progress and tally fields, written on the Worker 2 coroutine ([Dispatchers.Default]) and
     * read by [publishStats] on the caller's scope. Stale reads here only make the UI show a
     * value one update behind, but `chunksProcessed`/`chunksRecorded` also feed
     * [currentSessionStatus], where a stale pair reads as "still working" — so they are
     * published properly rather than left to chance.
     *
     * `@Volatile` rather than `AtomicInteger` because the `+=` and `++` below have exactly one
     * writer — the single Worker 2 coroutine — so there is no update to lose, only a value to
     * publish. An atomic would buy nothing and read worse at the call sites.
     */
    @Volatile
    private var chunksRecorded = 0

    @Volatile
    private var chunksProcessed = 0

    @Volatile
    private var facesKept = 0

    @Volatile
    private var facesSkipped = 0

    @Volatile
    private var lastChunkProcessMs = 0L

    @Volatile
    private var currentChunkPercent = 0

    @Volatile
    private var processingChunkName: String? = null

    @Volatile
    private var importPercent = 0

    @Volatile
    private var importName: String? = null

    @Volatile
    private var pipelinePaused = false

    private var resolution = StreamResolution.Fhd
    private var extractionTarget = ExtractionTarget.Face
    /** Capture Zone, normalised. Null (or full frame) means every corner counts. */
    private var detectZone: DetectZone? = null
    private var minFaceScore = FaceDetLiteDetector.DEFAULT_SCORE_THRESHOLD

    /** One row per kept JPEG, flushed to `photos.csv` when the session finishes. */
    /**
     * Bodies of `photos.csv` / `tracks.csv` / `chunks.csv`, appended as each chunk finishes.
     *
     * These replaced two in-memory row lists. See [CsvPart] for why: a session that died lost
     * every row, and `buildString` at drain made peak heap grow with session length.
     */
    private val photosPart = CsvPart(File(sessionDir, PHOTO_INDEX_FILE + CsvPart.SUFFIX))
    private val tracksPart = CsvPart(File(sessionDir, TRACK_INDEX_FILE + CsvPart.SUFFIX))
    private val chunksPart = CsvPart(File(sessionDir, CHUNK_INDEX_FILE + CsvPart.SUFFIX))

    /**
     * Running totals for the `#` header lines and [subjectCount], which used to be counted by
     * walking the row list at drain. The list is gone; these are the only survivors of it.
     */
    private val trackTotals = TrackTotals()

    /** Every passage the tracker saw this session, with the chunk it belongs to. */
    private var shutterCeilingFps: Int? = null
    private var exposureIndex: Int = 0
    private var exposureStepEv: Double? = null
    private var detectorBackend = DetectorBackend.DEFAULT

    /**
     * Projected chunk total for an import, so the UI can show one smooth bar while the
     * splitter is blocked on queue backpressure. Projected from the splitter's timeline
     * percentage at each segment boundary, then pinned to the real count when the split ends.
     * Monotonic on purpose: the bar must never walk backwards.
     *
     * Written from the splitter's thread via [onChunkRecorded] and from [importVideo]; read by
     * [publishStats] on the caller's scope.
     */
    @Volatile
    private var expectedChunks = 0
    private val chunkIndexSeq = AtomicInteger(0)
    /** Guards against writing the session log more than once per session. */
    private val drainNotified = AtomicBoolean(false)
    private var drainWatchdog: Job? = null

    /** Crash-survivable copy of the perf data. Null when instrumentation is off. */
    private var perfStream: PerfStream? = null
    private var heartbeat: Job? = null

    init {
        sessionDir.mkdirs()
        facesDir.mkdirs()

        scope.launch(Dispatchers.Default) {
            for (item in videoQueue) {
                workerBusy.set(true)
                processingChunkName = item.videoFile.name
                currentChunkPercent = 0
                historyLock.withLock {
                    val i = chunkHistory.indexOfFirst { it.index == item.index }
                    if (i >= 0) {
                        chunkHistory[i] = chunkHistory[i].copy(status = ChunkProcessStatus.Processing)
                    }
                }
                publishStats()
                samplePerfLoad("chunk_process_start", item.index)
                try {
                    val processStartMs = System.currentTimeMillis()
                    val result = frameProcessor.process(
                        item.videoFile,
                        chunkIndex = item.index,
                        resolution = resolution,
                        extractionTarget = extractionTarget,
                        sampleIntervalMs = resolution.frameSampleIntervalMs,
                        detectorBackend = detectorBackend,
                        detectZone = detectZone,
                        minFaceScore = minFaceScore,
                    ) { percent ->
                        currentChunkPercent = percent
                        publishStats()
                    }
                    val images = result.savedPhotos.map { photo ->
                        ExtractedFaceImage(
                            fileName = photo.file.name,
                            sizeBytes = photo.file.length(),
                            absolutePath = photo.file.absolutePath,
                            score = photo.score,
                        )
                    }
                    result.tracks.forEach { track ->
                        trackTotals.add(track)
                        tracksPart.append(trackRow(item.index, track))
                    }
                    if (result.tracks.isNotEmpty()) trackTotals.chunks++
                    chunksPart.append(chunkRow(item, result))
                    result.savedPhotos.forEach { photosPart.append(photoRow(item.index, it)) }
                    warnIfDiskLow(item.index)
                    facesKept += result.kept
                    facesSkipped += result.skipped
                    lastChunkProcessMs = result.durationMs
                    chunksProcessed++
                    historyLock.withLock {
                        val i = chunkHistory.indexOfFirst { it.index == item.index }
                        if (i >= 0) {
                            chunkHistory[i] = chunkHistory[i].copy(
                                status = ChunkProcessStatus.Done,
                                processDurationMs = result.durationMs,
                                facesKept = result.kept,
                                facesSkipped = result.skipped,
                                framesSampled = result.framesSampled,
                                sampleIntervalMs = resolution.frameSampleIntervalMs,
                                extractedImages = images,
                            )
                        }
                    }
                    recordChunkEndToEnd(item, processStartMs, result)
                    for (imageFile in result.savedFiles) {
                        imageDelivery.enqueue(imageFile)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Video process failed for ${item.videoFile.name}", t)
                    chunksProcessed++
                    perfReport.addEvent(
                        System.currentTimeMillis(),
                        "chunk_failed",
                        item.index,
                        videoPending.get(),
                    )
                    historyLock.withLock {
                        val i = chunkHistory.indexOfFirst { it.index == item.index }
                        if (i >= 0) {
                            chunkHistory[i] = chunkHistory[i].copy(status = ChunkProcessStatus.Failed)
                        }
                    }
                } finally {
                    samplePerfLoad("chunk_process_end", item.index)
                    releaseChunkFile(item)
                    workerBusy.set(false)
                    processingChunkName = null
                    currentChunkPercent = 0
                    videoPending.decrementAndGet()
                    publishStats()
                    maybeNotifyDrainComplete()
                }
            }
        }
    }

    fun setResolution(value: StreamResolution) {
        resolution = value
        publishStats()
    }

    fun setExtractionTarget(value: ExtractionTarget) {
        extractionTarget = value
        publishStats()
    }

    /** Null or a full-frame zone both mean "scan everything". */
    fun setDetectZone(value: DetectZone?) {
        detectZone = value?.takeUnless { it.isFullFrame }
    }

    /** Confidence a face must reach. Honoured by LiteRT backends only; ML Kit has no score. */
    fun setMinFaceScore(value: Float) {
        minFaceScore = value
    }

    /** Recorded onto the session so a field report can say what the AE was told to do. */
    fun setExposureSettings(shutterCeilingFps: Int?, exposureIndex: Int, stepEv: Double?) {
        this.shutterCeilingFps = shutterCeilingFps
        this.exposureIndex = exposureIndex
        this.exposureStepEv = stepEv
    }

    /**
     * Which detector Worker 2 runs. Changing it between imports of the same clip is how the
     * 0.1.4 bench isolates the detector as the single variable — see [DetectorBackend].
     */
    fun setDetectorBackend(value: DetectorBackend) {
        detectorBackend = value
        publishStats()
    }

    /**
     * Give back the chunk's disk once Worker 2 is finished with it.
     *
     * Until 0.1.4 nothing deleted these, and `videoQueue`'s capacity only bounds how many
     * chunks are *waiting* — not how many exist. So the cache accumulated a complete second
     * copy of the source: run4mins left 1.93 GB behind, which nobody noticed at 4½ minutes
     * and which extrapolates to **~25 GB for a one-hour import and ~51 GB for two**, on top
     * of the original file. `hasStorageForRecording()` is checked once when the import starts
     * and never again, so nothing would have stopped it filling the device mid-run.
     *
     * Safe to delete here: the only thing that outlives a chunk is its **name**, recorded in
     * [ChunkRecord.videoFileName] for `session_log.txt`. No path re-opens the file — the
     * photos were written to `facesDir` and handed to the delivery queue inside the try above,
     * and [recordChunkEndToEnd] read `length()` before this runs.
     *
     * Set [KEEP_PROCESSED_CHUNKS] to inspect what the splitter actually produced. That costs
     * the whole source size in cache, so it is a debugging switch, not a setting.
     */
    private fun releaseChunkFile(item: ChunkWorkItem) {
        if (KEEP_PROCESSED_CHUNKS) return
        val file = item.videoFile
        val bytes = runCatching { file.length() }.getOrDefault(0L)
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (!deleted) {
            // Not fatal on its own, but a run of these is how the cache fills up — say so
            // once per chunk rather than letting it be silent.
            Log.w(TAG, "Could not delete processed chunk ${file.name} (${bytes / 1024}KB)")
        }
    }

    fun sessionDirectory(): File = sessionDir

    fun canAcceptVideoChunk(): Boolean = videoPending.get() < VIDEO_QUEUE_CAPACITY

    fun isBusy(): Boolean {
        return recording ||
            importing ||
            awaitingRecorderFinalize ||
            workerBusy.get() ||
            videoPending.get() > 0 ||
            imageDelivery.pendingCount > 0
    }

    /**
     * Feed a video already on the device — or a direct HTTP(S) file URL — into the
     * same chunk → extract → deliver path. Remote sources are streamed: the first
     * remuxed chunk can enter Worker 2 while later samples are still arriving.
     * Splitting reuses the live queue's backpressure, so a long import cannot outrun
     * Worker 2 or blow up memory.
     */
    suspend fun importVideo(
        source: Uri,
        displayName: String?,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
    ): ImportSplitResult {
        if (closed) {
            return ImportSplitResult(0, 0L, 0L, error = "Pipeline already closed")
        }
        beginSession(SessionSource.VideoImport, displayName ?: "Imported video")
        val splitStartMs = System.currentTimeMillis()
        importing = true
        importPercent = 0
        expectedChunks = 0
        importName = displayName
        publishStats()

        val splitter = ImportedVideoSplitter(appContext, File(sessionDir, "video"))
        return try {
            withContext(Dispatchers.IO) {
                val probe = splitter.probe(source)
                if (probe != null) {
                    val detected = StreamResolution.fromVideoDimensions(
                        probe.width,
                        probe.height,
                        probe.rotationDegrees,
                    )
                    setResolution(detected)
                    sessionMeta?.sourceVideoWidth = probe.width
                    sessionMeta?.sourceVideoHeight = probe.height
                    sessionMeta?.sourceRotationDegrees = probe.rotationDegrees
                    sessionMeta?.sourceDurationMs = probe.durationMs.takeIf { it > 0 }
                    Log.i(
                        TAG,
                        "Import probe ${probe.displayWidth}x${probe.displayHeight} " +
                            "(rot ${probe.rotationDegrees}°) → ${detected.label}",
                    )
                    publishStats()
                } else {
                    Log.w(TAG, "Import probe failed; using UI resolution ${resolution.label}")
                }

                suspend fun runSplit(src: Uri) = splitter.split(
                    source = src,
                    targetSegmentBytes = resolution.chunkTargetBytes,
                    startIndex = chunkIndexSeq.get() + 1,
                    canAcceptChunk = ::canAcceptVideoChunk,
                    onChunkReady = ::onChunkRecorded,
                    onProgress = { percent ->
                        importPercent = percent
                        publishStats()
                    },
                    startTimeUs = (rangeStartMs ?: 0L).coerceAtLeast(0L) * 1000L,
                    endTimeUs = rangeEndMs
                        ?.takeIf { it > 0L }
                        ?.let { it * 1000L }
                        ?: Long.MAX_VALUE,
                )

                val streamed = runSplit(source)
                if (!shouldDownloadFallback(source, streamed)) {
                    streamed
                } else {
                    Log.w(
                        TAG,
                        "HTTP stream split failed (${streamed.error}); downloading then retrying",
                    )
                    val safeName = File(displayName ?: "video.mp4").name.ifBlank { "video.mp4" }
                    val dest = File(appContext.cacheDir, "network_import/$safeName")
                    try {
                        RemoteVideoFetcher.download(source.toString(), dest) { percent ->
                            importPercent = percent
                            publishStats()
                        }
                        // Deleted whatever the split makes of it: the chunks it produces are
                        // the output, and this copy is scratch. Leaving it behind meant a
                        // gigabyte per network import sitting in the cache until Android
                        // decided to reclaim it — which it only does under pressure, long
                        // after the disk has become the operator's problem.
                        try {
                            runSplit(Uri.fromFile(dest))
                        } finally {
                            dest.delete()
                        }
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        dest.delete()
                        ImportSplitResult(
                            0, 0L, 0L,
                            error = t.message ?: "Download failed",
                            blockedMs = streamed.blockedMs,
                        )
                    }
                }
            }.also { result ->
                sessionMeta?.splitDurationMs = System.currentTimeMillis() - splitStartMs
                sessionMeta?.splitBlockedMs = result.blockedMs
                if (result.videoWidth > 0 && result.videoHeight > 0) {
                    sessionMeta?.sourceVideoWidth = result.videoWidth
                    sessionMeta?.sourceVideoHeight = result.videoHeight
                    sessionMeta?.sourceRotationDegrees = result.rotationDegrees
                }
                sessionMeta?.sourceDurationMs = result.sourceDurationMs.takeIf { it > 0 }
                sessionMeta?.sourceSizeBytes = result.totalBytes.takeIf { it > 0 }
                if (result.error != null || result.segments == 0) {
                    sessionMeta?.failed = true
                    sessionMeta?.errorMessage = result.error ?: "No video segments produced"
                }
                Log.i(
                    TAG,
                    "Import ${displayName ?: source} -> ${result.segments} chunk(s)" +
                        (result.error?.let { " (error: $it)" } ?: ""),
                )
            }
        } catch (t: CancellationException) {
            // The user backed out; that is not an import failure and must not be recorded as
            // one. Rethrowing also keeps the caller's coroutine cancellation intact — the
            // catch below would otherwise report success-with-an-error-string and let the
            // job carry on as if nothing had been cancelled. The finally still runs.
            sessionMeta?.splitDurationMs = System.currentTimeMillis() - splitStartMs
            throw t
        } catch (t: Throwable) {
            sessionMeta?.splitDurationMs = System.currentTimeMillis() - splitStartMs
            sessionMeta?.failed = true
            sessionMeta?.errorMessage = t.message ?: t::class.java.simpleName
            Log.e(TAG, "Import failed for ${displayName ?: source}", t)
            ImportSplitResult(0, 0L, 0L, error = t.message ?: t::class.java.simpleName)
        } finally {
            importing = false
            importPercent = 0
            importName = null
            // Split is done, so the projection is replaced by the real count and the bar's
            // denominator stops moving for the rest of the extract.
            expectedChunks = chunksRecorded
            publishStats()
            maybeNotifyDrainComplete()
        }
    }

    /**
     * Stream failed before any chunk was produced. Range validation errors will not
     * be fixed by downloading the file, so those stay as-is.
     */
    private fun shouldDownloadFallback(source: Uri, result: ImportSplitResult): Boolean {
        if (!VideoHttp.isRemote(source) || result.segments > 0) return false
        val error = result.error ?: return true
        return !error.contains("Start must be before", ignoreCase = true)
    }

    fun onRecordingStarted() {
        beginSession(SessionSource.LiveCapture, liveSessionDisplayName())
        recording = true
        publishStats()
    }

    fun stopRecording() {
        recording = false
        pipelinePaused = false
        awaitingRecorderFinalize = true
        publishStats()
    }

    /** Camera finished stopping; safe if no partial chunk was emitted. */
    fun onRecorderStopSettled() {
        awaitingRecorderFinalize = false
        publishStats()
        maybeNotifyDrainComplete()
    }

    fun onChunkRecorded(meta: ChunkCaptureMeta) {
        if (closed) {
            Log.w(TAG, "Ignoring chunk ${meta.file.name}; pipeline already closed")
            return
        }
        awaitingRecorderFinalize = false
        chunksRecorded++
        videoPending.incrementAndGet()
        if (importing) {
            // Segment boundaries are the only points where both counters are consistent:
            // the splitter has just finished writing this chunk, so importPercent is the
            // fraction of the source timeline these chunksRecorded cover.
            val projected = if (importPercent in 1..99) {
                (chunksRecorded * 100 + importPercent - 1) / importPercent
            } else {
                chunksRecorded
            }
            expectedChunks = maxOf(expectedChunks, projected, chunksRecorded)
        }
        chunkStartWallMs[meta.index] = meta.recordedAtEpochMs
        chunkQueuedWallMs[meta.index] = System.currentTimeMillis()
        chunkRecordDurationMs[meta.index] = meta.recordDurationMs
        chunkIndexSeq.updateAndGet { maxOf(it, meta.index) }
        val record = ChunkRecord(
            sessionId = sessionId,
            index = meta.index,
            videoFileName = meta.file.name,
            videoAbsolutePath = meta.file.absolutePath,
            recordedAtEpochMs = meta.recordedAtEpochMs,
            resolution = resolution,
            extractionTarget = extractionTarget,
            recordDurationMs = meta.recordDurationMs,
            videoSizeBytes = meta.videoSizeBytes,
            targetVideoBytes = resolution.chunkTargetBytes,
            status = ChunkProcessStatus.Pending,
            sampleIntervalMs = resolution.frameSampleIntervalMs,
        )
        scope.launch {
            historyLock.withLock {
                chunkHistory.removeAll { it.index == meta.index }
                chunkHistory.add(record)
                chunkHistory.sortBy { it.index }
            }
            publishStats()
        }
        val accepted = videoQueue.trySend(
            ChunkWorkItem(meta.index, meta.file, meta.sourceOffsetUs, meta.recordedAtEpochMs),
        )
        if (!accepted.isSuccess) {
            videoPending.decrementAndGet()
            Log.w(TAG, "Video queue full, dropped ${meta.file.name}")
            perfReport.addEvent(
                System.currentTimeMillis(),
                "chunk_dropped_queue_full",
                meta.index,
                videoPending.get(),
                imageDelivery.pendingCount,
            )
        } else {
            perfReport.addEvent(
                System.currentTimeMillis(),
                "chunk_queued",
                meta.index,
                videoPending.get(),
                imageDelivery.pendingCount,
            )
        }
        publishStats()
        maybeNotifyDrainComplete()
    }

    fun onRecorderPaused() {
        pipelinePaused = true
        // Backpressure kicked in: from here until resume, nothing is being recorded.
        perfReport.addEvent(
            System.currentTimeMillis(),
            "recorder_paused",
            videoQueueDepth = videoPending.get(),
            imageQueuePending = imageDelivery.pendingCount,
        )
        publishStats()
    }

    fun onRecorderResumed() {
        pipelinePaused = false
        perfReport.addEvent(
            System.currentTimeMillis(),
            "recorder_resumed",
            videoQueueDepth = videoPending.get(),
            imageQueuePending = imageDelivery.pendingCount,
        )
        publishStats()
    }

    fun hasStorageForRecording(): Boolean = freeStorageMb() >= VideoPreviewController.MIN_FREE_STORAGE_MB

    private fun freeStorageMb(): Long = VideoPreviewController.freeStorageMb(appContext.cacheDir)

    /**
     * Log once per chunk when the cache is running out, because nothing else will.
     *
     * [hasStorageForRecording] is checked when a session *starts* and never again, which is how
     * the un-deleted chunk `.mp4` bug reached 25 GB with nothing to show for it in any log. A
     * line per chunk is the cheapest thing that would have caught it.
     *
     * Deliberately not a stop: the video is already recorded, so aborting here would lose
     * runners to protect disk. The stop belongs where the writing starts, not where the reading
     * has already happened.
     */
    private fun warnIfDiskLow(chunkIndex: Int) {
        val freeMb = freeStorageMb()
        if (freeMb >= VideoPreviewController.MIN_FREE_STORAGE_MB) return
        Log.w(TAG, "cache low: ${freeMb}MB free after chunk $chunkIndex — session files may be dropped")
        perfReport.addEvent(System.currentTimeMillis(), "disk_low", chunkIndex, freeMb.toInt())
    }

    fun close() {
        if (closed) return
        closed = true
        recording = false
        drainWatchdog?.cancel()
        heartbeat?.cancel()
        // No `end` record: a coordinator closed before drain did not write a report, so the
        // stream must stay recoverable. SessionRecovery decides by whether the report exists.
        perfStream?.finish("closed")
        videoQueue.close()
        frameProcessor.close()
        imageDelivery.close()
    }

    /**
     * The two numbers that decide whether Phase 3 (shorter chunks) is safe:
     * how long a chunk waits before processing, and whether Worker 2 runs faster
     * than realtime. A realtime ratio ≥ 1.0 means shorter chunks will stall the recorder.
     */
    private fun recordChunkEndToEnd(
        item: ChunkWorkItem,
        processStartMs: Long,
        result: VideoProcessResult,
    ) {
        val recordStart = chunkStartWallMs[item.index]
        val queuedAt = chunkQueuedWallMs[item.index]
        // Footage length is the honest denominator; queue timing may be missing for a
        // chunk that arrived before this coordinator started tracking it.
        val recordedMs = chunkRecordDurationMs[item.index]
            ?: (if (recordStart != null && queuedAt != null) queuedAt - recordStart else 0L)
        val queueWaitMs = if (queuedAt != null) processStartMs - queuedAt else -1L
        val ratio = if (recordedMs > 0) result.durationMs.toDouble() / recordedMs else 0.0

        perfReport.addChunk(
            PerfReport.ChunkEntry(
                index = item.index,
                fileName = item.videoFile.name,
                sizeBytes = runCatching { item.videoFile.length() }.getOrDefault(0L),
                recordDurationMs = recordedMs,
                queueWaitMs = queueWaitMs,
                realtimeRatio = ratio,
                diag = result.diag,
            ),
        )

        if (recordStart == null || queuedAt == null || recordedMs <= 0L) return
        lastRealtimeRatio = ratio.toFloat()
        publishStats()

        CamPerf.log {
            buildString {
                append("┌─ chunk#${item.index} ${item.videoFile.name} end-to-end\n")
                append("│ recorded        ${CamPerf.sec(recordedMs)} of footage\n")
                append("│ queue wait      ${CamPerf.sec(queueWaitMs)}\n")
                append("│ process         ${CamPerf.sec(result.durationMs)}  ")
                append("(sampled=${result.framesSampled} kept=${result.kept} skipped=${result.skipped})\n")
                append(
                    String.format(
                        Locale.US,
                        "│ REALTIME RATIO  %.2fx  %s\n",
                        ratio,
                        if (ratio >= 1.0) "<-- SLOWER than realtime, queue will back up" else "(headroom ok)",
                    ),
                )
                append("│ worst latency   ${CamPerf.sec(processStartMs + result.durationMs - recordStart)} ")
                append("(moment at chunk start, before gallery write)\n")
                append("└ video queue     ${videoPending.get()}/$VIDEO_QUEUE_CAPACITY pending")
            }
        }
    }

    /** Wall time from the instant the runner was in front of the lens to a gallery-visible file. */
    private fun recordMomentToGallery(file: File) {
        val parts = file.nameWithoutExtension.split('_')
        if (parts.size < 3) return
        val chunkIndex = parts[1].removePrefix("c").toIntOrNull() ?: return
        val ptsUs = parts[2].toLongOrNull() ?: return
        val recordStart = chunkStartWallMs[chunkIndex] ?: return

        val latencyMs = System.currentTimeMillis() - (recordStart + ptsUs / 1000L)
        synchronized(this) {
            photoLatencySumMs += latencyMs
            photoLatencyCount++
            if (latencyMs > photoLatencyMaxMs) photoLatencyMaxMs = latencyMs
            if (latencyMs < photoLatencyMinMs) photoLatencyMinMs = latencyMs
            CamPerf.log {
                "MOMENT→GALLERY ${CamPerf.sec(latencyMs)} · ${file.name} " +
                    "(chunk#$chunkIndex at t+${CamPerf.sec(ptsUs / 1000L)}) · " +
                    "avg ${CamPerf.sec(photoLatencySumMs / photoLatencyCount)} " +
                    "min ${CamPerf.sec(photoLatencyMinMs)} max ${CamPerf.sec(photoLatencyMaxMs)} " +
                    "over $photoLatencyCount photos"
            }
        }
    }

    /**
     * Poll instead of relying on a callback landing at exactly the right moment.
     *
     * Drain used to be triggered only from whichever event happened to be last — worker
     * finish, photo delivered, import finish. Twice that turned out to miss: a session
     * whose final chunk produced photos wrote neither `session_log.txt` nor
     * `perf_report.json`. The events still fire the check (so a quiet session finishes
     * promptly); this loop only guarantees no session can end without one.
     */
    /**
     * A load sample on a fixed clock, independent of chunk boundaries.
     *
     * Without it the last line in the stream is the last chunk that *finished*, so a session
     * that dies mid-chunk is pinned only to within however long a chunk takes — minutes, on a
     * portrait 4K run. A tick every 30 seconds bounds the time of death to half a minute and
     * gives the memory series a constant sampling rate, which is what makes a leak show as a
     * slope instead of as noise.
     */
    private fun startHeartbeat() {
        heartbeat?.cancel()
        if (!perfReport.enabled) return
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (closed || sessionMeta == null) return@launch
                samplePerfLoad("heartbeat", null)
            }
        }
    }

    private fun startDrainWatchdog() {
        drainWatchdog?.cancel()
        drainWatchdog = scope.launch {
            while (isActive) {
                delay(DRAIN_POLL_MS)
                if (closed || drainNotified.get()) return@launch
                maybeNotifyDrainComplete(fromWatchdog = true)
            }
        }
    }

    private fun maybeNotifyDrainComplete(fromWatchdog: Boolean = false) {
        if (closed || recording) return
        if (sessionMeta == null) return
        if (isBusy()) {
            // One line per second from the watchdog — enough to name the stuck flag
            // without flooding the log from the event callers.
            if (fromWatchdog) {
                CamPerf.log {
                    "drain blocked · recording=$recording importing=$importing " +
                        "awaitingFinalize=$awaitingRecorderFinalize worker=${workerBusy.get()} " +
                        "videoPending=${videoPending.get()} imageQueue=${imageDelivery.pendingCount}"
                }
            }
            return
        }
        // Exactly once per session, whichever caller gets here first.
        if (!drainNotified.compareAndSet(false, true)) return
        CamPerf.log { "drain complete · trigger=${if (fromWatchdog) "watchdog" else "event"}" }
        scope.launch {
            finalizeCurrentSession()
            val session = historyLock.withLock { buildSessionRecord(chunkHistory.toList()) }
            publishStats()
            withContext(Dispatchers.IO) {
                session?.let { writeSessionLog(it) }
                // After the report is on disk, so a crash between the two still recovers.
                perfStream?.finish("drain")
            }
            drainWatchdog?.cancel()
            heartbeat?.cancel()
            onDrainComplete()
        }
    }

    /**
     * `photos.csv` — one row per kept JPEG, next to `session_log.txt`.
     *
     * Written on every session, not only under `CamPerf`: the score that let a photo through
     * cannot be recovered from the photo, and a threshold nobody has distribution data for
     * can only be tuned by guessing. CSV rather than JSON because the consumer is a
     * spreadsheet or a dataframe, not this app.
     */
    private fun writePhotoIndex(session: PipelineSessionRecord) {
        if (!photosPart.hasRows) return
        val header = buildString {
            appendLine(
                "# ${session.displayName} · ${session.extractionTarget.label} · " +
                    "${detectorBackend.slug} · minFaceScore=" +
                    String.format(Locale.US, "%.4f", minFaceScore) +
                    " (logit " +
                    String.format(Locale.US, "%.4f", FaceDetLiteDetector.logitOf(minFaceScore)) +
                    ")",
            )
            appendLine(
                "# weights sharpness=${FrameQuality.W_SHARPNESS} size=${FrameQuality.W_SUBJECT_SIZE} " +
                    "centre=${FrameQuality.W_CENTRE} confidence=${FrameQuality.W_CONFIDENCE} " +
                    "framing=${FrameQuality.W_FRAMING}",
            )
            appendLine(
                "file,chunk,ptsUs,score,scoreLogit,sharpness,subjectRatio," +
                    "quality,qSharpness,qSize,qCentre,qConfidence,qFraming,track",
            )
        }
        publishPart(session, PHOTO_INDEX_FILE, header, photosPart)
    }

    /**
     * One row per passage — including the ones that produced no photograph.
     *
     * `photos.csv` can only ever describe what was kept, so it cannot answer "did we miss
     * anyone". This can: a row with `captured=false` is somebody who went past the lens and
     * came away with nothing, and there is no other record that they were ever there.
     *
     * CSV rather than JSON deliberately. Every field is a scalar, the rows are independent, and
     * the questions asked of it — how many went through, how many did we photograph, which
     * direction, how fast — are one filter and one count in a spreadsheet.
     */
    private fun writeTrackIndex(session: PipelineSessionRecord) {
        if (!tracksPart.hasRows) return
        val header = buildString {
            appendLine("# ${session.displayName} · ${session.extractionTarget.label} · ${detectorBackend.slug}")
            appendLine(
                "# passages=${trackTotals.passages} movedThrough=${trackTotals.movedThrough} " +
                    "likelySubject=${trackTotals.likelySubject} captured=${trackTotals.captured} " +
                    "chunks=${trackTotals.chunks}",
            )
            // Said here rather than left to be rediscovered: this is an upper bound.
            appendLine(
                "# a track is one continuous sighting, not one person — bystanders are tracked, " +
                    "chunk boundaries split a subject in two, and there is no re-identification",
            )
            appendLine(
                "chunk,track,firstUs,lastUs,durationUs,frames,firstX,firstY,lastX,lastY," +
                    "velX,velY,speed,directionDeg,direction,displacement,closestToCentre," +
                    "meanHeight,movedThrough,likelySubject,captured,photos",
            )
        }
        publishPart(session, TRACK_INDEX_FILE, header, tracksPart)
    }

    /** The key that maps every normalised box in the other files back onto video — see [chunkRow]. */
    private fun writeChunkIndex(session: PipelineSessionRecord) {
        if (!chunksPart.hasRows) return
        val header = buildString {
            appendLine("# ${session.displayName} · boxes in the other files are normalised on an upright detect bitmap of detectW×detectH")
            appendLine("chunk,video,sourceOffsetUs,recordedAtEpochMs,framesSampled,sampleIntervalMs,detectW,detectH,rotationDeg")
        }
        publishPart(session, CHUNK_INDEX_FILE, header, chunksPart)
    }

    /**
     * Header, then the body straight off disk — never both in one `String`.
     *
     * Routing this through [writeSessionFile] would undo the whole point of [CsvPart]: the file
     * would have to be materialised in memory at drain, which is the allocation that grows with
     * session length. The body is discarded only after both destinations have it.
     */
    private fun publishPart(
        session: PipelineSessionRecord,
        fileName: String,
        header: String,
        part: CsvPart,
    ) {
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        runCatching {
            sessionDir.mkdirs()
            File(sessionDir, fileName).outputStream().use { out ->
                out.write(headerBytes)
                part.copyTo(out)
            }
        }.onFailure { Log.e(TAG, "$fileName cache write failed", it) }
        runCatching {
            deliveryWriter.publishStream(fileName) { out ->
                out.write(headerBytes)
                part.copyTo(out)
            }
        }.onFailure { Log.e(TAG, "$fileName gallery write failed", it) }
        part.discard()
    }

    /** Null unless the person detector ran — see [PipelineSessionRecord.subjectCount]. */
    private fun subjectCount(): SubjectCount? {
        if (!extractionTarget.usesPerson) return null
        if (trackTotals.passages == 0) return null
        return SubjectCount(
            subjects = trackTotals.likelySubject,
            passages = trackTotals.passages,
            captured = trackTotals.capturedSubjects,
        )
    }

    private fun f(value: Float): String = String.format(Locale.US, "%.4f", value)

    private fun photoRow(chunk: Int, photo: SavedPhoto): String = listOf(
        photo.file.name,
        chunk.toString(),
        photo.timestampUs.toString(),
        photo.score?.let { f(it) } ?: "",
        photo.score?.let { f(FaceDetLiteDetector.logitOf(it)) } ?: "",
        String.format(Locale.US, "%.2f", photo.sharpness),
        f(photo.subjectRatio),
        // The composite and every term that fed it. The weights in FrameQuality are argued,
        // not measured; recording the components is what lets a real race re-fit them instead
        // of re-arguing them.
        f(photo.quality.total),
        f(photo.quality.sharpness),
        f(photo.quality.size),
        f(photo.quality.centre),
        photo.quality.confidence?.let { f(it) } ?: "",
        f(photo.quality.framing),
        photo.trackId.toString(),
    ).joinToString(",")

    private fun trackRow(chunk: Int, t: TrackSummary): String = listOf(
        chunk.toString(),
        t.id.toString(),
        t.firstSeenUs.toString(),
        t.lastSeenUs.toString(),
        t.durationUs.toString(),
        t.frames.toString(),
        f(t.firstCentreX), f(t.firstCentreY), f(t.lastCentreX), f(t.lastCentreY),
        f(t.velocityX), f(t.velocityY), f(t.speed),
        String.format(Locale.US, "%.1f", t.directionDegrees),
        t.directionLabel,
        f(t.displacement), f(t.closestToCentre), f(t.meanHeight),
        if (t.movedThrough) "1" else "0",
        if (t.likelySubject) "1" else "0",
        if (t.captured) "1" else "0",
        t.photos.toString(),
    ).joinToString(",")

    /**
     * One row per chunk — the key that lets the other three files be mapped back onto video.
     *
     * `sourceOffsetUs` is where this chunk starts inside the imported file. The splitter
     * already knows it (`ImportedVideoSplitter` subtracts it to rebase each chunk's PTS to
     * zero); recording it is what makes a desktop overlay possible without keeping the chunks,
     * which are deleted as soon as Worker 2 is done with them.
     *
     * `rotationDeg` matters for the same reason: detection runs on an upright, downscaled
     * bitmap, so every normalised box in the other files is relative to *that* orientation.
     */
    private fun chunkRow(item: ChunkWorkItem, result: VideoProcessResult): String = listOf(
        item.index.toString(),
        item.videoFile.name,
        item.sourceOffsetUs.toString(),
        item.recordedAtEpochMs.toString(),
        result.framesSampled.toString(),
        resolution.frameSampleIntervalMs.toString(),
        result.detectWidth.toString(),
        result.detectHeight.toString(),
        result.rotationDegrees.toString(),
    ).joinToString(",")

    /** Replaces walking the old row list at drain — see [trackTotals]. */
    private class TrackTotals {
        var passages = 0
        var movedThrough = 0
        var likelySubject = 0
        var captured = 0
        var capturedSubjects = 0
        var chunks = 0

        fun add(t: TrackSummary) {
            passages++
            if (t.movedThrough) movedThrough++
            if (t.likelySubject) likelySubject++
            if (t.captured) captured++
            if (t.likelySubject && t.captured) capturedSubjects++
        }

        fun reset() {
            passages = 0; movedThrough = 0; likelySubject = 0
            captured = 0; capturedSubjects = 0; chunks = 0
        }
    }

    private fun writeSessionLog(session: PipelineSessionRecord) {
        val text = session.toLogText()
        if (session.albumFolderName.isNotEmpty()) {
            deliveryWriter.albumSubfolder = session.albumFolderName
        }
        writeSessionFile(session, LocalDeliveryWriter.SESSION_LOG_FILE, text)
        writePhotoIndex(session)
        writeTrackIndex(session)
        writeChunkIndex(session)

        // Written before the perf report so a compare-mode session still leaves its
        // observations behind even if perf collection is off.
        frameProcessor.comparisonReport()?.let {
            writeSessionFile(session, DetectorComparison.FILE_NAME, it)
        }

        val report = buildPerfReport(session) ?: return
        writeSessionFile(session, PerfReport.FILE_NAME, report)
    }

    /** Cache mirrors first (survive a failed MediaStore write), then the gallery copy. */
    private fun writeSessionFile(session: PipelineSessionRecord, fileName: String, text: String) {
        runCatching {
            sessionDir.mkdirs()
            File(sessionDir, fileName).writeText(text)
            if (session.albumFolderName.isNotEmpty()) {
                // Flat, like the public albums — the folder name already carries the version.
                val mirrorDir = File(appContext.cacheDir, "autobots/logs/${session.albumFolderName}")
                mirrorDir.mkdirs()
                File(mirrorDir, fileName).writeText(text)
            }
        }.onFailure { error ->
            Log.e(TAG, "$fileName cache write failed for ${session.albumFolderName}", error)
        }
        runCatching {
            deliveryWriter.publishText(fileName, text)
        }.onFailure { error ->
            Log.e(TAG, "$fileName gallery write failed for ${session.albumFolderName}", error)
        }
    }

    private fun buildPerfReport(session: PipelineSessionRecord): String? {
        if (!perfReport.enabled) return null
        samplePerfLoad("session_end", null)
        perfReport.setSession {
            put("id", session.id)
            put("source", session.source.name)
            put("displayName", session.displayName)
            put("albumFolder", session.albumFolderName)
            put("status", session.status.name)
            put("startedAtEpochMs", session.startedAtEpochMs)
            put("totalDurationMs", session.totalDurationMs)
            // splitDurationMs is wall clock and therefore dominated by backpressure on any
            // clip long enough to fill the video queue. splitActiveMs is the remux itself,
            // and is the only one of the three comparable between runs.
            put("splitDurationMs", session.splitDurationMs)
            put("splitBlockedMs", session.splitBlockedMs)
            put("splitActiveMs", (session.splitDurationMs - session.splitBlockedMs).coerceAtLeast(0L))
            put("errorMessage", session.errorMessage ?: JSONObject.NULL)
            // Config that the numbers must be read against.
            put("resolutionLabel", session.resolution.label)
            put("resolution", session.resolution.name)
            put("extractionTarget", session.extractionTarget.slug)
            put("detectorBackend", detectorBackend.slug)
            put("sampleIntervalMs", session.resolution.frameSampleIntervalMs)
            put("chunkTargetBytes", session.resolution.chunkTargetBytes)
            put("videoQueueCapacity", VIDEO_QUEUE_CAPACITY)
            put("imageQueueCapacity", IMAGE_QUEUE_CAPACITY)
            put(
                "sourceVideo",
                JSONObject().apply {
                    put("width", session.sourceVideoWidth ?: JSONObject.NULL)
                    put("height", session.sourceVideoHeight ?: JSONObject.NULL)
                    put("rotationDegrees", session.sourceRotationDegrees)
                    put("durationMs", session.sourceDurationMs ?: JSONObject.NULL)
                    put("sizeBytes", session.sourceSizeBytes ?: JSONObject.NULL)
                },
            )
        }
        return perfReport.render(System.currentTimeMillis())
    }

    /** Thermal + RAM at pipeline milestones — enough to spot throttling without a timer. */
    private fun samplePerfLoad(type: String, chunkIndex: Int?) {
        val reader = loadReader ?: return
        val now = System.currentTimeMillis()
        runCatching { reader.sample() }.getOrNull()?.let { perfReport.addLoad(now, it) }
        perfReport.addEvent(now, type, chunkIndex, videoPending.get(), imageDelivery.pendingCount)
    }

    /**
     * Record a published photo for upload.
     *
     * The album folder doubles as the session key: it is what the photos actually live under
     * in DCIM, so a queue row can always be traced back to something the operator can see.
     * [LocalDeliveryWriter.albumSubfolder] is the same value the photo was just published
     * with, read on this callback rather than from [sessionMeta] because that field is per
     * coordinator instance, not per run.
     *
     * Size and timestamp are read here because [WriteQueue] deletes the file immediately
     * after this returns.
     */
    private fun enqueueForUpload(uri: Uri, file: File) {
        val album = deliveryWriter.albumSubfolder
        if (album.isEmpty()) return
        val candidate = UploadCandidate(
            contentUri = uri.toString(),
            fileName = file.name,
            sessionId = album,
            capturedAtMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            sizeBytes = file.length(),
        )
        scope.launch {
            runCatching { uploadQueue.enqueue(listOf(candidate)) }
                .onSuccess { UploadScheduler.ensureScheduled(appContext) }
                .onFailure { Log.e(TAG, "Upload enqueue failed for ${file.name}", it) }
        }
    }

    private fun beginSession(source: SessionSource, displayName: String) {
        val startedAt = System.currentTimeMillis()
        val albumFolder = when (source) {
            SessionSource.VideoImport -> SessionAlbumNaming.importFolder(startedAt)
            SessionSource.LiveCapture -> SessionAlbumNaming.liveFolder(startedAt)
        }
        deliveryWriter.albumSubfolder = albumFolder
        // Both accumulate for the life of the coordinator, which outlives a session: extracting
        // twice without restarting the app would otherwise put the first run's rows in the
        // second run's file, under the second run's album name.
        photosPart.discard()
        tracksPart.discard()
        chunksPart.discard()
        trackTotals.reset()
        // Live capture has no knowable total; the bar falls back to chunksRecorded.
        expectedChunks = 0
        drainNotified.set(false)
        startDrainWatchdog()
        // Attach before markStart: the stream's first record is written by markStart itself.
        if (perfReport.enabled) {
            perfStream = PerfStream(File(sessionDir, PerfStream.FILE_NAME))
                .also { perfReport.attachStream(it) }
            loadReader?.let { perfReport.setProbeAvailability(it.probeAvailability) }
        }
        perfReport.markStart(startedAt)
        samplePerfLoad("session_start", null)
        startHeartbeat()
        sessionMeta = SessionMeta(
            source = source,
            displayName = displayName,
            startedAtEpochMs = startedAt,
            albumFolderName = albumFolder,
        )
    }

    private fun liveSessionDisplayName(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return "Live · $time"
    }

    private fun finalizeCurrentSession() {
        val meta = sessionMeta ?: return
        if (meta.finalized) return
        meta.finalized = true
        meta.finishedAtEpochMs = System.currentTimeMillis()
        if (meta.failed) {
            meta.status = SessionStatus.Failed
        } else {
            meta.status = SessionStatus.Done
        }
    }

    /**
     * @param chunks the same snapshot the caller is reporting counts from.
     *
     * It is passed in rather than read off [chunkHistory] so that the status and the numbers
     * shown beside it describe one moment. Reading the live list here also meant touching a
     * plain `MutableList` without [historyLock] — `isEmpty()` is a size read and cannot throw,
     * but the worker coroutine mutates that list, and a status derived from a different
     * observation than the counts is a report that contradicts itself.
     */
    private fun currentSessionStatus(meta: SessionMeta, chunks: List<ChunkRecord>): SessionStatus {
        if (meta.finalized) return meta.status
        if (meta.failed && chunks.isEmpty()) return SessionStatus.Failed

        val pipelineActive = importing ||
            recording ||
            workerBusy.get() ||
            videoPending.get() > 0 ||
            chunksProcessed < chunksRecorded

        if (!pipelineActive && chunks.isNotEmpty()) {
            return SessionStatus.Done
        }

        return when (meta.source) {
            SessionSource.VideoImport -> if (importing) SessionStatus.Splitting else SessionStatus.Processing
            SessionSource.LiveCapture -> if (recording) SessionStatus.Recording else SessionStatus.Processing
        }
    }

    private fun buildSessionRecord(chunks: List<ChunkRecord>): PipelineSessionRecord? {
        val meta = sessionMeta ?: return null
        val now = System.currentTimeMillis()
        val processDurationMs = chunks.sumOf { it.processDurationMs }
        val facesKeptTotal = chunks.sumOf { it.facesKept }
        val facesSkippedTotal = chunks.sumOf { it.facesSkipped }
        val chunksDone = chunks.count {
            it.status == ChunkProcessStatus.Done || it.status == ChunkProcessStatus.Failed
        }
        val status = currentSessionStatus(meta, chunks)
        val totalDurationMs = when {
            meta.finalized && meta.finishedAtEpochMs != null ->
                meta.finishedAtEpochMs!! - meta.startedAtEpochMs
            else -> now - meta.startedAtEpochMs
        }
        return PipelineSessionRecord(
            id = sessionId,
            source = meta.source,
            displayName = meta.displayName,
            startedAtEpochMs = meta.startedAtEpochMs,
            sourceDurationMs = meta.sourceDurationMs,
            sourceSizeBytes = meta.sourceSizeBytes,
            sourceVideoWidth = meta.sourceVideoWidth,
            sourceVideoHeight = meta.sourceVideoHeight,
            sourceRotationDegrees = meta.sourceRotationDegrees,
            resolution = resolution,
            extractionTarget = extractionTarget,
            detectorBackend = detectorBackend,
            detectZone = detectZone,
            shutterCeilingFps = shutterCeilingFps,
            exposureIndex = exposureIndex,
            exposureStepEv = exposureStepEv,
            status = status,
            splitDurationMs = meta.splitDurationMs,
            splitBlockedMs = meta.splitBlockedMs,
            processDurationMs = processDurationMs,
            totalDurationMs = totalDurationMs,
            chunkCount = chunks.size,
            chunksDone = chunksDone,
            facesKept = facesKeptTotal,
            facesSkipped = facesSkippedTotal,
            errorMessage = meta.errorMessage,
            albumFolderName = meta.albumFolderName,
            subjectCount = subjectCount(),
            chunks = chunks,
        )
    }

    private data class SessionMeta(
        val source: SessionSource,
        val displayName: String,
        val startedAtEpochMs: Long,
        val albumFolderName: String,
        var status: SessionStatus = SessionStatus.Processing,
        var sourceDurationMs: Long? = null,
        var sourceSizeBytes: Long? = null,
        var sourceVideoWidth: Int? = null,
        var sourceVideoHeight: Int? = null,
        var sourceRotationDegrees: Int = 0,
        var splitDurationMs: Long = 0,
        /** Of [splitDurationMs], how much was spent waiting on backpressure. */
        var splitBlockedMs: Long = 0,
        var errorMessage: String? = null,
        var failed: Boolean = false,
        var finalized: Boolean = false,
        var finishedAtEpochMs: Long? = null,
    )

    private fun publishStats() {
        scope.launch {
            val historySnapshot = historyLock.withLock { chunkHistory.toList() }
            val sessionSnapshot = listOfNotNull(buildSessionRecord(historySnapshot))
            val isProcessing = workerBusy.get() || videoPending.get() > 0
            val snapshot = PipelineStats(
                sessionId = sessionId,
                resolution = resolution,
                extractionTarget = extractionTarget,
                videoChunksRecorded = chunksRecorded,
                videoQueueDepth = videoPending.get(),
                chunksProcessed = chunksProcessed,
                facesKept = facesKept,
                facesSkipped = facesSkipped,
                lastChunkProcessMs = lastChunkProcessMs,
                storageFreeMb = VideoPreviewController.freeStorageMb(appContext.cacheDir),
                pipelinePaused = pipelinePaused,
                isRecording = recording,
                isProcessing = isProcessing,
                isImporting = importing,
                importPercent = importPercent,
                importName = importName,
                expectedChunks = expectedChunks,
                lastRealtimeRatio = lastRealtimeRatio,
                avgPhotoLatencyMs = synchronized(this@CapturePipelineCoordinator) {
                    if (photoLatencyCount == 0) 0L else photoLatencySumMs / photoLatencyCount
                },
                currentChunkPercent = currentChunkPercent,
                processingChunkName = processingChunkName,
                imageQueuePending = imageDelivery.pendingCount,
                sessionHistory = sessionSnapshot,
            )
            onStats(snapshot)
        }
    }

    private data class ChunkWorkItem(
        val index: Int,
        val videoFile: File,
        /** For `chunks.csv` — see [chunkRow]. 0 for live capture, where the chunk *is* the source. */
        val sourceOffsetUs: Long = 0L,
        val recordedAtEpochMs: Long = 0L,
    )

    companion object {
        /** Sits beside `session_log.txt` in the session album. */
        const val PHOTO_INDEX_FILE = "photos.csv"
        const val TRACK_INDEX_FILE = "tracks.csv"
        const val CHUNK_INDEX_FILE = "chunks.csv"

        private const val TAG = "CapturePipeline"
        const val VIDEO_QUEUE_CAPACITY = 8

        /**
         * Debugging switch — keep every chunk `.mp4` after Worker 2 has read it.
         *
         * Costs the full size of the source in cache (1.93 GB for run4mins, ~51 GB for a
         * two-hour import), which is why it is off. See [releaseChunkFile].
         */
        private const val KEEP_PROCESSED_CHUNKS = false

        /** Watchdog cadence — a session ends at most this late. */
        private const val DRAIN_POLL_MS = 1_000L

        /**
         * Load-sample cadence while a session is open. Bounds the time of death in the
         * stream to half a minute without adding a line anyone has to read.
         */
        private const val HEARTBEAT_MS = 30_000L

        /**
         * Raised from 16 in 0.1.3: keeping the top 3 frames per dedup window instead of 1
         * roughly triples the files a single chunk hands to delivery, and a full WriteQueue
         * silently drops photos.
         */
        const val IMAGE_QUEUE_CAPACITY = 48

        fun create(
            context: Context,
            onStats: (PipelineStats) -> Unit,
            onPhotoDelivered: (Uri) -> Unit,
            onDrainComplete: () -> Unit = {},
        ): CapturePipelineCoordinator {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return CapturePipelineCoordinator(
                context,
                scope,
                onStats,
                onPhotoDelivered,
                onDrainComplete,
            )
        }
    }
}
