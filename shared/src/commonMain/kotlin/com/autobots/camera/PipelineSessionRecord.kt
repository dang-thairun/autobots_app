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
    val sourceVideoWidth: Int? = null,
    val sourceVideoHeight: Int? = null,
    val sourceRotationDegrees: Int = 0,
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
    val albumFolderName: String = "",
    val chunks: List<ChunkRecord> = emptyList(),
) {
    val galleryPath: String
        get() = if (albumFolderName.isNotEmpty()) "DCIM/AutoBots/$albumFolderName" else "DCIM/AutoBots"
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

    val videoDimensionsLabel: String?
        get() {
            val w = sourceVideoWidth ?: return null
            val h = sourceVideoHeight ?: return null
            val dw = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) h else w
            val dh = if (sourceRotationDegrees == 90 || sourceRotationDegrees == 270) w else h
            return "${dw}×${dh}"
        }

    val resolutionLine: String
        get() = videoDimensionsLabel?.let { "${resolution.label} · $it" } ?: resolution.label

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

/** Plain-text session log for saving alongside extracted photos. */
fun PipelineSessionRecord.toLogText(): String = buildString {
    appendLine("AutoBots Session Log")
    appendLine("====================")
    appendLine()
    appendLine("Session ID: $id")
    appendLine("Source: $sourceLabel")
    appendLine("Name: $displayName")
    appendLine("Status: $statusLabel")
    appendLine("Started: ${formatLogTimestamp(startedAtEpochMs)}")
    appendLine("Resolution: ${resolutionLine}")
    appendLine("Target: ${extractionTarget.label}")
    appendLine("Gallery folder: $galleryPath")
    appendLine()
    sourceDurationMs?.takeIf { it > 0 }?.let {
        appendLine("Video duration: ${formatVideoDurationMs(it)}")
    }
    sourceSizeBytes?.takeIf { it > 0 }?.let {
        appendLine("Video size: ${formatChunkBytes(it)}")
    }
    appendLine()
    appendLine("Summary")
    appendLine("-------")
    appendLine("Chunks: $chunkCount (done $chunksDone)")
    appendLine("Photos kept: $facesKept ${extractionTarget.keptNoun}")
    appendLine("Photos skipped: $facesSkipped")
    appendLine(headlineSummary)
    if (totalDurationMs > 0) appendLine(timingSummary)
    splitDurationMs.takeIf { it > 0 }?.let {
        appendLine("Split time: ${formatDurationMs(it)}")
    }
    processDurationMs.takeIf { it > 0 }?.let {
        appendLine("Extract time: ${formatDurationMs(it)}")
    }
    errorMessage?.let {
        appendLine()
        appendLine("Error: $it")
    }
    if (chunks.isNotEmpty()) {
        appendLine()
        appendLine("Chunks")
        appendLine("------")
        chunks.forEach { chunk ->
            appendLine()
            appendLine("Chunk #${chunk.index}")
            appendLine("  Video: ${chunk.videoFileName}")
            appendLine("  Record: ${chunk.recordDurationSec}s · ${formatChunkBytes(chunk.videoSizeBytes)}")
            appendLine("  Extract: ${chunk.extractSummary}")
            if (chunk.extractedImages.isNotEmpty()) {
                chunk.extractedImages.forEach { image ->
                    appendLine("    - ${image.fileName} (${formatChunkBytes(image.sizeBytes)})")
                }
            }
        }
    }
    appendLine()
    appendLine("Generated by AutoBots Camera")
}

private fun formatLogTimestamp(epochMs: Long): String {
    val totalSec = epochMs / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hour = (totalSec / 3600) % 24
    return "${epochMs} (${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')})"
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
