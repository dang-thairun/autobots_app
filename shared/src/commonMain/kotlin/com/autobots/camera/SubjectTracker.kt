package com.autobots.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A detection in normalised 0..1 frame coordinates, so a track survives a change of source. */
data class TrackedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Who is who, across frames.
 *
 * **The problem this exists for.** Until now every frame was judged alone and the pipeline had
 * no notion of a person persisting through time. Dedup stood in for one: a one-second window
 * kept its three best frames, on the assumption that a second of footage is one runner going
 * past. In a marathon that assumption fails constantly. Two runners in the same second are one
 * window, so all three keepers can be the nearer of them and the other is never photographed at
 * all — the pipeline reports a healthy yield the whole time.
 *
 * Windowing by track instead of by clock fixes exactly that, and nothing else here would.
 *
 * **Offline is the easy case, and this is offline.** A live tracker sees each frame once and
 * commits; this one runs over a whole chunk that has already been sorted by PTS, after every
 * detection is in hand. That is why a plain IOU tracker is enough — there is no latency budget
 * to defend and no partial state to guess from.
 *
 * ### Matching, in two passes
 *
 * A runner at 3 m/s crossing the frame in a second moves further between two 120 ms samples
 * than their own body is wide, so consecutive detections of one person routinely have **zero
 * overlap**. Matching on raw IOU alone would therefore split every fast runner into a string of
 * one-frame tracks — the failure would look like the tracker working, since every frame gets an
 * id. So:
 *
 *  1. **IOU against the predicted box**, not the last one. Each track carries the per-second
 *     velocity of its centre and is advanced to where it should be now before comparison.
 *  2. **Centre distance** for whatever pass 1 left unmatched, gated on the two boxes being a
 *     similar size — this is what recovers the runner who accelerated, or whose box the
 *     detector drew differently between frames.
 *
 * Both passes are greedy over the best available pair, which is sufficient at the handful of
 * simultaneous subjects a lane produces and avoids an assignment solver for no measurable gain.
 */
class SubjectTracker(
    /** Overlap with the *predicted* box that counts as the same person. */
    private val iouThreshold: Float = DEFAULT_IOU,
    /** Centre distance, in frame widths, that pass 2 will still match across. */
    private val maxCentreDistance: Float = DEFAULT_CENTRE_DISTANCE,
    /**
     * How long a track survives with nothing matched to it.
     *
     * At the 120 ms sample interval this is five missed frames. Long enough to ride out a
     * runner passing behind a marshal or a sign, short enough that the next runner along the
     * same line does not inherit their identity.
     */
    private val maxGapUs: Long = DEFAULT_MAX_GAP_US,
) {
    private class Track(
        val id: Int,
        var box: TrackedBox,
        var lastSeenUs: Long,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var hits: Int = 1,
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    /** Track ids for [boxes], positionally. Call once per frame, in ascending [timestampUs]. */
    fun assign(timestampUs: Long, boxes: List<TrackedBox>): List<Int> {
        tracks.removeAll { timestampUs - it.lastSeenUs > maxGapUs }
        if (boxes.isEmpty()) return emptyList()

        val assigned = IntArray(boxes.size) { UNASSIGNED }
        val taken = HashSet<Int>()

        // Pass 1 — overlap with where each track should be by now.
        greedyMatch(assigned, taken, boxes) { track, box ->
            val dtSec = (timestampUs - track.lastSeenUs) / 1_000_000f
            val predicted = track.box.shifted(track.vx * dtSec, track.vy * dtSec)
            iou(predicted, box).takeIf { it >= iouThreshold }
        }

        // Pass 2 — proximity, for the ones movement outran.
        greedyMatch(assigned, taken, boxes) { track, box ->
            if (!similarSize(track.box, box)) return@greedyMatch null
            val d = distance(track.box, box)
            if (d > maxCentreDistance) null else 1f - d / maxCentreDistance
        }

        for (i in boxes.indices) {
            if (assigned[i] != UNASSIGNED) continue
            val track = Track(nextId++, boxes[i], timestampUs)
            tracks.add(track)
            assigned[i] = track.id
        }
        return assigned.toList()
    }

    /**
     * Repeatedly commit the single best track/box pair [score] still offers.
     *
     * Recomputing after each commit is what makes it greedy rather than first-come: a box that
     * two tracks both want goes to whichever wants it most, and the loser is free to take its
     * own second choice on the next turn instead of being crowded out by iteration order.
     */
    private inline fun greedyMatch(
        assigned: IntArray,
        taken: MutableSet<Int>,
        boxes: List<TrackedBox>,
        score: (Track, TrackedBox) -> Float?,
    ) {
        while (true) {
            var bestTrack: Track? = null
            var bestBox = -1
            var best = 0f
            for (track in tracks) {
                if (track.id in taken) continue
                for (i in boxes.indices) {
                    if (assigned[i] != UNASSIGNED) continue
                    val s = score(track, boxes[i]) ?: continue
                    if (s > best) {
                        best = s
                        bestTrack = track
                        bestBox = i
                    }
                }
            }
            val track = bestTrack ?: return
            update(track, boxes[bestBox], assigned, bestBox)
            taken.add(track.id)
        }
    }

    private fun update(track: Track, box: TrackedBox, assigned: IntArray, index: Int) {
        assigned[index] = track.id
        track.box = box
        track.hits++
    }

    /**
     * Velocity is refreshed only once the whole frame is matched.
     *
     * Doing it inside [update] would let a track's new velocity influence the prediction used
     * for a later pair in the same frame, which makes the result depend on match order.
     */
    fun commit(timestampUs: Long, boxes: List<TrackedBox>, ids: List<Int>) {
        for ((i, id) in ids.withIndex()) {
            val track = tracks.firstOrNull { it.id == id } ?: continue
            val dtSec = (timestampUs - track.lastSeenUs) / 1_000_000f
            if (dtSec > 0f && track.hits > 1) {
                val box = boxes[i]
                // Smoothed, because one detector jitter should not send the prediction flying.
                track.vx = track.vx * (1f - VELOCITY_GAIN) +
                    ((box.centreX - track.box.centreX) / dtSec) * VELOCITY_GAIN
                track.vy = track.vy * (1f - VELOCITY_GAIN) +
                    ((box.centreY - track.box.centreY) / dtSec) * VELOCITY_GAIN
            }
            track.lastSeenUs = timestampUs
        }
    }

    private fun TrackedBox.shifted(dx: Float, dy: Float) =
        TrackedBox(left + dx, top + dy, right + dx, bottom + dy)

    private fun similarSize(a: TrackedBox, b: TrackedBox): Boolean {
        val ha = a.height
        val hb = b.height
        if (ha <= 0f || hb <= 0f) return false
        val ratio = max(ha, hb) / min(ha, hb)
        return ratio <= MAX_SIZE_RATIO
    }

    private fun distance(a: TrackedBox, b: TrackedBox): Float {
        val dx = a.centreX - b.centreX
        val dy = a.centreY - b.centreY
        return sqrt(dx * dx + dy * dy)
    }

    private fun iou(a: TrackedBox, b: TrackedBox): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = abs(a.width * a.height) + abs(b.width * b.height) - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val UNASSIGNED = 0

        /**
         * Low on purpose.
         *
         * This is overlap against a *predicted* box, and the prediction is only as good as one
         * frame of velocity. Demanding the 0.5 a live tracker would use throws away correct
         * matches whenever the runner changed pace, and the cost of that is a split track --
         * which shows up as extra photos of one person, the exact thing being fixed.
         */
        const val DEFAULT_IOU = 0.2f

        /** In frame widths. A runner covers well under a fifth of the frame in 120 ms. */
        const val DEFAULT_CENTRE_DISTANCE = 0.18f

        /** Five missed samples at the 120 ms interval. */
        const val DEFAULT_MAX_GAP_US = 600_000L

        /** Pass 2 will not match boxes whose heights differ by more than this factor. */
        private const val MAX_SIZE_RATIO = 2.0f

        /** How much of a new velocity estimate replaces the old one per frame. */
        private const val VELOCITY_GAIN = 0.5f
    }
}
