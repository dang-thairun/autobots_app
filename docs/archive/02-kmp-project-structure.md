# 02 — KMP Project Structure

## Module Layout

```
autobots_app/
├── shared/                          ← KMP Shared Module
│   └── src/
│       ├── commonMain/kotlin/
│       │   └── com/autobots/camera/
│       │       ├── capture/
│       │       │   ├── CaptureStrategy.kt        ← INTERFACE (Phase 1/2 switch point)
│       │       │   ├── CaptureResult.kt          ← data class
│       │       │   ├── CaptureConfig.kt          ← config data class
│       │       │   └── CaptureQueue.kt           ← abstract queue contract
│       │       ├── detection/
│       │       │   ├── FaceDetector.kt           ← INTERFACE
│       │       │   ├── FaceDetectionResult.kt    ← data class (bbox, confidence)
│       │       │   ├── ProximityCalculator.kt    ← pure Kotlin (bbox/frame ratio)
│       │       │   └── FocusStrategy.kt          ← INTERFACE (AF/AE control)
│       │       ├── thermal/
│       │       │   ├── ThermalMonitor.kt         ← INTERFACE
│       │       │   └── ThermalStatus.kt          ← expect/actual enum
│       │       └── pipeline/
│       │           └── CameraPipeline.kt         ← INTERFACE (orchestrator)
│       │
│       └── androidMain/kotlin/
│           └── com/autobots/camera/
│               ├── capture/
│               │   ├── ImageCaptureStrategy.kt   ← Phase 1 MVP implementation
│               │   ├── HighResImageCaptureStrategy.kt ← Phase 2 (50MP)
│               │   └── AdaptiveCaptureQueue.kt   ← coroutine Channel queue
│               ├── detection/
│               │   ├── TFLiteFaceDetector.kt     ← JNI bridge to C++ TFLite
│               │   ├── FaceFocusController.kt    ← AF + AE lock on face bbox
│               │   └── jni/
│               │       ├── face_detector_jni.cpp ← C++ inference engine
│               │       └── CMakeLists.txt
│               ├── thermal/
│               │   └── AndroidThermalMonitor.kt  ← PowerManager Thermal API
│               └── pipeline/
│                   ├── CameraXPipeline.kt        ← CameraX orchestrator
│                   └── FaceAnalyzer.kt           ← ImageAnalysis.Analyzer impl
│
├── androidApp/                      ← Android UI Application
│   └── src/main/
│       ├── java/com/autobots/
│       │   ├── MainActivity.kt
│       │   ├── CameraViewModel.kt
│       │   └── ui/
│       │       ├── CameraPreviewScreen.kt
│       │       └── CaptureStatusOverlay.kt   ← แสดง AF status, queue stats
│       └── res/
│
└── docs/                            ← This documentation
```

---

## Interface Definitions (`commonMain`)

### `CaptureStrategy.kt`

```kotlin
// commonMain
package com.autobots.camera.capture

/**
 * Core abstraction for capture methods.
 * Implement in androidMain (Phase 1 or Phase 2).
 * Future: iosMain implementation using AVFoundation.
 */
interface CaptureStrategy {

    /**
     * Called when face proximity threshold is exceeded.
     * Implementation decides HOW to capture.
     *
     * @param frameContext  metadata about the current frame (timestamp, rotation, etc.)
     * @return CaptureResult wrapping the captured bytes or error
     */
    suspend fun capture(frameContext: FrameContext): CaptureResult

    /**
     * Called by ThermalGuard to reduce capture pressure.
     * Each strategy can define its own throttle behaviour.
     */
    fun applyThermalThrottle(status: ThermalStatus)

    /**
     * Release resources (camera surfaces, TFLite sessions, etc.)
     */
    fun release()
}
```

---

### `FaceDetector.kt`

```kotlin
// commonMain
package com.autobots.camera.detection

interface FaceDetector {

    /**
     * @param imageBytes  raw YUV_420_888 or RGB byte array
     * @param width       image width in pixels
     * @param height      image height in pixels
     * @return list of detected faces sorted by confidence DESC
     */
    fun detect(imageBytes: ByteArray, width: Int, height: Int): List<FaceDetectionResult>

    fun close()
}
```

---

### `FaceDetectionResult.kt`

```kotlin
// commonMain
package com.autobots.camera.detection

data class FaceDetectionResult(
    val boundingBox: BoundingBox,   // normalised 0.0-1.0 coordinates
    val confidence: Float,
    val landmarksNorm: List<Point>? = null  // optional: eyes, nose, mouth
)

data class BoundingBox(
    val left: Float,   // 0.0–1.0
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height  // fraction of frame area (0.0–1.0)
}

data class Point(val x: Float, val y: Float)
```

---

### `ProximityCalculator.kt`

```kotlin
// commonMain — pure Kotlin, no Android dependencies
package com.autobots.camera.detection

object ProximityCalculator {

    /**
     * Returns true when the largest detected face occupies more than
     * [thresholdFraction] of the total frame area.
     *
     * BoundingBox is already normalised (0–1), so:
     *   bbox.area == bbox.width * bbox.height
     * and this directly represents the fraction of the frame.
     *
     * Example: face bbox 0.4w × 0.5h → area = 0.20 (20%) → NOT triggered
     *          face bbox 0.7w × 0.6h → area = 0.42 (42%) → TRIGGERED ✅
     */
    fun shouldCapture(
        faces: List<FaceDetectionResult>,
        thresholdFraction: Float = 0.40f
    ): Boolean {
        if (faces.isEmpty()) return false
        val largestFace = faces.maxByOrNull { it.boundingBox.area } ?: return false
        return largestFace.boundingBox.area >= thresholdFraction
    }
}
```

---

### `ThermalStatus.kt`

```kotlin
// commonMain — expect/actual
package com.autobots.camera.thermal

expect enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
}
```

```kotlin
// androidMain — actual
package com.autobots.camera.thermal

import android.os.PowerManager

actual enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    companion object {
        fun from(androidStatus: Int): ThermalStatus = when (androidStatus) {
            PowerManager.THERMAL_STATUS_NONE      -> NONE
            PowerManager.THERMAL_STATUS_LIGHT     -> LIGHT
            PowerManager.THERMAL_STATUS_MODERATE  -> MODERATE
            PowerManager.THERMAL_STATUS_SEVERE    -> SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL  -> CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN  -> SHUTDOWN
            else -> NONE
        }
    }
}
```

---

### `ThermalMonitor.kt`

```kotlin
// commonMain
package com.autobots.camera.thermal

interface ThermalMonitor {
    fun start(onStatusChange: (ThermalStatus) -> Unit)
    fun stop()
    val currentStatus: ThermalStatus
}
```

---

## androidMain Implementations Summary

| Class | Implements | Key Tech |
|-------|-----------|----------|
| `ImageCaptureStrategy` | `CaptureStrategy` | CameraX `ImageCapture.takePicture()` — Phase 1 MVP |
| `HighResImageCaptureStrategy` | `CaptureStrategy` | CameraX max-quality mode — Phase 2 (50MP) |
| `FaceFocusController` | `FocusStrategy` | `CameraControl.startFocusAndMetering()`, `SurfaceOrientedMeteringPointFactory` |
| `TFLiteFaceDetector` | `FaceDetector` | JNI → C++ TFLite + GPU/NNAPI delegate |
| `AndroidThermalMonitor` | `ThermalMonitor` | `PowerManager.addThermalStatusListener` |
| `AdaptiveCaptureQueue` | `CaptureQueue` | `Channel<CaptureJob>`, coroutine scope |
| `CameraXPipeline` | `CameraPipeline` | Preview + ImageAnalysis + ImageCapture (no VideoCapture) |
| `FaceAnalyzer` | `ImageAnalysis.Analyzer` | Drives both FocusController + CaptureStrategy |

---

## Strategy Pattern: Phase Switch

```kotlin
// androidMain — CameraXPipeline.kt (simplified)
class CameraXPipeline(
    private val captureStrategy: CaptureStrategy,   // Phase 1 or Phase 2
    private val focusController: FaceFocusController // shared — used by both phases
) : CameraPipeline {
    // CaptureStrategy injected via DI (Koin / manual)
    // FocusController is always active regardless of phase
}

// --- Wiring examples ---

// Phase 1 — MVP (default)
val pipeline = CameraXPipeline(
    captureStrategy = ImageCaptureStrategy(imageCapture, captureQueue),
    focusController = FaceFocusController(cameraControl, cameraInfo)
)

// Phase 2 — High-Res A/B toggle
val pipeline = CameraXPipeline(
    captureStrategy = HighResImageCaptureStrategy(imageCapture, captureQueue),
    focusController = FaceFocusController(cameraControl, cameraInfo)
)
```

---

## `FocusStrategy.kt` Interface (`commonMain`)

```kotlin
// commonMain
package com.autobots.camera.detection

/**
 * Abstraction for camera focus + exposure control.
 * Implemented in androidMain via CameraX CameraControl.
 * Future: iosMain via AVCaptureDevice focus/exposure lock.
 */
interface FocusStrategy {

    /**
     * Lock AF + AE on the given face bounding box.
     * Called continuously as face position updates.
     *
     * @param bbox  normalised bounding box (0.0–1.0)
     */
    fun lockOnFace(bbox: BoundingBox)

    /**
     * Release AF/AE lock and return to default (center / continuous) mode.
     * Called when no face is detected.
     */
    fun reset()
}
```
