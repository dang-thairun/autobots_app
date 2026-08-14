package com.autobots.camera.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autobots.camera.DetectorBackend
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
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.delivery.SessionAlbumNaming
import com.autobots.camera.delivery.WriteQueue
import com.autobots.camera.toLogText
import com.autobots.camera.load.DeviceLoadReader
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.PerfReport
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
    private var sessionMeta: SessionMeta? = null

    private val frameProcessor = VideoFrameProcessor(facesDir, appContext)
    private val deliveryWriter = LocalDeliveryWriter(appContext)
    private val perfReport = PerfReport()
    private val loadReader = if (CamPerf.enabled) {
        DeviceLoadReader(appContext) { it.run() }
    } else {
        null
    }
    private val imageDelivery = WriteQueue(
        writer = deliveryWriter,
        capacity = IMAGE_QUEUE_CAPACITY,
        onDelivered = { uri ->
            onPhotoDelivered(uri)
            publishStats()
            maybeNotifyDrainComplete()
        },
        onDeliveredFile = ::recordMomentToGallery,
    )

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

    private var chunksRecorded = 0
    private var chunksProcessed = 0
    private var facesKept = 0
    private var facesSkipped = 0
    private var lastChunkProcessMs = 0L
    private var resolution = StreamResolution.Fhd
    private var extractionTarget = ExtractionTarget.Face
    private var detectorBackend = DetectorBackend.DEFAULT
    private var recording = false
    private var awaitingRecorderFinalize = false
    private var pipelinePaused = false
    private var currentChunkPercent = 0
    private var processingChunkName: String? = null
    private var closed = false
    private var importing = false
    private var importPercent = 0
    private var importName: String? = null

    /**
     * Projected chunk total for an import, so the UI can show one smooth bar while the
     * splitter is blocked on queue backpressure. Projected from the splitter's timeline
     * percentage at each segment boundary, then pinned to the real count when the split ends.
     * Monotonic on purpose: the bar must never walk backwards.
     */
    private var expectedChunks = 0
    private val chunkIndexSeq = AtomicInteger(0)
    /** Guards against writing the session log more than once per session. */
    private val drainNotified = AtomicBoolean(false)
    private var drainWatchdog: Job? = null

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
                    ) { percent ->
                        currentChunkPercent = percent
                        publishStats()
                    }
                    val images = result.savedFiles.map { file ->
                        ExtractedFaceImage(
                            fileName = file.name,
                            sizeBytes = file.length(),
                            absolutePath = file.absolutePath,
                        )
                    }
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

    /**
     * Which detector Worker 2 runs. Changing it between imports of the same clip is how the
     * 0.1.4 bench isolates the detector as the single variable — see [DetectorBackend].
     */
    fun setDetectorBackend(value: DetectorBackend) {
        detectorBackend = value
        publishStats()
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
     * Feed a video already on the device into the same chunk → extract → deliver path.
     * Splitting reuses the live queue's backpressure, so a long import cannot outrun
     * Worker 2 or blow up memory.
     */
    suspend fun importVideo(source: Uri, displayName: String?): ImportSplitResult {
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

                splitter.split(
                    source = source,
                    targetSegmentBytes = resolution.chunkTargetBytes,
                    startIndex = chunkIndexSeq.get() + 1,
                    canAcceptChunk = ::canAcceptVideoChunk,
                    onChunkReady = ::onChunkRecorded,
                    onProgress = { percent ->
                        importPercent = percent
                        publishStats()
                    },
                )
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
        val accepted = videoQueue.trySend(ChunkWorkItem(meta.index, meta.file))
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

    fun hasStorageForRecording(): Boolean {
        val freeMb = VideoPreviewController.freeStorageMb(appContext.cacheDir)
        return freeMb >= VideoPreviewController.MIN_FREE_STORAGE_MB
    }

    fun close() {
        if (closed) return
        closed = true
        recording = false
        drainWatchdog?.cancel()
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
            }
            drainWatchdog?.cancel()
            onDrainComplete()
        }
    }

    private fun writeSessionLog(session: PipelineSessionRecord) {
        val text = session.toLogText()
        if (session.albumFolderName.isNotEmpty()) {
            deliveryWriter.albumSubfolder = session.albumFolderName
        }
        writeSessionFile(session, LocalDeliveryWriter.SESSION_LOG_FILE, text)

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
            put("extractionTarget", session.extractionTarget.name)
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

    private fun beginSession(source: SessionSource, displayName: String) {
        val startedAt = System.currentTimeMillis()
        val albumFolder = when (source) {
            SessionSource.VideoImport -> SessionAlbumNaming.importFolder(startedAt)
            SessionSource.LiveCapture -> SessionAlbumNaming.liveFolder(startedAt)
        }
        deliveryWriter.albumSubfolder = albumFolder
        // Live capture has no knowable total; the bar falls back to chunksRecorded.
        expectedChunks = 0
        drainNotified.set(false)
        startDrainWatchdog()
        perfReport.markStart(startedAt)
        samplePerfLoad("session_start", null)
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

    private fun currentSessionStatus(meta: SessionMeta): SessionStatus {
        if (meta.finalized) return meta.status
        if (meta.failed && chunkHistory.isEmpty()) return SessionStatus.Failed

        val pipelineActive = importing ||
            recording ||
            workerBusy.get() ||
            videoPending.get() > 0 ||
            chunksProcessed < chunksRecorded

        if (!pipelineActive && chunkHistory.isNotEmpty()) {
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
        val status = currentSessionStatus(meta)
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
    )

    companion object {
        private const val TAG = "CapturePipeline"
        const val VIDEO_QUEUE_CAPACITY = 8

        /** Watchdog cadence — a session ends at most this late. */
        private const val DRAIN_POLL_MS = 1_000L

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
