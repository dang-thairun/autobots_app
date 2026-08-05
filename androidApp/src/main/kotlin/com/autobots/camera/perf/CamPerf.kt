package com.autobots.camera.perf

import android.util.Log
import com.autobots.BuildConfig
import java.util.Locale

/**
 * Phase 0 instrumentation. Measurement only — nothing here may change camera or
 * pipeline behaviour. Gated by [BuildConfig.CAM_PERF] so it can stay in the tree.
 *
 * Everything lands in Logcat under a single tag: `CamPerf`.
 */
object CamPerf {
    const val TAG = "CamPerf"

    val enabled: Boolean = BuildConfig.CAM_PERF

    fun nowNs(): Long = System.nanoTime()

    inline fun log(block: () -> String) {
        if (enabled) Log.i(TAG, block())
    }

    /** Fresh accumulator, or null when instrumentation is off (callers stay allocation-free). */
    fun stageStats(): StageStats? = if (enabled) StageStats() else null

    /** Times [block] under [stage]. No-op wrapper when [stats] is null. */
    inline fun <T> timed(stats: StageStats?, stage: String, block: () -> T): T {
        if (stats == null) return block()
        val startNs = System.nanoTime()
        return try {
            block()
        } finally {
            stats.add(stage, System.nanoTime() - startNs)
        }
    }

    fun ms(ns: Long): String = String.format(Locale.US, "%.1fms", ns / 1_000_000.0)

    fun sec(ms: Long): String = String.format(Locale.US, "%.2fs", ms / 1000.0)

    /** Exposure time as a photographer's shutter speed. */
    fun shutter(exposureNs: Long): String {
        if (exposureNs <= 0L) return "—"
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
        }
    }

    fun afStateLabel(state: Int?): String = when (state) {
        null -> "none"
        0 -> "INACTIVE"
        1 -> "PASSIVE_SCAN"
        2 -> "PASSIVE_FOCUSED"
        3 -> "ACTIVE_SCAN"
        4 -> "FOCUSED_LOCKED"
        5 -> "NOT_FOCUSED_LOCKED"
        6 -> "PASSIVE_UNFOCUSED"
        else -> "UNKNOWN($state)"
    }

    fun aeStateLabel(state: Int?): String = when (state) {
        null -> "none"
        0 -> "INACTIVE"
        1 -> "SEARCHING"
        2 -> "CONVERGED"
        3 -> "LOCKED"
        4 -> "FLASH_REQUIRED"
        5 -> "PRECAPTURE"
        else -> "UNKNOWN($state)"
    }
}
