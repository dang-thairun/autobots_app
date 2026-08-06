package com.autobots.camera

enum class SessionSource {
    LiveCapture,
    VideoImport,
}

enum class SessionStatus {
    Splitting,
    Recording,
    Processing,
    Done,
    Failed,
}

/**
 * Top-level log for one live-capture or video-import run.
 * Aggregates all [ChunkRecord] entries produced in that run.
 */
data class PipelineSessionRecord(
    val id: String,
    val source: SessionSource,
    val displayName: String,
    val startedAtEpochMs: Long,
    val sourceDurationMs: Long? = null,
    val sourceSizeBytes: Long? = null,
    val resolution: StreamResolution,
    val extractionTarget: ExtractionTarget,
    val status: SessionStatus,
    val splitDurationMs: Long = 0,
    val processDurationMs: Long = 0,
    val totalDurationMs: Long = 0,
    val chunkCount: Int = 0,
    val chunksDone: Int = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val errorMessage: String? = null,
    val chunks: List<ChunkRecord> = emptyList(),
) {
    val sourceLabel: String
        get() = when (source) {
            SessionSource.LiveCapture -> "Live"
            SessionSource.VideoImport -> "Import"
        }

    val statusLabel: String
        get() = when (status) {
            SessionStatus.Splitting -> "Splitting…"
            SessionStatus.Recording -> "Recording"
            SessionStatus.Processing -> "Processing"
            SessionStatus.Done -> "Done"
            SessionStatus.Failed -> "Failed"
        }

    val videoDurationLabel: String?
        get() = sourceDurationMs?.takeIf { it > 0 }?.let { formatVideoDurationMs(it) }

    val headlineSummary: String
        get() = buildString {
            append("$chunkCount chunks")
            append(" · $facesKept ${extractionTarget.keptNoun}")
            if (totalDurationMs > 0) append(" · ${formatDurationMs(totalDurationMs)}")
        }

    val timingSummary: String
        get() = buildString {
            append("Total ${formatDurationMs(totalDurationMs)}")
            when {
                splitDurationMs > 0 && processDurationMs > 0 ->
                    append(" (split ${formatDurationMs(splitDurationMs)} + extract ${formatDurationMs(processDurationMs)})")
                splitDurationMs > 0 ->
                    append(" (split ${formatDurationMs(splitDurationMs)})")
                processDurationMs > 0 ->
                    append(" (extract ${formatDurationMs(processDurationMs)})")
            }
        }

    val progressLine: String?
        get() = when (status) {
            SessionStatus.Splitting -> "Splitting into chunks…"
            SessionStatus.Recording -> "$chunkCount chunks recorded"
            SessionStatus.Processing -> "Processing $chunksDone/$chunkCount chunks · $facesKept ${extractionTarget.keptNoun}"
            SessionStatus.Done -> null
            SessionStatus.Failed -> errorMessage ?: "Failed"
        }
}

/** Wall-clock duration for UI labels (e.g. "3m 12s", "45s"). */
fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return "0s"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "${min}m ${sec}s" else "${sec}s"
}

/** Source video length (e.g. "5:30", "42s"). */
fun formatVideoDurationMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) {
        "${min}:${sec.toString().padStart(2, '0')}"
    } else {
        "${sec}s"
    }
}
