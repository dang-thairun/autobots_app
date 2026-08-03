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
)
