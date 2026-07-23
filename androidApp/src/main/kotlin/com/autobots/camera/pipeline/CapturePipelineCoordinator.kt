package com.autobots.camera.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.autobots.camera.PipelineStats
import com.autobots.camera.StreamResolution
import com.autobots.camera.VideoPreviewController
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.delivery.WriteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates queueVideo → face extract → queueImage → local gallery delivery.
 */
class CapturePipelineCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val onStats: (PipelineStats) -> Unit,
    private val onPhotoDelivered: (Uri) -> Unit,
) {
    private val appContext = context.applicationContext
    private val sessionId = System.currentTimeMillis().toString()
    private val sessionDir = File(appContext.cacheDir, "autobots/$sessionId")
    private val facesDir = File(sessionDir, "faces")
    private val videoQueue = Channel<File>(VIDEO_QUEUE_CAPACITY)
    private val videoPending = AtomicInteger(0)

    private val faceProcessor = VideoFaceProcessor(facesDir)
    private val deliveryWriter = LocalDeliveryWriter(appContext)
    private val imageDelivery = WriteQueue(
        writer = deliveryWriter,
        capacity = IMAGE_QUEUE_CAPACITY,
        onDelivered = onPhotoDelivered,
    )

    private var chunksRecorded = 0
    private var facesKept = 0
    private var facesSkipped = 0
    private var lastChunkProcessMs = 0L
    private var resolution = StreamResolution.Fhd
    private var recording = false
    private var pipelinePaused = false

    init {
        sessionDir.mkdirs()
        facesDir.mkdirs()

        scope.launch(Dispatchers.Default) {
            for (videoFile in videoQueue) {
                try {
                    val result = faceProcessor.process(videoFile)
                    facesKept += result.kept
                    facesSkipped += result.skipped
                    lastChunkProcessMs = result.durationMs
                    for (imageFile in result.savedFiles) {
                        imageDelivery.enqueue(imageFile)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Video process failed for ${videoFile.name}", t)
                } finally {
                    videoPending.decrementAndGet()
                    publishStats()
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

    fun onRecordingStarted() {
        recording = true
        publishStats()
    }

    fun onRecordingStopped() {
        recording = false
        pipelinePaused = false
        publishStats()
    }

    fun onChunkRecorded(file: File) {
        chunksRecorded++
        videoPending.incrementAndGet()
        val accepted = videoQueue.trySend(file)
        if (!accepted.isSuccess) {
            videoPending.decrementAndGet()
            Log.w(TAG, "Video queue full, dropped ${file.name}")
        }
        publishStats()
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
        videoQueue.close()
        faceProcessor.close()
        imageDelivery.close()
    }

    private fun publishStats() {
        scope.launch {
            val snapshot = PipelineStats(
                sessionId = sessionId,
                resolution = resolution,
                videoChunksRecorded = chunksRecorded,
                videoQueueDepth = videoPending.get(),
                facesKept = facesKept,
                facesSkipped = facesSkipped,
                lastChunkProcessMs = lastChunkProcessMs,
                storageFreeMb = VideoPreviewController.freeStorageMb(appContext.cacheDir),
                pipelinePaused = pipelinePaused,
                isRecording = recording,
            )
            onStats(snapshot)
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val VIDEO_QUEUE_CAPACITY = 8
        const val IMAGE_QUEUE_CAPACITY = 16

        fun create(
            context: Context,
            onStats: (PipelineStats) -> Unit,
            onPhotoDelivered: (Uri) -> Unit,
        ): CapturePipelineCoordinator {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return CapturePipelineCoordinator(context, scope, onStats, onPhotoDelivered)
        }
    }
}
