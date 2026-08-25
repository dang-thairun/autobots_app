package com.autobots.camera.load

/**
 * Lightweight device load snapshot for Operator UI (display-only, Flow 11) and for
 * `perf_report.json`.
 *
 * The first five fields are what the UI shows. Everything after them exists only for the
 * report and is optional: each degrades to a sentinel rather than being absent, so a series
 * always has the same shape and a missing reading is distinguishable from a zero one.
 */
data class DeviceLoadSnapshot(
    val thermalLabel: String,
    val thermalLevel: Int,
    /** Device-wide, **not** this process — see [vitals] for ours. */
    val usedRamMb: Long,
    val availRamMb: Long,
    val totalRamMb: Long,
    /**
     * Highest current core frequency in kHz, or 0 when the platform will not say.
     *
     * Recorded because [thermalLabel] reported OK for the whole of TC-06 while the session's
     * per-frame cost drifted ~23% — so the field the pipeline already sampled could not see
     * the throttling that was happening. Not shown in the UI; it exists for `perf_report.json`
     * and is a coarse cross-check on `cpuProbeMs`, which is the reading to trust.
     */
    val cpuMaxFreqKhz: Int = 0,
    /**
     * This process's own CPU and memory. Null when perf collection is off.
     *
     * The device-wide RAM fields above cannot answer *"were we the ones holding it?"* after a
     * long run dies, which is the question that matters — see [ProcessVitals].
     */
    val vitals: ProcessVitals? = null,
    /** Battery counters and heat. Null when perf collection is off. */
    val power: PowerSample? = null,
    /** SoC temperature in °C from sysfs, or NaN when this device will not report it. */
    val socTempC: Double = Double.NaN,
    /** GPU busy percentage from sysfs, or -1 when unavailable. */
    val gpuBusyPercent: Int = -1,
)
