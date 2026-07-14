package com.autobots.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.autobots.camera.capture.LeanBurstCapturer
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.delivery.WriteQueue
import com.autobots.camera.detection.FaceFrameResult
import com.autobots.camera.detection.MlKitFaceAnalyzer
import com.autobots.camera.focus.FaceFocusController
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX Preview + Analysis + Capture + Face Lock + Lean Burst + Local Delivery (P6).
 */
@OptIn(ExperimentalCamera2Interop::class)
class PreviewCameraController(
    private val context: Context,
) {
    private val providerRef = AtomicReference<ProcessCameraProvider?>(null)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var faceAnalyzer: MlKitFaceAnalyzer? = null
    private var faceFocus: FaceFocusController? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var burstCapturer: LeanBurstCapturer? = null
    private var writeQueue: WriteQueue? = null
    private var onPhotoDelivered: ((Uri) -> Unit)? = null
    private var onExposureReadout: ((CameraExposureReadout) -> Unit)? = null
    private val armThresholdRef = AtomicReference(PassageThresholds.ARM_HALF_BODY)
    private val fireThresholdRef = AtomicReference(PassageThresholds.FIRE_HALF_BODY)
    private val burstShotCountRef = AtomicInteger(LeanBurstCapturer.DEFAULT_SHOTS)
    private val captureModeRef = AtomicReference(CaptureMode.Standard)
    private val bindGeneration = AtomicReference(0)
    private val shutdown = AtomicBoolean(false)
    private val lastExposurePublishMs = AtomicLong(0L)
    private val burstOutputDir = File(context.cacheDir, "autobots_burst")

    fun setPhotoDeliveredListener(listener: (Uri) -> Unit) {
        onPhotoDelivered = listener
        ensureWriteQueue()
    }

    fun setExposureReadoutListener(listener: (CameraExposureReadout) -> Unit) {
        onExposureReadout = listener
    }

    fun setArmThreshold(threshold: Float) {
        armThresholdRef.set(threshold.coerceIn(0.005f, 0.5f))
    }

    fun setFireThreshold(threshold: Float) {
        fireThresholdRef.set(threshold.coerceIn(0.01f, 0.6f))
    }

    fun setBurstShotCount(count: Int) {
        burstShotCountRef.set(count.coerceIn(1, 5))
    }

    fun setCaptureMode(mode: CaptureMode) {
        captureModeRef.set(mode)
    }

    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFaceResult: (FaceFrameResult) -> Unit,
    ) {
        if (shutdown.get()) return

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        val generation = bindGeneration.updateAndGet { it + 1 }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (shutdown.get() || generation != bindGeneration.get()) return@addListener
                try {
                    val provider = future.get()
                    providerRef.set(provider)
                    bindInternal(
                        lifecycleOwner,
                        previewView,
                        provider,
                        onFaceResult,
                        generation,
                        attempt = 0,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to get camera provider", t)
                }
            },
            mainExecutor,
        )
    }

    fun triggerBurst(onBurstFinished: (capturedCount: Int) -> Unit = {}) {
        val capturer = burstCapturer
        if (capturer == null) {
            Log.w(TAG, "triggerBurst: capturer not ready")
            onBurstFinished(0)
            return
        }
        capturer.capture(shotCount = burstShotCountRef.get()) { saved, files ->
            mainExecutor.execute { onBurstFinished(saved) }
            val queue = ensureWriteQueue()
            val enqueued = queue.enqueueAll(files)
            Log.i(TAG, "Burst captured=$saved enqueued=$enqueued → DCIM/AutoBots")
        }
    }

    private fun ensureWriteQueue(): WriteQueue {
        writeQueue?.let { return it }
        val queue = WriteQueue(
            writer = LocalDeliveryWriter(context),
            onDelivered = { uri ->
                mainExecutor.execute { onPhotoDelivered?.invoke(uri) }
            },
        )
        writeQueue = queue
        return queue
    }

    private fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        provider: ProcessCameraProvider,
        onFaceResult: (FaceFrameResult) -> Unit,
        generation: Int,
        attempt: Int,
    ) {
        if (shutdown.get() || generation != bindGeneration.get()) return

        if (previewView.width == 0 || previewView.height == 0 || previewView.viewPort == null) {
            if (attempt >= 30) {
                Log.e(TAG, "PreviewView never ready; giving up bind")
                return
            }
            previewView.post {
                bindInternal(
                    lifecycleOwner,
                    previewView,
                    provider,
                    onFaceResult,
                    generation,
                    attempt + 1,
                )
            }
            return
        }

        try {
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        publishExposure(result)
                    }
                },
            )
            val preview = previewBuilder
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val mode = captureModeRef.get()
            val capture = ImageCapture.Builder()
                .setCaptureMode(CaptureResolutions.captureModeQuality(mode))
                .setResolutionSelector(CaptureResolutions.imageCaptureSelector(mode))
                .build()
            imageCapture = capture
            burstCapturer = LeanBurstCapturer(capture, mainExecutor, burstOutputDir)
            Log.i(TAG, "ImageCapture mode=$mode target=${CaptureResolutions.label(context, mode)}")

            faceFocus?.detach()
            val focus = FaceFocusController(previewView)
            faceFocus = focus

            faceAnalyzer?.close()
            val analyzer = MlKitFaceAnalyzer(previewView) { result ->
                applyFaceLock(result)
                onFaceResult(result)
            }
            faceAnalyzer = analyzer

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 360),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            provider.unbindAll()

            val boundCamera: Camera = run {
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    try {
                        val useCaseGroup = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .addUseCase(capture)
                            .build()
                        val cam = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            useCaseGroup,
                        )
                        Log.i(TAG, "Bound Preview+Analysis+Capture with ViewPort")
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
                    analysis,
                    capture,
                )
                Log.i(TAG, "bound Preview+Analysis+Capture (fallback)")
                cam
            }

            camera = boundCamera
            focus.attach(boundCamera.cameraControl)
            Log.d(TAG, "Burst output dir: ${burstOutputDir.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "bindInternal failed", t)
        }
    }

    private fun publishExposure(result: TotalCaptureResult) {
        val now = SystemClock.elapsedRealtime()
        val last = lastExposurePublishMs.get()
        if (now - last < EXPOSURE_THROTTLE_MS) return
        if (!lastExposurePublishMs.compareAndSet(last, now)) return

        val readout = CameraExposureReadout(
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        )
        mainExecutor.execute { onExposureReadout?.invoke(readout) }
    }

    private fun applyFaceLock(result: FaceFrameResult) {
        val focus = faceFocus ?: return
        val subjectIndex = result.subjectIndex
        if (subjectIndex == null || result.proximity < armThresholdRef.get()) {
            return
        }
        val subject = result.faces.getOrNull(subjectIndex) ?: return
        focus.lockOnFace(subject)
    }

    fun unbind() {
        bindGeneration.updateAndGet { it + 1 }
        burstCapturer?.cancelPending()
        burstCapturer = null
        imageCapture = null
        faceFocus?.detach()
        faceFocus = null
        camera = null
        faceAnalyzer?.close()
        faceAnalyzer = null
        lastExposurePublishMs.set(0L)
        mainExecutor.execute {
            onExposureReadout?.invoke(CameraExposureReadout())
        }
        val provider = providerRef.get() ?: return
        try {
            provider.unbindAll()
            Log.i(TAG, "Camera unbound")
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
    }

    fun shutdown() {
        shutdown.set(true)
        unbind()
        writeQueue?.close()
        writeQueue = null
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PreviewCamera"
        private const val EXPOSURE_THROTTLE_MS = 250L
    }
}
