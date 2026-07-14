package com.autobots.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Capture still targets shown in Operator UI and applied to ImageCapture.
 */
object CaptureResolutions {
    /** Standard mode still target (16:9). */
    val STANDARD: Size = Size(1920, 1080)

    fun label(context: Context, mode: CaptureMode): String = when (mode) {
        CaptureMode.Standard -> format(STANDARD)
        CaptureMode.MaxSensor -> maxJpegSize(context)?.let(::format) ?: "Max sensor"
    }

    fun imageCaptureSelector(mode: CaptureMode): ResolutionSelector {
        val strategy = when (mode) {
            CaptureMode.Standard -> ResolutionStrategy(
                STANDARD,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            )
            CaptureMode.MaxSensor -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        }
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(strategy)
            .build()
    }

    fun captureModeQuality(mode: CaptureMode): Int = when (mode) {
        CaptureMode.Standard -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        CaptureMode.MaxSensor -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
    }

    fun format(size: Size): String = "${size.width}×${size.height}"

    fun maxJpegSize(context: Context): Size? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: return null

            val map = manager.getCameraCharacteristics(backId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            map.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        } catch (_: Throwable) {
            null
        }
    }
}
