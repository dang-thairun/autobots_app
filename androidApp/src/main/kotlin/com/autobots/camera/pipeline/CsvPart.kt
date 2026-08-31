package com.autobots.camera.pipeline

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream

/**
 * Append-only body of a session CSV, one line per row, flushed as each chunk finishes.
 *
 * ### Why this exists
 *
 * `photos.csv` and `tracks.csv` were accumulated in memory for the whole session and written
 * once at drain. Two problems, and the second is worse than the first:
 *
 *  - **A session that dies loses everything.** Not a truncated file — nothing. Exactly the
 *    failure [com.autobots.camera.perf.PerfStream] was built to remove for `perf_report.json`;
 *    this is the same fix for the CSVs, and deliberately the same shape.
 *  - **Peak heap grows with session length.** `buildString` materialises the whole file, and a
 *    Java `String` is UTF-16, so a 15 MB CSV costs 30 MB before `StringBuilder`'s doubling.
 *    That allocation lands at drain — after hours of recording, on a hot device still holding
 *    4K bitmaps.
 *
 * ### Why one file per session and not one per chunk
 *
 * A per-chunk file would need a merge step at drain and a delete step after it, and the delete
 * is a leak waiting to be forgotten — this project has already been bitten twice by "nobody
 * deletes it" (chunk `.mp4`, 25 GB/hour). One append-only file has nothing extra to clean up:
 * it lives in the session directory and dies with it.
 *
 * Worker 2 will want per-chunk addressability, but that is its own slice; durability does not
 * need it.
 *
 * ### Header
 *
 * The `#` comment lines carry session totals that are only known at drain, so they are not in
 * this file. [copyTo] writes the body; the caller writes the header first. Same split as
 * `PerfStream`, which writes `env` up front and the `session` record at the end.
 *
 * ### Ordering
 *
 * Append order is chunk-completion order, and chunks are consumed one at a time in index order
 * ([CapturePipelineCoordinator]'s single `for (item in videoQueue)` loop). Within a chunk
 * `SubjectTracker.finish()` already sorts by first sighting. So the file comes out sorted
 * without a sort — but nothing here enforces that, so do not add a consumer that assumes it.
 */
class CsvPart(private val file: File) {

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var failed = false
    private var lines = 0L

    /** True once at least one row has been written and the file is readable. */
    val hasRows: Boolean get() = synchronized(lock) { lines > 0L }

    /**
     * Append one row and flush it.
     *
     * Flushing every call is the point: the rows worth having after a crash are the last few,
     * which is exactly what a buffer would still be holding. One `write(2)` per chunk is
     * invisible next to a pipeline whose JPEG encode already dominates the wall clock.
     *
     * A failure disables this part permanently and logs once. It is bookkeeping; it may
     * degrade, but it may never throw into the pipeline.
     */
    fun append(line: String) {
        synchronized(lock) {
            if (failed) return
            val out = writer ?: runCatching {
                file.parentFile?.mkdirs()
                BufferedWriter(FileWriter(file, false)).also { writer = it }
            }.getOrElse {
                Log.e(TAG, "cannot open ${file.name}: ${it.message}")
                failed = true
                return
            }
            runCatching {
                out.write(line)
                out.write("\n")
                out.flush()
                lines++
            }.onFailure {
                Log.e(TAG, "write failed, ${file.name} disabled: ${it.message}")
                failed = true
                runCatching { out.close() }
                writer = null
            }
        }
    }

    /** Copy the body out. Closes the writer first so the tail is on disk. */
    fun copyTo(out: OutputStream) {
        synchronized(lock) {
            closeWriter()
            if (!file.isFile) return
            runCatching { file.inputStream().use { it.copyTo(out) } }
                .onFailure { Log.e(TAG, "copy failed for ${file.name}: ${it.message}") }
        }
    }

    fun close() = synchronized(lock) { closeWriter() }

    /** Drop the body once it has been published. Safe to call twice. */
    fun discard() {
        synchronized(lock) {
            closeWriter()
            runCatching { file.delete() }
            lines = 0L
            failed = false
        }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }

    companion object {
        private const val TAG = "CsvPart"

        /**
         * Suffix for every body file, so [com.autobots.camera.perf.SessionRecovery] can sweep
         * them by pattern instead of by name.
         */
        const val SUFFIX = ".part"
    }
}
