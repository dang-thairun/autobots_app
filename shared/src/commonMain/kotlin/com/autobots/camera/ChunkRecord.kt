package com.autobots.camera

/**
 * One extracted face JPEG from a video chunk.
 */
data class ExtractedFaceImage(
    val fileName: String,
    val sizeBytes: Long,
    val absolutePath: String,
)

enum class ChunkProcessStatus {
    Pending,
    Processing,
    Done,
    Failed,
}

/**
 * Session log entry for one recorded video chunk.
 */
data class ChunkRecord(
    val index: Int,
    val videoFileName: String,
    val videoAbsolutePath: String,
    val recordedAtEpochMs: Long,
    val resolution: StreamResolution,
    val recordDurationMs: Long,
    val videoSizeBytes: Long,
    val targetVideoBytes: Long = ChunkRecordingProgress.DEFAULT_CHUNK_TARGET_BYTES,
    val status: ChunkProcessStatus = ChunkProcessStatus.Pending,
    val processDurationMs: Long = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val extractedImages: List<ExtractedFaceImage> = emptyList(),
) {
    val recordDurationSec: Long get() = recordDurationMs / 1000L

    val isPartialChunk: Boolean
        get() = videoSizeBytes < targetVideoBytes

    val imagesTotalBytes: Long
        get() = extractedImages.sumOf { it.sizeBytes }

    val extractSummary: String
        get() = when (status) {
            ChunkProcessStatus.Pending -> "Waiting to extract"
            ChunkProcessStatus.Processing -> "Extracting…"
            ChunkProcessStatus.Failed -> "Extract failed"
            ChunkProcessStatus.Done -> when {
                facesKept > 0 -> {
                    "Extract $facesKept faces · ${processDurationMs / 1000}s · ${formatChunkBytes(imagesTotalBytes)}"
                }
                else -> "No face · ${processDurationMs / 1000}s"
            }
        }
}
