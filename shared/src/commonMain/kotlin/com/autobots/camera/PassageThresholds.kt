package com.autobots.camera

/**
 * Domain thresholds for Passage arm/fire (see CONTEXT.md).
 * Operator UI sliders can override at runtime for field tuning.
 */
object PassageThresholds {
    /** Original doc default — often too high for half/full-body framing. */
    const val ARM_DOC_DEFAULT: Float = 0.10f
    const val FIRE_DOC_DEFAULT: Float = 0.40f

    /** Early Arm for short 1.5–3 s zones (tripod). */
    const val ARM_EARLY: Float = 0.025f

    /** Minimum face size floor for Fire (zone is the primary trigger). */
    const val FIRE_MIN_SIZE: Float = 0.06f

    /** @see ARM_EARLY */
    const val ARM_HALF_BODY: Float = ARM_EARLY

    /** @see FIRE_MIN_SIZE */
    const val FIRE_HALF_BODY: Float = FIRE_MIN_SIZE

    fun armRelease(arm: Float): Float = (arm * 0.7f).coerceAtLeast(0.005f)
}
