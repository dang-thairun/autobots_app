# AutoBots Sports Camera — Codebase Structure & Directory Layout

Directory trees, package mapping, and build configurations.  
**Active operator build:** Plan B video pipeline (v0.1.6) — see [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

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
│   ├── DESIGN_FLOW.md           # Flow Design v1 answered point by point
│   ├── SEQUENCE_FLOW.md         # Mermaid sequences incl. upload
│   ├── PHASES.md                # B3 upload slices + backend contract
│   ├── GLOSSARY_TH.md           # ศัพท์การวัดผล (ไทย)
│   └── ROADMAP.md
│
├── shared/                      # KMP common module
│   └── src/commonMain/kotlin/com/autobots/camera/
│       ├── AutobotsApp.kt              # name, version, phase banner
│       ├── StreamResolution.kt         # 1080p/4K, 50 MB, 120 ms sample
│       ├── ExtractionTarget.kt         # Face · Pose · Person flags (AND gates)
│       ├── SubjectTracker.kt           # Who is who across sampled frames
│       ├── TrackSummary.kt             # One row per person → tracks.csv
│       ├── FrameQuality.kt             # 5-term 0..1 score per frame
│       ├── DetectorBackend.kt          # ML Kit / LiteRT CPU · GPU · NPU
│       ├── DetectZone.kt               # Operator-drawn detect region
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
        │   ├── OperatorShellScreen.kt  # Home menu + destinations (v0.1.5)
        │   ├── OperatorViewModel.kt    # UI state, capture/import actions
        │   ├── CameraPreviewPane.kt    # PreviewView + recorder bind
        │   ├── ChunkHistoryPage.kt     # Session history cards
        │   ├── ImportPreviewPage.kt    # Preview + trim before extract
        │   ├── NetworkUrlPage.kt       # Third ingest path: stream from URL
        │   ├── ZoneEditorPage.kt       # Draw the capture zone on real video
        │   ├── UploadQueuePage.kt      # Queue state, retry, pause
        │   ├── UploadSettingsPage.kt   # Sign in, pick event, endpoint
        │   ├── QrScanPreview.kt        # Provision endpoint + token by QR
        │   ├── FaceOverlay.kt          # [legacy] not wired in shell
        │   └── AfGridOverlay.kt        # [legacy] not wired in shell
        │
        └── camera/
            ├── VideoPreviewController.kt    # Preview + VideoCapture (Plan B)
            ├── CapturePipelineCoordinator.kt# Workers, queues, session lifecycle
            │
            ├── pipeline/                    # Plan B extract path (active)
            │   ├── VideoFrameProcessor.kt   # detect + gates + score + dedup
            │   ├── VideoFrameSampler.kt     # MediaCodec HW decode
            │   ├── SampledFrame.kt
            │   ├── CapturePipelineCoordinator.kt
            │   └── FaceSharpnessScorer.kt   # Laplacian on normalised ROI
            │
            ├── capture/
            │   ├── VideoChunkRecorder.kt    # Live MP4 chunk rotation
            │   ├── ImportedVideoSplitter.kt # Import remux → chunks
            │   ├── ChunkCaptureMeta.kt
            │   └── LeanBurstCapturer.kt     # [legacy] still burst
            │
            ├── detection/
            │   ├── OfflineFaceDetector.kt   # ML Kit on decoded bitmaps
            │   ├── OfflinePoseDetector.kt   # ML Kit Pose
            │   ├── FaceDetLiteDetector.kt   # face_det_lite 640×480, tiled
            │   ├── PersonFootDetector.kt    # foot_track_net — sees everyone
            │   ├── SubjectFaceDetector.kt
            │   ├── QnnDelegate.kt           # NPU delegate load
            │   ├── DetectorAvailability.kt · DetectorProbe.kt
            │   ├── DetectorComparison.kt    # bench: run all backends per frame
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
            │   ├── CamPerf.kt               # BuildConfig.CAM_PERF gate
            │   ├── PerfReport.kt            # → perf_report.json
            │   ├── PerfStream.kt            # → perf_stream.jsonl (live)
            │   ├── PerfRecovery.kt          # rebuild a report after a crash
            │   ├── DvfsProbe.kt
            │   ├── StageStats.kt
            │   └── ExposureStats.kt
            │
            ├── upload/                      # B3 — shipped v0.1.5
            │   ├── UploadItem.kt            # Room entity, points at MediaStore
            │   ├── UploadStatus.kt          # 6 states
            │   ├── UploadDao.kt · UploadDatabase.kt
            │   ├── UploadRepository.kt      # claim, backoff, attempt cap
            │   ├── UploadWorker.kt          # one worker drains whole queue
            │   ├── UploadScheduler.kt       # unique work + constraints
            │   ├── UploadTransport.kt       # backend-agnostic interface
            │   ├── RunxUploadTransport.kt   # GraphQL presign → GCS → complete
            │   ├── FakeUploadTransport.kt   # local sink; built against this first
            │   ├── RunxAuthClient.kt        # sign in, list events
            │   ├── UploadConfig.kt · UploadSettings.kt · UploadSession.kt
            │   ├── UploadDestination.kt · UploadAuthUiState.kt
            │   └── UploadNotification.kt    # foreground service notification
            │
            ├── diag/
            │   ├── CrashDiagnostics.kt      # crash.txt next to the session
            │   └── SessionRecovery.kt       # finish a session the app died in
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
| `ExtractionTarget` | Face · Pose · Person — three independent flags, gates are AND |
| `SubjectTracker` | Matches detections to people across frames (predicted-box IOU, then centre distance) |
| `TrackSummary` | One person per row → `tracks.csv`, **including people who got no photo** |
| `FrameQuality` | 0..1 score: sharpness `.40` · size `.20` · centre `.15` · confidence `.15` · framing `.10` |
| `DetectorBackend` | ML Kit FAST/ACCURATE · `face_det_lite` CPU/GPU/NPU · Compare-all bench mode |
| `DetectZone` | Operator-drawn region a subject's centre must fall inside |
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

Not wired in the current operator shell — see B4 in [IMPLEMENTATION.md](./IMPLEMENTATION.md).

### 2.3. Android UI (`com.autobots.ui`)

| File | Role |
|------|------|
| `OperatorShellScreen` | **Home menu** + `OperatorDestination` routing (v0.1.5 — replaced the 3-page pager as the shell) |
| `OperatorViewModel` | `OperatorUiState`, Start/Stop/Import, session history, throughput, upload actions |
| `CameraPreviewPane` | `PreviewView` host; binds `VideoPreviewController` + `VideoChunkRecorder` |
| `ChunkHistoryPage` | `PipelineSessionRecord` cards, expand chunks, people counts |
| `ImportPreviewPage` | Inspect a clip (length, resolution, backend) and trim before extracting |
| `NetworkUrlPage` | Third ingest path — stream a video by URL over byte-range |
| `ZoneEditorPage` | Draw the capture zone on the real image |
| `UploadQueuePage` | Queue state per row, retry, pause |
| `UploadSettingsPage` · `QrScanPreview` | Sign in, pick event, provision endpoint + token by QR |

`FaceOverlay` / `AfGridOverlay` exist but are **not called** from the current shell.

### 2.4. Android Camera — Plan B pipeline (active)

| Module | Key types | Role |
|--------|-----------|------|
| **Preview + record** | `VideoPreviewController`, `VideoChunkRecorder` | CameraX Preview + VideoCapture; rotate at 50 MB |
| **Import** | `ImportedVideoSplitter` | `MediaExtractor`/`MediaMuxer` remux split |
| **Orchestration** | `CapturePipelineCoordinator` | `videoQueue` (cap 8), workers, session dirs, gallery delivery |
| **Extract** | `VideoFrameSampler`, `VideoFrameProcessor` | HW decode → sample 120 ms → ML Kit → sharpness → dedup |
| **Detect** | `OfflineFaceDetector`, `OfflinePoseDetector`, `PersonFootDetector` | Bitmap inference; `face_det_lite` + `foot_track_net` run on NPU via LiteRT |
| **Rank** | `SubjectTracker`, `FrameQuality` | Group frames per person, score each, keep the best 3 per track-second |
| **Delivery** | `WriteQueue`, `LocalDeliveryWriter`, `SessionAlbumNaming` | JPEG → `DCIM/AutoBots/{session}/`, log → `Download/AutoBots/{session}/` · session name carries the app version |
| **Remote** | `AutobotsServer` | Start/Stop, state push on `:8080` |
| **Load** | `DeviceLoadReader` | Thermal + RAM (display only) |
| **Upload** | `UploadRepository`, `UploadWorker`, `RunxUploadTransport` | Room queue (6 states) → WorkManager → GraphQL presign → GCS → complete · never deletes local files |
| **Perf** | `PerfReport`, `PerfStream`, `PerfRecovery` | `perf_report.json` (debug builds) + live `perf_stream.jsonl` that survives a crash |
| **Diag** | `CrashDiagnostics`, `SessionRecovery` | Write `crash.txt`; finish a session the app died inside |

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
