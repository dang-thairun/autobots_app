package com.autobots.camera.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class PoseDetectionResult(
    val torsoBounds: Rect,
    val subjectHeightRatio: Float,
)

/**
 * ML Kit pose detection for offline video frame processing.
 */
class OfflinePoseDetector {
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build(),
    )

    suspend fun detect(bitmap: Bitmap): PoseDetectionResult? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { pose ->
                if (cont.isActive) cont.resume(pose.toDetectionResult(bitmap.height))
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    fun close() {
        detector.close()
    }

    private fun Pose.toDetectionResult(frameHeight: Int): PoseDetectionResult? {
        val points = REQUIRED_LANDMARKS.mapNotNull { type ->
            getPoseLandmark(type)?.takeIf { it.inFrameLikelihood >= MIN_IN_FRAME_LIKELIHOOD }?.position
        }
        if (points.size < REQUIRED_LANDMARKS.size) return null

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = Float.MIN_VALUE
        var bottom = Float.MIN_VALUE
        for (point in points) {
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }

        val bounds = Rect(
            left.toInt().coerceAtLeast(0),
            top.toInt().coerceAtLeast(0),
            right.toInt(),
            bottom.toInt(),
        )
        if (bounds.width() < 8 || bounds.height() < 8) return null

        val subjectHeightRatio = bounds.height().toFloat() / frameHeight
        return PoseDetectionResult(
            torsoBounds = bounds,
            subjectHeightRatio = subjectHeightRatio,
        )
    }

    companion object {
        private const val MIN_IN_FRAME_LIKELIHOOD = 0.5f

        private val REQUIRED_LANDMARKS = listOf(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
        )
    }
}
