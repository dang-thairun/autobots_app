package com.autobots.camera

/**
 * Live progress for the chunk currently being recorded.
 */
data class ChunkRecordingProgress(
    val chunkIndex: Int = 0,
    val elapsedMs: Long = 0L,
    val bytesWritten: Long = 0L,
    val targetBytes: Long = DEFAULT_CHUNK_TARGET_BYTES,
) {
    val elapsedSec: Long get() = elapsedMs / 1000L

    val rateKbPerSec: Long
        get() = if (elapsedMs > 0) {
            (bytesWritten * 1000L / elapsedMs) / 1024L
        } else {
            0L
        }

    val progressFraction: Float
        get() = if (targetBytes > 0) {
            (bytesWritten.toFloat() / targetBytes).coerceIn(0f, 1f)
        } else {
            0f
        }

    companion object {
        const val DEFAULT_CHUNK_TARGET_BYTES = 20L * 1024L * 1024L
    }
}

fun formatChunkBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
