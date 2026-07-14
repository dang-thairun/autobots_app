package com.autobots.camera

/**
 * Operator-selectable capture mode (ADR 0007).
 * Factory default = Standard.
 */
enum class CaptureMode {
    /** Lean Burst Keep-All (~3 Kept Photos). */
    Standard,

    /** Max sensor resolution, 1 Kept Photo per Passage. */
    MaxSensor,
}
