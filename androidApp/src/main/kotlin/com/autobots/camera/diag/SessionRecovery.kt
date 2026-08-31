package com.autobots.camera.diag

import android.content.Context
import android.util.Log
import com.autobots.camera.delivery.LocalDeliveryWriter
import com.autobots.camera.perf.PerfRecovery
import com.autobots.camera.perf.PerfReport
import com.autobots.camera.perf.PerfStream
import com.autobots.camera.pipeline.CsvPart
import java.io.File

/**
 * Finds sessions that never wrote a report and writes one from their stream.
 *
 * A session directory holding a [PerfStream.FILE_NAME] but no [PerfReport.FILE_NAME] is, by
 * definition, a session that stopped before drain — the report is written at drain and only
 * at drain. Run at startup, so the run after the crash is the one that produces the evidence
 * for it.
 *
 * The recovered report is published to the gallery under its own folder rather than into the
 * album the dead session was writing to. Two reasons: a crashed session's album is not always
 * knowable from the stream, and a file that appears in an existing album days later reads as
 * part of that session's normal output when it is not.
 */
object SessionRecovery {

    /**
     * @return how many reports were recovered.
     */
    fun sweep(context: Context, root: File): Int {
        val dirs = runCatching { root.listFiles { f -> f.isDirectory } }.getOrNull()
            ?: return 0
        val now = System.currentTimeMillis()
        var recovered = 0
        for (dir in dirs) {
            sweepCsvParts(dir, now)
            val stream = File(dir, PerfStream.FILE_NAME)
            if (!stream.isFile) continue
            // A stream being written right now belongs to a live session, not a dead one.
            // The sweep runs on a background thread at startup and the operator can begin
            // capturing in the same second, so without this the sweep could "recover" — and
            // then delete — the stream of the session that is still filling it.
            if (now - stream.lastModified() < LIVE_GRACE_MS) {
                Log.i(TAG, "${dir.name}: stream touched ${now - stream.lastModified()}ms ago, skipping")
                continue
            }
            if (File(dir, PerfReport.FILE_NAME).isFile) {
                // Session finished normally; the stream did its job by not being needed.
                deleteStream(stream)
                continue
            }
            if (recoverOne(context, dir, stream)) recovered++
        }
        if (recovered > 0) Log.i(TAG, "Recovered $recovered report(s) from crashed sessions")
        return recovered
    }

    /**
     * Drop `*.part` CSV bodies left by a session that died before publishing.
     *
     * Done before the `perf_stream.jsonl` checks below, and keyed on each part's own timestamp,
     * because a session with `CamPerf` disabled writes no stream at all — the loop would
     * `continue` past it and the parts would never be swept. There is nothing to recover from
     * them: the rows they hold were already published if the session drained, and a crashed
     * session's photos are the record that matters.
     */
    private fun sweepCsvParts(dir: File, now: Long) {
        val parts = dir.listFiles { f -> f.isFile && f.name.endsWith(CsvPart.SUFFIX) } ?: return
        for (part in parts) {
            // Same reason as the stream's grace window: a live session is still appending.
            if (now - part.lastModified() < LIVE_GRACE_MS) continue
            if (part.delete()) Log.i(TAG, "${dir.name}: dropped stale ${part.name}")
        }
    }

    private fun recoverOne(context: Context, dir: File, stream: File): Boolean {
        val outcome = runCatching { PerfRecovery.rebuild(stream) }.getOrElse {
            Log.e(TAG, "Rebuild failed for ${dir.name}", it)
            return false
        }
        val text = outcome.rendered
        if (text == null) {
            Log.w(TAG, "${dir.name}: stream had no usable chunks, leaving it in place")
            return false
        }
        Log.i(
            TAG,
            "${dir.name}: recovered ${outcome.chunks} chunks " +
                "(complete=${outcome.complete}, badLines=${outcome.badLines})",
        )

        runCatching { File(dir, PerfReport.FILE_NAME).writeText(text) }
            .onFailure { Log.w(TAG, "Cannot write recovered report to cache: ${it.message}") }

        val writer = LocalDeliveryWriter(context)
        writer.albumSubfolder = "recovered_${dir.name}"
        runCatching { writer.publishText(PerfReport.FILE_NAME, text) }
            .onFailure { Log.w(TAG, "Cannot publish recovered report: ${it.message}") }

        // Renamed, not copied and not deleted: it holds per-frame detail the report may have
        // dropped to its budgets, and after a crash that detail is the whole point. The new
        // suffix is also what stops the next sweep from recovering the same session twice —
        // sweeps match the exact file name.
        val kept = File(dir, "${PerfStream.FILE_NAME}.kept")
        val renamed = runCatching { stream.renameTo(kept) }.getOrDefault(false)
        if (!renamed) {
            runCatching { stream.copyTo(kept, overwrite = true) }
                .onFailure { Log.w(TAG, "Cannot preserve stream: ${it.message}") }
            deleteStream(stream)
        }
        return true
    }

    private fun deleteStream(stream: File) {
        runCatching { stream.delete() }
            .onFailure { Log.w(TAG, "Cannot delete ${stream.name}: ${it.message}") }
    }

    private const val TAG = "SessionRecovery"

    /**
     * How recently a stream must have been written to count as live. Generous against the
     * 30-second heartbeat, because deleting a live session's stream is far worse than
     * leaving a dead one for the next launch.
     */
    private const val LIVE_GRACE_MS = 120_000L
}
