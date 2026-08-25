package com.autobots.camera.perf

import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * A write-ahead log for [PerfReport], one JSON object per line.
 *
 * ### Why this exists
 *
 * `perf_report.json` is built entirely in memory and written once, at drain. A session that
 * dies at minute 105 of 120 therefore leaves **nothing at all** — not a truncated report, not
 * a partial one. Nothing. That is the failure this file removes.
 *
 * ### Why JSONL and not a partial JSON document
 *
 * A crash does not choose its moment. A single JSON array left unclosed is unparseable in
 * its entirety, which loses exactly as much as writing nothing; newline-delimited records
 * lose only the final, half-written line. [PerfRecovery] drops that line and reads the rest.
 *
 * ### Why per chunk and not per frame
 *
 * A 50-minute run samples ~22,600 frames but produces ~300 chunks. One ~16 KB line every few
 * seconds is invisible next to a pipeline whose `save_jpeg` stage already owns 71% of the
 * wall clock; 22,600 appends would be competing with it for the same disk.
 *
 * ### Ordering
 *
 * Records are appended in arrival order, which for chunks is *completion* order — detect
 * workers finish out of sequence. That is already true of the in-memory report, and
 * [PerfRecovery] sorts by `index` when it rebuilds. Do not assume the file is ordered.
 */
class PerfStream(private val file: File) {

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var lines = 0L
    private var failed = false

    /**
     * Open the file and record the session's fixed facts.
     *
     * `env` is written first so a recovered report can name the device even if the session
     * block never arrived — which is the normal case for a crash, because
     * [PerfReport.setSession] only runs at drain.
     */
    fun start(startedAtEpochMs: Long, env: JSONObject) {
        synchronized(lock) {
            if (failed) return
            runCatching {
                file.parentFile?.mkdirs()
                writer = BufferedWriter(FileWriter(file, false), BUFFER_BYTES)
            }.onFailure {
                Log.e(TAG, "Cannot open ${file.name}: ${it.message}")
                failed = true
                return
            }
        }
        write(
            "start",
            JSONObject().apply {
                put("schema", PerfReport.SCHEMA_VERSION)
                put("startedAtEpochMs", startedAtEpochMs)
                put("env", env)
            },
        )
    }

    fun session(json: JSONObject) = write("session", json)

    fun chunk(json: JSONObject) = write("chunk", json)

    fun event(json: JSONObject) = write("event", json)

    fun load(json: JSONObject) = write("load", json)

    /**
     * Close cleanly and stamp the file as complete.
     *
     * The `end` record is the whole point of closing: its **absence** is how [PerfRecovery]
     * tells a session that crashed from one that finished. A file with no `end` line is a
     * session whose report was never written.
     */
    fun finish(reason: String) {
        write("end", JSONObject().apply { put("reason", reason); put("lines", lines) })
        synchronized(lock) {
            runCatching { writer?.flush(); writer?.close() }
                .onFailure { Log.w(TAG, "close failed: ${it.message}") }
            writer = null
        }
    }

    /**
     * Append one record.
     *
     * Flushed on every write rather than left to the buffer. Buffering is what would make
     * this file lie: the last few seconds before a crash — the only part anyone will read —
     * are precisely the part a 64 KB buffer would still be holding when the process died.
     * The cost is one `write(2)` per chunk, which at one line every few seconds does not
     * register.
     *
     * A write failure disables the stream permanently and is logged once. This is a
     * diagnostic; it may degrade, but it may never throw into the pipeline.
     */
    private fun write(type: String, body: JSONObject) {
        synchronized(lock) {
            val out = writer ?: return
            if (failed) return
            runCatching {
                val record = JSONObject().apply {
                    put("t", type)
                    put("v", body)
                }
                out.write(record.toString())
                out.write("\n")
                out.flush()
                lines++
            }.onFailure {
                Log.e(TAG, "write failed, stream disabled: ${it.message}")
                failed = true
                runCatching { out.close() }
                writer = null
            }
        }
    }

    companion object {
        private const val TAG = "PerfStream"
        const val FILE_NAME = "perf_stream.jsonl"

        /** One chunk line is ~16 KB; this holds a couple without growing. */
        private const val BUFFER_BYTES = 64 * 1024
    }
}
