package com.autobots.camera

/**
 * Stream-grab output resolution (ImageAnalysis bind target).
 */
enum class StreamResolution {
    /** 1920×1080 */
    Hd1080,

    /** 3840×2160 — falls back if device cannot bind 4K analysis. */
    Uhd4K,
}
