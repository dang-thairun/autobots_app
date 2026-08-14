package com.autobots.camera.detection

import android.content.Context
import android.util.Log
import com.autobots.camera.DetectorBackend
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * Which detector backends this device can actually run, checked cheaply enough for the UI.
 *
 * Backends are not portable in the same way. ML Kit runs anywhere; the GPU delegate needs a
 * usable OpenCL driver; the NPU needs a Qualcomm HTP of the architecture the app ships skels
 * for, plus ~97 MB of libraries that are gitignored and have gone missing once already. Rather
 * than let the operator pick something that will silently fall back mid-session, the picker
 * greys the option out and says what is missing.
 *
 * Distinct from [DetectorProbe], which builds every backend *and runs inference* — that answers
 * "how fast", takes seconds, and only runs under `CamPerf`. This answers "can it start at all"
 * and is cheap enough to run at launch every time.
 */
object DetectorAvailability {
    private const val TAG = "DetectorAvail"

    /** @return null when the backend can run, otherwise a short reason for the UI. */
    fun check(context: Context, backend: DetectorBackend): String? = when (backend) {
        DetectorBackend.MlKitFast, DetectorBackend.MlKitAccurate -> null

        DetectorBackend.LiteRtCpu -> modelMissing(context)

        DetectorBackend.LiteRtGpu -> modelMissing(context) ?: gpuUnavailable()

        DetectorBackend.LiteRtNpu -> modelMissing(context) ?: QnnDelegate.unavailableReason(context)

        // Skips whatever cannot start and records the reason in the report, so it is always
        // runnable — with fewer entries than the operator might expect.
        DetectorBackend.CompareAll -> null
    }

    fun checkAll(context: Context): Map<DetectorBackend, String?> =
        DetectorBackend.entries.associateWith { check(context, it) }
            .also { result ->
                val bad = result.filterValues { it != null }
                if (bad.isNotEmpty()) Log.i(TAG, "unavailable: $bad")
            }

    private fun modelMissing(context: Context): String? =
        runCatching { context.assets.openFd(FaceDetLiteDetector.ASSET_PATH).close(); null }
            .getOrElse { "model asset missing" }

    /**
     * Creating the delegate is the honest check that costs milliseconds. Whether TFLite can
     * then *apply* it to this particular graph only shows up when an interpreter is built, and
     * that is too slow for launch — [FaceDetLiteDetector] falls back to CPU in that case and
     * records the real backend in `perf_report.json`, so a late failure is still visible.
     */
    private fun gpuUnavailable(): String? = runCatching {
        GpuDelegate(GpuDelegate.Options().setQuantizedModelsAllowed(true)).close()
        null
    }.getOrElse { "GPU delegate unavailable" }
}
