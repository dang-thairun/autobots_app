package com.autobots.camera

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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
import com.autobots.camera.capture.VideoChunkRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX Preview + VideoCapture for Plan B pipeline (no live face analysis).
 */
class VideoPreviewController(
    private val context: Context,
) {
    private val providerRef = AtomicReference<ProcessCameraProvider?>(null)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var chunkRecorder: VideoChunkRecorder? = null
    private val bindGeneration = AtomicReference(0)
    private var shutdown = false

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
        canAcceptChunk: () -> Boolean,
        onChunkReady: (File) -> Unit,
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
            canAcceptChunk = canAcceptChunk,
            onChunkReady = onChunkReady,
            onProgress = onProgress,
            onPaused = onPaused,
            onResumed = onResumed,
        ).also { it.start() }
    }

    fun stopChunkRecording() {
        chunkRecorder?.stop()
        chunkRecorder = null
    }

    fun resumeRecordingIfPaused() {
        chunkRecorder?.resumeIfPaused()
    }

    fun unbind() {
        stopChunkRecording()
        bindGeneration.updateAndGet { it + 1 }
        camera = null
        videoCapture = null
        val provider = providerRef.get() ?: return
        try {
            provider.unbindAll()
            Log.i(TAG, "Camera unbound")
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
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

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

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
            mainExecutor.execute { onBound(capture) }
        } catch (t: Throwable) {
            Log.e(TAG, "bindInternal failed", t)
        }
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
