# 07 — Thermal Management

> **MVP status: DEFERRED** — ไม่มี ThermalGuard / auto-throttle ใน MVP ([ADR 0008](./adr/0008-defer-thermal-past-mvp.md)).  
> MVP มีแค่ **Device Load Readout** (แสดง thermal + RAM) ([ADR 0011](./adr/0011-device-load-readout-mvp.md)).  
> เนื้อหาด้านล่างเก็บไว้สำหรับ post-MVP

## Problem

งานมาราธอนกินเวลา 2–6 ชั่วโมง — อุปกรณ์จะร้อน:
- กล้องเร็วถูก throttle โดย OS
- CPU/GPU clock down → AI inference ช้าลง → missed captures
- ในกรณีรุนแรง: OS force-stop แอป หรือ camera hardware ถูก reset

Post-MVP ควรจัดการ thermal อย่าง **pro-active** (ก่อนที่ OS จะ throttle เอง)

---

## Android Thermal API (API 29+)

```
PowerManager.THERMAL_STATUS_NONE       = 0  → Normal
PowerManager.THERMAL_STATUS_LIGHT      = 1  → Slightly warm
PowerManager.THERMAL_STATUS_MODERATE   = 2  → Warm (reduce activity)
PowerManager.THERMAL_STATUS_SEVERE     = 3  → Hot (significantly reduce)
PowerManager.THERMAL_STATUS_CRITICAL   = 4  → Very hot (emergency mode)
PowerManager.THERMAL_STATUS_EMERGENCY  = 5  → About to shutdown
PowerManager.THERMAL_STATUS_SHUTDOWN   = 6  → Shutting down
```

เราตั้ง threshold ที่ **MODERATE** = เริ่ม throttle, **SEVERE** = aggressive throttle / pause captures

---

## `AndroidThermalMonitor.kt` (androidMain)

```kotlin
package com.autobots.camera.thermal

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(29)
class AndroidThermalMonitor(context: Context) : ThermalMonitor {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile override var currentStatus: ThermalStatus = ThermalStatus.NONE
        private set

    private var listener: ((ThermalStatus) -> Unit)? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val mapped = ThermalStatus.from(status)
        if (mapped != currentStatus) {
            currentStatus = mapped
            Log.w("ThermalMonitor", "Thermal status → $mapped (raw=$status)")
            listener?.invoke(mapped)
        }
    }

    override fun start(onStatusChange: (ThermalStatus) -> Unit) {
        listener = onStatusChange
        powerManager.addThermalStatusListener(thermalListener)
        // Poll current status immediately
        currentStatus = ThermalStatus.from(powerManager.currentThermalStatus)
        onStatusChange(currentStatus)
        Log.i("ThermalMonitor", "Started. Initial status: $currentStatus")
    }

    override fun stop() {
        powerManager.removeThermalStatusListener(thermalListener)
        listener = null
        Log.i("ThermalMonitor", "Stopped")
    }
}
```

---

## Thermal Throttle Policy

```kotlin
// commonMain — ThermalThrottlePolicy.kt
object ThermalThrottlePolicy {

    data class CaptureConfig(
        val minCooldownMs: Long,      // minimum time between captures
        val maxQueueSize: Int,        // max items in capture queue
        val jpegQuality: Int,         // lower quality = faster I/O
        val faceThreshold: Float,     // higher threshold = fewer triggers
        val captureEnabled: Boolean   // false = all captures paused
    )

    fun forStatus(status: ThermalStatus): CaptureConfig = when (status) {
        ThermalStatus.NONE, ThermalStatus.LIGHT -> CaptureConfig(
            minCooldownMs    = 300L,
            maxQueueSize     = 20,
            jpegQuality      = 95,
            faceThreshold    = 0.40f,
            captureEnabled   = true
        )
        ThermalStatus.MODERATE -> CaptureConfig(
            minCooldownMs    = 800L,    // slower capture rate
            maxQueueSize     = 10,
            jpegQuality      = 85,     // reduce I/O load
            faceThreshold    = 0.50f,  // only very close faces
            captureEnabled   = true
        )
        ThermalStatus.SEVERE -> CaptureConfig(
            minCooldownMs    = 2000L,   // max 1 capture per 2 seconds
            maxQueueSize     = 5,
            jpegQuality      = 75,
            faceThreshold    = 0.60f,
            captureEnabled   = true
        )
        ThermalStatus.CRITICAL,
        ThermalStatus.EMERGENCY,
        ThermalStatus.SHUTDOWN -> CaptureConfig(
            minCooldownMs    = Long.MAX_VALUE,
            maxQueueSize     = 0,
            jpegQuality      = 0,
            faceThreshold    = 1.0f,
            captureEnabled   = false   // pause ALL captures
        )
    }
}
```

---

## Integration in FaceAnalyzer

```kotlin
// FaceAnalyzer.kt — updated to use ThermalThrottlePolicy
class FaceAnalyzer(
    private val detector: FaceDetector,
    private val captureStrategy: CaptureStrategy,
    private val thermalMonitor: ThermalMonitor
) : ImageAnalysis.Analyzer {

    @Volatile private var policy = ThermalThrottlePolicy.forStatus(ThermalStatus.NONE)
    private var lastCaptureMs    = 0L

    init {
        thermalMonitor.start { newStatus ->
            policy = ThermalThrottlePolicy.forStatus(newStatus)
            captureStrategy.applyThermalThrottle(newStatus)
            android.util.Log.w("FaceAnalyzer", "Policy updated: cooldown=${policy.minCooldownMs}ms")
        }
    }

    override fun analyze(image: ImageProxy) {
        if (!policy.captureEnabled) {
            image.close(); return   // emergency mode — do nothing
        }

        val now = System.currentTimeMillis()
        if (now - lastCaptureMs < policy.minCooldownMs) {
            image.close(); return   // still in cooldown
        }

        val nv21  = imageProxyToNv21(image)
        val faces = detector.detect(nv21, image.width, image.height)

        if (ProximityCalculator.shouldCapture(faces, policy.faceThreshold)) {
            lastCaptureMs = now
            analyzerScope.launch {
                captureStrategy.capture(
                    FrameContext(
                        timestampMs = now,
                        rotationDeg = image.imageInfo.rotationDegrees,
                        faces       = faces
                    )
                )
            }
        }
        image.close()
    }
}
```

---

## Additional Cooling Strategies

### 1. Use WakeLock Wisely

```kotlin
// Avoid FULL_WAKE_LOCK — it prevents CPU frequency scaling
// Use FLAG_KEEP_SCREEN_ON via WindowManager instead
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

### 2. Reduce Preview Load When Mounted on Tripod

```kotlin
// Optional: hide preview when on tripod (saves GPU power)
// Show only a status overlay
binding.previewView.visibility = View.GONE
```

### 3. Foreground Service for Long Sessions

```kotlin
// CameraForegroundService.kt
class CameraForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()   // required API 26+
        startForeground(NOTIF_ID, notification)
        // Init camera pipeline here
        return START_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("AutoBots Sports Camera")
        .setContentText("Recording... Thermal: ${thermalMonitor.currentStatus}")
        .setSmallIcon(R.drawable.ic_camera)
        .setOngoing(true)
        .build()
}
```

### 4. Thermal Logging for Analysis

```kotlin
// Log thermal events to a CSV for post-race analysis
data class ThermalEvent(
    val timestampMs: Long,
    val status: ThermalStatus,
    val captureCount: Int
)
// Write to: outputDir/thermal_log_${sessionId}.csv
```

---

## Thermal Status Flow Diagram

```
Session Start
      │
      ▼
  NONE / LIGHT ──── normal operation ───────────────────────────┐
      │ (device warms up after ~30min heavy use)                │
      ▼                                                          │
  MODERATE ─── throttle: +cooldown, raise threshold ──────────  │
      │ (if cooling continues)                                   │
      ▼                                                          │
  SEVERE ──── aggressive throttle: 1 capture / 2s ──────────── │
      │ (emergency)                                              │
      ▼                                                          │
  CRITICAL / EMERGENCY ── PAUSE all captures                    │
      │ (device cools if user stops running heavy tasks)         │
      └──────────────────────────────────────────────────────────┘
                         (status improves → policy relaxes)
```
