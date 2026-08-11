package com.autobots.camera.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit face detection for offline video frame processing.
 *
 * Uses Google ML Kit (bundled TFLite) — hardware acceleration is handled
 * internally by the SDK on supported devices.
 */
class OfflineFaceDetector(
    accurate: Boolean = false,
) {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (accurate) {
                    FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                } else {
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                },
            )
            // Must sit below VideoFrameProcessor.MIN_FACE_HEIGHT_RATIO (0.035), or ML Kit
            // drops distant runners before the pipeline's own size gate can judge them.
            // Relative to image *width*; the pipeline gate is relative to height.
            .setMinFaceSize(0.025f)
            // Reuse face IDs across consecutive frames in the same chunk.
            .enableTracking()
            .build(),
    )

    suspend fun detect(bitmap: Bitmap): List<Rect> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (cont.isActive) cont.resume(faces.map { it.boundingBox })
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    fun close() {
        detector.close()
    }
}