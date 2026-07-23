package com.autobots.camera

/**
 * How stills are produced after a face is seen.
 *
 * - [StillBurst] — Passage Gate + Capture Zone → [ImageCapture] burst (production).
 * - [StreamGrab] — grab JPEG from each [ImageAnalysis] frame while a face is in frame (spike).
 */
enum class CapturePipeline {
    StillBurst,
    StreamGrab,
}
