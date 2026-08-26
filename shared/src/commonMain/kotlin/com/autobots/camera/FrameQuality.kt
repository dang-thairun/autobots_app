package com.autobots.camera

import kotlin.math.min

/**
 * How good a photograph a candidate frame is, as one number.
 *
 * **Why this exists.** Up to 0.1.6 a dedup window kept its three sharpest
 * frames and nothing else entered the decision:
 *
 * ```kotlin
 * window.sortedByDescending { it.sharpness }   // every other measurement discarded
 * ```
 *
 * That is not what makes a race photo sellable. The pipeline already measures four other
 * things and then throws all of them away at the moment it chooses: how big the runner is,
 * how confident the detector was, where in the frame they are, and whether they are clear of
 * the edges. A tack-sharp frame of a runner half out of shot at the frame border outranked a
 * marginally softer one of the same runner centred and whole — and the second is the one
 * that sells.
 *
 * **Every term is normalised to 0..1 before weighting**, because they are measured in
 * incomparable units: Laplacian variance is unbounded above (65 to 300+ in the field),
 * ratios are fractions, confidence is a probability. Without normalisation sharpness would
 * dominate by three orders of magnitude and the other four terms would be decoration.
 *
 * **The weights below are not measured.** They are a starting point argued from what a buyer
 * looks at, and they are the one part of this file that should not be trusted until a real
 * race has been through it. That is why [score] returns every component and not just the
 * total: `photos.csv` records each one per kept photo, so the weights can be re-fitted
 * against evidence instead of re-argued. A threshold cannot be tuned against evidence that
 * was never kept, and neither can a weight.
 *
 * Ranking happens *within* a one-second dedup window, so only the ordering of frames of the
 * same runner seconds apart matters — the absolute value is diagnostic, not a gate.
 */
object FrameQuality {

    /**
     * Sharpness stays the largest single term.
     *
     * It is the only one with a threshold tuned against measured field data
     * (`MIN_SHARPNESS_UHD = 65.0`, from the v0.1.4 UHD runs), it is the defect a buyer
     * notices first, and it is the one that cannot be fixed after the fact. It is dominant
     * but no longer absolute: the remaining 0.60 can outvote it when four other things agree.
     */
    const val W_SHARPNESS = 0.40f

    /** A bigger runner is a better photograph, up to the point where the crop is already good. */
    const val W_SUBJECT_SIZE = 0.20f

    /** Composition. Cheap to measure, and the difference between a photo and a snapshot. */
    const val W_CENTRE = 0.15f

    /** How sure the detector was it was looking at a person at all. */
    const val W_CONFIDENCE = 0.15f

    /** Clear of the frame edges — a runner cropped at the knee is not sellable at any sharpness. */
    const val W_FRAMING = 0.10f

    /**
     * Subject height worth full marks, as a multiple of the mode's minimum.
     *
     * At UHD the minimum is 0.030 of frame height, so full marks land at 0.12 — a face
     * filling an eighth of the frame, which is already a tight portrait of a runner. Beyond
     * that, more size is not more sellable and the term saturates rather than continuing to
     * outbid sharpness.
     */
    const val SIZE_TARGET_MULTIPLE = 4f

    /**
     * Distance from every frame edge worth full marks, as a fraction of the short side.
     *
     * 0.10 is deliberately loose. The point is not to reward dead-centre framing — [centreTerm]
     * already does that — but to separate "comfortably inside the frame" from "touching the
     * edge", which is the case that produces a half runner.
     */
    const val FRAMING_TARGET = 0.10f

    /**
     * One frame's quality, with the workings kept.
     *
     * [confidence] is null on detectors that do not report one (ML Kit reports no score at
     * all). It is not defaulted to a neutral value — that would quietly penalise or reward
     * a whole backend by a fixed amount. The weight is dropped and the rest renormalised, so
     * a frame from ML Kit and a frame from LiteRT are scored on the same 0..1 scale.
     */
    data class Score(
        val total: Float,
        val sharpness: Float,
        val size: Float,
        val centre: Float,
        val confidence: Float?,
        val framing: Float,
    )

    /**
     * Laplacian variance → 0..1, saturating.
     *
     * `s / (s + floor)` puts a frame exactly on the reject threshold at 0.5, double it at
     * 0.67, and four times it at 0.8. The curve matters more than it looks: field values run
     * 65 to 300+, and any linear normalisation would either clip the top or squash the
     * bottom, where the frames actually being chosen between live.
     */
    fun sharpnessTerm(sharpness: Double, floor: Double): Float {
        if (sharpness <= 0.0 || floor <= 0.0) return 0f
        return (sharpness / (sharpness + floor)).toFloat().coerceIn(0f, 1f)
    }

    /** Subject height ratio → 0..1, linear to [SIZE_TARGET_MULTIPLE]× the minimum, then flat. */
    fun sizeTerm(subjectRatio: Float, minRatio: Float): Float {
        if (subjectRatio <= 0f || minRatio <= 0f) return 0f
        val target = minRatio * SIZE_TARGET_MULTIPLE
        return (subjectRatio / target).coerceIn(0f, 1f)
    }

    /**
     * Offset from the target centre → 0..1, where 0 offset scores 1.
     *
     * [centreOffset] is already normalised so that 1.0 means "as far from the centre as it is
     * possible to be and still be inside the region", which makes this a plain inversion.
     */
    fun centreTerm(centreOffset: Float): Float = (1f - centreOffset).coerceIn(0f, 1f)

    /**
     * Detector confidence → 0..1, rescaled to the detector's own reachable ceiling.
     *
     * `face_det_lite` cannot output above 0.853 — its quantised heatmap simply has no
     * codepoint higher. Scoring a 0.85 face as 0.85 would permanently dock every frame from
     * that backend by 15% against one that can reach 1.0, for a reason that has nothing to do
     * with the photograph.
     */
    fun confidenceTerm(score: Float, ceiling: Float): Float {
        if (ceiling <= 0f) return 0f
        return (score / ceiling).coerceIn(0f, 1f)
    }

    /** Smallest edge margin → 0..1, linear to [FRAMING_TARGET], then flat. */
    fun framingTerm(edgeMargin: Float): Float =
        (edgeMargin / FRAMING_TARGET).coerceIn(0f, 1f)

    /**
     * Weighted total, with any absent term's weight removed rather than filled in.
     *
     * @param confidenceCeiling the highest value [confidence] could ever take on this backend.
     */
    fun score(
        sharpness: Double,
        sharpnessFloor: Double,
        subjectRatio: Float,
        minSubjectRatio: Float,
        centreOffset: Float,
        confidence: Float?,
        confidenceCeiling: Float,
        edgeMargin: Float,
    ): Score {
        val sharp = sharpnessTerm(sharpness, sharpnessFloor)
        val size = sizeTerm(subjectRatio, minSubjectRatio)
        val centre = centreTerm(centreOffset)
        val framing = framingTerm(edgeMargin)
        val conf = confidence?.let { confidenceTerm(it, confidenceCeiling) }

        var weighted = sharp * W_SHARPNESS +
            size * W_SUBJECT_SIZE +
            centre * W_CENTRE +
            framing * W_FRAMING
        var weight = W_SHARPNESS + W_SUBJECT_SIZE + W_CENTRE + W_FRAMING
        if (conf != null) {
            weighted += conf * W_CONFIDENCE
            weight += W_CONFIDENCE
        }

        return Score(
            total = if (weight <= 0f) 0f else (weighted / weight).coerceIn(0f, 1f),
            sharpness = sharp,
            size = size,
            centre = centre,
            confidence = conf,
            framing = framing,
        )
    }

    /**
     * How far a box's centre sits from the centre of [region], as 0..1.
     *
     * Normalised by the region's half-extent per axis and then taken as the larger of the
     * two, not the diagonal distance. A runner 90% of the way to the left edge but vertically
     * centred is badly composed, and a diagonal norm would score that ~0.64 — comfortably
     * mid-table. The per-axis max calls it 0.9, which is what it is.
     *
     * All coordinates share one space; the caller decides whether that is the whole frame or
     * the capture zone.
     */
    fun centreOffsetOf(
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        regionLeft: Float,
        regionTop: Float,
        regionRight: Float,
        regionBottom: Float,
    ): Float {
        val halfW = (regionRight - regionLeft) / 2f
        val halfH = (regionBottom - regionTop) / 2f
        if (halfW <= 0f || halfH <= 0f) return 0f
        val dx = (boxLeft + boxRight) / 2f - (regionLeft + regionRight) / 2f
        val dy = (boxTop + boxBottom) / 2f - (regionTop + regionBottom) / 2f
        val ox = kotlin.math.abs(dx) / halfW
        val oy = kotlin.math.abs(dy) / halfH
        return kotlin.math.max(ox, oy).coerceIn(0f, 1f)
    }

    /**
     * Smallest gap between a box and any frame edge, as a fraction of the frame's short side.
     *
     * The short side is the normaliser because it is the axis a runner is cropped on first,
     * and using each axis's own extent would call a 20 px gap "generous" horizontally and
     * "tight" vertically in the same frame.
     */
    fun edgeMarginOf(
        boxLeft: Float,
        boxTop: Float,
        boxRight: Float,
        boxBottom: Float,
        frameWidth: Float,
        frameHeight: Float,
    ): Float {
        if (frameWidth <= 0f || frameHeight <= 0f) return 0f
        val shortSide = min(frameWidth, frameHeight)
        val gap = minOf(
            boxLeft,
            boxTop,
            frameWidth - boxRight,
            frameHeight - boxBottom,
        )
        return (gap / shortSide).coerceAtLeast(0f)
    }
}
