package com.autobots.camera.detection

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import android.os.SystemClock
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P3 interim face detector (ML Kit).
 * Maps boxes into [PreviewView] coordinates on the main thread (PreviewView is not thread-safe).
 */
class MlKitFaceAnalyzer(
    private val previewView: PreviewView,
    private val onFrameEncoded: ((ByteArray) -> Unit)? = null,
    private val onResult: (FaceFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(previewView.context)
    private var lastFrameTime = 0L

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build(),
    )

    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }

    private val timingLock = Any()
    private var timingWindowStartMs = 0L
    private var timingFrameCount = 0
    private var timingInferSumMs = 0L
    private var timingInferMinMs = Long.MAX_VALUE
    private var timingInferMaxMs = 0L
    private var timingMapSumMs = 0L
    private var timingTotalSumMs = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }

        // Throttle and encode preview frames for remote streaming
        if (onFrameEncoded != null) {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= 66) { // Max ~15 FPS
                lastFrameTime = now
                val jpeg = imageProxy.toJpeg()
                if (jpeg != null) {
                    onFrameEncoded.invoke(jpeg)
                }
            }
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

        val startedNs = System.nanoTime()

        detector.process(image)
            .addOnSuccessListener { faces ->
                if (closed.get()) return@addOnSuccessListener
                val inferMs = nanosToMs(System.nanoTime() - startedNs)
                // PreviewView / outputTransform must be touched on the main thread.
                mainExecutor.execute {
                    if (closed.get()) return@execute
                    val mapStartNs = System.nanoTime()
                    publishMapped(faces.map { it.boundingBox }, sourceTransform)
                    val mapMs = nanosToMs(System.nanoTime() - mapStartNs)
                    val totalMs = nanosToMs(System.nanoTime() - startedNs)
                    recordTiming(inferMs, mapMs, totalMs, faces.size)
                }
            }
            .addOnFailureListener { e ->
                val inferMs = nanosToMs(System.nanoTime() - startedNs)
                recordTiming(inferMs, mapMs = 0L, totalMs = inferMs, faceCount = 0)
                Log.w(TAG, "Face detect failed (${inferMs}ms)", e)
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

    private fun recordTiming(inferMs: Long, mapMs: Long, totalMs: Long, faceCount: Int) {
        synchronized(timingLock) {
            val now = SystemClock.elapsedRealtime()
            if (timingWindowStartMs == 0L) timingWindowStartMs = now

            timingFrameCount++
            timingInferSumMs += inferMs
            timingInferMinMs = minOf(timingInferMinMs, inferMs)
            timingInferMaxMs = maxOf(timingInferMaxMs, inferMs)
            timingMapSumMs += mapMs
            timingTotalSumMs += totalMs

            if (now - timingWindowStartMs < TIMING_LOG_INTERVAL_MS) return

            val n = timingFrameCount
            Log.i(
                TAG,
                "analyze timing 1s: n=$n (~${n}fps) " +
                    "infer avg=${timingInferSumMs / n}ms min=$timingInferMinMs max=$timingInferMaxMs " +
                    "map avg=${timingMapSumMs / n}ms total avg=${timingTotalSumMs / n}ms " +
                    "last faces=$faceCount",
            )

            timingWindowStartMs = now
            timingFrameCount = 0
            timingInferSumMs = 0L
            timingInferMinMs = Long.MAX_VALUE
            timingInferMaxMs = 0L
            timingMapSumMs = 0L
            timingTotalSumMs = 0L
        }
    }

    private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000L

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { detector.close() }
            .onFailure { Log.w(TAG, "detector.close() failed", it) }
    }

    companion object {
        private const val TAG = "MlKitFaceAnalyzer"
        /** Rolling summary interval — filter logcat: `adb logcat -s MlKitFaceAnalyzer` */
        private const val TIMING_LOG_INTERVAL_MS = 1_000L
    }
}

/**
 * Extension helper to convert YUV_420_888 ImageProxy to JPEG ByteArray.
 */
fun ImageProxy.toJpeg(): ByteArray? {
    try {
        if (format != ImageFormat.YUV_420_888) return null
        
        val yBuffer = planes[0].buffer.duplicate()
        val uBuffer = planes[1].buffer.duplicate()
        val vBuffer = planes[2].buffer.duplicate()

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val uPixels = ByteArray(uBuffer.remaining())
        val vPixels = ByteArray(vBuffer.remaining())
        uBuffer.get(uPixels)
        vBuffer.get(vPixels)

        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride

        var nvIdx = ySize
        val gridWidth = width / 2
        val gridHeight = height / 2

        for (row in 0 until gridHeight) {
            for (col in 0 until gridWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride

                if (vIndex < vPixels.size) {
                    nv21[nvIdx++] = vPixels[vIndex]
                }
                if (uIndex < uPixels.size) {
                    nv21[nvIdx++] = uPixels[uIndex]
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 60, out)
        return out.toByteArray()
    } catch (e: Exception) {
        Log.e("MlKitFaceAnalyzer", "YUV conversion failed", e)
    }
    return null
}
