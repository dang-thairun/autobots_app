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
)
