package com.autobots.ui

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobots.camera.ChunkRecordingProgress
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
        val coordinator = CapturePipelineCoordinator.create(
            context = getApplication(),
            onStats = ::applyPipelineStats,
            onPhotoDelivered = { uri -> onPhotoDelivered(uri.toString()) },
        )
        val resolution = _state.value.streamResolution
        coordinator.setResolution(resolution)

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
            )
        }
        applyDeviceLoad(deviceLoadReader.sample())
    }

    fun stopCapture() {
        pipeline?.onRecordingStopped()
        pipeline?.close()
        pipeline = null
        _state.update {
            it.copy(
                isCapturing = false,
                pipelinePaused = false,
                exposureLine = "—mm  ·  —  ·  ISO —",
                recordingProgress = ChunkRecordingProgress(),
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

    fun onExposureReadout(line: String) {
        _state.update { it.copy(exposureLine = line) }
    }

    private fun applyPipelineStats(stats: PipelineStats) {
        _state.update {
            it.copy(
                streamResolution = stats.resolution,
                videoChunksRecorded = stats.videoChunksRecorded,
                videoQueueDepth = stats.videoQueueDepth,
                facesKept = stats.facesKept,
                facesSkipped = stats.facesSkipped,
                lastChunkProcessMs = stats.lastChunkProcessMs,
                storageFreeMb = stats.storageFreeMb,
                pipelinePaused = stats.pipelinePaused,
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
