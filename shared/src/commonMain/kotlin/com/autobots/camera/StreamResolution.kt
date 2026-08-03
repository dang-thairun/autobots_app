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
        get() = when (this) {
            Fhd -> CHUNK_TARGET_FHD_BYTES
            Uhd -> CHUNK_TARGET_UHD_BYTES
        }

    val frameSampleIntervalMs: Long
        get() = when (this) {
            Fhd -> FRAME_SAMPLE_INTERVAL_FHD_MS
            Uhd -> FRAME_SAMPLE_INTERVAL_UHD_MS
        }

    companion object {
        const val CHUNK_TARGET_FHD_BYTES = 20L * 1024L * 1024L
        const val CHUNK_TARGET_UHD_BYTES = 50L * 1024L * 1024L
        const val FRAME_SAMPLE_INTERVAL_FHD_MS = 300L
        const val FRAME_SAMPLE_INTERVAL_UHD_MS = 120L
    }
}
