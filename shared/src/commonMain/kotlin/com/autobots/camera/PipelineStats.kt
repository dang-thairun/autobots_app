package com.autobots.camera

/**
 * Runtime counters for the record → extract → deliver pipeline.
 */
data class PipelineStats(
    val sessionId: String = "",
    val resolution: StreamResolution = StreamResolution.Fhd,
    val extractionTarget: ExtractionTarget = ExtractionTarget.Face,
    val videoChunksRecorded: Int = 0,
    val videoQueueDepth: Int = 0,
    val chunksProcessed: Int = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val lastChunkProcessMs: Long = 0,
    val storageFreeMb: Long = 0,
    val pipelinePaused: Boolean = false,
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    /** 0–100 for the chunk currently being scanned. */
    val currentChunkPercent: Int = 0,
    val processingChunkName: String? = null,
    val imageQueuePending: Int = 0,
    /** A device video is being split into chunks right now. */
    val isImporting: Boolean = false,
    val importPercent: Int = 0,
    val importName: String? = null,
    /**
     * Best known chunk total for this session: projected while an import is still splitting,
     * exact once the split ends. 0 for live capture, where the total is not knowable.
     */
    val expectedChunks: Int = 0,
    /** process time ÷ footage length for the last chunk. ≥ 1.0 means the queue backs up. */
    val lastRealtimeRatio: Float = 0f,
    /** Mean wall time from the moment in front of the lens to a gallery-visible file. */
    val avgPhotoLatencyMs: Long = 0,
    val sessionHistory: List<PipelineSessionRecord> = emptyList(),
) {
    /** Flat chunk list from all sessions (convenience for callers that only need chunks). */
    val chunkHistory: List<ChunkRecord>
        get() = sessionHistory.flatMap { it.chunks }
    /**
     * Overall progress across the whole job (includes the in-flight chunk).
     *
     * The denominator is [expectedChunks] rather than [videoChunksRecorded] so that an import
     * reads as one continuous 0→100%. Splitting is throttled by the video queue and spends
     * ~95% of its time blocked, so its own percentage sits still for ~11 s at a time; the
     * extractor is the honest clock, and it reports sub-chunk progress every sampled frame.
     */
    val overallProcessingPercent: Int
        get() {
            val total = maxOf(expectedChunks, videoChunksRecorded)
            if (total <= 0) return 0
            val done = chunksProcessed.coerceAtMost(total)
            val sum = (done * 100) + if (isProcessing) currentChunkPercent else 0
            return (sum / total).coerceIn(0, 100)
        }
}
