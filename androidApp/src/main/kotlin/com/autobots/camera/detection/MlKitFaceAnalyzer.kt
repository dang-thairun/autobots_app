package com.autobots.camera.detection

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P3 interim face detector (ML Kit).
 * Maps boxes into [PreviewView] coordinates on the main thread (PreviewView is not thread-safe).
 */
class MlKitFaceAnalyzer(
    private val previewView: PreviewView,
    private val onResult: (FaceFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(previewView.context)

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build(),
    )

    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        val sourceTransform = runCatching {
            transformFactory.getOutputTransform(imageProxy)
        }.getOrNull()

        detector.process(image)
            .addOnSuccessListener { faces ->
                if (closed.get()) return@addOnSuccessListener
                // PreviewView / outputTransform must be touched on the main thread.
                mainExecutor.execute {
                    if (closed.get()) return@execute
                    publishMapped(faces.map { it.boundingBox }, sourceTransform)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detect failed", e)
                if (!closed.get()) {
                    mainExecutor.execute {
                        if (!closed.get()) onResult(SubjectFaceSelector.select(emptyList()))
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun publishMapped(
        bounds: List<android.graphics.Rect>,
        sourceTransform: androidx.camera.view.transform.OutputTransform?,
    ) {
        try {
            val targetTransform = previewView.outputTransform
            val viewW = previewView.width.toFloat()
            val viewH = previewView.height.toFloat()
            if (sourceTransform == null || targetTransform == null || viewW <= 0f || viewH <= 0f) {
                onResult(SubjectFaceSelector.select(emptyList()))
                return
            }

            val matrix = Matrix()
            CoordinateTransform(sourceTransform, targetTransform).transform(matrix)

            val boxes = bounds.map { box ->
                val mapped = RectF(box)
                matrix.mapRect(mapped)
                NormalizedFaceBox(
                    left = (mapped.left / viewW).coerceIn(0f, 1f),
                    top = (mapped.top / viewH).coerceIn(0f, 1f),
                    right = (mapped.right / viewW).coerceIn(0f, 1f),
                    bottom = (mapped.bottom / viewH).coerceIn(0f, 1f),
                    confidence = 1f,
                )
            }
            onResult(SubjectFaceSelector.select(boxes))
        } catch (t: Throwable) {
            // Aspect-ratio mismatch / transform not ready — skip frame, don't crash.
            Log.w(TAG, "Overlay mapping failed: ${t.message}")
            onResult(SubjectFaceSelector.select(emptyList()))
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { detector.close() }
            .onFailure { Log.w(TAG, "detector.close() failed", it) }
    }

    companion object {
        private const val TAG = "MlKitFaceAnalyzer"
    }
}
