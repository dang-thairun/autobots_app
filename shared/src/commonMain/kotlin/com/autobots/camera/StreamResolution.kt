package com.autobots.camera

/**
 * Video recording target for Plan B pipeline (v0.1.2).
 * Compare FHD vs UHD in the field before picking a default.
 */
enum class StreamResolution(val label: String, val width: Int, val height: Int) {
    Fhd("1080p", 1920, 1080),
    Uhd("4K", 3840, 2160),
}
