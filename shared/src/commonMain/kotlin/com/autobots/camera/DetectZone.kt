package com.autobots.camera

/**
 * The part of the frame a detection has to fall inside to count.
 *
 * PRD calls this the **Capture Zone** — the lane the operator points the phone at, as opposed
 * to everything else the lens happens to see: marshals, spectators, runners in the far lane.
 *
 * Stored **normalised to 0..1**, never in pixels. The test itself happens in detect space (a
 * 640-wide bitmap), the operator draws it against the source resolution, and live capture uses
 * a third size again — normalised coordinates are the only form that survives all three, and
 * they keep a zone meaningful when the same setup is reused on a clip of another resolution.
 */
data class DetectZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isFullFrame: Boolean
        get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /**
     * Whether a detection belongs to this zone, judged by the **centre** of its box.
     *
     * Not full containment: a runner close enough to fill much of the frame would fail that
     * test at any zone edge, which is backwards — the closer the subject, the more the gate
     * would reject. The centre point has no such bias.
     */
    fun containsCentre(boxLeft: Int, boxTop: Int, boxRight: Int, boxBottom: Int, frameWidth: Int, frameHeight: Int): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0) return true
        val cx = (boxLeft + boxRight) / 2f / frameWidth
        val cy = (boxTop + boxBottom) / 2f / frameHeight
        return cx >= left && cx <= right && cy >= top && cy <= bottom
    }

    fun xIn(frameWidth: Int): Int = (left * frameWidth).toInt()
    fun yIn(frameHeight: Int): Int = (top * frameHeight).toInt()
    fun widthIn(frameWidth: Int): Int = (width * frameWidth).toInt()
    fun heightIn(frameHeight: Int): Int = (height * frameHeight).toInt()

    /** `500 · 100 · 400×500` against a given source size — how the operator reads a zone. */
    fun pixelSummary(frameWidth: Int, frameHeight: Int): String =
        "${xIn(frameWidth)} · ${yIn(frameHeight)} · ${widthIn(frameWidth)}×${heightIn(frameHeight)}"

    companion object {
        val FULL = DetectZone(0f, 0f, 1f, 1f)

        /**
         * Smallest zone the editor will produce, per side.
         *
         * A stray drag can otherwise leave a zone a few pixels wide, and the session then
         * keeps nothing at all while every other indicator says the pipeline is healthy —
         * the quietest way this feature can fail.
         */
        const val MIN_SIDE = 0.1f

        /** Clamped to the frame, ordered, and never smaller than [MIN_SIDE]. */
        fun of(left: Float, top: Float, right: Float, bottom: Float): DetectZone {
            var l = minOf(left, right).coerceIn(0f, 1f)
            var t = minOf(top, bottom).coerceIn(0f, 1f)
            var r = maxOf(left, right).coerceIn(0f, 1f)
            var b = maxOf(top, bottom).coerceIn(0f, 1f)
            if (r - l < MIN_SIDE) {
                if (l + MIN_SIDE <= 1f) r = l + MIN_SIDE else l = r - MIN_SIDE
            }
            if (b - t < MIN_SIDE) {
                if (t + MIN_SIDE <= 1f) b = t + MIN_SIDE else t = b - MIN_SIDE
            }
            return DetectZone(l, t, r, b)
        }

        /** A sensible starting rectangle when the operator first opens the editor. */
        val DEFAULT = DetectZone(0.2f, 0.15f, 0.8f, 0.85f)
    }
}
