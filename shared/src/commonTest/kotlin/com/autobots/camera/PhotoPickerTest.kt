package com.autobots.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/** The pipeline's own values, so a change there that breaks the picker shows up here. */
private const val WINDOW_US = 1_000_000L
private const val MAX_PER_WINDOW = 3

private fun pick(vararg c: PickCandidate) = pickKeepers(c.toList(), WINDOW_US, MAX_PER_WINDOW)

class PhotoPickerTest {

    @Test
    fun keepsEverythingUnderTheBudget() {
        val kept = pick(
            PickCandidate(0L, trackId = 1, quality = 0.4f),
            PickCandidate(120_000L, trackId = 1, quality = 0.9f),
        )
        assertEquals(setOf(0L, 120_000L), kept)
    }

    @Test
    fun keepsTheBestThreeOfAWindow() {
        val kept = pick(
            PickCandidate(0L, 1, 0.1f),
            PickCandidate(120_000L, 1, 0.9f),
            PickCandidate(240_000L, 1, 0.5f),
            PickCandidate(360_000L, 1, 0.7f),
            PickCandidate(480_000L, 1, 0.2f),
        )
        assertEquals(setOf(120_000L, 240_000L, 360_000L), kept)
    }

    /** The bug the per-person window exists to fix: one budget was shared between two runners. */
    @Test
    fun eachPersonGetsTheirOwnBudget() {
        val near = (0..3).map { PickCandidate(it * 120_000L, trackId = 1, quality = 0.9f) }
        val far = (0..3).map { PickCandidate(it * 120_000L + 10L, trackId = 2, quality = 0.1f) }
        val kept = pickKeepers(near + far, WINDOW_US, MAX_PER_WINDOW)
        assertEquals(3, near.count { it.timestampUs in kept })
        assertEquals(3, far.count { it.timestampUs in kept })
    }

    @Test
    fun aNewWindowGetsANewBudget() {
        val first = (0..3).map { PickCandidate(it * 120_000L, 1, 0.5f) }
        val second = (0..3).map { PickCandidate(WINDOW_US + it * 120_000L, 1, 0.5f) }
        assertEquals(6, pickKeepers(first + second, WINDOW_US, MAX_PER_WINDOW).size)
    }

    /**
     * S3's whole point: a runner spanning a chunk edge is one track, so the budget must not
     * reset at the edge. Before S3 these six frames were two tracks and produced six photos.
     */
    @Test
    fun oneTrackAcrossAChunkEdgeSharesOneBudget() {
        val spanning = (0..5).map { PickCandidate(it * 120_000L, trackId = 1, quality = 0.5f) }
        assertEquals(3, pickKeepers(spanning, WINDOW_US, MAX_PER_WINDOW).size)
    }

    @Test
    fun emptyInEmptyOut() {
        assertEquals(emptySet(), pickKeepers(emptyList(), WINDOW_US, MAX_PER_WINDOW))
    }
}
