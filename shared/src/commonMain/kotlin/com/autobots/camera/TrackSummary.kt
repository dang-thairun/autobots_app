package com.autobots.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One person's whole passage through a chunk, as a single row.
 *
 * Everything here was already being computed inside [SubjectTracker] and thrown away when the
 * photos were chosen. Keeping it answers the questions selection cannot: **how many people went
 * past**, how fast, which way, and which of them the pipeline actually photographed.
 *
 * ### Read the count carefully
 *
 * A track is not a person. It is *a thing that was continuously visible*, and the difference
 * matters in three directions, all of which inflate a naive count:
 *
 *  - **Anyone standing still is tracked too.** A marshal at the roadside is a person and the
 *    detector is right to report them — but they appear in every chunk, so one marshal can
 *    produce twenty-odd tracks across a session. [movedThrough] is the filter for this, and it
 *    is why velocity is recorded rather than inferred later.
 *  - **Chunks are separate files.** A runner crossing a chunk boundary is two tracks, because
 *    the tracker is reset per chunk and has no way to know otherwise.
 *  - **There is no re-identification.** Someone lost behind a sign for longer than the
 *    tracker's gap comes back as a new person. The tracker matches on position and motion only;
 *    it never looks at a face or a bib.
 *
 * So [SubjectTracker] counts **passages, not people**, and it counts them high. That is the
 * safe direction for a photo pipeline — over-counting costs a number, under-counting would mean
 * a runner nobody photographed and nobody noticed — but the number must be read as an upper
 * bound, not as attendance.
 */
data class TrackSummary(
    /** Unique within its chunk only. Pair it with the chunk index to identify a passage. */
    val id: Int,
    val firstSeenUs: Long,
    val lastSeenUs: Long,
    /** Frames this track was matched in — how much evidence stands behind the row. */
    val frames: Int,
    val firstCentreX: Float,
    val firstCentreY: Float,
    val lastCentreX: Float,
    val lastCentreY: Float,
    /** Per second, in normalised frame widths/heights. */
    val velocityX: Float,
    val velocityY: Float,
    /** Nearest this track ever came to the middle of the frame. 0 = dead centre. */
    val closestToCentre: Float,
    /** Mean box height as a fraction of frame height — how close to the lens they ran. */
    val meanHeight: Float,
    /** True once this track produced at least one kept photo. */
    val captured: Boolean = false,
    val photos: Int = 0,
) {
    val durationUs: Long get() = lastSeenUs - firstSeenUs

    /** How far the centre travelled, start to end, in normalised units. */
    val displacement: Float
        get() {
            val dx = lastCentreX - firstCentreX
            val dy = lastCentreY - firstCentreY
            return sqrt(dx * dx + dy * dy)
        }

    val speed: Float get() = sqrt(velocityX * velocityX + velocityY * velocityY)

    /** Screen degrees: 0 = moving right, 90 = moving down, 180 = left, 270 = up. */
    val directionDegrees: Float
        get() = ((atan2(velocityY, velocityX) * 180f / PI_F) + 360f) % 360f

    /**
     * Which way they went, in the terms a lane is set up in.
     *
     * Horizontal wins ties because a runner passing a fixed camera crosses the frame; vertical
     * movement mostly means approaching or receding, which is the weaker signal of the two.
     */
    val directionLabel: String
        get() = when {
            !movedThrough -> "static"
            abs(velocityX) >= abs(velocityY) -> if (velocityX >= 0f) "L→R" else "R→L"
            velocityY >= 0f -> "toward"
            else -> "away"
        }

    /**
     * Whether this track actually went somewhere.
     *
     * The threshold is on **displacement**, not speed: a marshal shifting their weight has a
     * non-zero instantaneous velocity all afternoon and never leaves their spot, and it is
     * leaving the spot that separates a runner from a bystander. A tenth of the frame is a
     * deliberately low bar — the aim is to exclude the stationary, not to judge pace.
     */
    val movedThrough: Boolean
        get() = displacement >= MOVED_THROUGH_DISPLACEMENT

    /**
     * Moved through **and** was near enough for the movement to have been a run.
     *
     * [movedThrough] alone counts the crowd. Measured on the asicsmeta finish chute: the four
     * tracks it called "L→R" against forty going the other way were not runners doubling back,
     * they were **spectators behind the barrier taking a couple of steps** — all of them in the
     * top tenth of the frame at [meanHeight] 0.13–0.19, moving 0.085–0.156, just over the
     * displacement bar. Every track that actually produced a photograph measured 0.32–0.81.
     *
     * Nothing sat between the two groups, so [RUNNER_MIN_HEIGHT] is set in the middle of an
     * empty gap rather than on a slope — but it is **one camera angle's gap**. A wider lens or
     * a lane further from the kerb would put real runners lower, and this would then discard
     * them silently. [movedThrough] is still reported alongside so the raw figure stays
     * visible, and `tracks.csv` keeps `meanHeight` per row so the cut can be re-drawn.
     *
     * **Being photographed counts as having moved through.** [movedThrough] measures
     * displacement, and displacement needs two sightings — so a track seen in a single sampled
     * frame scores exactly zero however obviously it was a runner. That is not a rare corner:
     * a runner close to the lens crosses the frame in well under the 120 ms sample interval, so
     * one frame is all there ever was. On the same clip this excluded **9 of the 33 tracks the
     * pipeline had photographs of** — a count reporting 40 people while the album held pictures
     * of 49, with the evidence sitting right there. A frame that cleared the face, size,
     * sharpness and person gates is better evidence of a runner than any displacement figure.
     */
    val likelyRunner: Boolean
        get() = meanHeight >= RUNNER_MIN_HEIGHT && (movedThrough || captured)

    companion object {
        private const val PI_F = 3.1415927f

        /** @see movedThrough */
        const val MOVED_THROUGH_DISPLACEMENT = 0.10f

        /** @see likelyRunner */
        const val RUNNER_MIN_HEIGHT = 0.25f
    }
}
