package com.autobots.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.autobots.camera.capture.ChunkCaptureMeta
import com.autobots.camera.capture.VideoChunkRecorder
import com.autobots.camera.perf.CamPerf
import com.autobots.camera.perf.ExposureStats
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX Preview + VideoCapture for Plan B pipeline (no live face analysis).
 */
@OptIn(ExperimentalCamera2Interop::class)
class VideoPreviewController(
    private val context: Context,
) {
    private val providerRef = AtomicReference<ProcessCameraProvider?>(null)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    /** Exactly what this controller bound, so unbinding never reaches another pane's. */
    @Volatile
    private var boundUseCases: List<UseCase> = emptyList()
    private var chunkRecorder: VideoChunkRecorder? = null
    private val bindGeneration = AtomicReference(0)
    private var shutdown = false
    private var onExposureReadout: ((CameraExposureReadout) -> Unit)? = null
    private val lastExposurePublishMs = AtomicLong(0L)

    /**
     * Replaced on the main thread by `bindInternal`, but `add()`-ed from the camera's own
     * callback thread. The instance is internally safe (`ExposureStats.add` is
     * `@Synchronized`); what needs publishing is the *reference*, so a rebind cannot leave the
     * callback thread accumulating into the previous session's stats.
     */
    @Volatile
    private var exposureStats = ExposureStats()

    /**
     * Live sensor readout from the repeating request. Always attached — the operator
     * needs to see the shutter speed on the tripod; only the verbose logging is gated.
     */
    fun setExposureReadoutListener(listener: (CameraExposureReadout) -> Unit) {
        onExposureReadout = listener
    }

    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: StreamResolution,
        onBound: (VideoCapture<Recorder>) -> Unit,
    ) {
        if (shutdown) return
        val generation = bindGeneration.updateAndGet { it + 1 }

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (shutdown || generation != bindGeneration.get()) return@addListener
                try {
                    val provider = future.get()
                    providerRef.set(provider)
                    bindInternal(lifecycleOwner, previewView, provider, resolution, generation, 0, onBound)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to get camera provider", t)
                }
            },
            mainExecutor,
        )
    }

    fun startChunkRecording(
        sessionDir: File,
        maxChunkBytes: Long,
        canAcceptChunk: () -> Boolean,
        onChunkReady: (ChunkCaptureMeta) -> Unit,
        onProgress: (Int, Long, Long) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
    ) {
        val capture = videoCapture ?: run {
            Log.w(TAG, "startChunkRecording: video capture not ready")
            return
        }
        val videoDir = File(sessionDir, "video").apply { mkdirs() }
        chunkRecorder?.stop()
        chunkRecorder = VideoChunkRecorder(
            context = context,
            videoCapture = capture,
            videoDir = videoDir,
            mainExecutor = mainExecutor,
            maxChunkBytes = maxChunkBytes,
            canAcceptChunk = canAcceptChunk,
            onChunkReady = onChunkReady,
            onProgress = onProgress,
            onPaused = onPaused,
            onResumed = onResumed,
        ).also { it.start() }
    }

    fun stopChunkRecording(onFinalized: () -> Unit = {}) {
        val recorder = chunkRecorder
        chunkRecorder = null
        if (recorder == null) {
            onFinalized()
            return
        }
        recorder.stop(onFinalized)
    }

    fun resumeRecordingIfPaused() {
        chunkRecorder?.resumeIfPaused()
    }

    fun unbindCamera() {
        bindGeneration.updateAndGet { it + 1 }
        camera = null
        videoCapture = null
        if (CamPerf.enabled && exposureStats.sampleCount() > 0) {
            CamPerf.log { exposureStats.summary("SESSION TOTAL — sensor while bound") }
        }
        lastExposurePublishMs.set(0L)
        mainExecutor.execute { onExposureReadout?.invoke(CameraExposureReadout()) }
        val provider = providerRef.get() ?: return
        val mine = boundUseCases
        boundUseCases = emptyList()
        try {
            // Only this controller's own use cases. `unbindAll()` would also tear down a
            // preview another pane has just bound — leaving the camera on (green dot lit)
            // with nothing on screen. That is what happens when the operator moves from the
            // live page into the zone editor, since the old pane's asynchronous stop lands
            // after the new pane has bound.
            if (mine.isEmpty()) {
                Log.i(TAG, "Nothing bound by this controller")
            } else {
                provider.unbind(*mine.toTypedArray())
                Log.i(TAG, "Camera unbound (${mine.size} use cases)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
    }

    fun unbind() {
        stopChunkRecording { unbindCamera() }
    }

    fun shutdown() {
        shutdown = true
        unbind()
    }

    private fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        provider: ProcessCameraProvider,
        resolution: StreamResolution,
        generation: Int,
        attempt: Int,
        onBound: (VideoCapture<Recorder>) -> Unit,
    ) {
        if (shutdown || generation != bindGeneration.get()) return

        if (previewView.width == 0 || previewView.height == 0 || previewView.viewPort == null) {
            if (attempt >= 30) {
                Log.e(TAG, "PreviewView never ready")
                return
            }
            previewView.post {
                bindInternal(lifecycleOwner, previewView, provider, resolution, generation, attempt + 1, onBound)
            }
            return
        }

        try {
            val qualitySelector = qualitySelectorFor(resolution)
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture
            boundUseCases = listOf(capture)

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        onSensorMetadata(result)
                    }
                },
            )
            val preview = previewBuilder
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            exposureStats = ExposureStats()
            boundUseCases = listOf(preview, capture)
            provider.unbindAll()

            val boundCamera: Camera = run {
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    try {
                        val group = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(capture)
                            .build()
                        val cam = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            group,
                        )
                        Log.i(TAG, "Bound Preview+VideoCapture (${resolution.label}) with ViewPort")
                        return@run cam
                    } catch (t: Throwable) {
                        Log.w(TAG, "ViewPort bind failed, falling back", t)
                        provider.unbindAll()
                    }
                }
                val cam = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                Log.i(TAG, "Bound Preview+VideoCapture (${resolution.label}) fallback")
                cam
            }

            camera = boundCamera
            CamPerf.log {
                val caps = CameraCapabilities.read(boundCamera.cameraInfo)
                caps?.summary() ?: "┌─ camera capabilities\n└ unavailable (Camera2 interop read failed)"
            }
            mainExecutor.execute { onBound(capture) }
        } catch (t: Throwable) {
            Log.e(TAG, "bindInternal failed", t)
        }
    }

    /**
     * Fires per frame on a camera thread. Reads what the sensor actually chose —
     * never what we asked for. Phase 1 depends on this distinction.
     */
    private fun onSensorMetadata(result: TotalCaptureResult) {
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)

        if (CamPerf.enabled && exposureNs != null && iso != null) {
            val samples = exposureStats.add(
                exposureNs = exposureNs,
                iso = iso,
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            )
            if (samples % EXPOSURE_SUMMARY_FRAMES == 0) {
                CamPerf.log { exposureStats.summary("sensor over last $samples frames") }
            }
        }

        val now = SystemClock.elapsedRealtime()
        val last = lastExposurePublishMs.get()
        if (now - last < EXPOSURE_THROTTLE_MS) return
        if (!lastExposurePublishMs.compareAndSet(last, now)) return

        val readout = CameraExposureReadout(
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            exposureTimeNs = exposureNs,
            iso = iso,
        )
        mainExecutor.execute { onExposureReadout?.invoke(readout) }
    }

    private fun qualitySelectorFor(resolution: StreamResolution): QualitySelector {
        return when (resolution) {
            StreamResolution.Uhd -> QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
            )
            StreamResolution.Fhd -> QualitySelector.from(
                Quality.FHD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
            )
        }
    }

    companion object {
        private const val TAG = "VideoPreview"
        private const val EXPOSURE_THROTTLE_MS = 250L
        private const val EXPOSURE_SUMMARY_FRAMES = 300

        fun freeStorageMb(dir: File): Long {
            return try {
                val stat = StatFs(dir.absolutePath)
                stat.availableBytes / (1024L * 1024L)
            } catch (_: Throwable) {
                0L
            }
        }

        const val MIN_FREE_STORAGE_MB = 2_048L
    }
}
