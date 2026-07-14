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
 */
class DeviceLoadReader(
    context: Context,
    private val mainExecutor: Executor,
) {
    private val appContext = context.applicationContext
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val listenerRef = AtomicReference<((DeviceLoadSnapshot) -> Unit)?>(null)
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    fun sample(): DeviceLoadSnapshot {
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
        )
    }

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
    }
}
