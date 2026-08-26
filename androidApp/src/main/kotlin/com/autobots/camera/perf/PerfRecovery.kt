package com.autobots.camera.perf

import android.util.Log
import com.autobots.camera.load.DeviceLoadSnapshot
import com.autobots.camera.load.PowerSample
import com.autobots.camera.load.ProcessVitals
import org.json.JSONObject
import java.io.File

/**
 * Rebuilds a `perf_report.json` from a [PerfStream] left behind by a session that died.
 *
 * ### How it stays honest
 *
 * The records are parsed back into the same data classes the live pipeline produces and
 * replayed through a fresh [PerfReport], which then renders normally. Nothing here knows how
 * to build a report — it only knows how to undo the serialisation. That is deliberate: a
 * second, parallel renderer would drift from the real one, and the first time anyone noticed
 * would be while reading a report from the crash they were trying to explain.
 *
 * The one asymmetry is in our favour. The stream carries every chunk's `frames[]` regardless
 * of [PerfReport] budgets, so a recovered report can be **more** complete than the one the
 * live session would have written.
 *
 * ### What is lost
 *
 * Timings round-trip through `round3`, so recovered stage figures carry three decimal places
 * of a millisecond rather than nanosecond counters. Nothing reads them at finer resolution
 * than that.
 */
object PerfRecovery {

    /** What a stream file turned out to be. */
    data class Outcome(
        val rendered: String?,
        val chunks: Int,
        val complete: Boolean,
        val badLines: Int,
    )

    /**
     * Read one `perf_stream.jsonl` and render the report it implies.
     *
     * @return null when the file holds nothing renderable — no `start` record, or no chunks.
     */
    fun rebuild(stream: File): Outcome {
        val report = PerfReport()
        var startedAt = 0L
        var chunks = 0
        var complete = false
        var bad = 0
        var sawStart = false

        val lines = runCatching { stream.readLines() }.getOrElse {
            Log.e(TAG, "Cannot read ${stream.name}: ${it.message}")
            return Outcome(null, 0, false, 0)
        }

        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val record = runCatching { JSONObject(line) }.getOrNull()
            if (record == null) {
                // Expected exactly once, on the last line, when the process died mid-write.
                // Anywhere else it means something truncated the file.
                bad++
                Log.w(TAG, "unparseable line ${i + 1}/${lines.size} in ${stream.name}")
                continue
            }
            val body = record.optJSONObject("v") ?: continue
            when (record.optString("t")) {
                "start" -> {
                    sawStart = true
                    startedAt = body.optLong("startedAtEpochMs")
                    report.markStart(startedAt)
                }
                "session" -> report.setSession { copyInto(this, body) }
                "chunk" -> {
                    parseChunk(body)?.let { report.addChunk(it); chunks++ }
                }
                "event" -> parseEvent(body, startedAt)?.let {
                    report.addEvent(it.atMs, it.type, it.chunkIndex, it.videoQueueDepth, it.imageQueuePending)
                }
                "load" -> parseLoad(body, startedAt)?.let { (atMs, snapshot) ->
                    report.addLoad(atMs, snapshot)
                }
                "end" -> complete = true
            }
        }

        if (!sawStart || chunks == 0) return Outcome(null, chunks, complete, bad)

        // A crashed session never reached setSession, so stand one in that says so rather
        // than letting render() bail on a null session block.
        report.setSessionIfAbsent {
            put("id", stream.parentFile?.name ?: "unknown")
            put("status", if (complete) "Done" else "Crashed")
            put("startedAtEpochMs", startedAt)
            put("recovered", true)
        }
        return Outcome(report.render(System.currentTimeMillis()), chunks, complete, bad)
    }

    private fun copyInto(target: JSONObject, source: JSONObject) {
        for (key in source.keys()) target.put(key, source.get(key))
    }

    private fun parseChunk(json: JSONObject): PerfReport.ChunkEntry? {
        val index = json.optInt("index", -1)
        if (index < 0) return null
        val diag = if (json.has("framesSampled")) parseDiag(json) else null
        return PerfReport.ChunkEntry(
            index = index,
            fileName = json.optString("file"),
            sizeBytes = json.optLong("sizeBytes"),
            recordDurationMs = json.optLong("recordDurationMs"),
            queueWaitMs = json.optLong("queueWaitMs"),
            realtimeRatio = json.optDouble("realtimeRatio", 0.0),
            diag = diag,
        )
    }

    private fun parseDiag(json: JSONObject): PerfReport.ChunkDiag {
        val rejects = json.optJSONObject("rejects")
        val frames = mutableListOf<PerfReport.FrameDiag>()
        val kept = mutableListOf<Long>()
        json.optJSONArray("frames")?.let { array ->
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                val pts = f.optLong("ptsUs")
                frames += PerfReport.FrameDiag(
                    ptsUs = pts,
                    outcome = f.optString("outcome"),
                    sharpness = if (f.isNull("sharpness")) null else f.optDouble("sharpness"),
                    subjectRatio = if (f.isNull("subjectRatio")) {
                        null
                    } else {
                        f.optDouble("subjectRatio").toFloat()
                    },
                    score = if (f.isNull("score")) null else f.optDouble("score").toFloat(),
                )
                if (f.optBoolean("kept")) kept += pts
            }
        }
        val stages = mutableListOf<StageStats.StageRow>()
        json.optJSONArray("stages")?.let { array ->
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i) ?: continue
                stages += StageStats.StageRow(
                    stage = s.optString("stage"),
                    n = s.optInt("n"),
                    totalNs = (s.optDouble("totalMs") * 1e6).toLong(),
                    maxNs = (s.optDouble("maxMs") * 1e6).toLong(),
                )
            }
        }
        val detector = mutableMapOf<String, Any>()
        json.optJSONObject("detector")?.let { d ->
            for (key in d.keys()) detector[key] = d.get(key)
        }
        return PerfReport.ChunkDiag(
            framesSampled = json.optInt("framesSampled"),
            kept = json.optInt("kept"),
            skipped = json.optInt("skipped"),
            decodeFailures = json.optInt("decodeFailures"),
            processDurationMs = json.optLong("processDurationMs"),
            noSubject = rejects?.optInt("noSubject") ?: 0,
            tooSmall = rejects?.optInt("tooSmall") ?: 0,
            tooSoft = rejects?.optInt("tooSoft") ?: 0,
            roiInvalid = rejects?.optInt("roiInvalid") ?: 0,
            // Only ever serialised inside the sharpness block, so that is where it comes back
            // from. A chunk that scored nothing has no cutoff to recover and reports 0.
            sharpnessCutoff = json.optJSONObject("sharpness")?.optDouble("cutoff") ?: 0.0,
            detectWidth = json.optInt("detectWidth"),
            decodePath = json.optString("decodePath"),
            detectWorkers = json.optInt("detectWorkers"),
            frameQueueCapacity = json.optInt("frameQueueCapacity"),
            downscaleMode = json.optString("downscaleMode"),
            cpuProbeNs = (json.optDouble("cpuProbeMs", 0.0) * 1e6).toLong(),
            detector = detector,
            stages = stages,
            frames = frames,
            keptPtsUs = kept,
        )
    }

    private fun parseEvent(json: JSONObject, startedAt: Long): PerfReport.Event? {
        val type = json.optString("type").takeIf { it.isNotEmpty() } ?: return null
        return PerfReport.Event(
            atMs = startedAt + json.optLong("tMs"),
            type = type,
            chunkIndex = if (json.isNull("chunk")) null else json.optInt("chunk"),
            videoQueueDepth = json.optInt("videoQueue", -1),
            imageQueuePending = json.optInt("imageQueue", -1),
        )
    }

    private fun parseLoad(json: JSONObject, startedAt: Long): Pair<Long, DeviceLoadSnapshot>? {
        val atMs = startedAt + json.optLong("tMs")
        val proc = json.optJSONObject("proc")
        val power = json.optJSONObject("power")
        val threads = mutableMapOf<String, Long>()
        proc?.optJSONObject("threadCpuJiffies")?.let { t ->
            for (key in t.keys()) threads[key] = t.optLong(key)
        }
        val snapshot = DeviceLoadSnapshot(
            thermalLabel = json.optString("thermal", "OK"),
            thermalLevel = json.optInt("thermalLevel"),
            usedRamMb = json.optLong("usedRamMb"),
            availRamMb = json.optLong("availRamMb"),
            // Not serialised — it is a constant of the device, already in env.
            totalRamMb = 0L,
            cpuMaxFreqKhz = json.optInt("cpuMaxFreqKhz", 0),
            vitals = proc?.let {
                ProcessVitals(
                    cpuJiffies = it.optLong("cpuJiffies"),
                    threadCpuJiffies = threads,
                    javaHeapKb = it.optLong("javaHeapKb"),
                    javaHeapMaxKb = it.optLong("javaHeapMaxKb"),
                    nativeHeapKb = it.optLong("nativeHeapKb"),
                    graphicsKb = it.optLong("graphicsKb", 0L),
                    totalPssKb = it.optLong("totalPssKb", 0L),
                )
            },
            power = power?.let {
                PowerSample(
                    chargeCounterUah = it.optLong("chargeUah", PowerSample.UNAVAILABLE),
                    currentNowUa = it.optLong("currentUa", PowerSample.UNAVAILABLE),
                    capacityPercent = it.optInt("capacityPercent", PowerSample.UNAVAILABLE_INT),
                    voltageMv = it.optInt("voltageMv", PowerSample.UNAVAILABLE_INT),
                    batteryTempC = it.optDouble("batteryTempC", Double.NaN),
                    isCharging = it.optBoolean("charging"),
                    thermalHeadroom = it.optDouble("thermalHeadroom", -1.0).toFloat(),
                )
            },
            socTempC = json.optDouble("socTempC", Double.NaN),
            gpuBusyPercent = json.optInt("gpuBusyPercent", -1),
        )
        return atMs to snapshot
    }

    private const val TAG = "PerfRecovery"
}
