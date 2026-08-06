package com.autobots.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobots.camera.ChunkRecord
import com.autobots.camera.ChunkRecordingProgress
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.PipelineStats
import com.autobots.camera.StreamResolution
import com.autobots.camera.formatChunkBytes
import com.autobots.camera.load.DeviceLoadReader
import com.autobots.camera.load.DeviceLoadSnapshot
import com.autobots.camera.pipeline.CapturePipelineCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class OperatorUiState(
    val isCapturing: Boolean = false,
    val streamResolution: StreamResolution = StreamResolution.Fhd,
    val extractionTarget: ExtractionTarget = ExtractionTarget.Face,
    val videoChunksRecorded: Int = 0,
    val videoQueueDepth: Int = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val lastChunkProcessMs: Long = 0,
    val storageFreeMb: Long = 0,
    val pipelinePaused: Boolean = false,
    val keptPhotoCount: Int = 0,
    val lastGalleryUri: String? = null,
    val thermalLabel: String = "—",
    val thermalLevel: Int = -1,
    val usedRamMb: Long = 0,
    val availRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val exposureLine: String = "—mm  ·  —  ·  ISO —",
    val serverIp: String = "—",
    val storageBlocked: Boolean = false,
    val recordingProgress: ChunkRecordingProgress = ChunkRecordingProgress(),
    val isProcessing: Boolean = false,
    val processingPercent: Int = 0,
    val currentChunkPercent: Int = 0,
    val chunksProcessed: Int = 0,
    val processingChunkName: String? = null,
    val imageQueuePending: Int = 0,
    val isImporting: Boolean = false,
    val importPercent: Int = 0,
    val importName: String? = null,
    val importError: String? = null,
    val lastRealtimeRatio: Float = 0f,
    val avgPhotoLatencyMs: Long = 0,
    val chunkHistory: List<ChunkRecord> = emptyList(),
) {
    val deviceLoadLine: String
        get() = if (totalRamMb > 0) {
            "RAM ${formatRam(usedRamMb)}/${formatRam(totalRamMb)} (free ${formatRam(availRamMb)})"
        } else {
            "RAM —"
        }

    val statusLine: String
        get() = if (isCapturing) "" else "IDLE"

    val recordingLine: String
        get() {
            if (!isCapturing) return ""
            if (pipelinePaused) return "PAUSED · queue full — waiting to resume"
            val progress = recordingProgress
            if (progress.chunkIndex <= 0) return "REC · starting chunk…"
            val size = formatChunkBytes(progress.bytesWritten)
            val target = formatChunkBytes(progress.targetBytes)
            val rate = progress.rateKbPerSec
            val rateLine = if (rate > 0) " · ~${rate} KB/s" else ""
            return "REC #${progress.chunkIndex} · $size / $target · ${progress.elapsedSec}s$rateLine"
        }

    val processingLine: String
        get() {
            if (!isProcessing) return ""
            val chunkLabel = processingChunkName?.substringBefore('.') ?: "chunk"
            val queue = videoQueueDepth
            val gallery = imageQueuePending
            return buildString {
                append("Processing $chunkLabel")
                append(" · ${processingPercent}%")
                append(" · ${chunksProcessed}/${videoChunksRecorded} chunks")
                if (currentChunkPercent in 1..99) append(" · scan $currentChunkPercent%")
                if (queue > 0) append(" · VQ $queue")
                if (gallery > 0) append(" · save $gallery")
            }
        }

    val canStartCapture: Boolean
        get() = !isCapturing && !isProcessing && !isImporting

    /** Importing needs the pipeline free, but not the camera. */
    val canImportVideo: Boolean
        get() = !isCapturing && !isProcessing && !isImporting

    val importLine: String
        get() {
            if (!isImporting) return ""
            val name = importName ?: "video"
            return "Importing $name · splitting $importPercent%"
        }

    /** Can Worker 2 keep up, and how long until a photo lands? Empty until a chunk finishes. */
    val throughputLine: String
        get() {
            if (lastRealtimeRatio <= 0f) return ""
            return buildString {
                append(String.format("%.2fx realtime", lastRealtimeRatio))
                if (avgPhotoLatencyMs > 0) {
                    append(String.format(" · photo in ~%.1fs", avgPhotoLatencyMs / 1000.0))
                }
                if (isThroughputTooSlow) append(" · TOO SLOW, queue will back up")
            }
        }

    val isThroughputTooSlow: Boolean
        get() = lastRealtimeRatio >= 1f
}

/** ≥1000 MB → "X.X GB", else "NNN MB". */
internal fun formatRam(mb: Long): String {
    return if (mb >= 1000L) {
        val gb = mb / 1024.0
        String.format("%.1f GB", gb)
    } else {
        "$mb MB"
    }
}

class OperatorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(OperatorUiState())
    val state: StateFlow<OperatorUiState> = _state.asStateFlow()

    private var pipeline: CapturePipelineCoordinator? = null

    private val deviceLoadReader = DeviceLoadReader(
        context = application,
        mainExecutor = ContextCompat.getMainExecutor(application),
    )

    var pendingStartAfterPermission: Boolean = false
        private set

    init {
        deviceLoadReader.start(::applyDeviceLoad)
        viewModelScope.launch {
            while (isActive) {
                delay(LOAD_POLL_MS)
                applyDeviceLoad(deviceLoadReader.sample())
            }
        }
    }

    override fun onCleared() {
        pipeline?.close()
        pipeline = null
        deviceLoadReader.stop()
        super.onCleared()
    }

    fun setServerIp(ip: String) {
        _state.update { it.copy(serverIp = ip) }
    }

    fun pipelineCoordinator(): CapturePipelineCoordinator? = pipeline

    fun markPendingStartAfterPermission() {
        pendingStartAfterPermission = true
    }

    fun consumePendingStart() {
        pendingStartAfterPermission = false
    }

    fun startCapture() {
        if (pipeline?.isBusy() == true) return

        pipeline?.close()
        pipeline = null

        val coordinator = CapturePipelineCoordinator.create(
            context = getApplication(),
            onStats = ::applyPipelineStats,
            onPhotoDelivered = { uri -> onPhotoDelivered(uri.toString()) },
            onDrainComplete = ::onPipelineDrainComplete,
        )
        val resolution = _state.value.streamResolution
        val extractionTarget = _state.value.extractionTarget
        coordinator.setResolution(resolution)
        coordinator.setExtractionTarget(extractionTarget)

        if (!coordinator.hasStorageForRecording()) {
            _state.update { it.copy(storageBlocked = true) }
            coordinator.close()
            return
        }

        pipeline = coordinator
        coordinator.onRecordingStarted()
        _state.update {
            it.copy(
                isCapturing = true,
                storageBlocked = false,
                videoChunksRecorded = 0,
                videoQueueDepth = 0,
                facesKept = 0,
                facesSkipped = 0,
                lastChunkProcessMs = 0,
                pipelinePaused = false,
                recordingProgress = ChunkRecordingProgress(),
                isProcessing = false,
                processingPercent = 0,
                currentChunkPercent = 0,
                chunksProcessed = 0,
                processingChunkName = null,
                imageQueuePending = 0,
                lastRealtimeRatio = 0f,
                avgPhotoLatencyMs = 0,
                chunkHistory = emptyList(),
            )
        }
        applyDeviceLoad(deviceLoadReader.sample())
    }

    /**
     * Runs a device video through the same pipeline as a live Passage. No camera is
     * bound — [OperatorUiState.isCapturing] stays false so the preview stays idle.
     */
    fun importVideo(uri: Uri) {
        if (!_state.value.canImportVideo) return

        pipeline?.close()
        val coordinator = CapturePipelineCoordinator.create(
            context = getApplication(),
            onStats = ::applyPipelineStats,
            onPhotoDelivered = { delivered -> onPhotoDelivered(delivered.toString()) },
            onDrainComplete = ::onPipelineDrainComplete,
        )
        coordinator.setResolution(_state.value.streamResolution)
        coordinator.setExtractionTarget(_state.value.extractionTarget)

        if (!coordinator.hasStorageForRecording()) {
            _state.update { it.copy(storageBlocked = true) }
            coordinator.close()
            return
        }

        pipeline = coordinator
        val displayName = resolveDisplayName(uri)
        _state.update {
            it.copy(
                storageBlocked = false,
                importError = null,
                isImporting = true,
                importName = displayName,
                importPercent = 0,
                videoChunksRecorded = 0,
                videoQueueDepth = 0,
                facesKept = 0,
                facesSkipped = 0,
                chunksProcessed = 0,
                imageQueuePending = 0,
                lastRealtimeRatio = 0f,
                avgPhotoLatencyMs = 0,
                chunkHistory = emptyList(),
            )
        }

        viewModelScope.launch {
            val result = coordinator.importVideo(uri, displayName)
            if (result.error != null || result.segments == 0) {
                _state.update {
                    it.copy(importError = result.error ?: "No video segments produced")
                }
            }
        }
    }

    fun clearImportError() {
        _state.update { it.copy(importError = null) }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment

    fun stopCapture() {
        pipeline?.stopRecording()
        _state.update {
            it.copy(
                isCapturing = false,
                pipelinePaused = false,
                exposureLine = "—mm  ·  —  ·  ISO —",
                recordingProgress = ChunkRecordingProgress(),
            )
        }
    }

    private fun onPipelineDrainComplete() {
        pipeline?.close()
        pipeline = null
        _state.update {
            it.copy(
                isProcessing = false,
                processingPercent = 0,
                currentChunkPercent = 0,
                processingChunkName = null,
                imageQueuePending = 0,
                isImporting = false,
                importPercent = 0,
                importName = null,
            )
        }
    }

    fun onRecordingProgress(chunkIndex: Int, elapsedMs: Long, bytesWritten: Long) {
        _state.update {
            it.copy(
                recordingProgress = ChunkRecordingProgress(
                    chunkIndex = chunkIndex,
                    elapsedMs = elapsedMs,
                    bytesWritten = bytesWritten,
                    targetBytes = it.streamResolution.chunkTargetBytes,
                ),
            )
        }
    }

    fun onPhotoDelivered(uriString: String) {
        _state.update {
            it.copy(
                keptPhotoCount = it.keptPhotoCount + 1,
                lastGalleryUri = uriString,
            )
        }
    }

    fun setStreamResolution(resolution: StreamResolution) {
        if (_state.value.isCapturing) return
        _state.update { it.copy(streamResolution = resolution) }
    }

    fun setExtractionTarget(target: ExtractionTarget) {
        if (_state.value.isCapturing) return
        _state.update { it.copy(extractionTarget = target) }
    }

    fun onExposureReadout(line: String) {
        _state.update { it.copy(exposureLine = line) }
    }

    private fun applyPipelineStats(stats: PipelineStats) {
        _state.update {
            it.copy(
                streamResolution = stats.resolution,
                extractionTarget = stats.extractionTarget,
                videoChunksRecorded = stats.videoChunksRecorded,
                videoQueueDepth = stats.videoQueueDepth,
                chunksProcessed = stats.chunksProcessed,
                facesKept = stats.facesKept,
                facesSkipped = stats.facesSkipped,
                lastChunkProcessMs = stats.lastChunkProcessMs,
                storageFreeMb = stats.storageFreeMb,
                pipelinePaused = stats.pipelinePaused,
                isProcessing = stats.isProcessing,
                processingPercent = stats.overallProcessingPercent,
                currentChunkPercent = stats.currentChunkPercent,
                processingChunkName = stats.processingChunkName,
                imageQueuePending = stats.imageQueuePending,
                isImporting = stats.isImporting,
                importPercent = stats.importPercent,
                importName = stats.importName ?: it.importName,
                lastRealtimeRatio = stats.lastRealtimeRatio,
                avgPhotoLatencyMs = stats.avgPhotoLatencyMs,
                chunkHistory = stats.chunkHistory,
            )
        }
    }

    private fun applyDeviceLoad(snapshot: DeviceLoadSnapshot) {
        _state.update {
            it.copy(
                thermalLabel = snapshot.thermalLabel,
                thermalLevel = snapshot.thermalLevel,
                usedRamMb = snapshot.usedRamMb,
                availRamMb = snapshot.availRamMb,
                totalRamMb = snapshot.totalRamMb,
            )
        }
    }

    companion object {
        private const val LOAD_POLL_MS = 2_000L
    }
}
