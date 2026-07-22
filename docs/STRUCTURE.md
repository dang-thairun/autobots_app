# AutoBots Sports Camera — Codebase Structure & Directory Layout

Directory trees, package mapping, and build configurations.

---

## 1. Directory Layout

The workspace is organized into a Kotlin Multiplatform structure as shown below:

```
autobots_app/
├── build.gradle.kts           # Root gradle script
├── settings.gradle.kts        # Root build configuration/settings
├── gradle.properties          # Gradle properties and options
├── gradlew                    # Unix wrapper script
│
├── docs/                      # Project Documentation
│   ├── DOCS.md                # Documentation entry point
│   ├── CONVENTIONS.md         # How to write & name docs (Phase / Flow / …)
│   ├── PRD.md                 # Product requirements
│   ├── architecture.md        # Architecture + Design Flows
│   ├── IMPLEMENTATION.md      # P9 / P10 build slices
│   ├── FIELD_SETUP.md         # Tripod setup checklist
│   ├── SCREEN.md              # Operator UI layout (ASCII)
│   ├── PLATFORM_APIS.md       # CV / camera / native API inventory
│   ├── BUILD.md               # Build APK + adb install
│   ├── STRUCTURE.md           # [This file] Codebase structure
│   └── ROADMAP.md             # Unscheduled ideas
│
├── shared/                    # KMP common module (shared contracts)
│   ├── build.gradle.kts       # Shared module gradle build
│   └── src/
│       └── commonMain/kotlin/com/autobots/camera/
│           ├── AutobotsApp.kt         # Smoke banner constants
│           ├── CaptureMode.kt         # Standard / Max-Sensor enums
│           ├── FocusStrategy.kt       # Fixed (default) / FaceAf
│           ├── CaptureZone.kt         # Grid zone + Fire evaluator
│           ├── PassageThresholds.kt   # Arming / firing defaults
│           └── detection/
│               └── SubjectFace.kt     # Face boundary & selection contracts
│
└── androidApp/                # Android application module (CameraX runtime)
    ├── build.gradle.kts       # Android module gradle build
    └── src/main/kotlin/com/autobots/
        ├── MainActivity.kt    # Main entry Activity
        ├── ui/                # Compose Operator screens & overlays
        │   ├── CameraPreviewPane.kt   # Camera preview container
        │   ├── FaceOverlay.kt         # Bounding boxes preview
        │   ├── AfGridOverlay.kt       # 9x11 alignment grid
        │   ├── OperatorShellScreen.kt # Operator Control Panel UI
        │   └── OperatorViewModel.kt   # state/event View Model
        │
        └── camera/            # Android CameraX subsystems
            ├── PreviewCameraController.kt # Main controller binding preview
            ├── CameraExposureReadout.kt   # Format exposure values
            ├── CaptureResolutions.kt      # Handle camera resolutions
            │
            ├── capture/
            │   └── LeanBurstCapturer.kt   # Sequential photo taker
            ├── delivery/
            │   ├── PhotoDeliveryService.kt# Abstracts storage subsystem
            │   ├── WriteQueue.kt          # Bounded Coroutine Channel queue
            │   ├── LocalDeliveryWriter.kt # MediaStore publishing handler
            │   └── GalleryLauncher.kt     # Gallery helper intent launcher
            ├── detection/
            │   └── MlKitFaceAnalyzer.kt   # Low-res frame analyzer
            ├── focus/
            │   └── FaceFocusController.kt # Focus & exposure lock controller
            └── load/
                ├── DeviceLoadReader.kt    # Thermal & memory sampler
                └── DeviceLoadSnapshot.kt  # Load data structure representation
```

---

## 2. Package Descriptions

### 2.1. Shared Package (`com.autobots.camera`)
Holds core logic, data structures, and thresholds that are platform-independent:
* **`CaptureMode`**: Defines Standard vs Max-Sensor capture configurations.
* **`FocusStrategy`**: Fixed (tripod default) vs FaceAf (fallback).
* **`CaptureZone` / `CaptureZoneEvaluator`**: Grid composition zone for Fire (wired in P10).
* **`PassageThresholds`**: Houses default and release thresholds (e.g. `ARM_HALF_BODY = 0.04f`, `FIRE_HALF_BODY = 0.10f`).
* **`SubjectFaceSelector`**: Decides which face qualifies as the runner. Selection logic focuses on the largest face area.

### 2.2. Android UI Package (`com.autobots.ui`)
Manages Jetpack Compose components for the Operator UI:
* **`OperatorShellScreen`**: The primary dashboard showing status readouts, controls, and parameter sliders.
* **`CameraPreviewPane`**: Hosts the CameraX `PreviewView` and draws the face overlays.
* **`OperatorViewModel`**: Holds UI state (thermal load, RAM reading, count of kept photos) and handles Start/Stop capture actions.

### 2.3. Android Camera Subsystems (`com.autobots.camera.*`)
Implements camera controls, photo bursts, local writes, and performance readings:
* **`capture/LeanBurstCapturer`**: Executes non-blocking burst sequences.
* **`delivery/PhotoDeliveryService`**: Interfaces the WriteQueue to decouple it from CameraX.
* **`detection/MlKitFaceAnalyzer`**: Drives the Google ML Kit Face Detector on analysis frames.
* **`focus/FaceFocusController`**: Interfaces with CameraX MeteringPoint inputs to lock AF and AE onto the runner's face.
* **`load/DeviceLoadReader`**: Monitors Android `PowerManager` thermal status and `ActivityManager` RAM availability.

---

## 3. Gradle Configurations & Core Dependencies

The application uses standard modern Gradle plugins to manage dependencies:

* **Android Target SDK**: Android SDK 34 (Upside Down Cake).
* **Jetpack Compose**: Used for the native Android operator dashboard.
* **CameraX Suite (`androidx.camera:*`)**:
  * `camera-core` / `camera-camera2`: High-level camera abstraction and sessions.
  * `camera-lifecycle`: Binds session execution to Android lifecycle components.
  * `camera-view`: Provides `PreviewView` for rendering.
* **Google ML Kit Face Detection (`com.google.mlkit:face-detection`)**: Used for CPU-efficient face boundary calculations on preview analysis frames.
* **Kotlin Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-*`)**: Provides Channel buffers for asynchronous task scheduling.

For a **per-library API inventory** (which functions/options are actually called), see [PLATFORM_APIS.md](./PLATFORM_APIS.md).
