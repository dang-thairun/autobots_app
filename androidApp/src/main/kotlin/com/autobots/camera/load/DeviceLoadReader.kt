package com.autobots.camera.load

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Samples thermal status + approximate RAM. Display-only — never throttles capture.
 *
 * @param detailed adds this process's own CPU/memory, battery counters and the sysfs probes.
 *   Off by default because **two readers exist**: the ViewModel's, which drives the UI, and
 *   the pipeline's, which feeds `perf_report.json`. Only the second one should pay for the
 *   extra reads — and one of them, `gpubusy`, *resets its counters when read*, so two
 *   instances sampling it would each see a fraction of the real busy time and neither would
 *   be wrong in a way that shows.
 */
class DeviceLoadReader(
    context: Context,
    private val detailed: Boolean = false,
    private val mainExecutor: Executor,
) {
    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val vitalsReader = if (detailed) ProcessVitalsReader(appContext) else null
    private val powerReader = if (detailed) PowerReader(appContext) else null
    private val sysfs = if (detailed) SysfsProbes() else null

    /** Which optional probes this device actually answers — written into the report's `env`. */
    val probeAvailability: Map<String, Boolean> get() = sysfs?.available ?: emptyMap()

    private val listenerRef = AtomicReference<((DeviceLoadSnapshot) -> Unit)?>(null)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun sample(): DeviceLoadSnapshot {
        val now = System.currentTimeMillis()
        val thermal = readThermal()
        val mem = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mem)
        val totalMb = mem.totalMem / BYTES_PER_MB
        val availMb = mem.availMem / BYTES_PER_MB
        val usedMb = (totalMb - availMb).coerceAtLeast(0)
        return DeviceLoadSnapshot(
            thermalLabel = thermal.label,
            thermalLevel = thermal.level,
            usedRamMb = usedMb,
            availRamMb = availMb,
            totalRamMb = totalMb,
            cpuMaxFreqKhz = readCpuMaxFreqKhz(),
            vitals = vitalsReader?.sample(now),
            power = powerReader?.sample(now),
            socTempC = sysfs?.socTempC() ?: Double.NaN,
            gpuBusyPercent = sysfs?.gpuBusyPercent() ?: -1,
        )
    }

    /**
     * Fastest core's current frequency, straight from sysfs.
     *
     * There is no platform API for this, and the sysfs path is not guaranteed: it is absent
     * on some kernels and unreadable to apps on others. Both cases return 0 rather than
     * throwing — this is a diagnostic, and a missing diagnostic must not affect a capture.
     *
     * The core count is discovered once and capped, because the files are read on every
     * thermal callback and a machine with many cores would otherwise turn a UI update into
     * a directory walk.
     */
    private fun readCpuMaxFreqKhz(): Int {
        var best = 0
        for (cpu in 0 until cpuProbeCount) {
            val value = runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
                    .readText()
                    .trim()
                    .toIntOrNull()
            }.getOrNull() ?: continue
            if (value > best) best = value
        }
        return best
    }

    private val cpuProbeCount: Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_CPUS_PROBED)

    fun start(onChange: (DeviceLoadSnapshot) -> Unit) {
        listenerRef.set(onChange)
        onChange(sample())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val listener = PowerManager.OnThermalStatusChangedListener {
            listenerRef.get()?.invoke(sample())
        }
        thermalListener = listener
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                powerManager.addThermalStatusListener(mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                powerManager.addThermalStatusListener(listener)
            }
            Log.i(TAG, "Thermal listener registered")
        } catch (t: Throwable) {
            Log.w(TAG, "addThermalStatusListener failed", t)
        }
    }

    fun stop() {
        listenerRef.set(null)
        val listener = thermalListener ?: return
        thermalListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager.removeThermalStatusListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "removeThermalStatusListener failed", t)
            }
        }
    }

    private fun readThermal(): ThermalReading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalReading("OK", PowerManager.THERMAL_STATUS_NONE)
        }
        return try {
            val status = powerManager.currentThermalStatus
            ThermalReading(labelFor(status), status)
        } catch (t: Throwable) {
            Log.w(TAG, "currentThermalStatus failed", t)
            ThermalReading("OK", PowerManager.THERMAL_STATUS_NONE)
        }
    }

    private fun labelFor(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "OK"
        PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "Hot"
        PowerManager.THERMAL_STATUS_SEVERE -> "Very hot"
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
        else -> "OK"
    }

    private data class ThermalReading(val label: String, val level: Int)

    companion object {
        private const val TAG = "DeviceLoad"
        private const val BYTES_PER_MB = 1024L * 1024L

        /** Enough to cover every cluster on a phone without walking a server's worth of cores. */
        private const val MAX_CPUS_PROBED = 16
    }
}
