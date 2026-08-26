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
) : SubjectFaceDetector {

    private val mode = if (accurate) "accurate" else "fast"

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
            // NOTE: enableTracking() is deliberately absent since 0.1.4.
            //
            // It exists to give faces a stable ID across frames, and [detect] discards the
            // ID — nothing in this project has ever read `Face.trackingId`. What it did cost:
            //
            //  - every `roiInvalid` frame. A tracker predicts where a face will be, so it
            //    returns boxes past the frame edge for a subject at the border; the sharpness
            //    scorer then cannot measure them.
            //  - reproducibility. Detection became a function of frame history, so per-reason
            //    reject counts drifted ±2–3 between runs of the same file (v0.1.3 had to warn
            //    readers not to trust small deltas).
            //  - correctness under the 0.1.4 two-stage pipeline. With DETECT_WORKERS = 2 each
            //    detector sees every *other* frame, so the gap it tracks across doubled to
            //    ~240 ms and arrival order stopped being guaranteed. Measured on run4mins:
            //    `roiInvalid` 16 → 29 on identical footage.
            //
            // Turning it off makes a chunk's result depend only on its frames, which is what
            // every remaining experiment needs. See docs/RELEASE_0_1_4.md.
            .build(),
    )

    override val diagnostics: Map<String, Any> get() = mapOf("mlkitMode" to mode)

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                // No score: ML Kit's face API does not expose a detection confidence.
                if (cont.isActive) cont.resume(faces.map { DetectedFace(it.boundingBox) })
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    override fun close() {
        detector.close()
    }
}