package com.autobots.camera

/**
 * Runtime counters for the record → extract → deliver pipeline.
 */
data class PipelineStats(
    val sessionId: String = "",
    val resolution: StreamResolution = StreamResolution.Fhd,
    val videoChunksRecorded: Int = 0,
    val videoQueueDepth: Int = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val lastChunkProcessMs: Long = 0,
    val storageFreeMb: Long = 0,
    val pipelinePaused: Boolean = false,
    val isRecording: Boolean = false,
)
