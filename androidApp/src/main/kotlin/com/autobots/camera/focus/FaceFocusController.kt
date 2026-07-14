package com.autobots.camera.focus

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import com.autobots.camera.detection.NormalizedFaceBox
import java.util.concurrent.TimeUnit

/**
 * Phase 4 Face Lock — AF + AE on Subject Face via PreviewView metering points.
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
        val control = cameraControl ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockElapsedMs < MIN_INTERVAL_MS) return
        lastLockElapsedMs = now

        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val cx = ((box.left + box.right) / 2f) * viewW
        val cy = ((box.top + box.bottom) / 2f) * viewH
        val afSize = (box.width * 0.5f).coerceIn(0.1f, 0.5f)
        val aeSize = (box.width * 0.8f).coerceIn(0.15f, 0.7f)

        try {
            val factory = previewView.meteringPointFactory
            val afPoint = factory.createPoint(cx, cy, afSize)
            val aePoint = factory.createPoint(cx, cy, aeSize)
            val action = FocusMeteringAction.Builder(
                afPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
                .addPoint(aePoint, FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(AUTO_CANCEL_SEC, TimeUnit.SECONDS)
                .build()
            control.startFocusAndMetering(action)
            Log.d(TAG, "Face Lock cx=${cx / viewW} cy=${cy / viewH}")
        } catch (t: Throwable) {
            Log.w(TAG, "Face Lock failed: ${t.message}")
        }
    }

    fun cancel() {
        runCatching { cameraControl?.cancelFocusAndMetering() }
    }

    companion object {
        private const val TAG = "FaceFocus"
        private const val MIN_INTERVAL_MS = 100L
        private const val AUTO_CANCEL_SEC = 3L
    }
}
