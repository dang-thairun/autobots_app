package com.autobots.camera

/**
 * Operator-selectable capture mode (Flow 7 in architecture.md).
 * Factory default = Standard.
 */
enum class CaptureMode {
    /** Lean Burst Keep-All (~3 Kept Photos). */
    Standard,

    /** Max sensor resolution, 1 Kept Photo per Passage. */
    MaxSensor,
}
