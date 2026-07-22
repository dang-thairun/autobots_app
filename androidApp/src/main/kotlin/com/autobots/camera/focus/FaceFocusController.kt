package com.autobots.camera.focus

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import com.autobots.camera.FocusStrategy
import com.autobots.camera.detection.NormalizedFaceBox

/**
 * Face metering on Subject Face (Flow 10 / 13 / 18).
 * [meterExposureOnFace] = tripod default (AE only, no AF hunt).
 * [lockOnFace] = FaceAf fallback (AF + AE).
 */
class FaceFocusController(
    private val previewView: PreviewView,
) {
    @Volatile
    private var cameraControl: CameraControl? = null

    private var lastLockElapsedMs: Long = 0L

    fun attach(control: CameraControl) {
        cameraControl = control
    }

    fun detach() {
        cancel()
        cameraControl = null
    }

    fun lockOnFace(box: NormalizedFaceBox) {
        meterOnFace(box, FocusStrategy.FaceAf)
    }

    /** Tripod path — AE only, faster than AF+AE each frame. */
    fun meterExposureOnFace(box: NormalizedFaceBox) {
        meterOnFace(box, FocusStrategy.Fixed)
    }

    private fun meterOnFace(box: NormalizedFaceBox, strategy: FocusStrategy) {
        val control = cameraControl ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockElapsedMs < MIN_INTERVAL_MS) return
        lastLockElapsedMs = now

        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val cx = ((box.left + box.right) / 2f) * viewW
        val cy = ((box.top + box.bottom) / 2f) * viewH
        val regionSize = (box.width * 0.7f).coerceIn(0.15f, 0.7f)

        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(cx, cy, regionSize)
            val flags = when (strategy) {
                FocusStrategy.Fixed -> FocusMeteringAction.FLAG_AE
                FocusStrategy.FaceAf -> FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            }
            // Flow 13: no setAutoCancelDuration — cancel on detach / passage end.
            val action = FocusMeteringAction.Builder(point, flags).build()
            control.startFocusAndMetering(action)
            Log.d(TAG, "meter strategy=$strategy cx=${cx / viewW} cy=${cy / viewH}")
        } catch (t: Throwable) {
            Log.w(TAG, "meter failed: ${t.message}")
        }
    }

    fun cancel() {
        runCatching { cameraControl?.cancelFocusAndMetering() }
    }

    companion object {
        private const val TAG = "FaceFocus"
        private const val MIN_INTERVAL_MS = 50L
    }
}
