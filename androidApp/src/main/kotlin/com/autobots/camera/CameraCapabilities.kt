package com.autobots.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import java.util.Locale

/**
 * What the device will actually let us set. Read once per bind.
 *
 * Phase 0 only logs this. Phase 1 uses it to clamp every exposure/ISO/focus value
 * to the supported range and to pick the manual vs. AE-constrained path.
 */
@OptIn(ExperimentalCamera2Interop::class)
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: String,
    val hasManualSensor: Boolean,
    val exposureTimeRangeNs: Range<Long>?,
    val sensitivityRange: Range<Int>?,
    val minFocusDistanceDiopters: Float?,
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStepEv: Double?,
    val supportedFrameRateRanges: List<Range<Int>>,
    val zslSupported: Boolean,
) {
    /** A fixed-focus lens reports 0.0 and cannot be driven manually. */
    val hasManualFocus: Boolean
        get() = (minFocusDistanceDiopters ?: 0f) > 0f

    fun summary(): String = buildString {
        append("┌─ camera capabilities\n")
        append("│ id=$cameraId  level=$hardwareLevel\n")
        append("│ MANUAL_SENSOR   ${yesNo(hasManualSensor)}   <-- decides Phase 1 path A vs B\n")
        append("│ exposure range  ${exposureRangeLabel()}\n")
        append("│ iso range       ${sensitivityRange?.let { "${it.lower} … ${it.upper}" } ?: "unavailable"}\n")
        append("│ min focus       ${focusLabel()}\n")
        append("│ ev compensation ${evLabel()}\n")
        append("│ fps ranges      ${fpsLabel()}\n")
        append("└ zsl supported   ${yesNo(zslSupported)}   (unused on the video path)")
    }

    private fun exposureRangeLabel(): String {
        val range = exposureTimeRangeNs ?: return "unavailable (no manual exposure)"
        return String.format(
            Locale.US,
            "%,dns … %,dns  (%s … %s)",
            range.lower,
            range.upper,
            shutter(range.lower),
            shutter(range.upper),
        )
    }

    private fun focusLabel(): String {
        val diopters = minFocusDistanceDiopters ?: return "unavailable"
        return if (diopters <= 0f) {
            "0.0 diopters — FIXED FOCUS lens, manual focus impossible"
        } else {
            String.format(Locale.US, "%.2f diopters (closest %.2f m)", diopters, 1f / diopters)
        }
    }

    private fun evLabel(): String {
        val range = aeCompensationRange ?: return "unavailable"
        val step = aeCompensationStepEv ?: return "${range.lower} … ${range.upper} (step unknown)"
        return String.format(
            Locale.US,
            "%d … %d steps of %.3f EV  (%.1f … %.1f EV)",
            range.lower,
            range.upper,
            step,
            range.lower * step,
            range.upper * step,
        )
    }

    private fun fpsLabel(): String =
        if (supportedFrameRateRanges.isEmpty()) {
            "unavailable"
        } else {
            supportedFrameRateRanges.joinToString(" ") { "[${it.lower},${it.upper}]" }
        }

    private fun yesNo(value: Boolean) = if (value) "YES" else "no"

    private fun shutter(exposureNs: Long): String {
        if (exposureNs <= 0L) return "—"
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
        }
    }

    companion object {
        fun read(cameraInfo: CameraInfo): CameraCapabilities? = runCatching {
            val camera2Info = Camera2CameraInfo.from(cameraInfo)
            val capabilities = camera2Info
                .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toList()
                .orEmpty()

            CameraCapabilities(
                cameraId = camera2Info.cameraId,
                hardwareLevel = hardwareLevelLabel(
                    camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                    ),
                ),
                hasManualSensor = capabilities.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                ),
                exposureTimeRangeNs = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                ),
                sensitivityRange = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                ),
                minFocusDistanceDiopters = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
                ),
                aeCompensationRange = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
                ),
                aeCompensationStepEv = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                )?.toDouble(),
                supportedFrameRateRanges = runCatching {
                    cameraInfo.supportedFrameRateRanges.sortedBy { it.upper }
                }.getOrDefault(emptyList()),
                zslSupported = runCatching { cameraInfo.isZslSupported }.getOrDefault(false),
            )
        }.getOrNull()

        private fun hardwareLevelLabel(level: Int?): String = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($level)"
        }
    }
}
