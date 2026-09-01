package com.autobots.camera.pipeline

import com.autobots.camera.PickCandidate
import com.autobots.camera.SubjectTracker
import com.autobots.camera.TrackSummary
import com.autobots.camera.pickKeepers

/**
 * Worker 2 — who each person is, and which of their frames to keep.
 *
 * ### Why this is not part of Worker 1
 *
 * Worker 1 can only ever see one chunk. Selection used to run at the end of each chunk, so a
 * runner still on screen when a chunk ended was closed off mid-passage, became a second person
 * in the next chunk, and was given a second budget of photos — 4.2% of tracks, measured in S0.2.
 * This class holds one [SubjectTracker] for the whole session and waits for the *person* to
 * finish instead of for the chunk: a track closes when nobody has matched it for
 * `SubjectTracker.maxGapUs`, which is what "they left the frame" actually means.
 *
 * ### What it costs
 *
 * A candidate cannot be deleted until its person is decided, so losing candidates stay on disk
 * a little longer — bounded by [retentionChunks]. See the constant's comment for the numbers.
 *
 * Not thread-safe and does not need to be: the coordinator drives it from a single consumer,
 * exactly like the chunk loop it sits behind.
 */
class PhotoSelector(
    private val windowUs: Long,
    private val maxPerWindow: Int,
    private val retentionChunks: Int,
    /** Sampling period of the frames, so every track can report how many of its own it missed. */
    sampleIntervalUs: Long,
) {

    /** One candidate, rebased onto the session clock and waiting for its person to finish. */
    private class Pending(
        val chunkIndex: Int,
        val sessionUs: Long,
        val candidate: SavedCandidate,
        val trackId: Int,
    )

    /** A kept photo and the chunk it came out of, which is what `photos.csv` keys on. */
    data class PhotoRow(val chunkIndex: Int, val photo: SavedPhoto)

    /** A finished passage and the chunk it *started* in — it may well have ended in a later one. */
    data class TrackRow(val chunkIndex: Int, val track: TrackSummary)

    data class Outcome(
        val photos: List<PhotoRow> = emptyList(),
        val tracks: List<TrackRow> = emptyList(),
        /** Candidates that lost their window and have been deleted. */
        val losers: Int = 0,
    )

    private val tracker = SubjectTracker(sampleIntervalUs = sampleIntervalUs)
    private val pending = ArrayList<Pending>()
    private val chunkOfTrack = HashMap<Int, Int>()
    private val photosOfTrack = HashMap<Int, Int>()
    private var latestChunk = -1

    /** Candidates still held for a person who has not finished going past. */
    val pendingCount: Int get() = pending.size

    /**
     * Take one chunk's observation and settle whoever finished during it.
     *
     * @param sessionOffsetUs where this chunk starts on the session clock. Chunk presentation
     *   timestamps restart near zero every chunk, and a tracker that spans chunks would read
     *   that as everybody teleporting back to the start of the session.
     */
    fun accept(observation: ChunkObservation, sessionOffsetUs: Long): Outcome {
        latestChunk = observation.chunkIndex
        val trackOfFrame = HashMap<Long, Int>()

        // Identity over **every sampled frame**, in capture order — not over the kept
        // candidates. Someone briefly too small or clipped still has to stay the same person
        // through those frames, and someone merely present has to keep their id through the
        // frames where somebody else was the subject.
        for (frame in observation.frames.sortedBy { it.timestampUs }) {
            val sessionUs = frame.timestampUs + sessionOffsetUs
            val ids = tracker.assign(sessionUs, frame.boxes)
            tracker.commit(sessionUs, frame.boxes, ids)
            ids.forEach { chunkOfTrack.putIfAbsent(it, observation.chunkIndex) }
            if (frame.subjectIndex >= 0) {
                ids.getOrNull(frame.subjectIndex)?.let { trackOfFrame[sessionUs] = it }
            }
        }

        for (candidate in observation.candidates) {
            val sessionUs = candidate.timestampUs + sessionOffsetUs
            pending.add(
                Pending(
                    chunkIndex = observation.chunkIndex,
                    sessionUs = sessionUs,
                    candidate = candidate,
                    trackId = trackOfFrame[sessionUs] ?: UNTRACKED,
                ),
            )
        }

        return harvest(tracker.drainClosed(), flushAll = false)
    }

    /**
     * Close every remaining person and settle everything still held.
     *
     * Call once, after the last [accept]. Retention buys nothing here — nothing more is coming.
     */
    fun finish(): Outcome = harvest(tracker.finish(), flushAll = true)

    private fun harvest(closed: List<TrackSummary>, flushAll: Boolean): Outcome {
        val photos = ArrayList<PhotoRow>()
        val tracks = ArrayList<TrackRow>()
        var losers = 0

        fun settle(group: List<Pending>) {
            if (group.isEmpty()) return
            val keep = pickKeepers(
                group.map { PickCandidate(it.sessionUs, it.trackId, it.candidate.quality.total) },
                windowUs = windowUs,
                maxPerWindow = maxPerWindow,
            )
            for (entry in group) {
                if (entry.sessionUs in keep) {
                    photos.add(PhotoRow(entry.chunkIndex, entry.toPhoto()))
                    photosOfTrack[entry.trackId] = (photosOfTrack[entry.trackId] ?: 0) + 1
                } else {
                    entry.candidate.file.delete()
                    losers++
                }
            }
        }

        for (track in closed) {
            val group = pending.filter { it.trackId == track.id }
            pending.removeAll(group.toSet())
            settle(group)
            // Stamp the outcome onto the passage that produced it. A track with captured=false
            // is the interesting row: someone went past and there is no photograph of them.
            val count = photosOfTrack.remove(track.id) ?: 0
            tracks.add(
                TrackRow(
                    chunkIndex = chunkOfTrack.remove(track.id) ?: latestChunk,
                    track = track.copy(captured = count > 0, photos = count),
                ),
            )
        }

        // What the tracker never named, plus anything a still-open track left behind chunks ago.
        // ponytail: a track open longer than the retention window has its earliest candidates
        // decided in a separate pass, so its windows re-anchor there. Only matters for someone
        // on screen for more than a whole chunk — a marshal, not a runner (median passage is 2
        // frames). Hold candidates for the whole session if that ever needs to be exact.
        val stale = if (flushAll) {
            pending.toList()
        } else {
            pending.filter { it.chunkIndex <= latestChunk - retentionChunks }
        }
        if (stale.isNotEmpty()) {
            pending.removeAll(stale.toSet())
            settle(stale)
        }

        return Outcome(photos, tracks, losers)
    }

    private fun Pending.toPhoto() = SavedPhoto(
        file = candidate.file,
        // Session time, not chunk time: track ids now span chunks, so a chunk-local timestamp
        // could not be joined back to `tracks.csv`. `chunks.csv` records the offset either way.
        timestampUs = sessionUs,
        sharpness = candidate.sharpness,
        subjectRatio = candidate.subjectRatio,
        score = candidate.score,
        quality = candidate.quality,
        trackId = trackId,
    )

    companion object {
        /** Bucket for candidates the tracker could not give an id to — windowed by clock. */
        const val UNTRACKED = 0

        /**
         * How long one person's dedup window is.
         *
         * A window *of that runner's frames*, not a second of the clock — see [pickKeepers].
         */
        const val DEDUP_WINDOW_US = 1_000_000L

        /**
         * Photos kept per dedup window.
         *
         * 0.1.2–0.1.3 kept the single sharpest frame, which turned a runner's whole pass in
         * front of the lens into one photo — 42 candidates became 7 in the UHD test. Keeping
         * three matches the Passage Outcome in CONTEXT.md (Keep-All Policy, ~3 per passage).
         */
        const val MAX_KEEP_PER_WINDOW = 3

        /**
         * How many chunks a losing candidate may outlive its own chunk.
         *
         * It is one number doing two jobs: the handoff queue's capacity, and therefore the
         * ceiling on candidates held on disk. At 1, roughly two chunks' worth are in flight —
         * ~14 files, under 70 MB even if a full-frame 4K JPEG runs to 5 MB.
         *
         * Raise it when selection needs to look further back than the person in front of it:
         * re-identification (PLAN §11 C), or re-running selection with a different threshold
         * on the device. Holding a whole 3.5-hour session would be 17–42 GB, for exactly the
         * same photos — which is why this is 1 and not "keep everything".
         */
        const val CANDIDATE_RETENTION_CHUNKS = 1
    }
}
