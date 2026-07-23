package com.autobots.ui

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobots.camera.CaptureMode
import com.autobots.camera.CapturePipeline
import com.autobots.camera.PassageFireEvaluator
import com.autobots.camera.PassageThresholds
import com.autobots.camera.StreamResolution
import com.autobots.camera.detection.FaceFrameResult
import com.autobots.camera.detection.NormalizedFaceBox
import com.autobots.camera.load.DeviceLoadReader
import com.autobots.camera.load.DeviceLoadSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class OperatorUiState(
    val isCapturing: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.Standard,
    val capturePipeline: CapturePipeline = CapturePipeline.StreamGrab,
    val streamResolution: StreamResolution = StreamResolution.Hd1080,
    val isStreamGrabbing: Boolean = false,
    val isArmed: Boolean = false,
    val lastFired: Boolean = false,
    /** Passage Gate open = may fire; closed after a Fire until face leaves. */
    val passageGateOpen: Boolean = true,
    val keptPhotoCount: Int = 0,
    val lastBurstSaved: Int = 0,
    val lastGalleryUri: String? = null,
    val isBursting: Boolean = false,
    val thermalLabel: String = "—",
    val thermalLevel: Int = -1,
    val usedRamMb: Long = 0,
    val availRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val faces: List<NormalizedFaceBox> = emptyList(),
    val subjectIndex: Int? = null,
    val proximity: Float = 0f,
    val faceCount: Int = 0,
    val inCaptureZone: Boolean = false,
    val armThreshold: Float = PassageThresholds.ARM_HALF_BODY,
    /** Minimum face size for Fire (Capture Zone is the primary trigger). */
    val fireThreshold: Float = PassageThresholds.FIRE_HALF_BODY,
    val exposureLine: String = "—mm  ·  —  ·  ISO —",
    val serverIp: String = "—",
) {
    val armReleaseThreshold: Float
        get() = PassageThresholds.armRelease(armThreshold)

    val burstShotCount: Int
        get() = when (captureMode) {
            CaptureMode.Standard -> 3
            CaptureMode.MaxSensor -> 1
        }

    val deviceLoadLine: String
        get() = if (totalRamMb > 0) {
            "Load: thermal=$thermalLabel  RAM ${formatRam(usedRamMb)}/${formatRam(totalRamMb)} (free ${formatRam(availRamMb)})"
        } else {
            "Load: thermal=$thermalLabel  RAM —"
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

    fun setServerIp(ip: String) {
        _state.update { it.copy(serverIp = ip) }
    }

    /** Authoritative Passage Gate — closed after Fire until face leaves. */
    private val passageGateOpen = AtomicBoolean(true)
    private val bursting = AtomicBoolean(false)
    private var fireTracker = PassageFireEvaluator.Tracker()

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
        deviceLoadReader.stop()
        super.onCleared()
    }

    fun markPendingStartAfterPermission() {
        pendingStartAfterPermission = true
    }

    fun consumePendingStart() {
        pendingStartAfterPermission = false
    }

    fun startCapture() {
        passageGateOpen.set(true)
        bursting.set(false)
        fireTracker = PassageFireEvaluator.Tracker()
        _state.update {
            it.copy(
                isCapturing = true,
                isStreamGrabbing = false,
                isArmed = false,
                lastFired = false,
                passageGateOpen = true,
                isBursting = false,
                lastBurstSaved = 0,
                faces = emptyList(),
                subjectIndex = null,
                proximity = 0f,
                faceCount = 0,
                inCaptureZone = false,
            )
        }
        applyDeviceLoad(deviceLoadReader.sample())
    }

    fun stopCapture() {
        passageGateOpen.set(true)
        bursting.set(false)
        fireTracker = PassageFireEvaluator.Tracker()
        _state.update {
            it.copy(
                isCapturing = false,
                isStreamGrabbing = false,
                isArmed = false,
                isBursting = false,
                passageGateOpen = true,
                faces = emptyList(),
                subjectIndex = null,
                proximity = 0f,
                faceCount = 0,
                inCaptureZone = false,
                exposureLine = "—mm  ·  —  ·  ISO —",
            )
        }
    }

    /**
     * @return true if a Lean Burst should be triggered now (Passage Gate fire).
     */
    fun onFaceResult(result: FaceFrameResult): Boolean {
        val current = _state.value
        if (!current.isCapturing) return false

        if (current.capturePipeline == CapturePipeline.StreamGrab) {
            val grabbing = result.faces.isNotEmpty()
            _state.update {
                it.copy(
                    faces = result.faces,
                    subjectIndex = result.subjectIndex,
                    proximity = result.proximity,
                    faceCount = result.faces.size,
                    isArmed = grabbing,
                    isStreamGrabbing = grabbing,
                    inCaptureZone = false,
                    passageGateOpen = true,
                    lastFired = grabbing,
                )
            }
            return false
        }

        val armed = resolveArmed(
            wasArmed = current.isArmed,
            proximity = result.proximity,
            arm = current.armThreshold,
            release = current.armReleaseThreshold,
        )

        // Re-open gate when face leaves / drops below release
        if (!passageGateOpen.get() &&
            (result.faces.isEmpty() || result.proximity < current.armReleaseThreshold)
        ) {
            passageGateOpen.set(true)
        }

        var shouldFire = false
        var inZone = false
        if (passageGateOpen.get() && !bursting.get() && result.subjectIndex != null) {
            val (ready, nextTracker) = PassageFireEvaluator.evaluate(
                result = result,
                armed = armed,
                nowMs = System.currentTimeMillis(),
                minFaceSize = current.fireThreshold,
                tracker = fireTracker,
            )
            fireTracker = nextTracker
            inZone = nextTracker.zoneStreak > 0
            if (ready) {
                if (passageGateOpen.compareAndSet(true, false) && bursting.compareAndSet(false, true)) {
                    shouldFire = true
                    fireTracker = PassageFireEvaluator.Tracker()
                }
            }
        } else if (!armed) {
            fireTracker = PassageFireEvaluator.Tracker()
        }

        _state.update {
            it.copy(
                faces = result.faces,
                subjectIndex = result.subjectIndex,
                proximity = result.proximity,
                faceCount = result.faces.size,
                isArmed = armed,
                inCaptureZone = inZone,
                passageGateOpen = passageGateOpen.get(),
                lastFired = if (shouldFire) true else it.lastFired,
                isBursting = bursting.get(),
            )
        }
        return shouldFire
    }

    fun onBurstComplete(savedCount: Int) {
        bursting.set(false)
        _state.update {
            it.copy(
                isBursting = false,
                lastBurstSaved = savedCount,
            )
        }
        applyDeviceLoad(deviceLoadReader.sample())
    }

    fun onPhotoDelivered(uriString: String) {
        _state.update {
            it.copy(
                keptPhotoCount = it.keptPhotoCount + 1,
                lastGalleryUri = uriString,
            )
        }
    }

    private fun resolveArmed(
        wasArmed: Boolean,
        proximity: Float,
        arm: Float,
        release: Float,
    ): Boolean {
        return when {
            proximity >= arm -> true
            proximity < release -> false
            else -> wasArmed
        }
    }

    fun setArmThreshold(value: Float) {
        val arm = value.coerceIn(0.01f, 0.30f)
        _state.update { current ->
            current.copy(
                armThreshold = arm,
                fireThreshold = current.fireThreshold.coerceAtLeast(arm + 0.01f),
            )
        }
    }

    fun setFireThreshold(value: Float) {
        _state.update { current ->
            val fire = value.coerceIn(0.02f, 0.50f)
            current.copy(
                fireThreshold = fire.coerceAtLeast(current.armThreshold + 0.01f),
            )
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        _state.update { it.copy(captureMode = mode) }
    }

    fun setCapturePipeline(pipeline: CapturePipeline) {
        _state.update { it.copy(capturePipeline = pipeline) }
    }

    fun setStreamResolution(resolution: StreamResolution) {
        _state.update { it.copy(streamResolution = resolution) }
    }

    fun onExposureReadout(line: String) {
        _state.update { it.copy(exposureLine = line) }
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
