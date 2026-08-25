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
    val detectorBackend: DetectorBackend = DetectorBackend.DEFAULT,
    /** Capture Zone this run used, if the operator drew one. */
    val detectZone: DetectZone? = null,
    /** Shutter ceiling as a pinned frame rate; null means AE was left to decide. */
    val shutterCeilingFps: Int? = null,
    /** AE bias in device steps, with the step size needed to read it back as EV. */
    val exposureIndex: Int = 0,
    val exposureStepEv: Double? = null,
    val status: SessionStatus,
    val splitDurationMs: Long = 0,
    /**
     * Of [splitDurationMs], the part spent parked on video-queue backpressure rather than
     * remuxing. On any clip long enough to fill the queue this is nearly all of it — v0.1.3
     * measured 455.8 s of "split time" for a remux worth about 20 s — so the two must be
     * reported apart for the numbers to mean anything.
     */
    val splitBlockedMs: Long = 0,
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

    /**
     * What detected frames this session. Pose always runs ML Kit pose-detection;
     * Face uses [detectorBackend] (ML Kit / GPU / NPU) and that backend's model.
     */
    val detectorLine: String
        get() = when (extractionTarget) {
            ExtractionTarget.Pose -> "Pose · ML Kit · pose-detection"
            ExtractionTarget.Face ->
                "Face · ${detectorBackend.hardwareLabel} · ${detectorBackend.modelName}"
            ExtractionTarget.FaceAndPose ->
                "Face + Pose · ${detectorBackend.hardwareLabel} · " +
                    "${detectorBackend.modelName} + pose-detection"
        }

    val headlineSummary: String
        get() = buildString {
            append("$chunkCount chunks")
            append(" · $facesKept ${extractionTarget.keptNoun}")
            if (totalDurationMs > 0) append(" · ${formatDurationMs(totalDurationMs)}")
        }

    /**
     * Footage this run had to get through: the source clip for an import, the sum of what was
     * recorded for a live capture.
     */
    val footageDurationMs: Long
        get() = sourceDurationMs ?: chunks.sumOf { it.recordDurationMs }

    /**
     * Extraction time over footage length — the same definition `perf_report.json` uses per
     * chunk, so a session line and a report can be compared without converting anything.
     *
     * Below 1 the phone gets through footage faster than it arrives; above 1 it falls behind,
     * which is fatal for live capture and merely slow for an import.
     */
    val realtimeRatio: Float?
        get() {
            val footage = footageDurationMs
            if (footage <= 0L || processDurationMs <= 0L) return null
            return processDurationMs.toFloat() / footage
        }

    val realtimeRatioLabel: String?
        get() = realtimeRatio?.let { formatRealtimeRatio(it) }

    val timingSummary: String
        get() = buildString {
            append("Total ${formatDurationMs(totalDurationMs)}")
            realtimeRatioLabel?.let { append(" · $it") }
            when {
                splitDurationMs > 0 && processDurationMs > 0 ->
                    append(" (split ${formatDurationMs(splitDurationMs)} + extract ${formatDurationMs(processDurationMs)})")
                splitDurationMs > 0 ->
                    append(" (split ${formatDurationMs(splitDurationMs)})")
                processDurationMs > 0 ->
                    append(" (extract ${formatDurationMs(processDurationMs)})")
            }
        }

    /**
     * The settings that shaped what this run kept, in one line.
     *
     * A field report that says "4 a.m. produced almost nothing" is unusable without them:
     * a shutter ceiling, an EV bias and a capture zone each change the yield on their own,
     * and none of them is recoverable from the photos afterwards.
     */
    val captureSettingsLine: String?
        get() {
            val parts = buildList {
                shutterCeilingFps?.let { add("shutter ≤ 1/$it") }
                if (exposureIndex != 0) {
                    val ev = exposureStepEv?.let { step -> exposureIndex * step }
                    add(ev?.let { formatEv(it) } ?: "EV index $exposureIndex")
                }
                detectZone?.let { add("zone ${it.pixelSummary(sourceVideoWidth ?: 0, sourceVideoHeight ?: 0)}") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    val progressLine: String?
        get() = when (status) {
            SessionStatus.Splitting -> "Splitting into chunks…"
            SessionStatus.Recording -> "$chunkCount chunks recorded"
            SessionStatus.Processing -> "Processing $chunksDone/$chunkCount chunks · $facesKept ${extractionTarget.keptNoun}"
            SessionStatus.Done -> null
            SessionStatus.Failed -> errorMessage ?: "Failed"
        }

    val framesSampledTotal: Int
        get() = chunks.sumOf { it.framesSampled }

    val detectionHitPercent: Int
        get() = if (framesSampledTotal > 0) ((facesKept * 100.0) / framesSampledTotal).toInt() else 0

    val avgFrameProcessMs: Long
        get() {
            val frames = framesSampledTotal
            return if (frames > 0) processDurationMs / frames else 0L
        }

    val detectionSummary: String?
        get() {
            val frames = framesSampledTotal
            if (frames <= 0) return null
            return buildString {
                append("Found $facesKept ${extractionTarget.keptNoun} from $frames frames ($detectionHitPercent%)")
                append(" · avg ${avgFrameProcessMs}ms/frame")
                append(" · sample ${StreamResolution.FRAME_SAMPLE_INTERVAL_MS}ms")
            }
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
    appendLine("Detector: ${detectorLine}")
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
    detectionSummary?.let { appendLine(it) }
    appendLine(headlineSummary)
    if (totalDurationMs > 0) appendLine(timingSummary)
    splitDurationMs.takeIf { it > 0 }?.let {
        val active = (it - splitBlockedMs).coerceAtLeast(0L)
        appendLine(
            "Split time: ${formatDurationMs(active)} active" +
                if (splitBlockedMs > 0) " + ${formatDurationMs(splitBlockedMs)} waiting on queue" else "",
        )
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
            appendLine("  Duration: ${chunk.recordDurationLabel} · ${formatChunkBytes(chunk.videoSizeBytes)}")
            if (chunk.framesSampled > 0) {
                appendLine("  Sample interval: ${chunk.sampleIntervalMs} ms")
                appendLine("  Frames sampled: ${chunk.framesSampled}")
                chunk.detectionSummary?.let { appendLine("  $it") }
                chunk.processStatsLine?.let { appendLine("  $it") }
            } else if (chunk.status == ChunkProcessStatus.Done) {
                appendLine("  Extract: ${chunk.extractSummary}")
            }
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

/** `+1.5 EV` / `-0.5 EV`, hand-rounded because common code has no String.format. */
fun formatEv(ev: Double): String {
    val tenths = (ev * 10).toInt()
    val sign = if (tenths > 0) "+" else if (tenths < 0) "-" else ""
    val abs = if (tenths < 0) -tenths else tenths
    return "$sign${abs / 10}.${abs % 10} EV"
}

/**
 * `0.50x realtime` — extraction time over footage length.
 *
 * Hand-rounded to two decimals rather than `String.format`, which is not available to
 * common code.
 */
fun formatRealtimeRatio(ratio: Float): String {
    val hundredths = (ratio * 100).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}x realtime"
}

/** Precise duration in seconds with millisecond precision (e.g. 45.123 s, 83.456 s). */
fun formatPreciseDurationMs(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val seconds = clamped / 1000
    val millis = clamped % 1000
    return "${seconds}.${millis.toString().padStart(3, '0')} s"
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
