package com.autobots.camera.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.autobots.camera.delivery.WriteQueue
import com.autobots.camera.perf.CamPerf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    private val frameProcessor = VideoFrameProcessor(facesDir)
    private val deliveryWriter = LocalDeliveryWriter(appContext)
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
    private var recording = false
    private var awaitingRecorderFinalize = false
    private var pipelinePaused = false
    private var currentChunkPercent = 0
    private var processingChunkName: String? = null
    private var closed = false
    private var importing = false
    private var importPercent = 0
    private var importName: String? = null
    private val chunkIndexSeq = AtomicInteger(0)

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
                try {
                    val processStartMs = System.currentTimeMillis()
                    val result = frameProcessor.process(
                        item.videoFile,
                        chunkIndex = item.index,
                        resolution = resolution,
                        extractionTarget = extractionTarget,
                        sampleIntervalMs = resolution.frameSampleIntervalMs,
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
                    historyLock.withLock {
                        val i = chunkHistory.indexOfFirst { it.index == item.index }
                        if (i >= 0) {
                            chunkHistory[i] = chunkHistory[i].copy(status = ChunkProcessStatus.Failed)
                        }
                    }
                } finally {
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
        importName = displayName
        publishStats()

        val splitter = ImportedVideoSplitter(appContext, File(sessionDir, "video"))
        return try {
            // Remuxing is blocking I/O — never on the caller's (main) thread.
            val result = withContext(Dispatchers.IO) {
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
            }
            sessionMeta?.splitDurationMs = System.currentTimeMillis() - splitStartMs
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
            result
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
        chunkStartWallMs[meta.index] = meta.recordedAtEpochMs
        chunkQueuedWallMs[meta.index] = System.currentTimeMillis()
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
        }
        publishStats()
        maybeNotifyDrainComplete()
    }

    fun onRecorderPaused() {
        pipelinePaused = true
        publishStats()
    }

    fun onRecorderResumed() {
        pipelinePaused = false
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
        val recordStart = chunkStartWallMs[item.index] ?: return
        val queuedAt = chunkQueuedWallMs[item.index] ?: return
        val recordedMs = (queuedAt - recordStart).coerceAtLeast(1L)
        val queueWaitMs = processStartMs - queuedAt
        val ratio = result.durationMs.toDouble() / recordedMs
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

    private fun maybeNotifyDrainComplete() {
        if (closed || recording || isBusy()) return
        finalizeCurrentSession()
        publishStats()
        onDrainComplete()
    }

    private fun beginSession(source: SessionSource, displayName: String) {
        sessionMeta = SessionMeta(
            source = source,
            displayName = displayName,
            startedAtEpochMs = System.currentTimeMillis(),
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
            resolution = resolution,
            extractionTarget = extractionTarget,
            status = status,
            splitDurationMs = meta.splitDurationMs,
            processDurationMs = processDurationMs,
            totalDurationMs = totalDurationMs,
            chunkCount = chunks.size,
            chunksDone = chunksDone,
            facesKept = facesKeptTotal,
            facesSkipped = facesSkippedTotal,
            errorMessage = meta.errorMessage,
            chunks = chunks,
        )
    }

    private data class SessionMeta(
        val source: SessionSource,
        val displayName: String,
        val startedAtEpochMs: Long,
        var status: SessionStatus = SessionStatus.Processing,
        var sourceDurationMs: Long? = null,
        var sourceSizeBytes: Long? = null,
        var splitDurationMs: Long = 0,
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
        const val IMAGE_QUEUE_CAPACITY = 16

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
