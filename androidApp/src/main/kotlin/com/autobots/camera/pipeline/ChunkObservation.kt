package com.autobots.camera.pipeline

import com.autobots.camera.FrameQuality
import com.autobots.camera.TrackedBox
import java.io.File

/** One frame's detections, normalised, with which of them the gates chose (-1 if none). */
data class Sighting(
    val timestampUs: Long,
    val boxes: List<TrackedBox>,
    val subjectIndex: Int,
)

/**
 * A candidate already on disk, waiting to be ranked against the rest of its window.
 *
 * No track id: identity belongs to [PhotoSelector] now. A candidate carries only what was
 * true of the frame itself, which is why Worker 1 can finish with it before anyone knows
 * whose frame it is.
 */
data class SavedCandidate(
    val file: File,
    val sharpness: Double,
    val timestampUs: Long,
    val subjectRatio: Float,
    val score: Float?,
    val quality: FrameQuality.Score,
)

/**
 * Everything Worker 1 saw in one chunk — and nothing it decided.
 *
 * This is the whole contract between the two workers. Worker 1 samples, detects, gates, scores
 * and writes JPEGs; it hands over what it observed and stops. Worker 2 owns identity and
 * selection, which is why a runner crossing a chunk edge can be decided once instead of twice.
 *
 * [timestampUs] on both lists is still the chunk's own presentation timestamp, which restarts
 * near zero in every chunk. Worker 2 rebases it, because only the coordinator knows where this
 * chunk sits in the session.
 */
data class ChunkObservation(
    val chunkIndex: Int,
    val frames: List<Sighting>,
    val candidates: List<SavedCandidate>,
)
