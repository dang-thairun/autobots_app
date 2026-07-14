package com.autobots.camera.network

import kotlinx.serialization.Serializable

/**
 * Commands sent from iPadOS remote controller to Android camera server.
 */
@Serializable
data class RemoteControlCommand(
    val action: String, // "TOGGLE_CAPTURE", "SET_CAPTURE_MODE", "SET_ARM_THRESHOLD", "SET_FIRE_THRESHOLD"
    val captureMode: String? = null, // "Standard" or "MaxSensor"
    val armThreshold: Float? = null,
    val fireThreshold: Float? = null
) {
    companion object {
        const val ACTION_TOGGLE_CAPTURE = "TOGGLE_CAPTURE"
        const val ACTION_SET_CAPTURE_MODE = "SET_CAPTURE_MODE"
        const val ACTION_SET_ARM_THRESHOLD = "SET_ARM_THRESHOLD"
        const val ACTION_SET_FIRE_THRESHOLD = "SET_FIRE_THRESHOLD"
    }
}

/**
 * State updates broadcasted from Android camera server to iPadOS remote controller.
 */
@Serializable
data class RemoteStateUpdate(
    val isCapturing: Boolean,
    val captureMode: String, // "Standard" or "MaxSensor"
    val isArmed: Boolean,
    val lastFired: Boolean,
    val passageGateOpen: Boolean,
    val keptPhotoCount: Int,
    val lastBurstSaved: Int,
    val lastGalleryUri: String? = null,
    val isBursting: Boolean,
    val thermalLabel: String,
    val thermalLevel: Int,
    val usedRamMb: Long,
    val totalRamMb: Long,
    val faceCount: Int,
    val proximity: Float,
    val armThreshold: Float,
    val fireThreshold: Float,
    val exposureLine: String,
    val deviceLoadLine: String,
    val lastPhotoUrl: String? = null // e.g. "http://192.168.1.15:8080/photos/pic_123.jpg"
)
