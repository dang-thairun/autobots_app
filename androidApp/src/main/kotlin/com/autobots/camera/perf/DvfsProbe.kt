package com.autobots.camera.perf

/**
 * How fast is this CPU *right now*, in units that mean the same thing in every chunk.
 *
 * TC-06 found the session's per-frame cost drifting ~23% from first chunk to last while the
 * platform `thermal` API reported OK throughout — so the drift is real, invisible to the API
 * the pipeline already samples, and large enough to swallow the effects being measured. The
 * 4% gap between ML Kit and NPU in TC-08/09/10 sits inside it, which is why that comparison
 * is still undecided.
 *
 * The probe is a fixed integer workload, so its wall time is a direct readout of the clock the
 * app's own threads are getting — no sysfs permissions, no vendor-specific paths, and it
 * captures anything that slows a core down (frequency, contention, big/little placement)
 * rather than only the one thing a frequency file would report.
 *
 * Read `cpuProbeMs` across a report's chunks: flat means the run is comparable end to end, a
 * rising curve means later chunks were measured on a slower machine than earlier ones and any
 * per-chunk trend has to be divided out before it means anything.
 *
 * Measurement only, and only when [CamPerf.enabled]. ~5 ms per chunk against an ~11 s chunk.
 */
object DvfsProbe {

    /**
     * Sized to run a few ms on a modern phone core: long enough to average over the
     * scheduler's tick, short enough to be noise against a chunk.
     */
    private const val ITERATIONS = 2_000_000

    /**
     * The **minimum** of several rounds is the reading. A round can only ever be lengthened
     * by preemption, so the fastest one is the closest to the clock the core is actually
     * running at — a mean would mostly measure how busy the rest of the phone was.
     */
    private const val ROUNDS = 3

    /**
     * Kept so the JIT cannot decide the loop below is dead code and delete the thing being
     * timed. Volatile because a plain field it can prove nobody reads is no obstacle.
     */
    @Volatile
    private var sink: Long = 0

    /** @return the fastest round's duration in nanoseconds, or 0 when instrumentation is off. */
    fun measureNs(): Long {
        if (!CamPerf.enabled) return 0L
        var best = Long.MAX_VALUE
        repeat(ROUNDS) {
            val startNs = System.nanoTime()
            var acc = 1L
            for (i in 1..ITERATIONS) {
                acc = acc * 31L + i
            }
            val elapsedNs = System.nanoTime() - startNs
            sink = acc
            if (elapsedNs < best) best = elapsedNs
        }
        return best
    }
}
