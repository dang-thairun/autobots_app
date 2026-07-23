package com.autobots.camera.detection

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.util.concurrent.atomic.AtomicLong

/**
 * P3 interim face detector (ML Kit).
 * Maps boxes into [PreviewView] coordinates on the main thread (PreviewView is not thread-safe).
 */
class MlKitFaceAnalyzer(
    private val previewView: PreviewView,
    private val detectMaxWidth: Int = Int.MAX_VALUE,
    private val onFrameEncoded: ((ByteArray) -> Unit)? = null,
    private val streamGrabEnabled: () -> Boolean = { false },
    private val onStreamFrame: ((ByteArray) -> Unit)? = null,
    private val onResult: (FaceFrameResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val closed = AtomicBoolean(false)
    private val detectInFlight = AtomicBoolean(false)
    private val lastStreamGrabMs = AtomicLong(0L)
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
        if (!detectInFlight.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val analyzeStartedNs = System.nanoTime()

        // Throttle and encode preview frames for remote streaming (always downscaled).
        if (onFrameEncoded != null) {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime >= 66) { // Max ~15 FPS
                lastFrameTime = now
                val jpeg = imageProxy.toPreviewJpeg(quality = PREVIEW_JPEG_QUALITY)
                if (jpeg != null) {
                    onFrameEncoded.invoke(jpeg)
                }
            }
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            detectInFlight.set(false)
            imageProxy.close()
            return
        }


        val detectInput = buildDetectInput(imageProxy) ?: run {
            detectInFlight.set(false)
            imageProxy.close()
            return
        }

        val sourceTransform = runCatching {
            transformFactory.getOutputTransform(imageProxy)
        }.getOrNull()

        val startedNs = System.nanoTime()
        val grabThisFrame = streamGrabEnabled() && onStreamFrame != null
        if (grabThisFrame) {
            val preDetectMs = nanosToMs(startedNs - analyzeStartedNs)
            Log.w(
                TAG,
                "test -> pre-detect: ${imageProxy.width}x${imageProxy.height} preDetect=${preDetectMs}ms",
            )
        }

        detector.process(detectInput.image)
            .addOnSuccessListener { faces ->
                if (closed.get()) return@addOnSuccessListener
                val inferMs = nanosToMs(System.nanoTime() - startedNs)
                if (grabThisFrame && faces.isNotEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    val lastGrab = lastStreamGrabMs.get()
                    val sinceLastMs = if (lastGrab == 0L) 0L else now - lastGrab
                    if (lastGrab == 0L || sinceLastMs >= STREAM_GRAB_INTERVAL_MS) {
                        lastStreamGrabMs.set(now)
                        val jpeg = imageProxy.toJpeg(quality = STREAM_JPEG_QUALITY)
                        if (jpeg != null) {
                            Log.w(
                                TAG,
                                "stream grab OK: ${imageProxy.width}x${imageProxy.height} " +
                                    "jpeg=${jpeg.size}B sinceLast=${sinceLastMs}ms infer=${inferMs}ms",
                            )
                            onStreamFrame?.invoke(jpeg)
                        } else {
                            Log.w(TAG, "stream grab skip: toJpeg failed infer=${inferMs}ms")
                        }
                    } else {
                        val waitMs = STREAM_GRAB_INTERVAL_MS - sinceLastMs
                        Log.w(
                            TAG,
                            "stream grab throttle: wait ${waitMs}ms more " +
                                "(sinceLast=${sinceLastMs}ms interval=${STREAM_GRAB_INTERVAL_MS}ms)",
                        )
                    }
                } else if (grabThisFrame && faces.isEmpty()) {
                    if (lastStreamGrabMs.getAndSet(0L) != 0L) {
                        Log.w(TAG, "stream grab reset: no face in frame")
                    }
                }
                val scaledBoxes = faces.map { face ->
                    scaleBox(face.boundingBox, detectInput.boxScaleX, detectInput.boxScaleY)
                }
                // PreviewView / outputTransform must be touched on the main thread.
                mainExecutor.execute {
                    if (closed.get()) return@execute
                    val mapStartNs = System.nanoTime()
                    publishMapped(scaledBoxes, sourceTransform)
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
                detectInFlight.set(false)
                imageProxy.close()
            }
    }

    private fun buildDetectInput(imageProxy: ImageProxy): DetectInput? {
        val mediaImage = imageProxy.image ?: return null
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (imageProxy.width <= detectMaxWidth) {
            return DetectInput(
                image = InputImage.fromMediaImage(mediaImage, rotation),
                boxScaleX = 1f,
                boxScaleY = 1f,
            )
        }

        val rawJpeg = imageProxy.yuvToJpegBytes(quality = DETECT_JPEG_QUALITY) ?: return DetectInput(
            image = InputImage.fromMediaImage(mediaImage, rotation),
            boxScaleX = 1f,
            boxScaleY = 1f,
        )

        val sampleSize = computeInSampleSize(imageProxy.width, detectMaxWidth)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, options) ?: return DetectInput(
            image = InputImage.fromMediaImage(mediaImage, rotation),
            boxScaleX = 1f,
            boxScaleY = 1f,
        )

        return DetectInput(
            image = InputImage.fromBitmap(bitmap, rotation),
            boxScaleX = imageProxy.width.toFloat() / bitmap.width.toFloat(),
            boxScaleY = imageProxy.height.toFloat() / bitmap.height.toFloat(),
        )
    }

    private fun scaleBox(box: Rect, scaleX: Float, scaleY: Float): Rect {
        if (scaleX == 1f && scaleY == 1f) return box
        return Rect(
            (box.left * scaleX).toInt(),
            (box.top * scaleY).toInt(),
            (box.right * scaleX).toInt(),
            (box.bottom * scaleY).toInt(),
        )
    }

    private fun computeInSampleSize(width: Int, maxWidth: Int): Int {
        var sample = 1
        while (width / sample > maxWidth) {
            sample *= 2
        }
        return sample
    }

    private data class DetectInput(
        val image: InputImage,
        val boxScaleX: Float,
        val boxScaleY: Float,
    )

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
        private const val PREVIEW_JPEG_QUALITY = 60
        private const val STREAM_JPEG_QUALITY = 92
        private const val DETECT_JPEG_QUALITY = 75
        /** Min gap between stream grabs while a face stays in frame. */
        private const val STREAM_GRAB_INTERVAL_MS = 150L
    }
}

private const val REMOTE_PREVIEW_MAX_WIDTH = 640

/**
 * Extension helper to convert YUV_420_888 ImageProxy to JPEG ByteArray.
 */
fun ImageProxy.toJpeg(quality: Int = 60): ByteArray? {
    try {
        val raw = yuvToJpegBytes(quality) ?: return null
        return rotateJpegToDisplayOrientation(raw, imageInfo.rotationDegrees, quality)
    } catch (e: Exception) {
        Log.e("MlKitFaceAnalyzer", "YUV conversion failed", e)
    }
    return null
}

/** Remote preview — capped width, portrait-corrected. */
fun ImageProxy.toPreviewJpeg(quality: Int = 60): ByteArray? {
    val raw = yuvToJpegBytes(quality) ?: return null
    val rotated = rotateJpegToDisplayOrientation(raw, imageInfo.rotationDegrees, quality)
    val bitmap = BitmapFactory.decodeByteArray(rotated, 0, rotated.size) ?: return rotated
    if (bitmap.width <= REMOTE_PREVIEW_MAX_WIDTH) return rotated
    val scale = REMOTE_PREVIEW_MAX_WIDTH.toFloat() / bitmap.width.toFloat()
    val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, REMOTE_PREVIEW_MAX_WIDTH, targetH, true)
    if (scaled !== bitmap) bitmap.recycle()
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        scaled.recycle()
        out.toByteArray()
    }
}

internal fun ImageProxy.yuvToJpegBytes(quality: Int): ByteArray? {
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
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality.coerceIn(1, 100), out)
        return out.toByteArray()
    } catch (e: Exception) {
        Log.e("MlKitFaceAnalyzer", "YUV conversion failed", e)
    }
    return null
}

/**
 * Sensor buffers are landscape; apply [rotationDegrees] so saved JPEG matches portrait UI.
 * ImageCapture does this automatically — stream grab must do it explicitly.
 */
internal fun rotateJpegToDisplayOrientation(
    jpeg: ByteArray,
    rotationDegrees: Int,
    quality: Int,
): ByteArray {
    if (rotationDegrees == 0 || jpeg.isEmpty()) return jpeg
    val normalized = ((rotationDegrees % 360) + 360) % 360
    if (normalized == 0) return jpeg

    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return ByteArrayOutputStream().use { out ->
        rotated.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        rotated.recycle()
        out.toByteArray()
    }
}
