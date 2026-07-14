# 05 — Max-Sensor Capture Mode (A/B Option)

> **MVP status:** No longer “Phase 2 only” — this is the **Max-Sensor Capture Mode** operator option ([ADR 0007](./adr/0007-capture-mode-ab-toggle.md)).  
> When enabled: **1 shot per Passage** (not a 3-shot Lean Burst). Factory default remains **Standard** mode.  
> Historical “Phase 1 vs Phase 2” wording below = Standard vs Max-Sensor.

## Overview

Max-Sensor mode ใช้ `CameraX ImageCapture` ที่ความละเอียดเซ็นเซอร์สูงสุด (เช่น 50MP)  
เหมาะเมื่อ operator ต้องการ detail สูงและยอมรับ RAM / shutter lag / ความร้อนมากขึ้น

---

## Phase 1 vs Phase 2 Comparison

| | Phase 1 (Frame Extract) | Phase 2 (ImageCapture) |
|--|------------------------|------------------------|
| **Resolution** | 8.3MP (4K frame) | 50MP (full sensor) |
| **Shutter Lag** | **≈ 0ms** ✅ | 150–600ms ❌ |
| **RAM per shot** | ~24MB (YUV 4K) | ~200MB (YUV 50MP) |
| **File size** | 4–8 MB JPEG | 15–30 MB JPEG |
| **Complexity** | Medium | Low (CameraX handles it) |
| **Risk for runners** | Low (captures correct moment) | High (runner may have passed) |
| **Use case** | Live race, fast capture | Portrait style, slow approach |

---

## Architecture: A/B Toggle

การสลับ Phase 1 ↔ Phase 2 ทำได้ผ่าน `CaptureStrategy` interface — ไม่ต้องเปลี่ยน code อื่น:

```kotlin
// CameraViewModel.kt
class CameraViewModel(context: Context) : ViewModel() {

    private val captureQueue = AdaptiveCaptureQueue(outputDir)

    // A/B toggle — read from config/feature flag
    val captureStrategy: CaptureStrategy = if (FeatureFlags.useHighResSensor) {
        ImageCaptureStrategy(context, imageCapture, captureQueue)   // Phase 2
    } else {
        FrameExtractStrategy(captureQueue)                          // Phase 1 (default)
    }
}
```

---

## `ImageCaptureStrategy.kt` (androidMain)

```kotlin
package com.autobots.camera.capture

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.autobots.camera.thermal.ThermalStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImageCaptureStrategy(
    private val imageCapture: ImageCapture,
    private val executor: Executor,
    private val queue: AdaptiveCaptureQueue,
    private val jpegQuality: Int = 95
) : CaptureStrategy {

    @Volatile private var thermalStatus = ThermalStatus.NONE

    // Prevent concurrent captures — ImageCapture is not re-entrant
    private val captureMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun capture(frameContext: FrameContext): CaptureResult {
        if (thermalStatus >= ThermalStatus.SEVERE) {
            return CaptureResult.Error("Thermal throttle: capture paused (${thermalStatus})")
        }

        // Ensure only one ImageCapture in-flight at a time to avoid OOM
        if (!captureMutex.tryLock()) {
            return CaptureResult.Error("Previous capture still in progress — skipping")
        }

        return try {
            val jpegBytes = takePhotoSuspend()
            queue.enqueue(CaptureJob(jpegBytes, frameContext.timestampMs))
            CaptureResult.Success(jpegBytes.size.toLong())
        } catch (e: Exception) {
            CaptureResult.Error(e.message ?: "ImageCapture failed")
        } finally {
            captureMutex.unlock()
        }
    }

    /**
     * Wrap ImageCapture.takePicture() in a coroutine-friendly suspend function.
     * Using IN_MEMORY capture to avoid double disk write.
     */
    private suspend fun takePhotoSuspend(): ByteArray =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {

                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        try {
                            // ⚠️ ImageProxy MUST be closed quickly — it holds a large RAM allocation
                            val buffer = imageProxy.planes[0].buffer
                            val bytes  = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            continuation.resume(bytes)
                        } finally {
                            imageProxy.close()  // CRITICAL — release ~200MB immediately
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }

    override fun applyThermalThrottle(status: ThermalStatus) {
        thermalStatus = status
    }

    override fun release() { /* ImageCapture lifecycle managed by CameraX */ }
}
```

---

## CameraX Setup — Adding ImageCapture Use-Case

```kotlin
// In CameraXPipeline.kt — Phase 2 variant
val imageCapture = ImageCapture.Builder()
    .setTargetRotation(Surface.ROTATION_0)
    .setJpegQuality(95)
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // full sensor
    // NOTE: CAPTURE_MODE_MAXIMIZE_QUALITY = highest resolution, highest latency
    // CAPTURE_MODE_MINIMIZE_LATENCY = faster but may crop / reduce quality
    .build()

// Bind it alongside VideoCapture and ImageAnalysis
// ⚠️ Check device capability — some chips cannot bind VideoCapture + ImageCapture simultaneously
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_BACK_CAMERA,
    preview,
    videoCapture,   // 4K video (may need to drop this in Phase 2 on some devices)
    imageCapture,
    imageAnalysis
)
```

> ⚠️ **Concurrent Use-Case Warning**  
> On many Qualcomm / MediaTek chips: `VideoCapture` + `ImageCapture` simultaneously **is not guaranteed**.  
> Use `Camera2CameraInfo.getSupportedCombinedOutputSizes()` or test per-device.  
> For Phase 2 MVP: consider **pausing video recording** during ImageCapture if concurrent fails.

---

## OOM Prevention Strategy

50MP × 4 bytes/px = **~200MB per frame uncompressed**. Strict management required:

```
Rule 1: Only ONE ImageCapture in-flight at a time (captureMutex)
Rule 2: ImageProxy.close() IMMEDIATELY after copying bytes
Rule 3: Queue has max capacity — DROP frames if queue is full (not block)
Rule 4: Thermal throttle — reduce capture frequency at MODERATE+
Rule 5: Use JPEG compression as early as possible (in-camera JPEG path)
```

### Memory flow:

```
takePicture() called
        │
        ▼
Camera ISP → full-res YUV (held in CameraX internal buffer ~200MB)
        │
        ▼ (onCaptureSuccess callback)
Copy to ByteArray → imageProxy.close() ← releases ISP buffer IMMEDIATELY
        │
        ▼
ByteArray in RAM (~15MB JPEG)
        │
        ▼
Enqueue to AdaptiveCaptureQueue (Channel — limited capacity)
        │
        ▼
Background coroutine: write to disk → release ByteArray reference → GC
```

---

## Shutter Lag Mitigation

เนื่องจาก Phase 2 มี shutter lag 150–600ms ต้องชดเชยใน trigger logic:

```kotlin
// In FaceAnalyzer — for Phase 2 mode
// Trigger capture EARLIER — when face > 30% (not 40%) to compensate lag
const val PHASE2_PROXIMITY_THRESHOLD = 0.30f  // trigger earlier

// Or: predict runner position based on velocity
// Advanced: track bbox size change per frame → estimate time-to-close
class ProximityPredictor {
    private val recentAreas = ArrayDeque<Float>(maxSize = 5)

    fun addMeasurement(area: Float) {
        if (recentAreas.size >= 5) recentAreas.removeFirst()
        recentAreas.addLast(area)
    }

    /** Returns estimated face area after [futureMs] milliseconds */
    fun predictFutureArea(futureMs: Float): Float {
        if (recentAreas.size < 2) return recentAreas.lastOrNull() ?: 0f
        val growthPerMs = (recentAreas.last() - recentAreas.first()) / (recentAreas.size * 33f)
        return recentAreas.last() + growthPerMs * futureMs
    }

    fun shouldCaptureNow(shutterLagMs: Float = 300f): Boolean {
        val predictedArea = predictFutureArea(shutterLagMs)
        return predictedArea >= 0.40f   // will be at 40% when shutter fires
    }
}
```

---

## Feature Flag Config

```kotlin
// commonMain
object FeatureFlags {
    var useHighResSensor: Boolean = false    // false = Phase 1, true = Phase 2
    var proximityThreshold: Float = 0.40f
    var captureCooldownMs: Long   = 500L
    var jpegQuality: Int          = 95
}
```

Toggle remotely via Firebase Remote Config หรือ local settings screen เพื่อ A/B test บน device จริง
