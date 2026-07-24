package com.autobots.camera.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autobots.camera.ChunkProcessStatus
import com.autobots.camera.ChunkRecord
import com.autobots.camera.ExtractedFaceImage
import com.autobots.camera.PipelineStats
import com.autobots.camera.StreamResolution
import com.autobots.camera.VideoPreviewController
import com.autobots.camera.capture.ChunkCaptureMeta
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.delivery.WriteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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

    private val faceProcessor = VideoFaceProcessor(facesDir)
    private val deliveryWriter = LocalDeliveryWriter(appContext)
    private val imageDelivery = WriteQueue(
        writer = deliveryWriter,
        capacity = IMAGE_QUEUE_CAPACITY,
        onDelivered = { uri ->
            onPhotoDelivered(uri)
            publishStats()
            maybeNotifyDrainComplete()
        },
    )

    private var chunksRecorded = 0
    private var chunksProcessed = 0
    private var facesKept = 0
    private var facesSkipped = 0
    private var lastChunkProcessMs = 0L
    private var resolution = StreamResolution.Fhd
    private var recording = false
    private var awaitingRecorderFinalize = false
    private var pipelinePaused = false
    private var currentChunkPercent = 0
    private var processingChunkName: String? = null
    private var closed = false

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
                    val result = faceProcessor.process(
                        item.videoFile,
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

    fun sessionDirectory(): File = sessionDir

    fun canAcceptVideoChunk(): Boolean = videoPending.get() < VIDEO_QUEUE_CAPACITY

    fun isBusy(): Boolean {
        return recording ||
            awaitingRecorderFinalize ||
            workerBusy.get() ||
            videoPending.get() > 0 ||
            imageDelivery.pendingCount > 0
    }

    fun onRecordingStarted() {
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
        val record = ChunkRecord(
            index = meta.index,
            videoFileName = meta.file.name,
            videoAbsolutePath = meta.file.absolutePath,
            recordedAtEpochMs = meta.recordedAtEpochMs,
            resolution = resolution,
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
        faceProcessor.close()
        imageDelivery.close()
    }

    private fun maybeNotifyDrainComplete() {
        if (closed || recording || isBusy()) return
        onDrainComplete()
    }

    private fun publishStats() {
        scope.launch {
            val historySnapshot = historyLock.withLock { chunkHistory.toList() }
            val isProcessing = workerBusy.get() || videoPending.get() > 0
            val snapshot = PipelineStats(
                sessionId = sessionId,
                resolution = resolution,
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
                currentChunkPercent = currentChunkPercent,
                processingChunkName = processingChunkName,
                imageQueuePending = imageDelivery.pendingCount,
                chunkHistory = historySnapshot,
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
