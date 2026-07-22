package com.autobots.camera

import com.autobots.camera.detection.FaceFrameResult

/**
 * Fast Fire path (Flow 14 / 17): zone composition + short settle — not proximity % alone.
 */
object PassageFireEvaluator {

    /** Frames Subject center must stay inside Capture Zone before Fire. */
    const val ZONE_DWELL_FRAMES: Int = 2

    /** Minimum time armed before Fire (AE settle on tripod). */
    const val ARM_SETTLE_MS: Long = 100L

    data class Tracker(
        val zoneStreak: Int = 0,
        val armedAtMs: Long? = null,
    )

    fun evaluate(
        result: FaceFrameResult,
        armed: Boolean,
        nowMs: Long,
        minFaceSize: Float,
        zone: CaptureZone = CaptureZone.DEFAULT,
        tracker: Tracker = Tracker(),
    ): Pair<Boolean, Tracker> {
        val subject = result.subjectIndex?.let { result.faces.getOrNull(it) }
        val armedAt = when {
            armed && tracker.armedAtMs == null -> nowMs
            !armed -> null
            else -> tracker.armedAtMs
        }

        val inZone = subject != null &&
            result.proximity >= minFaceSize &&
            CaptureZoneEvaluator.isCenterInZone(subject, zone)

        val streak = if (inZone) tracker.zoneStreak + 1 else 0
        val settled = armed &&
            armedAt != null &&
            (nowMs - armedAt) >= ARM_SETTLE_MS

        val shouldFire = settled &&
            streak >= ZONE_DWELL_FRAMES &&
            subject != null

        return shouldFire to Tracker(zoneStreak = streak, armedAtMs = armedAt)
    }
}
