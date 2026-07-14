package com.autobots.camera.detection

/**
 * Axis-aligned face box in normalized image coordinates (0..1).
 * Origin top-left of the analysis frame.
 */
data class NormalizedFaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1f,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
}

data class FaceFrameResult(
    val faces: List<NormalizedFaceBox>,
    /** Index into [faces] of the Subject Face (largest area), or null if empty. */
    val subjectIndex: Int?,
    /** Face Proximity of the Subject Face (area fraction of the frame). */
    val proximity: Float,
)

object SubjectFaceSelector {
    fun select(faces: List<NormalizedFaceBox>): FaceFrameResult {
        if (faces.isEmpty()) {
            return FaceFrameResult(faces = emptyList(), subjectIndex = null, proximity = 0f)
        }
        val subjectIndex = faces.indices.maxBy { faces[it].area }
        val subject = faces[subjectIndex]
        return FaceFrameResult(
            faces = faces,
            subjectIndex = subjectIndex,
            proximity = subject.area.coerceIn(0f, 1f),
        )
    }
}
