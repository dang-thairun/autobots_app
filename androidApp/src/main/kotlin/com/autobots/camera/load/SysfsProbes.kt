package com.autobots.camera.load

import android.util.Log
import java.io.File

/**
 * Readings the platform has no API for, taken straight from sysfs.
 *
 * Nothing in here is guaranteed to exist. The paths differ by SoC vendor, kernel version and
 * OEM, and SELinux blocks several of them outright on hardened builds — so every probe
 * **resolves once at construction** and is then either a live file handle or permanently off.
 * That is the same shape [DeviceLoadReader.readCpuMaxFreqKhz] already uses, and it is the
 * only honest one: a diagnostic that might work is worse than one that says whether it does.
 *
 * [available] is written into the report so a run with no SoC temperature reads as *"this
 * device would not tell us"* rather than as *"the phone stayed cold"*.
 */
class SysfsProbes {

    /**
     * The thermal zone that best represents the SoC, chosen once.
     *
     * A phone exposes anywhere from 10 to 100 zones covering the battery, the charger, the
     * modem, the display and several points on the die. Picking by `type` is the only way to
     * get a comparable reading across runs; picking the hottest zone would silently follow
     * the charger while the cable is in.
     */
    private val socTempFile: File? = resolveSocTempZone()

    /** Adreno's busy counter. Absent on Mali, and unreadable to apps on many builds. */
    private val gpuBusyFile: File? = GPU_BUSY_PATHS
        .map(::File)
        .firstOrNull { file -> runCatching { file.readText() }.isSuccess }

    val available: Map<String, Boolean> = mapOf(
        "socTempC" to (socTempFile != null),
        "gpuBusyPercent" to (gpuBusyFile != null),
    )

    init {
        Log.i(TAG, "sysfs probes: socTemp=${socTempFile?.path ?: "n/a"} gpu=${gpuBusyFile?.path ?: "n/a"}")
    }

    /**
     * SoC temperature in °C, or NaN.
     *
     * Kernels report either millidegrees or degrees in this file with no way to tell them
     * apart other than magnitude, so the scale is inferred: nothing inside a phone is at
     * 1,000 °C, and nothing running is at 0.045 °C.
     */
    fun socTempC(): Double {
        val raw = readLong(socTempFile) ?: return Double.NaN
        return when {
            raw > 1_000 -> raw / 1000.0
            raw > 0 -> raw.toDouble()
            else -> Double.NaN
        }
    }

    /**
     * GPU busy as a percentage, or -1.
     *
     * `gpubusy` reports two numbers — busy and total since the last read — and **reading it
     * resets the counters**, so the value is already an interval average and must not be
     * sampled from two places at once.
     */
    fun gpuBusyPercent(): Int {
        val text = runCatching { gpuBusyFile?.readText() }.getOrNull()?.trim() ?: return -1
        val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
        val busy = parts.getOrNull(0)?.toLongOrNull() ?: return -1
        val total = parts.getOrNull(1)?.toLongOrNull() ?: return -1
        if (total <= 0L) return -1
        return ((busy * 100.0) / total).toInt().coerceIn(0, 100)
    }

    private fun readLong(file: File?): Long? =
        runCatching { file?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    private fun resolveSocTempZone(): File? {
        val root = File("/sys/class/thermal")
        val zones = runCatching { root.listFiles { f -> f.name.startsWith("thermal_zone") } }
            .getOrNull() ?: return null
        // Ordered by preference: a CPU cluster reading beats a generic SoC one, which beats
        // the GPU, which still beats nothing.
        for (wanted in SOC_ZONE_TYPES) {
            for (zone in zones) {
                val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                    ?: continue
                if (!type.contains(wanted, ignoreCase = true)) continue
                val temp = File(zone, "temp")
                if (runCatching { temp.readText() }.isSuccess) return temp
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SysfsProbes"

        /**
         * Qualcomm names CPU cluster zones `cpu-1-0-usr` and similar; `apc` and `soc` are the
         * package-level ones. Matched as substrings because the numbering is per-SKU.
         */
        private val SOC_ZONE_TYPES = listOf("cpu-1", "cpu-0", "apc", "soc", "gpu")

        /**
         * Adreno only, and only the stable symlink — the real path underneath is
         * `/sys/devices/platform/soc/<addr>.qcom,kgsl-3d0/...` with a per-SoC address, which
         * cannot be written down here and is what the symlink exists to hide.
         */
        private val GPU_BUSY_PATHS = listOf("/sys/class/kgsl/kgsl-3d0/gpubusy")
    }
}
