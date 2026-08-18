package com.autobots.camera.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.autobots.camera.network.VideoHttp
import kotlinx.coroutines.CancellationException
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
    /**
     * Time spent parked in [ImportedVideoSplitter.awaitQueueSpace] waiting for the video
     * queue to drain.
     *
     * Without this, the caller's wall-clock timing of `split()` is unreadable: v0.1.3
     * reported 2,912 ms for a 1-minute clip and 455,841 ms for a 4-minute one, because the
     * long clip kept the queue at 8/8 and the splitter produced exactly one chunk per chunk
     * consumed. That number measured the whole pipeline, not the remux. Subtracting this
     * gives `splitActiveMs`, which is comparable across runs.
     */
    val blockedMs: Long = 0L,
)

data class VideoProbeResult(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val frameRate: Float? = null,
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
 *
 * HTTP/HTTPS sources are streamed (no full-file download first). Android's HTTP
 * extractor issues byte-range reads as it seeks and remuxes, so the pipeline can
 * start extracting the first chunk while later samples are still arriving.
 */
class ImportedVideoSplitter(
    private val context: Context,
    private val videoDir: File,
) {
    /** Accumulated backpressure wait for the current [split]; see [ImportSplitResult.blockedMs]. */
    private var blockedMs = 0L

    fun probe(source: Uri): VideoProbeResult? = Companion.probe(context, source)

    suspend fun split(
        source: Uri,
        targetSegmentBytes: Long,
        startIndex: Int,
        canAcceptChunk: () -> Boolean,
        onChunkReady: (ChunkCaptureMeta) -> Unit,
        onProgress: (Int) -> Unit,
        startTimeUs: Long = 0L,
        endTimeUs: Long = Long.MAX_VALUE,
    ): ImportSplitResult {
        videoDir.mkdirs()
        blockedMs = 0L
        val extractor = MediaExtractor()
        var segments = 0
        var totalBytes = 0L
        var durationUs = 0L
        val rotation = readRotationDegrees(source)
        var videoWidth = 0
        var videoHeight = 0
        var clipStartUs = 0L
        var clipEndUs = Long.MAX_VALUE

        // Declared out here, not inside the try, so the finally below can reach them. A
        // MediaMuxer holds native memory and a file descriptor; losing the reference on the
        // way out of an exception leaks both. See the finally for why that path is routine
        // rather than exotic.
        var muxer: MediaMuxer? = null
        var segmentFile: File? = null

        try {
            VideoHttp.bind(extractor, context, source)

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
                    blockedMs = blockedMs,
                )
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            videoWidth = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
            videoHeight = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
            durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            clipStartUs = startTimeUs.coerceAtLeast(0L)
            clipEndUs = when {
                endTimeUs == Long.MAX_VALUE || endTimeUs <= 0L ->
                    if (durationUs > 0L) durationUs else Long.MAX_VALUE
                durationUs > 0L -> endTimeUs.coerceAtMost(durationUs)
                else -> endTimeUs
            }
            if (clipEndUs != Long.MAX_VALUE && clipStartUs >= clipEndUs) {
                return ImportSplitResult(
                    0, 0L, 0L, rotation, videoWidth, videoHeight,
                    "Start must be before end",
                    blockedMs = blockedMs,
                )
            }
            if (clipStartUs > 0L) {
                extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val buffer = ByteBuffer.allocate(sampleBufferSize(format))
            val info = MediaCodec.BufferInfo()

            var index = startIndex
            var muxTrack = -1
            var segmentBytes = 0L
            var segmentStartUs = 0L
            var lastPtsUs = 0L

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
                if (clipEndUs != Long.MAX_VALUE && ptsUs > clipEndUs) break

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

                val spanUs = when {
                    clipEndUs != Long.MAX_VALUE && clipEndUs > clipStartUs -> clipEndUs - clipStartUs
                    durationUs > clipStartUs -> durationUs - clipStartUs
                    else -> 0L
                }
                if (spanUs > 0L) {
                    onProgress((((ptsUs - clipStartUs) * 100L) / spanUs).toInt().coerceIn(0, 99))
                }
                extractor.advance()
            }

            closeSegment(lastPtsUs)
            onProgress(100)
        } catch (t: CancellationException) {
            // Cancellation is not a split failure and must not be reported as one. It also
            // must not be swallowed: `catch (Throwable)` below would turn a cancelled import
            // into a normal error result and leave the coroutine looking alive to its parent.
            // This is the common exit, not a rare one — awaitQueueSpace parks for ~95% of a
            // split (TC-06: 293.8 s blocked of 313.8 s), so almost any cancel lands in a delay.
            throw t
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
                blockedMs = blockedMs,
            )
        } finally {
            runCatching { extractor.release() }
            // Only reachable when the loop left early — the normal path closes the last
            // segment itself and nulls this out. stop() throws if the muxer never received
            // a sample, so it gets its own runCatching and release() runs either way.
            muxer?.let { open ->
                Log.w(TAG, "Releasing muxer left open by an early exit (${segmentFile?.name})")
                runCatching { open.stop() }
                runCatching { open.release() }
            }
            muxer = null
            // The partial segment was never handed to onChunkReady, so nobody can read it —
            // and at ~52 MB a piece it is worth not leaving behind in the cache.
            segmentFile?.let { partial -> runCatching { partial.delete() } }
            segmentFile = null
        }

        val resultDurationMs = when {
            clipEndUs != Long.MAX_VALUE && clipEndUs > clipStartUs ->
                (clipEndUs - clipStartUs) / 1000L
            else -> durationUs / 1000L
        }

        Log.i(
            TAG,
            "Imported $segments segment(s), ${totalBytes / 1024}KB, " +
                "source ${videoWidth}x${videoHeight} ${durationUs / 1000}ms, " +
                "clip ${clipStartUs / 1000}-${if (clipEndUs == Long.MAX_VALUE) "end" else "${clipEndUs / 1000}"}ms, " +
                "rotation ${rotation}°, backpressure ${blockedMs}ms",
        )
        return ImportSplitResult(
            segments,
            totalBytes,
            resultDurationMs,
            rotation,
            videoWidth,
            videoHeight,
            blockedMs = blockedMs,
        )
    }

    /**
     * Honour the same backpressure the live recorder obeys — never overrun the queue.
     * Time parked here is accumulated so it can be subtracted from the split wall clock;
     * see [ImportSplitResult.blockedMs].
     */
    private suspend fun awaitQueueSpace(canAcceptChunk: () -> Boolean) {
        if (canAcceptChunk()) return
        val startMs = System.currentTimeMillis()
        while (!canAcceptChunk()) {
            delay(QUEUE_POLL_MS)
        }
        blockedMs += System.currentTimeMillis() - startMs
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
            VideoHttp.bind(retriever, context, source)
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

        fun probe(context: Context, source: Uri): VideoProbeResult? {
            val retriever = MediaMetadataRetriever()
            return try {
                VideoHttp.bind(retriever, context, source)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: return null
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: return null
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val captureFps = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                    ?.takeIf { it > 0f }
                VideoProbeResult(
                    width = width,
                    height = height,
                    rotationDegrees = rotation,
                    durationMs = durationMs,
                    frameRate = captureFps ?: readFrameRate(context, source),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Video probe failed", t)
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun readFrameRate(context: Context, source: Uri): Float? {
            val extractor = MediaExtractor()
            return try {
                VideoHttp.bind(extractor, context, source)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return null
                val format = extractor.getTrackFormat(track)
                if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
                    ?.takeIf { it > 0f }
                    ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                        ?.takeIf { it > 0f }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { extractor.release() }
            }
        }
    }
}
