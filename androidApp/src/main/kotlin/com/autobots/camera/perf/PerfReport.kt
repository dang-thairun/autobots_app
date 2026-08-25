package com.autobots.camera.perf

import android.os.Build
import com.autobots.BuildConfig
import com.autobots.camera.load.DeviceLoadSnapshot
import com.autobots.camera.load.PowerSample
import com.autobots.camera.load.ProcessVitals
import com.autobots.camera.load.ProcessVitalsReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
        /** candidate · no_subject · too_small · too_soft · roi_invalid · decode_failed */
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
         * Fixed-workload CPU probe taken just before this chunk started. See [DvfsProbe] —
         * compare it *across* chunks, never in absolute terms.
         */
        val cpuProbeNs: Long,
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
        /**
         * Filled in by [addChunk], not by the caller — it is computed there so that
         * [ChunkDiag.frames] can be dropped afterwards without taking the sharpness
         * distribution with it.
         */
        val sharpness: SharpnessSummary? = null,
    )

    /** Percentiles of what reached the scorer in one chunk, against the cutoff that judged it. */
    data class SharpnessSummary(
        val cutoff: Double,
        val n: Int,
        val min: Double,
        val p25: Double,
        val p50: Double,
        val p75: Double,
        val max: Double,
        val belowCutoff: Int,
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
        /** 0 when the platform would not report it — see [DeviceLoadSnapshot.cpuMaxFreqKhz]. */
        val cpuMaxFreqKhz: Int,
        /** This process's own CPU/memory, or null when the reader was not the detailed one. */
        val vitals: ProcessVitals? = null,
        /** Battery counters, or null. */
        val power: PowerSample? = null,
        val socTempC: Double = Double.NaN,
        val gpuBusyPercent: Int = -1,
    )

    private val chunks = Collections.synchronizedList(mutableListOf<ChunkEntry>())
    private val events = Collections.synchronizedList(mutableListOf<Event>())
    private val load = Collections.synchronizedList(mutableListOf<LoadSample>())

    /** Per-frame diagnostics kept vs. dropped for size — see [addChunk]. */
    private val framesKept = AtomicInteger(0)
    private val framesDropped = AtomicInteger(0)

    /** Events lost to [MAX_EVENTS]; reported rather than swallowed. */
    private val eventsDropped = AtomicInteger(0)

    /** Load-sample thinning state — see [addLoad]. Guarded by [load]'s own monitor. */
    private var loadSeen = 0L
    private var loadStride = 1L

    /**
     * Events arrive from the worker, the recorder callback and the delivery thread.
     * `synchronizedList` guards each mutator but not iteration, so every read that
     * builds the report takes the list's own monitor first.
     */
    private fun <T> snapshotOf(list: MutableList<T>): List<T> = synchronized(list) { ArrayList(list) }

    @Volatile private var session: JSONObject? = null
    @Volatile private var startedAtMs: Long = 0L

    /**
     * Optional write-ahead log. Every record is appended here **before** the in-memory caps
     * get a chance to thin or drop it, which is what makes a crashed session recoverable and
     * what lets the stream carry detail the report itself cannot afford to hold.
     */
    @Volatile private var stream: PerfStream? = null

    val enabled: Boolean get() = CamPerf.enabled

    /**
     * Start writing a crash-survivable copy of everything this report collects.
     *
     * Attach before the first chunk. Records that arrived earlier are not backfilled — there
     * are none in practice, because the coordinator attaches at session start.
     */
    fun attachStream(target: PerfStream) {
        if (!enabled) return
        stream = target
    }

    /** Session start, so every event timestamp can be rendered relative to it. */
    fun markStart(startedAtEpochMs: Long) {
        if (!enabled) return
        startedAtMs = startedAtEpochMs
        stream?.start(startedAtEpochMs, envJson())
    }

    /**
     * Session facts, written at drain time — by then the import probe has resolved the
     * real source dimensions, which is exactly the field that disambiguates an FHD run
     * from a UHD one after the fact.
     */
    fun setSession(build: JSONObject.() -> Unit) {
        if (!enabled) return
        val json = JSONObject().apply(build)
        session = json
        stream?.session(json)
    }

    /**
     * Stand in a session block only if the real one never arrived.
     *
     * Used by [PerfRecovery]: a crashed session never reached [setSession], and [render]
     * returns null without one. Never overwrites a real block.
     */
    fun setSessionIfAbsent(build: JSONObject.() -> Unit) {
        if (!enabled) return
        if (session != null) return
        session = JSONObject().apply(build)
    }

    /**
     * Which optional probes this device answered, from `DeviceLoadReader.probeAvailability`.
     *
     * Recorded in `env` so `socTempC` missing from every sample reads as *"this phone would
     * not report it"* rather than as a run that never got warm.
     */
    fun setProbeAvailability(probes: Map<String, Boolean>) {
        if (!enabled) return
        probeAvailability = probes
    }

    @Volatile private var probeAvailability: Map<String, Boolean> = emptyMap()

    /**
     * Record one chunk, keeping its per-frame detail only while there is budget for it.
     *
     * `frames[]` is one JSON object per **sampled frame**. At 4½ minutes that is 2,281 of
     * them and the file is a couple of MB; a two-hour import samples ~60,000 and would build
     * an ~11 MB string through an in-memory `JSONObject` tree several times that size, at
     * drain time, on a device that has just been pinned for two hours. The detail is what
     * gets dropped, because everything a long run is actually read for — reject tallies,
     * stage timings, `cpuProbeMs`, and the sharpness distribution — lives outside it.
     *
     * The sharpness percentiles are computed **here**, before the drop, precisely so they
     * survive. Early chunks keep full detail and late ones lose it, which is the right way
     * round: a run is diagnosed from where it started, and `framesDropped` in the totals
     * says plainly what is missing rather than letting a short `frames[]` read as a short
     * chunk.
     */
    fun addChunk(entry: ChunkEntry) {
        if (!enabled) return
        val diag = entry.diag
        if (diag == null) {
            chunks.add(entry)
            return
        }
        val summary = summarise(diag)
        // Streamed with its frames[] intact regardless of budget — MAX_FRAME_DIAGS is a
        // memory limit, and the stream is not memory. A recovered report can therefore be
        // *more* complete than one rendered in RAM from the same session.
        stream?.chunk(chunkJson(entry.copy(sharpness = summary)))
        val kept = framesKept.get()
        val withinBudget = kept + diag.frames.size <= MAX_FRAME_DIAGS
        if (withinBudget) {
            framesKept.addAndGet(diag.frames.size)
            chunks.add(entry.copy(sharpness = summary))
        } else {
            framesDropped.addAndGet(diag.frames.size)
            chunks.add(entry.copy(diag = diag.copy(frames = emptyList()), sharpness = summary))
        }
    }

    private fun summarise(diag: ChunkDiag): SharpnessSummary {
        val scores = diag.frames.mapNotNull { it.sharpness }.sorted()
        if (scores.isEmpty()) {
            return SharpnessSummary(diag.sharpnessCutoff, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }
        fun percentile(p: Int) = scores[((scores.size - 1) * p / 100).coerceIn(0, scores.size - 1)]
        return SharpnessSummary(
            cutoff = diag.sharpnessCutoff,
            n = scores.size,
            min = scores.first(),
            p25 = percentile(25),
            p50 = percentile(50),
            p75 = percentile(75),
            max = scores.last(),
            belowCutoff = scores.count { it < diag.sharpnessCutoff },
        )
    }

    /**
     * Events are individually meaningful — the drain trace is read as a sequence, so thinning
     * it the way [addLoad] thins its samples would destroy it. It is capped and the overflow
     * is **counted**, because the previous behaviour was to return silently and let a
     * truncated timeline read as a session that simply stopped doing things.
     */
    fun addEvent(
        atMs: Long,
        type: String,
        chunkIndex: Int? = null,
        videoQueueDepth: Int = -1,
        imageQueuePending: Int = -1,
    ) {
        if (!enabled) return
        val event = Event(atMs, type, chunkIndex, videoQueueDepth, imageQueuePending)
        stream?.event(eventJson(event))
        synchronized(events) {
            if (events.size >= MAX_EVENTS) {
                eventsDropped.incrementAndGet()
                return
            }
            events.add(event)
        }
    }

    /**
     * Device load, thinned by **halving** rather than truncated.
     *
     * The old cap stopped recording at 600 samples. Two go in per chunk, so a two-hour import
     * (~950 chunks) went blind about a third of the way through — which is precisely where a
     * long run gets interesting, since the whole reason these samples exist is to catch the
     * thermal and DVFS decline that TC-12 measured at −43% clock in four and a half minutes.
     *
     * On overflow every second sample is dropped and the intake stride doubles, so the series
     * always spans the **whole** session at a resolution that halves as the session grows:
     * ~600 points over 4 minutes, ~600 over 4 hours. `loadStride` in the JSON says how many
     * real samples one recorded point now stands for.
     */
    fun addLoad(atMs: Long, snapshot: DeviceLoadSnapshot) {
        if (!enabled) return
        val sample = LoadSample(
            atMs = atMs,
            thermalLabel = snapshot.thermalLabel,
            thermalLevel = snapshot.thermalLevel,
            usedRamMb = snapshot.usedRamMb,
            availRamMb = snapshot.availRamMb,
            cpuMaxFreqKhz = snapshot.cpuMaxFreqKhz,
            vitals = snapshot.vitals,
            power = snapshot.power,
            socTempC = snapshot.socTempC,
            gpuBusyPercent = snapshot.gpuBusyPercent,
        )
        // Streamed before thinning: the file keeps every sample, memory keeps a thinned copy.
        // The caps exist because of RAM, and the stream does not live in RAM.
        stream?.load(loadSampleJson(sample))
        synchronized(load) {
            // Skip whatever the current stride says is between kept samples.
            if (loadSeen++ % loadStride != 0L) return
            load.add(sample)
            if (load.size < MAX_LOAD_SAMPLES) return
            // Full: keep the even-indexed half and take one in two from here on. The first
            // sample is always index 0, so the start of the session is never the part lost.
            var write = 0
            for (read in load.indices) {
                if (read % 2 == 0) load[write++] = load[read]
            }
            while (load.size > write) load.removeAt(load.size - 1)
            loadStride *= 2
        }
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
                put("truncation", truncationJson())
            }.toString(2)
        } catch (t: Throwable) {
            CamPerf.log { "PerfReport render failed: ${t.message}" }
            null
        }
    }

    /**
     * What this report is **not** telling you.
     *
     * Every cap in here used to fail silently, which is the worst way for a diagnostic to
     * fail: a two-hour run produced a report that looked complete and was missing two thirds
     * of its device-load series. Read this block before concluding anything from a long
     * session — all zeroes means nothing was dropped.
     */
    private fun truncationJson() = JSONObject().apply {
        put("frameDiagsKept", framesKept.get())
        put("frameDiagsDropped", framesDropped.get())
        put("frameDiagBudget", MAX_FRAME_DIAGS)
        put("eventsDropped", eventsDropped.get())
        put("eventBudget", MAX_EVENTS)
        synchronized(load) {
            put("loadStride", loadStride)
            put("loadSamplesSeen", loadSeen)
        }
        put(
            "note",
            "frameDiagsDropped > 0: later chunks report aggregates and sharpness but an " +
                "empty frames[] (see framesOmitted per chunk). loadStride > 1: deviceLoad " +
                "still spans the whole session, one point per that many samples.",
        )
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
        put("jiffiesPerSecond", ProcessVitalsReader.JIFFIES_PER_SECOND)
        if (probeAvailability.isNotEmpty()) {
            put(
                "probes",
                JSONObject().apply { probeAvailability.forEach { (k, v) -> put(k, v) } },
            )
        }
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
            put("cpuProbe", cpuProbeJson(diags))
            put("cpu", cpuUsageJson())
            put("memory", memoryJson())
            put("thermal", thermalJson())
            put("power", powerJson(diags.sumOf { it.kept }))
            put("stages", mergedStagesJson(diags))
        }
    }

    /**
     * How much CPU the process actually burned, and where.
     *
     * Differenced across the whole load series rather than per sample: `cpuJiffies` is a
     * cumulative counter, so first-to-last over the session's wall clock is exact even when
     * samples are unevenly spaced or the series was thinned by [addLoad].
     *
     * `percentOfOneCore` can exceed 100 — the pipeline runs a producer and two detect workers
     * concurrently, so 250% is a healthy reading on an 8-core phone, not an error.
     */
    private fun cpuUsageJson(): JSONObject {
        val samples = snapshotOf(load).filter { it.vitals != null }
        return JSONObject().apply {
            put("n", samples.size)
            if (samples.size < 2) return@apply
            val first = samples.first()
            val last = samples.last()
            val wallMs = last.atMs - first.atMs
            if (wallMs <= 0L) return@apply
            val ticks = last.vitals!!.cpuJiffies - first.vitals!!.cpuJiffies
            val cpuMs = ticks / ProcessVitalsReader.JIFFIES_PER_SECOND * 1000.0
            put("wallMs", wallMs)
            put("cpuMs", round3(cpuMs))
            put("percentOfOneCore", round3(cpuMs * 100.0 / wallMs))
            put("percentOfDevice", round3(cpuMs * 100.0 / wallMs / cores()))
            val threads = JSONObject()
            val names = first.vitals.threadCpuJiffies.keys + last.vitals.threadCpuJiffies.keys
            for (name in names) {
                val delta = (last.vitals.threadCpuJiffies[name] ?: 0L) -
                    (first.vitals.threadCpuJiffies[name] ?: 0L)
                if (delta <= 0L) continue
                val threadMs = delta / ProcessVitalsReader.JIFFIES_PER_SECOND * 1000.0
                threads.put(name, round3(threadMs * 100.0 / wallMs))
            }
            put("percentOfOneCoreByThread", threads)
            put(
                "note",
                "percentOfOneCore > 100 is expected: producer and detect workers run at " +
                    "once. Thread rows are bucketed by role, not by tid — a role covers " +
                    "however many threads the dispatcher spun up.",
            )
        }
    }

    /**
     * Our own memory, high-water and final.
     *
     * `nativeHeapKb` is the row to read on a session that died: since Android 8 a Bitmap's
     * pixels are native, so a leak of 4K frames climbs here while the Java heap stays flat.
     */
    private fun memoryJson(): JSONObject {
        val vitals = snapshotOf(load).mapNotNull { it.vitals }
        return JSONObject().apply {
            put("n", vitals.size)
            if (vitals.isEmpty()) return@apply
            put("javaHeapMaxKb", vitals.last().javaHeapMaxKb)
            put("javaHeapPeakKb", vitals.maxOf { it.javaHeapKb })
            put("javaHeapEndKb", vitals.last().javaHeapKb)
            put("nativeHeapPeakKb", vitals.maxOf { it.nativeHeapKb })
            put("nativeHeapEndKb", vitals.last().nativeHeapKb)
            put("nativeHeapStartKb", vitals.first().nativeHeapKb)
            vitals.filter { it.graphicsKb > 0 }.maxOfOrNull { it.graphicsKb }
                ?.let { put("graphicsPeakKb", it) }
            vitals.filter { it.totalPssKb > 0 }.maxOfOrNull { it.totalPssKb }
                ?.let { put("totalPssPeakKb", it) }
            put(
                "note",
                "process-scoped. deviceLoad.usedRamMb is the whole machine and moves with " +
                    "other apps; these do not. Bitmaps live in the native heap.",
            )
        }
    }

    /** Heat, from the three sources that disagree with each other in useful ways. */
    private fun thermalJson(): JSONObject {
        val samples = snapshotOf(load)
        return JSONObject().apply {
            put("maxLevel", samples.maxOfOrNull { it.thermalLevel } ?: 0)
            put("endLevel", samples.lastOrNull()?.thermalLevel ?: 0)
            samples.mapNotNull { it.power?.batteryTempC?.takeIf { t -> !t.isNaN() } }
                .let { temps ->
                    if (temps.isEmpty()) return@let
                    put("batteryTempStartC", round3(temps.first()))
                    put("batteryTempMaxC", round3(temps.max()))
                    put("batteryTempEndC", round3(temps.last()))
                }
            samples.map { it.socTempC }.filter { !it.isNaN() }.let { temps ->
                if (temps.isEmpty()) return@let
                put("socTempMaxC", round3(temps.max()))
                put("socTempEndC", round3(temps.last()))
            }
            samples.mapNotNull { it.power?.thermalHeadroom?.takeIf { h -> h >= 0f } }
                .let { headroom ->
                    if (headroom.isEmpty()) return@let
                    put("headroomMax", round3(headroom.max().toDouble()))
                    put("headroomEnd", round3(headroom.last().toDouble()))
                }
            put(
                "note",
                "thermalLevel is the platform's 0-6 status and stayed OK through a run that " +
                    "throttled 23% (TC-06). headroom 1.0 means throttling now; it leads the " +
                    "level. Compare both against cpuProbe.driftPercent.",
            )
        }
    }

    /**
     * Energy, or an explicit refusal to state it.
     *
     * A charging phone's `chargeUah` *rises*, so a session run with the cable in would report
     * a negative drain — a number that looks like a measurement and is not one. Every sample
     * carries `charging`, and if **any** of them was charging this block reports why it has
     * no figure instead of producing one. Measuring energy therefore requires an unplugged
     * run, which is a change to the test protocol, not something the code can work around.
     */
    private fun powerJson(photosKept: Int): JSONObject {
        val samples = snapshotOf(load).mapNotNull { it.power }
        return JSONObject().apply {
            put("n", samples.size)
            if (samples.isEmpty()) {
                put("note", "no battery samples")
                return@apply
            }
            put("capacityStartPercent", samples.first().capacityPercent)
            put("capacityEndPercent", samples.last().capacityPercent)
            val charging = samples.count { it.isCharging }
            put("samplesCharging", charging)
            if (charging > 0) {
                put("energyMeasured", false)
                put(
                    "note",
                    "charging during $charging of ${samples.size} samples — charge counter " +
                        "rises while plugged in, so no energy figure is reported. Re-run " +
                        "unplugged to measure.",
                )
                return@apply
            }
            val counters = samples.map { it.chargeCounterUah }
                .filter { it != PowerSample.UNAVAILABLE }
            if (counters.size < 2) {
                put("energyMeasured", false)
                put("note", "no usable charge counter on this device")
                return@apply
            }
            val usedUah = counters.first() - counters.last()
            val usedMah = usedUah / 1000.0
            put("energyMeasured", true)
            put("usedMah", round3(usedMah))
            val volts = samples.mapNotNull { it.voltageMv.takeIf { v -> v != PowerSample.UNAVAILABLE_INT } }
            if (volts.isNotEmpty()) {
                val meanV = volts.average() / 1000.0
                put("meanVoltageV", round3(meanV))
                put("usedMwh", round3(usedMah * meanV))
            }
            if (photosKept > 0 && usedMah > 0) {
                put("mahPerPhoto", round3(usedMah / photosKept))
                put("photosPerMah", round3(photosKept / usedMah))
            }
            put(
                "note",
                "usedMah is first-to-last charge counter on an unplugged run. currentUa " +
                    "sign convention varies by OEM and is recorded raw, not interpreted.",
            )
        }
    }

    private fun cores(): Double =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toDouble()

    /**
     * The session's DVFS drift, as one number to check before reading anything else.
     *
     * `driftPercent` is the last chunk's probe against the first: positive means the CPU got
     * slower over the session, so later chunks were measured on a slower machine. Anything
     * near the ~23% TC-06 saw means per-chunk trends in this report are not attributable to
     * the pipeline, and a comparison against another session is only safe if that session
     * drifted the same way.
     */
    private fun cpuProbeJson(diags: List<ChunkDiag>): JSONObject {
        val probes = diags.map { it.cpuProbeNs }.filter { it > 0L }
        return JSONObject().apply {
            put("n", probes.size)
            if (probes.isEmpty()) return@apply
            put("firstMs", round3(probes.first() / 1e6))
            put("lastMs", round3(probes.last() / 1e6))
            put("minMs", round3((probes.min()) / 1e6))
            put("maxMs", round3((probes.max()) / 1e6))
            put(
                "driftPercent",
                round3((probes.last() - probes.first()) * 100.0 / probes.first()),
            )
            put(
                "note",
                "fixed CPU workload timed before each chunk. Compare across chunks only; " +
                    "driftPercent well above 0 means later chunks ran on a slower CPU.",
            )
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
        for (entry in snapshotOf(chunks)) put(chunkJson(entry))
    }

    /** One chunk. Shared by [chunksJson] and the JSONL stream — see [loadSampleJson]. */
    internal fun chunkJson(entry: ChunkEntry): JSONObject =
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
                        put("cpuProbeMs", round3(diag.cpuProbeNs / 1e6))
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
                        entry.sharpness?.let { put("sharpness", sharpnessJson(it)) }
                        put("frames", framesJson(diag))
                        // An empty frames[] on a chunk that sampled frames means the budget
                        // ran out, not that nothing happened. Say which.
                        if (diag.frames.isEmpty() && diag.framesSampled > 0) {
                            put("framesOmitted", diag.framesSampled)
                        }
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
     *
     * Rendered from the summary taken in [addChunk], so it is unaffected by whether that
     * chunk's `frames[]` survived the budget.
     */
    private fun sharpnessJson(summary: SharpnessSummary): JSONObject = JSONObject().apply {
        put("cutoff", summary.cutoff)
        put("n", summary.n)
        if (summary.n == 0) return@apply
        put("min", round3(summary.min))
        put("p25", round3(summary.p25))
        put("p50", round3(summary.p50))
        put("p75", round3(summary.p75))
        put("max", round3(summary.max))
        put("belowCutoff", summary.belowCutoff)
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
        for (event in snapshotOf(events)) put(eventJson(event))
    }

    /** One event. Shared by [eventsJson] and the JSONL stream — see [loadSampleJson]. */
    internal fun eventJson(event: Event): JSONObject = JSONObject().apply {
        put("tMs", event.atMs - startedAtMs)
        put("type", event.type)
        put("chunk", event.chunkIndex ?: JSONObject.NULL)
        if (event.videoQueueDepth >= 0) put("videoQueue", event.videoQueueDepth)
        if (event.imageQueuePending >= 0) put("imageQueue", event.imageQueuePending)
    }

    private fun loadJson() = JSONArray().apply {
        for (sample in snapshotOf(load)) put(loadSampleJson(sample))
    }

    /**
     * One device-load sample.
     *
     * Shared by [loadJson] and by the JSONL stream on purpose: a recovered report must be
     * indistinguishable from one rendered in memory, and the cheapest way to guarantee that
     * is for there to be exactly one function that knows the shape.
     *
     * Absent readings are **omitted**, never written as zero. A missing battery gauge and a
     * battery at 0% must not read the same.
     */
    internal fun loadSampleJson(sample: LoadSample): JSONObject = JSONObject().apply {
        put("tMs", sample.atMs - startedAtMs)
        put("thermal", sample.thermalLabel)
        put("thermalLevel", sample.thermalLevel)
        put("usedRamMb", sample.usedRamMb)
        put("availRamMb", sample.availRamMb)
        if (sample.cpuMaxFreqKhz > 0) put("cpuMaxFreqKhz", sample.cpuMaxFreqKhz)
        if (!sample.socTempC.isNaN()) put("socTempC", round3(sample.socTempC))
        if (sample.gpuBusyPercent >= 0) put("gpuBusyPercent", sample.gpuBusyPercent)
        sample.vitals?.let { v ->
            put(
                "proc",
                JSONObject().apply {
                    // Cumulative ticks, not a rate — see ProcessVitals. The report differences
                    // consecutive samples in `totals.cpu`; keeping the raw counter here means a
                    // gap in sampling cannot be mistaken for idle time.
                    put("cpuJiffies", v.cpuJiffies)
                    put("javaHeapKb", v.javaHeapKb)
                    put("javaHeapMaxKb", v.javaHeapMaxKb)
                    put("nativeHeapKb", v.nativeHeapKb)
                    if (v.graphicsKb > 0) put("graphicsKb", v.graphicsKb)
                    if (v.totalPssKb > 0) put("totalPssKb", v.totalPssKb)
                    if (v.threadCpuJiffies.isNotEmpty()) {
                        put(
                            "threadCpuJiffies",
                            JSONObject().apply {
                                v.threadCpuJiffies.forEach { (name, ticks) -> put(name, ticks) }
                            },
                        )
                    }
                },
            )
        }
        sample.power?.let { p ->
            put(
                "power",
                JSONObject().apply {
                    if (p.chargeCounterUah != PowerSample.UNAVAILABLE) {
                        put("chargeUah", p.chargeCounterUah)
                    }
                    if (p.currentNowUa != PowerSample.UNAVAILABLE) put("currentUa", p.currentNowUa)
                    if (p.capacityPercent != PowerSample.UNAVAILABLE_INT) {
                        put("capacityPercent", p.capacityPercent)
                    }
                    if (p.voltageMv != PowerSample.UNAVAILABLE_INT) put("voltageMv", p.voltageMv)
                    if (!p.batteryTempC.isNaN()) put("batteryTempC", round3(p.batteryTempC))
                    if (p.thermalHeadroom >= 0f) {
                        put("thermalHeadroom", round3(p.thermalHeadroom.toDouble()))
                    }
                    put("charging", p.isCharging)
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
         *
         * 4 — the ARGB decode moved off the decoder thread, so **`yuv_jpeg_argb` no longer
         * exists**. The producer now reports `yuv_nv21` + `nv21_jpeg`, and the two decodes it
         * used to cover appear on the consumer side as `jpeg_argb_detect` (every frame, at
         * detect resolution) and `jpeg_argb_full` (only frames past the size gate). A schema-3
         * `yuv_jpeg_argb` share is not comparable with any single schema-4 stage; the nearest
         * equivalent is the sum of all four. Chunks also gained `cpuProbeMs` and totals gained
         * `cpuProbe`, so DVFS drift is visible instead of inferred.
         *
         * 5 — **additive only**; every schema-4 field is unchanged and in the same place, so
         * a schema-4 report and a schema-5 one are directly comparable (unlike 3 → 4). What
         * is new answers the two questions a schema-4 report could not:
         *
         * - *Were we the ones holding the memory?* `deviceLoad[].proc` and `totals.memory`
         *   are process-scoped. `usedRamMb` was, and still is, the whole machine.
         * - *How hard did we actually work, and where?* `totals.cpu` differences the CPU
         *   counter across the session and splits it by thread role, which distinguishes
         *   saturated detect workers from idle ones — a reading `queue_wait` cannot give.
         *
         * Also new: `totals.thermal` (battery °C, SoC °C where the device allows it, and the
         * platform's throttling forecast), `totals.power` (energy, or an explicit refusal to
         * state it for a session that was charging), and `env.probes` saying which optional
         * sysfs readings this device answered at all.
         *
         * A schema-5 report may also be *recovered* rather than rendered live — see
         * [PerfStream]. Recovered ones carry `session.recovered = true`.
         */
        const val SCHEMA_VERSION = 5
        const val FILE_NAME = "perf_report.json"

        /**
         * Stages timed on the decoder thread; everything else runs on a detect worker.
         *
         * `yuv_jpeg_argb` is kept here so a schema-3 report re-rendered by this build still
         * attributes its stages to the right thread.
         */
        private val PRODUCER_STAGES = setOf(
            "decode",
            "yuv_nv21",
            "nv21_jpeg",
            "yuv_jpeg_argb",
            "surface_rgba",
            "queue_wait",
        )

        /**
         * Backstops so a long session cannot grow the file without bound. All three are
         * reported in `truncation` when they bite — see [truncationJson].
         *
         * Sized against the real shape of a session rather than round numbers: a chunk emits
         * ~3 events and 2 load samples, and samples ~64 frames. A two-hour import is roughly
         * 950 chunks, so [MAX_EVENTS] covers it outright, [MAX_LOAD_SAMPLES] thins to one
         * point in four, and [MAX_FRAME_DIAGS] keeps full per-frame detail for the first
         * ~470 chunks — about 40 minutes, which is longer than every clip measured so far.
         */
        private const val MAX_EVENTS = 4000
        private const val MAX_LOAD_SAMPLES = 600

        /**
         * Sampled frames whose per-frame verdict is written out in full.
         *
         * One `FrameDiag` is ~180 bytes of pretty-printed JSON built through an in-memory
         * `JSONObject` tree, so this is the knob that decides whether `perf_report.json`
         * renders at all on a long run: 30,000 is ~5 MB of frames, 60,000 would be ~11 MB
         * plus several times that in tree overhead, at drain time.
         */
        private const val MAX_FRAME_DIAGS = 30_000
    }
}
