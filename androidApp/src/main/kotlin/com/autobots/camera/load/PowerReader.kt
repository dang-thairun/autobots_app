package com.autobots.camera.load

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Battery and heat, sampled as raw counters.
 *
 * ### The charging trap
 *
 * Every long test so far was run with the cable in, and a charging phone's
 * [chargeCounterUah] *rises*. Energy figures taken from such a run are not merely inaccurate,
 * they have the wrong sign. That is why [isCharging] is recorded on **every** sample rather
 * than once per session: the report can then refuse to state an energy figure for a session
 * that was plugged in at any point, instead of quietly reporting a negative drain.
 *
 * ### Why not just read percent
 *
 * `BATTERY_PROPERTY_CAPACITY` is an integer percent. Over a 30-minute run it moves a handful
 * of steps, so differencing it gives a number with about one significant figure.
 * [chargeCounterUah] is in microamp-hours and moves continuously, which is what makes
 * "mAh per photo" a real measurement rather than a rounding artefact.
 *
 * ### Temperature
 *
 * The SoC's own temperature is not exposed to apps. Two proxies are, and they fail in
 * different directions, so both are recorded: [batteryTempC] is a real temperature but lags
 * the die badly, and [thermalHeadroom] is a forecast of how close the system thinks it is to
 * throttling — the number that actually predicts the DVFS drift `cpuProbeMs` measures after
 * the fact.
 */
data class PowerSample(
    /** Remaining charge in µAh, or [UNAVAILABLE] when the gauge will not say. */
    val chargeCounterUah: Long,
    /** Instantaneous current in µA. Sign convention varies by OEM — calibrate before trusting. */
    val currentNowUa: Long,
    /** Battery level 0–100, or [UNAVAILABLE]. */
    val capacityPercent: Int,
    /** Battery voltage in mV, or [UNAVAILABLE]. */
    val voltageMv: Int,
    /** Battery temperature in °C, or `Double.NaN`. */
    val batteryTempC: Double,
    /** True when plugged in — see the class note. */
    val isCharging: Boolean,
    /** 0.0 = cold, 1.0 = throttling now. Negative when the platform will not forecast. */
    val thermalHeadroom: Float,
) {
    companion object {
        const val UNAVAILABLE = Long.MIN_VALUE
        const val UNAVAILABLE_INT = Int.MIN_VALUE
    }
}

/**
 * Reads [PowerSample]. Every field degrades to "unavailable" rather than throwing, because
 * this is a diagnostic and a diagnostic must never be able to stop a capture.
 */
class PowerReader(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * `getThermalHeadroom` is rate-limited by the platform: calling it more often than once a
     * second returns `NaN` and, on some builds, logs a warning per call. The pipeline samples
     * load far more often than that around chunk boundaries, so the last good value is
     * carried forward instead of turning a rate limit into a hole in the series.
     */
    private var lastHeadroomAtMs = 0L
    private var lastHeadroom = -1f

    fun sample(nowMs: Long): PowerSample {
        val sticky = readSticky()
        return PowerSample(
            chargeCounterUah = intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.toLong() ?: PowerSample.UNAVAILABLE,
            currentNowUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.toLong() ?: PowerSample.UNAVAILABLE,
            capacityPercent = intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?: PowerSample.UNAVAILABLE_INT,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 } ?: PowerSample.UNAVAILABLE_INT,
            batteryTempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { it / 10.0 } ?: Double.NaN,
            isCharging = readCharging(sticky),
            thermalHeadroom = readHeadroom(nowMs),
        )
    }

    /**
     * The sticky `ACTION_BATTERY_CHANGED` broadcast. Registering with a null receiver returns
     * the last value immediately without ever subscribing — no unregister to leak, and no
     * callback arriving on a thread the pipeline does not own.
     */
    private fun readSticky(): Intent? = runCatching {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun readCharging(sticky: Intent?): Boolean {
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != 0) return true
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun intProperty(id: Int): Int? {
        val manager = batteryManager ?: return null
        val value = runCatching { manager.getIntProperty(id) }.getOrNull() ?: return null
        // The framework returns Integer.MIN_VALUE for "this device has no such gauge".
        return value.takeIf { it != Int.MIN_VALUE }
    }

    private fun readHeadroom(nowMs: Long): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1f
        if (nowMs - lastHeadroomAtMs < HEADROOM_INTERVAL_MS) return lastHeadroom
        lastHeadroomAtMs = nowMs
        val value = runCatching {
            powerManager?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
        }.onFailure {
            Log.w(TAG, "getThermalHeadroom failed: ${it.message}")
        }.getOrNull()
        if (value != null && !value.isNaN()) lastHeadroom = value
        return lastHeadroom
    }

    companion object {
        private const val TAG = "PowerReader"

        /** The platform's own floor is one second; stay clear of it. */
        private const val HEADROOM_INTERVAL_MS = 2_000L

        /**
         * How far ahead to forecast. Ten seconds is long enough to lead the throttle and
         * short enough that the platform still has a basis for the estimate.
         */
        private const val HEADROOM_FORECAST_SECONDS = 10
    }
}
