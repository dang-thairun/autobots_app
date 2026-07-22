package com.autobots.camera

/**
 * How focus is obtained for still capture (Flow 15 / 10).
 * Tripod product default = [Fixed].
 */
enum class FocusStrategy {
    /**
     * Focus distance locked at setup for the Fire sweet-spot.
     * Detector does not drive AF each Passage — only AE / timing.
     */
    Fixed,

    /**
     * Fallback: hardware AF+AE on Subject Face after Arm.
     * Use when the tripod moves or Fixed is not calibrated yet.
     */
    FaceAf,
}
