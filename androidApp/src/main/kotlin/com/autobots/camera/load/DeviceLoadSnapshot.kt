package com.autobots.camera.load

/**
 * Lightweight device load snapshot for Operator UI (display-only, ADR 0011).
 */
data class DeviceLoadSnapshot(
    val thermalLabel: String,
    val thermalLevel: Int,
    val usedRamMb: Long,
    val availRamMb: Long,
    val totalRamMb: Long,
)
