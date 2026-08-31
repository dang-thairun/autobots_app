package com.autobots.camera.capture

import java.io.File

/**
 * Metadata emitted when a video chunk file is finalized (full or partial on Stop).
 */
data class ChunkCaptureMeta(
    val index: Int,
    val file: File,
    val recordedAtEpochMs: Long,
    val recordDurationMs: Long,
    val videoSizeBytes: Long,
    /**
     * Where this chunk starts inside the source video, or 0 for live capture (each chunk *is*
     * the source). The splitter already computes it to rebase every chunk's PTS to zero;
     * carrying it out is what lets `chunks.csv` map a box back onto the original file after the
     * chunk itself has been deleted.
     */
    val sourceOffsetUs: Long = 0L,
)
