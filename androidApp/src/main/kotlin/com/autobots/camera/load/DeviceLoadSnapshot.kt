package com.autobots.camera.load

/**
 * Lightweight device load snapshot for Operator UI (display-only, Flow 11).
 */
data class DeviceLoadSnapshot(
    val thermalLabel: String,
    val thermalLevel: Int,
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
)
