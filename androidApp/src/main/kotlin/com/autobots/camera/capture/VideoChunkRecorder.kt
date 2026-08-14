package com.autobots.camera.capture

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.autobots.camera.StreamResolution
import com.autobots.camera.perf.CamPerf
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker 1 — record MP4 chunks, rotating when file size reaches [maxChunkBytes].
 */
class VideoChunkRecorder(
    private val context: Context,
    private val videoCapture: VideoCapture<Recorder>,
    private val videoDir: File,
    private val mainExecutor: Executor,
    private val maxChunkBytes: Long = DEFAULT_MAX_CHUNK_BYTES,
    private val canAcceptChunk: () -> Boolean,
    private val onChunkReady: (ChunkCaptureMeta) -> Unit,
    private val onProgress: (Int, Long, Long) -> Unit = { _, _, _ -> },
    private val onPaused: () -> Unit,
    private val onResumed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val rotating = AtomicBoolean(false)
    private val chunkCounter = AtomicInteger(0)
    /**
     * Written when a chunk starts or finalizes — on the main thread via CameraX callbacks, and
     * on [scope] when a paused recorder resumes itself — and read by `sizeMonitorJob` on
     * [Dispatchers.Default].
     *
     * The monitor is launched *after* these are set, which publishes them for the chunk it was
     * started for; the gap is across chunk boundaries, where a stale read shows a wrong elapsed
     * time or calls `stop()` on an already-finalized `Recording` (CameraX treats that as a
     * no-op). Small, but there is no reason for the reader to be guessing.
     */
    @Volatile
    private var activeRecording: Recording? = null

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var currentChunkIndex: Int = 0

    @Volatile
    private var chunkStartElapsedMs: Long = 0L

    @Volatile
    private var chunkStartWallMs: Long = 0L

    private var sizeMonitorJob: Job? = null
    private var resumeWatchJob: Job? = null
    private var stopFinalizeCallback: (() -> Unit)? = null

    /** Coverage-gap instrumentation: frames recorded by nobody between chunks. */
    private var stopRequestedNs: Long = 0L
    private var gapTotalNs: Long = 0L
    private var gapCount: Int = 0
    private var gapMaxNs: Long = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        videoDir.mkdirs()
        startNextChunkOrPause()
    }

    fun stop(onFinalized: () -> Unit = {}) {
        running.set(false)
        paused.set(false)
        sizeMonitorJob?.cancel()
        resumeWatchJob?.cancel()
        val recording = activeRecording
        if (recording == null) {
            scope.cancel()
            onFinalized()
            return
        }
        stopFinalizeCallback = onFinalized
        stopRequestedNs = CamPerf.nowNs()
        if (CamPerf.enabled && gapCount > 0) {
            CamPerf.log {
                "┌─ chunk boundary coverage gaps\n" +
                    "│ ${gapCount} rotations · avg ${CamPerf.ms(gapTotalNs / gapCount)} · " +
                    "max ${CamPerf.ms(gapMaxNs)}\n" +
                    "└ total ${CamPerf.ms(gapTotalNs)} of footage never recorded"
            }
        }
        recording.stop()
    }

    fun resumeIfPaused() {
        if (!running.get() || !paused.get()) return
        if (!canAcceptChunk()) return
        paused.set(false)
        onResumed()
        startNextChunkOrPause()
    }

    private fun startNextChunkOrPause() {
        if (!running.get()) return
        if (!canAcceptChunk()) {
            paused.set(true)
            onPaused()
            watchForResume()
            return
        }
        startNextChunk()
    }

    private fun watchForResume() {
        if (resumeWatchJob?.isActive == true) return
        resumeWatchJob = scope.launch {
            while (isActive && running.get() && paused.get()) {
                if (canAcceptChunk()) {
                    resumeIfPaused()
                    break
                }
                delay(RESUME_POLL_MS)
            }
        }
    }

    private fun startNextChunk() {
        if (!running.get()) return
        val index = chunkCounter.incrementAndGet()
        currentChunkIndex = index
        val file = File(videoDir, "chunk_${index.toString().padStart(3, '0')}.mp4")
        currentFile = file
        rotating.set(false)
        chunkStartElapsedMs = SystemClock.elapsedRealtime()
        chunkStartWallMs = System.currentTimeMillis()

        val outputOptions = FileOutputOptions.Builder(file).build()
        activeRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor, ::onRecordEvent)

        onProgress(index, 0L, 0L)

        sizeMonitorJob?.cancel()
        sizeMonitorJob = scope.launch {
            while (isActive && running.get() && !rotating.get()) {
                delay(SIZE_POLL_MS)
                val recording = activeRecording ?: continue
                val length = file.length()
                val elapsedMs = (SystemClock.elapsedRealtime() - chunkStartElapsedMs).coerceAtLeast(0L)
                mainExecutor.execute {
                    onProgress(index, elapsedMs, length)
                }
                if (length >= maxChunkBytes) {
                    rotating.set(true)
                    stopRequestedNs = CamPerf.nowNs()
                    recording.stop()
                }
            }
        }
        Log.i(TAG, "Recording ${file.name}")
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                // Camera is only capturing from here — everything since stopRequestedNs is lost.
                if (stopRequestedNs > 0L) {
                    val gapNs = CamPerf.nowNs() - stopRequestedNs
                    gapTotalNs += gapNs
                    gapCount++
                    if (gapNs > gapMaxNs) gapMaxNs = gapNs
                    CamPerf.log {
                        "chunk#$currentChunkIndex START · boundary gap ${CamPerf.ms(gapNs)} " +
                            "— no frames recorded in that window"
                    }
                    stopRequestedNs = 0L
                } else {
                    CamPerf.log { "chunk#$currentChunkIndex START · first chunk, no preceding gap" }
                }
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val index = currentChunkIndex
                val elapsedMs = stats.recordedDurationNanos / 1_000_000L
                val bytes = stats.numBytesRecorded
                mainExecutor.execute {
                    onProgress(index, elapsedMs, bytes)
                }
            }
            is VideoRecordEvent.Finalize -> {
                val file = currentFile
                val index = currentChunkIndex
                val wallMs = chunkStartWallMs
                val elapsedMs = (SystemClock.elapsedRealtime() - chunkStartElapsedMs).coerceAtLeast(0L)
                val stoppedIntentionally = !running.get()
                if (event.hasError()) {
                    Log.e(TAG, "Finalize error for ${file?.name}: ${event.error}")
                }
                val hasContent = file != null && file.exists() && file.length() > 0L
                if (hasContent && (!event.hasError() || stoppedIntentionally)) {
                    val meta = ChunkCaptureMeta(
                        index = index,
                        file = file,
                        recordedAtEpochMs = wallMs,
                        recordDurationMs = elapsedMs,
                        videoSizeBytes = file.length(),
                    )
                    val partialTag = if (file.length() < maxChunkBytes) ", partial" else ""
                    Log.i(TAG, "Chunk ready ${file.name} (${file.length() / 1024}KB, ${elapsedMs}ms$partialTag)")
                    onChunkReady(meta)
                }
                activeRecording = null
                currentFile = null
                rotating.set(false)
                if (running.get()) {
                    startNextChunkOrPause()
                } else {
                    scope.cancel()
                    stopFinalizeCallback?.invoke()
                    stopFinalizeCallback = null
                }
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "VideoChunkRecorder"
        const val DEFAULT_MAX_CHUNK_BYTES = StreamResolution.CHUNK_TARGET_BYTES
        private const val SIZE_POLL_MS = 250L
        private const val RESUME_POLL_MS = 1_000L
    }
}
