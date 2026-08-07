package com.autobots.camera

/**
 * Video recording target for Plan B pipeline (v0.1.2).
 * Compare FHD vs UHD in the field before picking a default.
 */
enum class StreamResolution(val label: String, val width: Int, val height: Int) {
    Fhd("1080p", 1920, 1080),
    Uhd("4K", 3840, 2160),
    ;

    val chunkTargetBytes: Long
        get() = CHUNK_TARGET_BYTES

    val frameSampleIntervalMs: Long
        get() = FRAME_SAMPLE_INTERVAL_MS

    companion object {
        const val CHUNK_TARGET_BYTES = 50L * 1024L * 1024L
        /** Frame sample interval for both 1080p and 4K — Worker 2 decodes every N ms. */
        const val FRAME_SAMPLE_INTERVAL_MS = 120L

        /** Map imported file dimensions to the pipeline profile (FHD vs UHD). */
        fun fromVideoDimensions(width: Int, height: Int, rotationDegrees: Int = 0): StreamResolution {
            val rotated = rotationDegrees == 90 || rotationDegrees == 270
            val displayW = if (rotated) height else width
            val displayH = if (rotated) width else height
            val longEdge = maxOf(displayW, displayH)
            // 2160p and above → UHD profile (lower sharpness threshold).
            return if (longEdge >= 2160) Uhd else Fhd
        }
    }
}
