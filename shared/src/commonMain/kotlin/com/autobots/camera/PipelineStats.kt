package com.autobots.camera

/**
 * Runtime counters for the record → extract → deliver pipeline.
 */
data class PipelineStats(
    val sessionId: String = "",
    val resolution: StreamResolution = StreamResolution.Fhd,
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
    val chunkHistory: List<ChunkRecord> = emptyList(),
) {
    /** Overall progress across recorded chunks (includes in-flight chunk). */
    val overallProcessingPercent: Int
        get() {
            if (videoChunksRecorded <= 0) return 0
            val done = chunksProcessed.coerceAtMost(videoChunksRecorded)
            val total = (done * 100) + if (isProcessing) currentChunkPercent else 0
            return (total / videoChunksRecorded).coerceIn(0, 100)
        }
}
