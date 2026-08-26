package com.autobots.camera.detection

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * A face detector Worker 2 can run, independent of who implements it.
 *
 * Exists so [OfflineFaceDetector] (ML Kit) and [FaceDetLiteDetector] (LiteRT) are
 * interchangeable at run time — the whole point of the 0.1.4 detector bench is that a session
 * differs from the previous one in exactly one variable.
 *
 * Implementations are **not** thread-safe: a TFLite `Interpreter` is not, and ML Kit
 * serialises internally, which is the cost the two-stage pipeline set out to remove. Every
 * detect worker therefore owns its own instance.
 */
/**
 * One detection, in the detect bitmap's own coordinate space.
 *
 * [score] is the detector's own confidence and is **null for ML Kit**, whose face API returns
 * no such number at all — the nullability is the honest shape of the two backends, not a
 * convenience. Anything that gates on it must therefore decide what "unknown" means rather
 * than defaulting it to 1.0 and quietly treating ML Kit as certain.
 */
data class DetectedFace(
    val bounds: Rect,
    val score: Float? = null,
)

interface SubjectFaceDetector : AutoCloseable {

    /** Detections in [bitmap]'s own coordinate space. Empty when nothing is found. */
    suspend fun detect(bitmap: Bitmap): List<DetectedFace>

    /**
     * How the detector describes itself in `perf_report.json` — backend, and anything that
     * changes what the numbers mean (tile count, delegate actually in use after fallback).
     */
    val diagnostics: Map<String, Any>
        get() = emptyMap()
}
