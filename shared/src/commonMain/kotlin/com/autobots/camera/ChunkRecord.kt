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
    val sessionId: String = "",
    val index: Int,
    val videoFileName: String,
    val videoAbsolutePath: String,
    val recordedAtEpochMs: Long,
    val resolution: StreamResolution,
    val extractionTarget: ExtractionTarget = ExtractionTarget.Face,
    val recordDurationMs: Long,
    val videoSizeBytes: Long,
    val targetVideoBytes: Long = ChunkRecordingProgress.DEFAULT_CHUNK_TARGET_BYTES,
    val status: ChunkProcessStatus = ChunkProcessStatus.Pending,
    val processDurationMs: Long = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val framesSampled: Int = 0,
    val sampleIntervalMs: Long = StreamResolution.FRAME_SAMPLE_INTERVAL_MS,
    val extractedImages: List<ExtractedFaceImage> = emptyList(),
) {
    val recordDurationLabel: String
        get() = formatPreciseDurationMs(recordDurationMs)

    val processDurationLabel: String
        get() = formatPreciseDurationMs(processDurationMs)

    val detectionHitPercent: Int
        get() = if (framesSampled > 0) ((facesKept * 100.0) / framesSampled).toInt() else 0

    val avgFrameProcessMs: Long
        get() = if (framesSampled > 0) processDurationMs / framesSampled else 0L

    val detectionSummary: String?
        get() = if (status == ChunkProcessStatus.Done && framesSampled > 0) {
            "Found $facesKept ${extractionTarget.keptNoun} from $framesSampled frames ($detectionHitPercent%)"
        } else {
            null
        }

    val processStatsLine: String?
        get() = if (status == ChunkProcessStatus.Done && framesSampled > 0) {
            "avg ${avgFrameProcessMs}ms/frame · extract $processDurationLabel"
        } else {
            null
        }

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
                framesSampled > 0 -> buildString {
                    append("Found $facesKept ${extractionTarget.keptNoun} from $framesSampled frames ($detectionHitPercent%)")
                    append(" · avg ${avgFrameProcessMs}ms/frame")
                    append(" · $processDurationLabel")
                    if (imagesTotalBytes > 0) append(" · ${formatChunkBytes(imagesTotalBytes)}")
                }
                facesKept > 0 -> {
                    "Found $facesKept ${extractionTarget.keptNoun} · $processDurationLabel · ${formatChunkBytes(imagesTotalBytes)}"
                }
                else -> "${extractionTarget.noKeptLabel} · $processDurationLabel"
            }
        }
}
