package com.autobots.camera.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer

data class ImportSplitResult(
    val segments: Int,
    val totalBytes: Long,
    val sourceDurationMs: Long,
    val rotationDegrees: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
)

data class VideoProbeResult(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
) {
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

/**
 * Turns a user-picked video into pipeline chunks by **remuxing** the video track —
 * no decode, no re-encode, so it is fast and lossless.
 *
 * Two rules make the segments actually decodable downstream:
 *  - a segment may only start on a sync (key) frame, otherwise the decoder opens
 *    mid-GOP and emits garbage until the next keyframe;
 *  - the source rotation hint is copied onto every segment, so [VideoFrameSampler]
 *    can upright the frames before detection.
 *
 * Audio is dropped: frame extraction never looks at it.
 */
class ImportedVideoSplitter(
    private val context: Context,
    private val videoDir: File,
) {
    fun probe(source: Uri): VideoProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, source)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            VideoProbeResult(width, height, rotation, durationMs)
        } catch (t: Throwable) {
            Log.w(TAG, "Video probe failed", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun split(
        source: Uri,
        targetSegmentBytes: Long,
        startIndex: Int,
        canAcceptChunk: () -> Boolean,
        onChunkReady: (ChunkCaptureMeta) -> Unit,
        onProgress: (Int) -> Unit,
    ): ImportSplitResult {
        videoDir.mkdirs()
        val extractor = MediaExtractor()
        var segments = 0
        var totalBytes = 0L
        var durationUs = 0L
        val rotation = readRotationDegrees(source)
        var videoWidth = 0
        var videoHeight = 0

        try {
            extractor.setDataSource(context, source, null)

            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) {
                return ImportSplitResult(
                    0, 0L, 0L, rotation, 0, 0,
                    "No video track in the selected file",
                )
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            videoWidth = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            videoHeight = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            val buffer = ByteBuffer.allocate(sampleBufferSize(format))
            val info = MediaCodec.BufferInfo()

            var index = startIndex
            var muxer: MediaMuxer? = null
            var muxTrack = -1
            var segmentFile: File? = null
            var segmentBytes = 0L
            var segmentStartUs = 0L
            var lastPtsUs = 0L
            var firstPtsUs = -1L

            fun openSegment(ptsUs: Long) {
                val file = File(videoDir, "import_${index.toString().padStart(3, '0')}.mp4")
                val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (rotation != 0) output.setOrientationHint(rotation)
                muxTrack = output.addTrack(format)
                output.start()
                muxer = output
                segmentFile = file
                segmentBytes = 0L
                segmentStartUs = ptsUs
            }

            fun closeSegment(endPtsUs: Long) {
                val output = muxer ?: return
                val file = segmentFile
                muxer = null
                segmentFile = null
                runCatching {
                    output.stop()
                    output.release()
                }.onFailure { Log.e(TAG, "Muxer stop failed for ${file?.name}", it) }

                if (file == null || !file.exists() || file.length() == 0L) return
                segments++
                totalBytes += file.length()
                onChunkReady(
                    ChunkCaptureMeta(
                        index = index,
                        file = file,
                        recordedAtEpochMs = System.currentTimeMillis(),
                        recordDurationMs = ((endPtsUs - segmentStartUs) / 1000L).coerceAtLeast(0L),
                        videoSizeBytes = file.length(),
                    ),
                )
                index++
            }

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                val ptsUs = extractor.sampleTime
                val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (firstPtsUs < 0) firstPtsUs = ptsUs

                if (muxer == null) {
                    openSegment(ptsUs)
                } else if (isSync && segmentBytes >= targetSegmentBytes) {
                    closeSegment(lastPtsUs)
                    awaitQueueSpace(canAcceptChunk)
                    openSegment(ptsUs)
                }

                info.offset = 0
                info.size = size
                // Rebase to segment-relative, matching how recorded chunks look. Keeping
                // absolute source time would make each segment's duration metadata report
                // the offset too, throwing off the scan progress in Worker 2.
                info.presentationTimeUs = (ptsUs - segmentStartUs).coerceAtLeast(0L)
                info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer?.writeSampleData(muxTrack, buffer, info)
                segmentBytes += size
                lastPtsUs = ptsUs

                if (durationUs > 0L) {
                    onProgress((((ptsUs - firstPtsUs) * 100L) / durationUs).toInt().coerceIn(0, 99))
                }
                extractor.advance()
            }

            closeSegment(lastPtsUs)
            onProgress(100)
        } catch (t: Throwable) {
            Log.e(TAG, "Import split failed", t)
            return ImportSplitResult(
                segments = segments,
                totalBytes = totalBytes,
                sourceDurationMs = durationUs / 1000L,
                rotationDegrees = rotation,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                error = t.message ?: t::class.java.simpleName,
            )
        } finally {
            runCatching { extractor.release() }
        }

        Log.i(
            TAG,
            "Imported $segments segment(s), ${totalBytes / 1024}KB, " +
                "source ${videoWidth}x${videoHeight} ${durationUs / 1000}ms, rotation ${rotation}°",
        )
        return ImportSplitResult(
            segments,
            totalBytes,
            durationUs / 1000L,
            rotation,
            videoWidth,
            videoHeight,
        )
    }

    /** Honour the same backpressure the live recorder obeys — never overrun the queue. */
    private suspend fun awaitQueueSpace(canAcceptChunk: () -> Boolean) {
        while (!canAcceptChunk()) {
            delay(QUEUE_POLL_MS)
        }
    }

    private fun sampleBufferSize(format: MediaFormat): Int {
        val declared = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        if (declared > 0) return declared.coerceAtLeast(MIN_BUFFER_BYTES)
        val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920)
        val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080)
        return (width * height).coerceAtLeast(MIN_BUFFER_BYTES)
    }

    private fun readRotationDegrees(source: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "ImportedVideoSplitter"
        private const val QUEUE_POLL_MS = 250L
        private const val MIN_BUFFER_BYTES = 1 shl 20
    }
}
