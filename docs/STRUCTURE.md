# AutoBots Sports Camera — Codebase Structure & Directory Layout

Directory trees, package mapping, and build configurations.  
**Active operator build:** Plan B video pipeline (v0.1.2) — see [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

---

## 1. Directory Layout

```
autobots-android-camera/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties          # appVersionName / appVersionCode
├── gradle/libs.versions.toml
├── gradlew
├── sync_gallery.sh            # Pull JPEG + session_log from device → Mac
├── install_with_log.sh        # installDebug + logcat capture
├── scripts/
│   └── check_docs_drift.sh    # Guard stale doc/UI patterns
├── CONTEXT.md                 # Domain glossary
├── README.md
│
├── docs/
│   ├── DOCS.md                # Documentation entry point
│   ├── OPERATOR_FLOW.md         # Operator guide (Plan B)
│   ├── PIPELINE_FLOW.md         # Technical pipeline reference
│   ├── SCREEN.md                # Operator UI layout
│   ├── CHANGELOG.md
│   ├── BUILD.md
│   ├── STRUCTURE.md             # [This file]
│   ├── PLATFORM_APIS.md
│   ├── ARCHITECTURE.md          # Plan B runtime + v0.1 Design Flows
│   ├── PRD.md                   # Plan B scope + v0.1 stills baseline
│   ├── IMPLEMENTATION.md
│   ├── CONVENTIONS.md
│   ├── FIELD_SETUP.md
│   ├── SCRCPY.md
│   └── ROADMAP.md
│
├── shared/                      # KMP common module
│   └── src/commonMain/kotlin/com/autobots/camera/
│       ├── AutobotsApp.kt              # name, version, phase banner
│       ├── StreamResolution.kt         # 1080p/4K, 50 MB, 120 ms sample
│       ├── ExtractionTarget.kt         # Face / Pose
│       ├── PipelineSessionRecord.kt    # Session log + history model
│       ├── ChunkRecord.kt              # Per-chunk metadata + ExtractedFaceImage
│       ├── ChunkRecordingProgress.kt   # REC line progress
│       ├── PipelineStats.kt            # Live pipeline counters
│       ├── CaptureMode.kt              # [legacy] Standard / Max-Sensor
│       ├── FocusStrategy.kt            # [legacy] Fixed / FaceAf
│       ├── CaptureZone.kt              # [legacy] grid Fire evaluator
│       ├── PassageThresholds.kt        # [legacy] Arm / Fire defaults
│       ├── PassageFireEvaluator.kt     # [legacy]
│       ├── detection/SubjectFace.kt    # [legacy] SubjectFaceSelector
│       └── network/NetworkModels.kt    # Remote API DTOs
│
└── androidApp/
    ├── build.gradle.kts
    └── src/main/kotlin/com/autobots/
        ├── MainActivity.kt             # Entry, permissions, import picker
        │
        ├── ui/                         # Compose operator shell (active)
        │   ├── OperatorShellScreen.kt  # 3-page pager + status cards
        │   ├── OperatorViewModel.kt    # UI state, capture/import actions
        │   ├── CameraPreviewPane.kt    # PreviewView + recorder bind
        │   ├── ChunkHistoryPage.kt     # Session history cards
        │   ├── FaceOverlay.kt          # [legacy] not wired in shell
        │   └── AfGridOverlay.kt        # [legacy] not wired in shell
        │
        └── camera/
            ├── VideoPreviewController.kt    # Preview + VideoCapture (Plan B)
            ├── CapturePipelineCoordinator.kt# Workers, queues, session lifecycle
            │
            ├── pipeline/                    # Plan B extract path (active)
            │   ├── VideoFrameProcessor.kt   # ML Kit + sharpness + dedup
            │   ├── VideoFrameSampler.kt     # MediaCodec HW decode
            │   └── FaceSharpnessScorer.kt   # Laplacian on face/pose ROI
            │
            ├── capture/
            │   ├── VideoChunkRecorder.kt    # Live MP4 chunk rotation
            │   ├── ImportedVideoSplitter.kt # Import remux → chunks
            │   ├── ChunkCaptureMeta.kt
            │   └── LeanBurstCapturer.kt     # [legacy] still burst
            │
            ├── detection/
            │   ├── OfflineFaceDetector.kt   # ML Kit on decoded bitmaps
            │   ├── OfflinePoseDetector.kt   # ML Kit Pose (experimental)
            │   └── MlKitFaceAnalyzer.kt     # [legacy] live ImageAnalysis
            │
            ├── delivery/
            │   ├── LocalDeliveryWriter.kt   # MediaStore JPEG + session log
            │   ├── SessionAlbumNaming.kt    # live / import folder names
            │   ├── WriteQueue.kt
            │   ├── GalleryLauncher.kt
            │   └── PhotoDeliveryService.kt
            │
            ├── network/
            │   └── AutobotsServer.kt        # Ktor HTTP + WebSocket
            │
            ├── load/
            │   ├── DeviceLoadReader.kt
            │   └── DeviceLoadSnapshot.kt
            │
            ├── perf/
            │   ├── CamPerf.kt
            │   ├── StageStats.kt
            │   └── ExposureStats.kt
            │
            ├── PreviewCameraController.kt   # [legacy] Preview+Analysis+Capture
            ├── CaptureResolutions.kt        # [legacy] still resolution pick
            ├── CameraExposureReadout.kt
            ├── CameraCapabilities.kt
            └── focus/FaceFocusController.kt # [legacy] AF/AE lock
```

---

## 2. Package Descriptions

### 2.1. Shared (`com.autobots.camera`) — Plan B (active)

| Type | Role |
|------|------|
| `StreamResolution` | FHD/UHD labels, **50 MB** chunk target, **120 ms** sample interval |
| `ExtractionTarget` | Face vs Pose offline extract mode |
| `PipelineSessionRecord` | One live or import run — aggregates chunks, writes `session_log.txt` text |
| `ChunkRecord` | Per-chunk video + extract stats, `ExtractedFaceImage` list |
| `PipelineStats` | Live counters for UI chips (Ch, VQ, faces kept, …) |
| `ChunkRecordingProgress` | REC #N line (bytes / target / elapsed) |

### 2.2. Shared — v0.1 stills (legacy, code retained)

| Type | Role |
|------|------|
| `CaptureMode` | Standard vs Max-Sensor still burst |
| `FocusStrategy`, `CaptureZone`, `PassageThresholds` | Tripod / Passage Gate contracts |
| `SubjectFaceSelector` | Largest face = Subject Face |

Not wired in v0.1.2 operator shell.

### 2.3. Android UI (`com.autobots.ui`)

| File | Role |
|------|------|
| `OperatorShellScreen` | Layered preview + 3-page `HorizontalPager` (Controls / Clean / History) |
| `OperatorViewModel` | `OperatorUiState`, Start/Stop/Import, session history, throughput |
| `CameraPreviewPane` | `PreviewView` host; binds `VideoPreviewController` + `VideoChunkRecorder` |
| `ChunkHistoryPage` | `PipelineSessionRecord` cards, expand chunks |

`FaceOverlay` / `AfGridOverlay` exist but are **not called** from the current shell.

### 2.4. Android Camera — Plan B pipeline (active)

| Module | Key types | Role |
|--------|-----------|------|
| **Preview + record** | `VideoPreviewController`, `VideoChunkRecorder` | CameraX Preview + VideoCapture; rotate at 50 MB |
| **Import** | `ImportedVideoSplitter` | `MediaExtractor`/`MediaMuxer` remux split |
| **Orchestration** | `CapturePipelineCoordinator` | `videoQueue` (cap 8), workers, session dirs, gallery delivery |
| **Extract** | `VideoFrameSampler`, `VideoFrameProcessor` | HW decode → sample 120 ms → ML Kit → sharpness → dedup |
| **Detect** | `OfflineFaceDetector`, `OfflinePoseDetector` | Bitmap-based ML Kit (not live analysis) |
| **Delivery** | `WriteQueue`, `LocalDeliveryWriter`, `SessionAlbumNaming` | JPEG → `DCIM/AutoBots/{session}/`, log → `Download/AutoBots/{session}/` · session name carries the app version |
| **Remote** | `AutobotsServer` | Start/Stop, state push on `:8080` |
| **Load** | `DeviceLoadReader` | Thermal + RAM (display only) |

### 2.5. Android Camera — v0.1 stills (legacy)

| Module | Key types | Role |
|--------|-----------|------|
| Preview + burst | `PreviewCameraController`, `LeanBurstCapturer` | ImageAnalysis + ImageCapture burst |
| Live detect | `MlKitFaceAnalyzer` | 640×360 analysis stream |
| Focus | `FaceFocusController` | Face-weighted AF/AE |

---

## 3. Runtime data flow (Plan B)

```
MainActivity
  └─ OperatorViewModel
       ├─ startCapture() → CapturePipelineCoordinator
       │     ├─ VideoPreviewController.bindPreview()
       │     └─ VideoChunkRecorder → videoQueue
       ├─ importVideo() → ImportedVideoSplitter → videoQueue
       └─ stats ← coordinator.flow

videoQueue (Channel, cap 8)
  └─ VideoFrameProcessor.process()
       └─ WriteQueue → LocalDeliveryWriter → MediaStore
```

Session cache: `cache/autobots/{sessionId}/video/`, `faces/`, mirror `session_log.txt`.

---

## 4. Gradle & dependencies

| Item | Value |
|------|-------|
| **compileSdk / targetSdk** | 35 |
| **minSdk** | 26 |
| **JDK** | 17 |
| **Kotlin** | 2.0.21 |
| **CameraX** | 1.4.1 (`camera-video` for `VideoCapture`) |
| **ML Kit Face** | 16.1.7 |
| **ML Kit Pose** | 18.0.0-beta5 |
| **Ktor** | 2.3.12 (embedded CIO server) |
| **Compose BOM** | 2024.10.01 |

Per-API inventory: [PLATFORM_APIS.md](./PLATFORM_APIS.md).

---

## 5. Related docs

- Operator: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Build: [BUILD.md](./BUILD.md)
- Index: [DOCS.md](./DOCS.md)
