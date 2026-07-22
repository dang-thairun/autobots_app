# Platform APIs & native libraries

Inventory of **computer vision**, **camera**, and related **Android platform** APIs actually used in this repo.  
For pipeline behavior see [architecture.md](./architecture.md); for file locations see [STRUCTURE.md](./STRUCTURE.md).

**Versions** (from `gradle/libs.versions.toml`): CameraX `1.4.1` · ML Kit Face `16.1.7`

---

## Quick map

| Area | Library / API | Wrapper in code | MVP role |
|------|---------------|-----------------|----------|
| Face detection | Google ML Kit | `MlKitFaceAnalyzer` | Bounding boxes → proximity |
| Preview + capture | CameraX | `PreviewCameraController` | Live view, analysis, stills |
| AF / AE lock | CameraX `CameraControl` | `FaceFocusController` | Face Lock at Arm |
| Exposure readout | Camera2 via CameraX interop | `PreviewCameraController` | Focal · shutter · ISO |
| Max resolution | Camera2 `CameraManager` | `CaptureResolutions` | Max-Sensor mode target |
| Gallery write | Android MediaStore | `LocalDeliveryWriter` | `DCIM/AutoBots` |
| Device load | Android `PowerManager` + `ActivityManager` | `DeviceLoadReader` | Thermal + RAM (display only) |
| Subject selection | App logic (KMP) | `SubjectFaceSelector` | Largest face = runner |
| AF grid overlay | Compose Canvas (no camera API) | `AfGridOverlay` | Observation UI only |

---

## 1. Google ML Kit — Face Detection

**Artifact:** `com.google.mlkit:face-detection`  
**File:** `androidApp/.../detection/MlKitFaceAnalyzer.kt`

### APIs used

| API | Usage |
|-----|--------|
| `FaceDetection.getClient(options)` | Create detector instance |
| `FaceDetectorOptions.Builder` | Configure detector |
| `FaceDetector.process(InputImage)` | Run detection per analysis frame |
| `FaceDetector.close()` | Release on unbind |
| `InputImage.fromMediaImage(mediaImage, rotation)` | Feed CameraX `ImageProxy` |

### Configuration (current)

| Option | Value | Notes |
|--------|-------|-------|
| `PERFORMANCE_MODE` | `PERFORMANCE_MODE_FAST` | Lower latency, less accuracy vs accurate mode |
| `MIN_FACE_SIZE` | `0.05f` | Min face as fraction of image shorter edge |
| Landmarks / contours / classification / tracking | **Not enabled** | Only `boundingBox` is read |

### Output we use

- `Face.boundingBox` → mapped to `PreviewView` coords → `NormalizedFaceBox` (0..1)
- **Not used:** head pose, smiling probability, eye open, face ID / tracking ID

### Coordinate mapping (CameraX + Android graphics)

| API | Role |
|-----|------|
| `ImageProxyTransformFactory.getOutputTransform` | Analysis frame → sensor space |
| `PreviewView.outputTransform` | Preview view space |
| `CoordinateTransform` + `Matrix.mapRect` | Map box into overlay coordinates |
| `ImageAnalysis.Analyzer.analyze(ImageProxy)` | Entry point; must `close()` proxy |

Analysis stream: **640×360**, YUV_420_888, `STRATEGY_KEEP_ONLY_LATEST`.

Optional side path: `ImageProxy.toJpeg()` (custom YUV→NV21→JPEG) throttled ~15 fps for remote preview (`AutobotsServer`).

---

## 2. CameraX

**Artifacts:** `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`  
**File:** `androidApp/.../PreviewCameraController.kt` (+ related)

### Use cases bound

| Use case | Builder / config | Purpose |
|----------|------------------|---------|
| `Preview` | `Preview.Builder` + `surfaceProvider` | Operator live view (`PreviewView`) |
| `ImageAnalysis` | 640×360, 16:9, YUV, keep-latest | ML Kit input |
| `ImageCapture` | Mode + resolution per `CaptureMode` | Lean Burst stills |

### Lifecycle & binding

| API | Usage |
|-----|--------|
| `ProcessCameraProvider.getInstance` | Obtain provider |
| `bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, …)` | Bind use cases |
| `UseCaseGroup` + `PreviewView.viewPort` | Align preview / analysis / capture FOV |
| `provider.unbindAll()` | On rebind / stop |

### PreviewView

| Setting / API | Value |
|---------------|-------|
| `ImplementationMode` | `COMPATIBLE` |
| `ScaleType` | `FILL_CENTER` |
| `meteringPointFactory` | Used by `FaceFocusController` |
| `surfaceProvider` | Attached to `Preview` use case |

### ImageCapture (still photos)

**File:** `androidApp/.../capture/LeanBurstCapturer.kt`

| API | Usage |
|-----|--------|
| `ImageCapture.takePicture(OutputFileOptions, executor, OnImageSavedCallback)` | Sequential burst to cache dir |
| `ImageCapture.OutputFileOptions.Builder(File)` | JPEG file per shot |
| `ImageCaptureException` | Log and continue burst |

Defaults: **3 shots**, **200 ms** gap (`LeanBurstCapturer.DEFAULT_*`).

### Capture mode & resolution

**File:** `androidApp/.../CaptureResolutions.kt`

| Mode | `captureMode` | Resolution strategy |
|------|---------------|---------------------|
| Standard | `CAPTURE_MODE_MINIMIZE_LATENCY` | Target **1920×1080** (16:9 fallback) |
| Max-Sensor | `CAPTURE_MODE_MAXIMIZE_QUALITY` | `HIGHEST_AVAILABLE_STRATEGY` |

| API | Usage |
|-----|--------|
| `ResolutionSelector` + `ResolutionStrategy` | Pick still size |
| `AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY` | Aspect handling |

### Focus & metering (Face Lock)

**File:** `androidApp/.../focus/FaceFocusController.kt`

| API | Usage |
|-----|--------|
| `CameraControl.startFocusAndMetering(FocusMeteringAction)` | Lock AF + AE on face center |
| `FocusMeteringAction.Builder(point, FLAG_AF \| FLAG_AE)` | AF + AE metering region |
| `MeteringPointFactory.createPoint(x, y, size)` | Normalized region from face box |
| `setAutoCancelDuration` | **Removed** on live path (Flow 13) — cancel on detach / passage end |
| `CameraControl.cancelFocusAndMetering()` | On detach / unbind |

Throttled to **≥100 ms** between lock calls.

**Also in `shared/` (not yet fully wired):** `FocusStrategy`, `CaptureZone` / `CaptureZoneEvaluator` — see [IMPLEMENTATION.md](./IMPLEMENTATION.md).

---

## 3. Camera2 (interop & characteristics)

Used where CameraX does not expose metadata or max JPEG size.

**File:** `PreviewCameraController.kt` — exposure readout

| API | Field read | UI |
|-----|------------|-----|
| `Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback` | Hook capture results | — |
| `CaptureResult.LENS_FOCAL_LENGTH` | Focal length mm | Exposure line |
| `CaptureResult.SENSOR_EXPOSURE_TIME` | Shutter ns | `1/x` or seconds |
| `CaptureResult.SENSOR_SENSITIVITY` | ISO | `ISO n` |

Published throttled **~250 ms** → `CameraExposureReadout`.

**File:** `CaptureResolutions.kt` — Max-Sensor label

| API | Usage |
|-----|--------|
| `CameraManager.cameraIdList` | Find back camera |
| `CameraCharacteristics.LENS_FACING` | Back vs front |
| `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(JPEG)` | Largest JPEG size |

**Not used:** manual sensor controls, RAW, HDR pipeline, physical multi-camera switching, OIS toggles.

---

## 4. App-owned vision logic (not from a CV library)

**File:** `shared/.../detection/SubjectFace.kt`

| Type | Role |
|------|------|
| `NormalizedFaceBox` | Face rect in 0..1 coords |
| `FaceFrameResult` | All faces + subject index + **proximity** (subject area) |
| `SubjectFaceSelector.select` | **Largest area** face → Subject Face |

Thresholds (`shared/.../PassageThresholds.kt`): Arm **4%**, Fire **10%** of frame area (proximity, not meters).

Passage Gate / Arm / Fire orchestration: `OperatorViewModel` (not a library).

---

## 5. Storage & gallery (Android platform)

| Component | APIs | File |
|-----------|------|------|
| `LocalDeliveryWriter` | `MediaStore.Images.Media.*`, `ContentResolver.insert/openOutputStream`, `RELATIVE_PATH = DCIM/AutoBots`, `IS_PENDING` | `delivery/LocalDeliveryWriter.kt` |
| `WriteQueue` | Kotlin `Channel` (not Android API) | `delivery/WriteQueue.kt` |
| `GalleryLauncher` | `Intent.ACTION_VIEW`, `MediaStore.EXTERNAL_CONTENT_URI` | `delivery/GalleryLauncher.kt` |

---

## 6. Device load (Android platform)

**File:** `androidApp/.../load/DeviceLoadReader.kt`

| API | Usage |
|-----|--------|
| `PowerManager.currentThermalStatus` | One-shot thermal level |
| `PowerManager.addThermalStatusListener` | Push updates (API 29+) |
| `ActivityManager.getMemoryInfo` | Used / available / total RAM |

Display only — **does not throttle** capture or analysis (Flow 11).

---

## 7. UI overlays (no extra CV / camera plugins)

| Component | Technology | Notes |
|-----------|------------|-------|
| `FaceOverlay` | Compose `Canvas` | Green = Subject, yellow = other faces |
| `AfGridOverlay` | Compose `Canvas` | 9×11 grid; **visual only**, not hardware AF points |
| `CameraPreviewPane` | `AndroidView` + `PreviewView` | Hosts camera preview |

---

## 8. Remote / network (optional, not MVP operator path)

**File:** `androidApp/.../network/AutobotsServer.kt`  
**Stack:** Ktor CIO server + WebSockets (not camera/CV library)

- Streams JPEG frames from analysis (`onFrameEncoded`)
- Exposes control/status JSON over HTTP/WebSocket
- Reads gallery via `MediaStore` for remote browse

---

## 9. Not in project (future / ROADMAP)

| Capability | Status |
|------------|--------|
| Custom YOLO / TFLite detector | ❌ Not integrated |
| ML Kit face **tracking** IDs | ❌ Not enabled |
| OpenCV / MediaPipe | ❌ Not a dependency |
| On-device frame scoring | ❌ Post-MVP |
| Thermal-based throttle | ❌ Post-MVP |
| True manual AF lock (disable auto-cancel) | ✅ Flow 13 — no auto-cancel on FaceAf path |
| Fixed Focus default | 🔄 P9c |
| Capture Zone Fire | ⏳ P10 |
| EV compensation | ⏳ P9d |
| iOS / KMP camera | ❌ Android-only runtime |

---

## 10. When to update this file

| Change | Action |
|--------|--------|
| New Maven dependency for camera/CV | Add section + row in quick map |
| New ML Kit option or detector | Update §1 table |
| New CameraX use case (e.g. VideoCapture) | Update §2 |
| Remove or replace interim ML Kit | Mark §1 status + ROADMAP link |
