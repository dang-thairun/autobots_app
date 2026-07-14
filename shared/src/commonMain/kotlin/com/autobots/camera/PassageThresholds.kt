package com.autobots.camera

/**
 * Domain thresholds for Passage arm/fire (see CONTEXT.md).
 * Operator UI sliders can override at runtime for field tuning.
 */
object PassageThresholds {
    /** Original doc default — often too high for half/full-body framing. */
    const val ARM_DOC_DEFAULT: Float = 0.10f
    const val FIRE_DOC_DEFAULT: Float = 0.40f

    /** Sensible starting point for half-body / full-body stills. */
    const val ARM_HALF_BODY: Float = 0.04f
    const val FIRE_HALF_BODY: Float = 0.10f

    fun armRelease(arm: Float): Float = (arm * 0.7f).coerceAtLeast(0.005f)
}
