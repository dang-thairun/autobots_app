package com.autobots.camera.perf

import android.os.Build
import com.autobots.BuildConfig
import com.autobots.camera.load.DeviceLoadSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Machine-readable companion to `session_log.txt`.
 *
 * `session_log.txt` is written for a human reading it on the phone. This one is written
 * for offline analysis: every number that decides whether the pipeline keeps up
 * (stage timings, realtime ratio, queue depth, reject reasons, sharpness distribution)
 * lands in one JSON file per session, next to the photos.
 *
 * Measurement only — like the rest of [CamPerf], nothing here may change pipeline
 * behaviour. Collection is a no-op unless [CamPerf.enabled].
 */
class PerfReport {

    /** One sampled frame and why it lived or died. */
    data class FrameDiag(
        val ptsUs: Long,
        /** candidate · no_subject · too_small · too_soft */
        val outcome: String,
        val sharpness: Double?,
        val subjectRatio: Float?,
    )

    /** What Worker 2 measured for one chunk. Filled in by VideoFrameProcessor. */
    data class ChunkDiag(
        val framesSampled: Int,
        val kept: Int,
        val skipped: Int,
        val decodeFailures: Int,
        val processDurationMs: Long,
        val noSubject: Int,
        val tooSmall: Int,
        val tooSoft: Int,
        val roiInvalid: Int,
        val sharpnessCutoff: Double,
        val detectWidth: Int,
        /** "surface" (0.1.3 fast path) or "yuv" (legacy fallback). */
        val decodePath: String,
        /** Detect workers running alongside the decoder (0.1.4 two-stage pipeline). */
        val detectWorkers: Int,
        /** Frames buffered between decoder and workers — the memory ceiling. */
        val frameQueueCapacity: Int,
        /** "halving" or "single" — how the detect bitmap was downscaled. */
        val downscaleMode: String,
        /**
         * What the detector reports about itself: requested backend, the backend actually in
         * use after any fallback, and tile count. Recorded so a session that quietly fell back
         * to ML Kit — because the QNN libraries were missing, say — can never be read as a
         * result for the backend that was asked for.
         */
        val detector: Map<String, Any>,
        val stages: List<StageStats.StageRow>,
        val frames: List<FrameDiag>,
        val keptPtsUs: List<Long>,
    )

    /** Recording-side facts the coordinator knows and Worker 2 does not. */
    data class ChunkEntry(
        val index: Int,
        val fileName: String,
        val sizeBytes: Long,
        val recordDurationMs: Long,
        val queueWaitMs: Long,
        val realtimeRatio: Double,
        val diag: ChunkDiag?,
    )

    data class Event(
        val atMs: Long,
        val type: String,
        val chunkIndex: Int?,
        val videoQueueDepth: Int,
        val imageQueuePending: Int,
    )

    data class LoadSample(
        val atMs: Long,
        val thermalLabel: String,
        val thermalLevel: Int,
        val usedRamMb: Long,
        val availRamMb: Long,
    )

    private val chunks = Collections.synchronizedList(mutableListOf<ChunkEntry>())
    private val events = Collections.synchronizedList(mutableListOf<Event>())
    private val load = Collections.synchronizedList(mutableListOf<LoadSample>())

    /**
     * Events arrive from the worker, the recorder callback and the delivery thread.
     * `synchronizedList` guards each mutator but not iteration, so every read that
     * builds the report takes the list's own monitor first.
     */
    private fun <T> snapshotOf(list: MutableList<T>): List<T> = synchronized(list) { ArrayList(list) }

    @Volatile private var session: JSONObject? = null
    @Volatile private var startedAtMs: Long = 0L

    val enabled: Boolean get() = CamPerf.enabled

    /** Session start, so every event timestamp can be rendered relative to it. */
    fun markStart(startedAtEpochMs: Long) {
        if (!enabled) return
        startedAtMs = startedAtEpochMs
    }

    /**
     * Session facts, written at drain time — by then the import probe has resolved the
     * real source dimensions, which is exactly the field that disambiguates an FHD run
     * from a UHD one after the fact.
     */
    fun setSession(build: JSONObject.() -> Unit) {
        if (!enabled) return
        session = JSONObject().apply(build)
    }

    fun addChunk(entry: ChunkEntry) {
        if (!enabled) return
        chunks.add(entry)
    }

    fun addEvent(
        atMs: Long,
        type: String,
        chunkIndex: Int? = null,
        videoQueueDepth: Int = -1,
        imageQueuePending: Int = -1,
    ) {
        if (!enabled) return
        if (events.size >= MAX_EVENTS) return
        events.add(Event(atMs, type, chunkIndex, videoQueueDepth, imageQueuePending))
    }

    fun addLoad(atMs: Long, snapshot: DeviceLoadSnapshot) {
        if (!enabled) return
        if (load.size >= MAX_LOAD_SAMPLES) return
        load.add(
            LoadSample(
                atMs = atMs,
                thermalLabel = snapshot.thermalLabel,
                thermalLevel = snapshot.thermalLevel,
                usedRamMb = snapshot.usedRamMb,
                availRamMb = snapshot.availRamMb,
            ),
        )
    }

    /** @return pretty-printed JSON, or null when instrumentation is off / nothing collected. */
    fun render(generatedAtEpochMs: Long): String? {
        if (!enabled) return null
        val sessionJson = session ?: return null
        return try {
            JSONObject().apply {
                put("schema", SCHEMA_VERSION)
                put("generatedAtEpochMs", generatedAtEpochMs)
                put("env", envJson())
                put("session", sessionJson)
                put("totals", totalsJson())
                put("chunks", chunksJson())
                put("events", eventsJson())
                put("deviceLoad", loadJson())
            }.toString(2)
        } catch (t: Throwable) {
            CamPerf.log { "PerfReport render failed: ${t.message}" }
            null
        }
    }

    private fun envJson() = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("androidRelease", Build.VERSION.RELEASE)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        put("cpuCores", Runtime.getRuntime().availableProcessors())
        put("appVersion", BuildConfig.VERSION_NAME)
        put("camPerf", BuildConfig.CAM_PERF)
        put("debugBuild", BuildConfig.DEBUG)
    }

    private fun totalsJson(): JSONObject {
        val snapshot = snapshotOf(chunks)
        val diags = snapshot.mapNotNull { it.diag }
        return JSONObject().apply {
            put("chunks", snapshot.size)
            put("chunksWithDiag", diags.size)
            put("framesSampled", diags.sumOf { it.framesSampled })
            put("kept", diags.sumOf { it.kept })
            put("skipped", diags.sumOf { it.skipped })
            put("decodeFailures", diags.sumOf { it.decodeFailures })
            put("processDurationMs", diags.sumOf { it.processDurationMs })
            put("recordedDurationMs", snapshot.sumOf { it.recordDurationMs })
            put("noSubject", diags.sumOf { it.noSubject })
            put("tooSmall", diags.sumOf { it.tooSmall })
            put("tooSoft", diags.sumOf { it.tooSoft })
            put("roiInvalid", diags.sumOf { it.roiInvalid })
            val recorded = snapshot.sumOf { it.recordDurationMs }
            val processed = diags.sumOf { it.processDurationMs }
            put(
                "realtimeRatio",
                if (recorded > 0) round3(processed.toDouble() / recorded) else JSONObject.NULL,
            )
            put("stages", mergedStagesJson(diags))
        }
    }

    /**
     * Stage totals across the whole session — the table to read first.
     *
     * `sharePercent` is measured against the chunks' **real wall clock**, not against a sum
     * of stage timings. Up to 0.1.3 the denominator was "top-level stages added together",
     * which required knowing which stages nested inside which — and got it wrong for
     * `rotate`, inflating the divisor and understating every share. Since 0.1.4 the pipeline
     * runs on two threads and no sum of stages equals wall time at all: the producer and the
     * consumers overlap, so the stage totals deliberately add up to **more** than 100%.
     * That is the whole point, and it is exactly what the overlap is worth.
     */
    private fun mergedStagesJson(diags: List<ChunkDiag>): JSONArray {
        val order = mutableListOf<String>()
        val n = HashMap<String, Int>()
        val total = HashMap<String, Long>()
        val max = HashMap<String, Long>()
        for (diag in diags) {
            for (row in diag.stages) {
                if (!n.containsKey(row.stage)) order.add(row.stage)
                n[row.stage] = (n[row.stage] ?: 0) + row.n
                total[row.stage] = (total[row.stage] ?: 0L) + row.totalNs
                max[row.stage] = maxOf(max[row.stage] ?: 0L, row.maxNs)
            }
        }
        val wallNs = (diags.sumOf { it.processDurationMs } * 1_000_000L).coerceAtLeast(1L)
        return JSONArray().apply {
            for (stage in order) {
                val stageTotal = total.getValue(stage)
                val stageN = n.getValue(stage)
                put(
                    JSONObject().apply {
                        put("stage", stage)
                        put("n", stageN)
                        put("avgMs", round3(stageTotal.toDouble() / stageN / 1e6))
                        put("maxMs", round3(max.getValue(stage) / 1e6))
                        put("totalMs", round3(stageTotal / 1e6))
                        put("sharePercent", round3(stageTotal * 100.0 / wallNs))
                        put("thread", if (stage in PRODUCER_STAGES) "producer" else "consumer")
                    },
                )
            }
            put(
                JSONObject().apply {
                    put("stage", "__wallTotal")
                    put("totalMs", round3(wallNs / 1e6))
                    put(
                        "note",
                        "real wall clock (sum of chunk processDurationMs). Producer and " +
                            "consumer stages overlap, so shares sum to more than 100%.",
                    )
                },
            )
        }
    }

    private fun chunksJson() = JSONArray().apply {
        for (entry in snapshotOf(chunks)) {
            put(
                JSONObject().apply {
                    put("index", entry.index)
                    put("file", entry.fileName)
                    put("sizeBytes", entry.sizeBytes)
                    put("recordDurationMs", entry.recordDurationMs)
                    put("queueWaitMs", entry.queueWaitMs)
                    put("realtimeRatio", round3(entry.realtimeRatio))
                    val diag = entry.diag
                    if (diag == null) {
                        put("diag", JSONObject.NULL)
                    } else {
                        put("framesSampled", diag.framesSampled)
                        put("kept", diag.kept)
                        put("skipped", diag.skipped)
                        put("decodeFailures", diag.decodeFailures)
                        put("processDurationMs", diag.processDurationMs)
                        put("detectWidth", diag.detectWidth)
                        put("decodePath", diag.decodePath)
                        put("detectWorkers", diag.detectWorkers)
                        put("frameQueueCapacity", diag.frameQueueCapacity)
                        put("downscaleMode", diag.downscaleMode)
                        put(
                            "detector",
                            JSONObject().apply { diag.detector.forEach { (k, v) -> put(k, v) } },
                        )
                        put(
                            "rejects",
                            JSONObject().apply {
                                put("noSubject", diag.noSubject)
                                put("tooSmall", diag.tooSmall)
                                put("tooSoft", diag.tooSoft)
                                put("roiInvalid", diag.roiInvalid)
                            },
                        )
                        put("stages", stagesJson(diag.stages))
                        put("sharpness", sharpnessJson(diag))
                        put("frames", framesJson(diag))
                    }
                },
            )
        }
    }

    private fun stagesJson(rows: List<StageStats.StageRow>) = JSONArray().apply {
        for (row in rows) {
            put(
                JSONObject().apply {
                    put("stage", row.stage)
                    put("n", row.n)
                    put("avgMs", round3(row.avgNs / 1e6))
                    put("maxMs", round3(row.maxNs / 1e6))
                    put("totalMs", round3(row.totalNs / 1e6))
                },
            )
        }
    }

    /**
     * Percentiles of what actually reached the scorer, against the cutoff that judged it.
     * A cutoff far below p25 means the gate is not discriminating at all.
     */
    private fun sharpnessJson(diag: ChunkDiag): JSONObject {
        val scores = diag.frames.mapNotNull { it.sharpness }.sorted()
        return JSONObject().apply {
            put("cutoff", diag.sharpnessCutoff)
            put("n", scores.size)
            if (scores.isEmpty()) return@apply
            fun percentile(p: Int) =
                scores[((scores.size - 1) * p / 100).coerceIn(0, scores.size - 1)]
            put("min", round3(scores.first()))
            put("p25", round3(percentile(25)))
            put("p50", round3(percentile(50)))
            put("p75", round3(percentile(75)))
            put("max", round3(scores.last()))
            put("belowCutoff", scores.count { it < diag.sharpnessCutoff })
        }
    }

    private fun framesJson(diag: ChunkDiag): JSONArray {
        val kept = diag.keptPtsUs.toHashSet()
        return JSONArray().apply {
            for (frame in diag.frames) {
                put(
                    JSONObject().apply {
                        put("ptsUs", frame.ptsUs)
                        put("outcome", frame.outcome)
                        put("kept", kept.contains(frame.ptsUs))
                        put("sharpness", frame.sharpness?.let { round3(it) } ?: JSONObject.NULL)
                        put(
                            "subjectRatio",
                            frame.subjectRatio?.let { round3(it.toDouble()) } ?: JSONObject.NULL,
                        )
                    },
                )
            }
        }
    }

    private fun eventsJson() = JSONArray().apply {
        for (event in snapshotOf(events)) {
            put(
                JSONObject().apply {
                    put("tMs", event.atMs - startedAtMs)
                    put("type", event.type)
                    put("chunk", event.chunkIndex ?: JSONObject.NULL)
                    if (event.videoQueueDepth >= 0) put("videoQueue", event.videoQueueDepth)
                    if (event.imageQueuePending >= 0) put("imageQueue", event.imageQueuePending)
                },
            )
        }
    }

    private fun loadJson() = JSONArray().apply {
        for (sample in snapshotOf(load)) {
            put(
                JSONObject().apply {
                    put("tMs", sample.atMs - startedAtMs)
                    put("thermal", sample.thermalLabel)
                    put("thermalLevel", sample.thermalLevel)
                    put("usedRamMb", sample.usedRamMb)
                    put("availRamMb", sample.availRamMb)
                },
            )
        }
    }

    private fun round3(value: Double): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return Math.round(value * 1000.0) / 1000.0
    }

    companion object {
        /**
         * 2 — `sharePercent` measured against real wall time, stages carry `thread` instead of
         * `nestedInDecoderBlocked`, `decoder_blocked` became `queue_wait`, chunks report
         * `detectWorkers` / `frameQueueCapacity` / `downscaleMode`. Schema 1 reports are not
         * share-comparable.
         *
         * 3 — the detector became selectable, so `mlkit_face` is now simply **`detect`** (and
         * `mlkit_pose` → `detect_pose`): one stage name across every backend, which is what
         * makes two runs directly comparable. Chunks gained `detector`, and the session gained
         * `detectorBackend`.
         */
        const val SCHEMA_VERSION = 3
        const val FILE_NAME = "perf_report.json"

        /** Stages timed on the decoder thread; everything else runs on a detect worker. */
        private val PRODUCER_STAGES =
            setOf("decode", "yuv_jpeg_argb", "surface_rgba", "queue_wait")

        /** Backstops so a runaway session cannot grow the file without bound. */
        private const val MAX_EVENTS = 4000
        private const val MAX_LOAD_SAMPLES = 600
    }
}
