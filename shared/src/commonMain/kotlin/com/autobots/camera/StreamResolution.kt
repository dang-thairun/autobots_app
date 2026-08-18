package com.autobots.camera

/**
 * Video recording target for Plan B pipeline (v0.1.2).
 * Compare FHD vs UHD in the field before picking a default.
 */
enum class StreamResolution(val label: String, val width: Int, val height: Int) {
    Fhd("1080p", 1920, 1080),
    Uhd("4K", 3840, 2160),
    ;

    /** Short status-card form: FHD / 4K. Chips keep [label] (`1080p` / `4K`). */
    val compactLabel: String
        get() = when (this) {
            Fhd -> "FHD"
            Uhd -> "4K"
        }

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

/**
 * Short resolution tag for status cards.
 * 1080-high → `FHD`, 2160-high → `4K`, anything else → `{shortSide}p`.
 */
fun compactResolutionLabel(
    width: Int?,
    height: Int?,
    rotationDegrees: Int = 0,
): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val displayW = if (rotated) height else width
    val displayH = if (rotated) width else height
    return when (val shortSide = minOf(displayW, displayH)) {
        1080 -> "FHD"
        2160 -> "4K"
        else -> "${shortSide}p"
    }
}
