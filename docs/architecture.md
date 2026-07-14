# AutoBots Sports Camera — Architecture Guide

Detailed system architecture, runtime pipelines, module design, and consolidated Architectural Decision Records (ADRs).

---

## 1. Modular System Overview

The project is structured as a Kotlin Multiplatform (KMP) codebase containing:
* **`shared/` (KMP commonMain)**: Core domain contracts, thresholds, and business rules (e.g. `CaptureMode`, `PassageThresholds`, `SubjectFaceSelector`). Keep-all and passage gate policies live here.
* **`androidApp/` (Android application)**: CameraX setup, ML Kit face detection integration, asynchronous Write Queue, MediaStore publishing, and Operator Compose UI screens.

---

## 2. Runtime Pipeline

The sequence below illustrates the data flow from camera analysis to final disk writing:

```
[Camera Sensor]
      │ (ImageAnalysis ~640x360)
      ▼
[MlKitFaceAnalyzer]
      │
      ▼
[SubjectFaceSelector] ──(Largest face selected)──► [SubjectFace / Face Proximity]
                                                               │
      ┌────────────────────────────────────────────────────────┤
      │ (Proximity >= Arm Threshold)                           │ (Proximity >= Fire Threshold)
      ▼                                                        ▼
[FaceFocusController]                                 [LeanBurstCapturer]
  * Locks AF/AE targeted at Face                               │
  * Settles focus/exposure                                     ▼ (Capture JPEGs)
                                                      [PhotoDeliveryService]
                                                               │ (enqueueAll)
                                                               ▼
                                                        [WriteQueue]
                                                               │ (async drain)
                                                               ▼
                                                      [LocalDeliveryWriter]
                                                               │ (publish)
                                                               ▼
                                                      [DCIM/AutoBots/]
```

---

## 3. Subsystem Breakdown

### 3.1. Camera Controlling Subsystem
* **`PreviewCameraController`**: Coordinates lifecycle binding, PreviewView attachment, ImageAnalysis setup, and binds capture/focus listeners. Delegates photo delivery to the `PhotoDeliveryService`.

### 3.2. Detection Subsystem
* **`MlKitFaceAnalyzer`**: Processes low-resolution frames (~640×360) via Google ML Kit Face Detection, publishing mapped bounding boxes.
* **`SubjectFaceSelector`**: Selects the largest face (highest proximity) from the detected list.

### 3.3. Focus Subsystem
* **`FaceFocusController`**: Drives CameraX focus and metering action to the face coordinates, lock-holding parameters before the burst triggers.

### 3.4. Capture Subsystem
* **`LeanBurstCapturer`**: Triggers a fast sequential photo capture (default 3 shots, ~200ms apart) or a single max-sensor capture, writing intermediate JPEGs to a temporary cache.

### 3.5. Delivery Subsystem
* **`PhotoDeliveryService`** (Interface): Abstracts the delivery queue from the camera controller, improving unit testing and decoupling.
* **`LocalPhotoDeliveryService`**: Coordinates the local writing and queue lifecycle, executing completion callbacks on the Main thread.
* **`WriteQueue`**: A bounded queue using Kotlin Coroutine channels to drain cache files in a background worker thread.
* **`LocalDeliveryWriter`**: Publishes files into the Android MediaStore under `DCIM/AutoBots`, making files visible in external galleries.

---

## 4. Architectural Decision Records (ADRs)

These records outline the fundamental architectural design decisions for the codebase:

### ADR 0001: Keep-All Policy (Lean Burst)
* **Decision**: All photos captured in a Lean Burst are saved. No on-device quality, smile, or pose ranking takes place.
* **Rationale**: Out-of-the-box still photos captured in shut order are lightweight to save. Scoring on-device introduces processing latency, thermal load, and increased power usage.

### ADR 0002: Proximity-Based Passage Gate
* **Decision**: The Passage Gate closes once a burst fires and opens only when the tracked runner's face exits the proximity zone or falls below the arm release. Time-based cooldown is not used.
* **Rationale**: Prevents double-triggering on the same runner who remains in focus.

### ADR 0003: Subject Face Focus
* **Decision**: Proximity, AF/AE lock, and triggers only track the single largest face (highest frame coverage) in the analysis zone.
* **Rationale**: MVP focuses on tripod marathon approaches where runners approach one-by-one. Person tracking IDs are deferred.

### ADR 0004: Still JPEG Format
* **Decision**: Strictly capture JPEGs. Video capture is out of scope.
* **Rationale**: Prevents Android device overheating, file sizing problems, and excessive memory allocations during continuous multi-hour runs.

### ADR 0005: Local Storage Success Criteria
* **Decision**: MVP success is defined as saving images to `DCIM/AutoBots`. Remote/cloud uploading is out of scope.
* **Rationale**: Cloud upload logic requires network connectivity which is unreliable at remote running events. Files are copied manually by operators.

### ADR 0006: Android-First Kotlin Multiplatform Layout
* **Decision**: Target Android OS first. The directory layout is prepared for KMP (`shared` commons) to share models if iOS is added.
* **Rationale**: Operator App runs on Android phone hardware mounted on tripods.

### ADR 0007: Standard vs Max-Sensor Modes
* **Decision**: Standard Mode captures ~3 standard stills. Max-Sensor Mode captures 1 high-resolution (e.g. 50MP) still. Never Standard 3-shot bursts at max-sensor quality.
* **Rationale**: Writing multiple 50MP images in rapid succession exceeds memory buffers and causes write locks/OOM errors.

### ADR 0008: Deferred Thermal Auto-Throttling
* **Decision**: Do not automatically throttle capture frequency during thermal buildup. Show a thermal level indicator on the screen instead.
* **Rationale**: Operators should make the decision to pause capturing rather than the app silently missing runner approaches.

### ADR 0009: Bounded Write Queue
* **Decision**: Bounded buffer (capacity 8) to write images asynchronously in IO scope. Drop incoming frames if the queue fills.
* **Rationale**: Protects UI thread response and limits RAM footprint.

### ADR 0010: Proactive Face Lock at Arming
* **Decision**: Trigger focus and exposure lock on the face bounding box at Arm Proximity (~10%) before Fire Proximity (~40%) is reached.
* **Rationale**: Focus needs a few hundred milliseconds to settle before taking a picture.

### ADR 0011: Display-Only Device Load Readout
* **Decision**: Show RAM usage and thermal conditions in Compose UI but perform no automated actions based on it.
* **Rationale**: Gives operators field visibility on hardware performance.

### ADR 0012: Photo Delivery Service Abstraction
* **Decision**: Encapsulate MediaStore writing and write queue management under a `PhotoDeliveryService` interface.
* **Rationale**: Decouples the Camera Controller from the I/O storage subsystem, enabling easier module splitting and simulation of mock delivery for test validations.
