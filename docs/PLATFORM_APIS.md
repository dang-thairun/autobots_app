# Platform APIs & native libraries

Inventory of **computer vision**, **camera**, and related **Android platform** APIs used in this repo.

- Pipeline behavior: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- File locations: [STRUCTURE.md](./STRUCTURE.md)
- Operator build: **v0.1.6 Plan B** — active path is **video chunk + offline extract + upload**

**Versions** (`gradle/libs.versions.toml`): CameraX **1.4.1** · ML Kit Face **16.1.7** · ML Kit Pose **18.0.0-beta5** · Ktor **2.3.12**

---

## Quick map

### Active — Plan B (v0.1.6 operator shell)

| Area | Library / API | Wrapper | Role |
|------|---------------|---------|------|
| Live preview + record | CameraX `Preview` + `VideoCapture` | `VideoPreviewController` | Operator live view + MP4 chunks |
| Video import split | `MediaExtractor` + `MediaMuxer` | `ImportedVideoSplitter` | Remux to 50 MB chunks (no re-encode) |
| Frame decode | `MediaCodec` (HW preferred) | `VideoFrameSampler` | Sample every 120 ms from MP4 |
| Face detect (offline) | ML Kit Face | `OfflineFaceDetector` | Bitmap inference on decoded frames |
| Face detect (offline) | **LiteRT / TFLite** `face_det_lite` w8a8 | `FaceDetLiteDetector` | 640×480 grayscale tensor, tiled · default backend is NPU |
| Person detect (offline) | **LiteRT / TFLite** `foot_track_net` w8a8 | `PersonFootDetector` | 640×480 letterboxed · the only detector that reports **everyone** in frame |
| Pose detect (offline) | ML Kit Pose | `OfflinePoseDetector` | Is the body usably in shot |
| NPU delegate | **QNN** (Qualcomm) | `QnnDelegate` | Falls back to GPU then CPU; what actually ran is recorded in `perf_report.json` |
| Sharpness | CPU Laplacian (app) | `FaceSharpnessScorer` | On a 128×128 normalised ROI · FHD ≥80 · UHD ≥65 |
| Frame ranking | KMP logic | `FrameQuality`, `SubjectTracker` | 5-term score; dedup window is per person |
| Gallery JPEG | MediaStore Images | `LocalDeliveryWriter.publish()` | `DCIM/AutoBots/{subfolder}/` |
| Session log | MediaStore Downloads (API 29+) | `LocalDeliveryWriter.publishText()` | `Download/AutoBots/{subfolder}/session_log.txt` |
| Async delivery | Kotlin `Channel` | `WriteQueue` | Bounded drain to MediaStore |
| Device load | `PowerManager` + `ActivityManager` | `DeviceLoadReader` | Thermal + RAM (display only) |
| Remote control | Ktor CIO + WebSockets | `AutobotsServer` | Start/Stop, state on `:8080` |
| File import picker | `ActivityResultContracts.OpenDocument` | `MainActivity` | User picks video file |
| Upload queue | **Room** (+ KSP) | `UploadDatabase`, `UploadDao` | Durable queue, 6 states, per-row backoff that survives reboot |
| Upload scheduling | **WorkManager** | `UploadScheduler`, `UploadWorker` | One unique job drains the whole queue |
| Foreground service | `FOREGROUND_SERVICE_DATA_SYNC` | WorkManager `setForeground()` | Keeps network + CPU during Doze · Android 14+ caps `dataSync` at 6 h/day |
| Presign / complete | `HttpURLConnection` + GraphQL | `RunxUploadTransport`, `RunxAuthClient` | Two JSON POSTs did not justify a client library |
| Object storage | Google Cloud Storage signed PUT | `RunxUploadTransport.put` | `Content-Type` must match what the URL was signed for |
| QR provisioning | ML Kit Barcode | `QrScanPreview` | Endpoint + token without typing |

### Legacy — v0.1 stills (code retained, not wired in shell)

| Area | Library / API | Wrapper | Role |
|------|---------------|---------|------|
| Live face detect | ML Kit Face | `MlKitFaceAnalyzer` | 640×360 `ImageAnalysis` stream |
| Still burst | CameraX `ImageCapture` | `LeanBurstCapturer` | 3 JPEGs ~200 ms gap |
| Preview + analysis | CameraX | `PreviewCameraController` | Preview + Analysis + Capture |
| AF / AE lock | `CameraControl` | `FaceFocusController` | Face Lock at Arm |
| Subject selection | KMP logic | `SubjectFaceSelector` | Largest face |
| Overlays | Compose Canvas | `FaceOverlay`, `AfGridOverlay` | Not used in current shell |

---

## 1. CameraX — Plan B (active)

**Artifacts:** `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, **`camera-video`**  
**File:** `androidApp/.../VideoPreviewController.kt`

### Use cases bound (operator shell)

| Use case | Config | Purpose |
|----------|--------|---------|
| `Preview` | `Preview.Builder` + `PreviewView.surfaceProvider` | Live operator view |
| `VideoCapture` | `VideoCapture.withOutput(Recorder)` + quality selector | MP4 chunk recording |

**Not bound in Plan B shell:** `ImageAnalysis`, `ImageCapture`.

### Lifecycle & binding

| API | Usage |
|-----|--------|
| `ProcessCameraProvider.getInstance` | Obtain provider |
| `bindToLifecycle(owner, DEFAULT_BACK_CAMERA, preview, videoCapture)` | Bind preview + video |
| `UseCaseGroup` + `PreviewView.viewPort` | Align preview and video FOV (preferred path) |
| `provider.unbindAll()` | On stop / rebind |

### PreviewView

| Setting | Value |
|---------|-------|
| `ImplementationMode` | `COMPATIBLE` |
| `ScaleType` | `FILL_CENTER` |

### VideoCapture / Recorder

**Files:** `VideoPreviewController.kt`, `VideoChunkRecorder.kt`

| API | Usage |
|-----|--------|
| `Recorder.Builder().setQualitySelector(...)` | FHD / UHD quality profile |
| `PendingRecording.start()` / `Recording.stop()` | Start/stop MP4 segment |
| `FileOutputOptions.Builder(File)` | Per-chunk output path |
| Size polling | Rotate when file ≥ `StreamResolution.CHUNK_TARGET_BYTES` (50 MB) |

---

## 2. MediaCodec + MediaExtractor (active)

**Files:** `VideoFrameSampler.kt`, `ImportedVideoSplitter.kt`

### VideoFrameSampler — decode for extract

| API | Usage |
|-----|--------|
| `MediaExtractor` | Open MP4, select video track |
| `MediaCodec.createByCodecName` / `createDecoderByType` | HW decoder preferred |
| `MediaCodec.dequeueInputBuffer` / `dequeueOutputBuffer` | Decode loop |
| `MediaMetadataRetriever` | Rotation metadata |
| Sample gate | Emit frame every **120 ms** (`FRAME_SAMPLE_INTERVAL_MS`) |
| YUV → Bitmap | NV21 bridge → JPEG compress → `BitmapFactory` (current path) |

### ImportedVideoSplitter — remux import

| API | Usage |
|-----|--------|
| `MediaExtractor.readSampleData` | Read encoded packets |
| `MediaMuxer` | Write `import_NNN.mp4` segments |
| Keyframe boundary | New segment starts on sync frame when size ≥ target |

---

## 3. Google ML Kit — offline extract (active)

### 3.1 Face Detection

**Artifact:** `com.google.mlkit:face-detection`  
**File:** `OfflineFaceDetector.kt` (active) · `MlKitFaceAnalyzer.kt` (legacy live)

| API | Usage |
|-----|--------|
| `FaceDetection.getClient(options)` | Create detector |
| `FaceDetector.process(InputImage)` | Per sampled bitmap |
| `InputImage.fromBitmap(bitmap, 0)` | Offline path |
| `InputImage.fromMediaImage(mediaImage, rotation)` | Legacy live path only |

| Option | Offline (`OfflineFaceDetector`) | Legacy live (`MlKitFaceAnalyzer`) |
|--------|--------------------------------|-----------------------------------|
| `PERFORMANCE_MODE` | FAST (default) or ACCURATE (4K path) | FAST |
| `MIN_FACE_SIZE` | `0.05f` | `0.05f` |
| Tracking | **enabled** | not enabled |
| Output used | `boundingBox` | `boundingBox` → overlay coords |

Filter in `VideoFrameProcessor`: face height ≥ **5%** of frame height.

### 3.2 Pose Detection

**Artifact:** `com.google.mlkit:pose-detection`  
**File:** `OfflinePoseDetector.kt`

| API | Usage |
|-----|--------|
| `PoseDetection.getClient(options)` | Create detector |
| `PoseDetector.process(InputImage)` | Per sampled bitmap |
| `PoseDetectorOptions.SINGLE_IMAGE_MODE` | One-shot per frame |

Filter: torso height ≥ **25%** with shoulders + hips in frame (`inFrameLikelihood` threshold).

---

## 4. App-owned vision logic

**File:** `FaceSharpnessScorer.kt`

| Function | Role |
|----------|------|
| `scoreNormalized(bitmap, roi)` | Laplacian variance on face/pose ROI |
| Thresholds | FHD **≥ 80** · UHD **≥ 65** |

**Dedup:** `VideoFrameProcessor` — 1 kept frame per second (best sharpness in window).

**Legacy KMP** (`shared/.../SubjectFace.kt`, `PassageThresholds.kt`): Arm/Fire proximity — not used in Plan B shell.

---

## 5. Storage & gallery (Android platform)

| Component | APIs | Output |
|-----------|------|--------|
| `LocalDeliveryWriter.publish()` | `MediaStore.Images.Media`, `RELATIVE_PATH`, `IS_PENDING` | `DCIM/AutoBots/{subfolder}/*.jpg` |
| `LocalDeliveryWriter.publishText()` | Legacy `File` + fallback `MediaStore.Downloads` | `Download/AutoBots/{subfolder}/session_log.txt` |
| `WriteQueue` | Kotlin `Channel` | Async serial writes |
| `GalleryLauncher` | `Intent.ACTION_VIEW` | Open latest JPEG |
| `SessionAlbumNaming` | — | `yyyyMMdd_HHmmss` (live) · `ext_DDMMYYYY_HHMM` (import) |

---

## 6. Device load (Android platform)

**File:** `DeviceLoadReader.kt`

| API | Usage |
|-----|--------|
| `PowerManager.currentThermalStatus` | Thermal chip |
| `PowerManager.addThermalStatusListener` | Push updates (API 29+) |
| `ActivityManager.getMemoryInfo` | RAM line on status card |

Display only — does not throttle capture (Flow 11).

---

## 7. Remote / network

**File:** `AutobotsServer.kt`  
**Stack:** Ktor CIO server + WebSockets

| Surface | Role |
|---------|------|
| `WS /ws/control` | Start/Stop, resolution change, state JSON push |
| `WS /ws/preview` | Reserved — **no frames** yet |
| `GET /photos/{id}` | Serve JPEG from MediaStore |

Legacy note: server may still reference analysis-frame hooks from v0.1; operator path does not stream live ML Kit frames.

---

## 8. Legacy — CameraX stills path (v0.1)

Retained in repo for possible B4 re-wire. **Not bound** in `VideoPreviewController` / operator shell.

### ImageAnalysis (live ML Kit)

- 640×360, YUV_420_888, `STRATEGY_KEEP_ONLY_LATEST`
- Coordinate transform: `ImageProxyTransformFactory`, `CoordinateTransform`

### ImageCapture (Lean Burst)

**File:** `LeanBurstCapturer.kt` — 3 shots, ~200 ms gap, `CAPTURE_MODE_MINIMIZE_LATENCY` @ 1080p.

### Focus & metering

**File:** `FaceFocusController.kt` — `startFocusAndMetering`, no auto-cancel on live path.

### Camera2 interop (legacy controller)

**File:** `PreviewCameraController.kt` — `CaptureResult` focal / shutter / ISO readout.

---

## 9. UI (no extra CV plugins)

| Component | Status | Notes |
|-----------|--------|-------|
| `CameraPreviewPane` | **Active** | `AndroidView` + `PreviewView`; no overlay |
| `OperatorShellScreen` | **Active** | Compose pager + cards |
| `FaceOverlay` | Legacy | Not called from shell |
| `AfGridOverlay` | Legacy | Not called from shell |

---

## 10. Not in project (future / ROADMAP)

| Capability | Status |
|------------|--------|
| Custom YOLO / TFLite detector | ❌ |
| HTTP upload / cloud delivery | ❌ (B3) |
| In-app screen mirror stream | ❌ (use scrcpy) |
| On-device frame scoring (smile/pose rank) | ❌ |
| Thermal auto-throttle | ❌ |
| iOS camera runtime | ❌ (KMP shell only) |

---

## 11. When to update this file

| Change | Action |
|--------|--------|
| New Maven CV/camera dependency | Add row in quick map + new section |
| New CameraX use case in shell | Update §1 |
| Replace decode path (skip JPEG roundtrip) | Update §2 |
| New extract target (e.g. body detector) | Update §3 |
| MediaStore path change | Update §5 |
