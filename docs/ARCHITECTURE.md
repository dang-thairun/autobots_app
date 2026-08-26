# Architecture

System design for AutoBots Sports Camera — modules, runtime pipelines, and **Design Flows**.

**Active operator build (v0.1.6):** Plan B video chunk pipeline — see [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).
**Upload shipped in v0.1.5** — see [SEQUENCE_FLOW.md §2](./SEQUENCE_FLOW.md) · spec-by-spec comparison: [DESIGN_FLOW.md](./DESIGN_FLOW.md).  
**Legacy (v0.1 stills):** Passage / Burst / Passage Gate — code retained, not wired in current shell.

Domain: [CONTEXT.md](../CONTEXT.md) · Requirements: [PRD.md](./PRD.md) · Phases: [IMPLEMENTATION.md](./IMPLEMENTATION.md)

---

## 1. Modules (current)

| Module | Role |
|--------|------|
| `shared/` | Plan B contracts: `StreamResolution`, `ExtractionTarget`, `PipelineSessionRecord`, `ChunkRecord`, `PipelineStats` · legacy: `CaptureMode`, `CaptureZone`, `PassageThresholds` |
| `androidApp/` | CameraX Preview+VideoCapture, MediaCodec decode, ML Kit offline, Write Queue, MediaStore, Compose Operator UI |

---

## 2. Runtime pipeline — Plan B (active, v0.1.6)

Two inputs merge before Worker 2:

```
[Live: CameraX Preview + VideoCapture]     [Import: OpenDocument]
         │                                        │
         ▼                                        ▼
  VideoChunkRecorder                    ImportedVideoSplitter
  (rotate at 50 MB)                     (remux at 50 MB)
         │                                        │
         └────────────────┬───────────────────────┘
                          ▼
                   videoQueue (cap 8)
                          ▼
              VideoFrameProcessor
              sample 120 ms → Face / Pose / Person (AND)
              → zone + size gate → Laplacian sharpness
              → FrameQuality score → dedup 1 s per track
                          ▼
              WriteQueue → LocalDeliveryWriter
                          ├─ JPEG → DCIM/AutoBots/{subfolder}/
                          ├─ session_log.txt · photos.csv · tracks.csv
                          │    perf_report.json → Download/AutoBots/{subfolder}/
                          └─ onDelivered(uri) → upload queue (Room) → UploadWorker
```

**Backpressure:** recorder pauses when `videoQueue` is full (8 chunks).

**Operator UI:** Home menu (v0.1.5) → Live capture · Import preview · Session history · Upload queue · Settings. See [SCREEN.md](./SCREEN.md).

Detail: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · Operator: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)

### Subsystems (Plan B)

| Subsystem | Key types | Role |
|-----------|-----------|------|
| Preview + record | `VideoPreviewController`, `VideoChunkRecorder` | CameraX Preview + VideoCapture; chunk rotation |
| Import | `ImportedVideoSplitter` | Remux split without re-encode |
| Orchestration | `CapturePipelineCoordinator` | Queues, session dirs, lifecycle |
| Extract | `VideoFrameSampler`, `VideoFrameProcessor` | HW decode, ML Kit, sharpness, dedup |
| Detect | `OfflineFaceDetector`, `OfflinePoseDetector`, `PersonFootDetector` | Bitmap inference (not live analysis); LiteRT on NPU for face/person |
| Rank | `SubjectTracker`, `FrameQuality` | Who is who across frames; one 0..1 score per frame |
| Delivery | `WriteQueue`, `LocalDeliveryWriter`, `SessionAlbumNaming` | Gallery JPEG + session log |
| Load | `DeviceLoadReader` | Thermal + RAM (display only) |
| Remote | `AutobotsServer` | HTTP/WebSocket Start/Stop on `:8080` |
| Upload | `UploadRepository`, `UploadWorker`, `RunxUploadTransport` | Room queue (6 states) → WorkManager → GraphQL presign → GCS → complete |

### Plan B defaults (source of truth: `StreamResolution.kt`)

| Parameter | Value | Notes |
|-----------|--------|--------|
| Chunk target | **50 MB** | FHD and UHD |
| Sample interval | **120 ms** | FHD and UHD |
| Video queue cap | **8** | Pause record when full |
| Sharpness min | FHD **80** · UHD **65** | `FaceSharpnessScorer` |
| Dedup window | **1 s** | Best sharpness per second |
| Min free storage (live) | **2 GB** | `VideoPreviewController.MIN_FREE_STORAGE_MB` |
| Gallery folder (live) | `v0_1_6_yyyyMMdd_HHmmss` | `SessionAlbumNaming.liveFolder` |
| Gallery folder (import) | `ext_v0_1_6_DDMMYYYY_HHMM` | `SessionAlbumNaming.importFolder` |
| Detect bitmap width | **640 px** | `VideoFrameProcessor.ProcessProfile` |
| Keep per dedup window | **3** | per **track**, not per clock second |
| Upload retry cap | **8** attempts | `UploadRepository.MAX_ATTEMPTS` |
| Upload backoff | 30 s ×2 → 30 min | per row, survives reboot |

---

## 3. Runtime pipeline — v0.1 stills (legacy)

> **Not active** in the current operator shell. Retained for B4 re-wire or retirement.

```
[Camera Sensor]
      │ ImageAnalysis ~640×360
      ▼
[MlKitFaceAnalyzer] → [SubjectFaceSelector] → box + proximity
      │
      ├─ Arm (early, size only) ──► Face AE on subject  (Flow 10 / 18)
      │                              Fixed Focus already set at setup (Flow 15)
      │
      └─ Fire when center ∈ Capture Zone + min size + settle (Flow 17)
                              ▼
                       [LeanBurstCapturer] → JPEGs
                              ▼
                       [WriteQueue] → [LocalDeliveryWriter] → DCIM/AutoBots
```

**Passage Gate** (Flow 2): one burst until Subject Face leaves.

### Subsystems (legacy)

| Subsystem | Key types | Role |
|-----------|-----------|------|
| Camera | `PreviewCameraController` | Bind preview, analysis, capture |
| Detection | `MlKitFaceAnalyzer`, `SubjectFaceSelector` | Live faces + largest = Subject |
| Focus | `FocusStrategy`, `FaceFocusController` | Fixed distance or FaceAf |
| Zone | `CaptureZone` | Composition sweet spot for Fire |
| Capture | `LeanBurstCapturer` | Sequential stills (~200 ms gap) |
| Delivery | `WriteQueue`, `LocalDeliveryWriter` | Bounded async drain to gallery |

Operator UI (legacy): pager page 3 = Observation grid / Capture Zone.

---

## 4. Design Flows

Product and engineering rules. Use **Flow N**, not `ADR 000X`.

Flows **1–3, 7, 9–18** apply to the **v0.1 stills** path.  
Flows **5, 6, 8, 11, 12** apply to **both** paths.  
Plan B adds no new numbered Flow yet — behavior is documented in [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

### Flow 1 — Keep-All Lean Burst

**Rule:** Every shot in a Lean Burst is kept; no on-device scoring on the default path.

**Why:** Scoring adds latency, RAM, and heat; burst order is enough for MVP.

**In code:** `LeanBurstCapturer` → all files enqueued. **[legacy v0.1]**

---

### Flow 2 — Passage Gate on face exit

**Rule:** One burst per Passage; gate re-opens only when Subject Face leaves or drops below arm release — not a time-only cooldown.

**Why:** Prevents duplicate bursts on the same runner still in frame.

**In code:** `OperatorViewModel` `passageGateOpen`. **[legacy v0.1]**

---

### Flow 3 — Subject Face = largest only

**Rule:** AE, Fire, and Gate follow the single largest face in the analysis frame.

**Why:** One runner at a time on a tripod lane; no tracking IDs.

**In code:** `SubjectFaceSelector` in `shared`. **[legacy v0.1 live path]**

---

### Flow 4 — Still JPEG product output

**Rule:** Deliverable product output is **still JPEGs** written to local storage — not video files for the operator.

**Why:** Operators need gallery photos; video chunks are an internal implementation detail in Plan B.

**Note:** Plan B **records MP4 chunks internally** but only **JPEGs** (+ `session_log.txt`) are delivered to gallery paths. Supersedes the older “no video capture” wording for v0.1.

---

### Flow 5 — Local delivery = success · upload is a copy on top

**Rule:** Session success is still **Kept Photos on device** (`DCIM/AutoBots`). Upload runs *after*
that and never gates it — a session with no network is a successful session.

**Why:** Field network is unreliable. Making delivery depend on it would turn a bad signal into
lost photos, and the operator can always retrieve files by cable.

**Revised in v0.1.5** — cloud upload is **no longer out of scope**; it shipped and runs in
production. The rule above survived the change because upload was built as a separate path that
starts at `WriteQueue.onDelivered(uri)`, after the photo is already safe on disk. Nothing in
capture → extract → deliver knows a bucket exists.

**Local files are never deleted after upload** — upload is a *copy*, not a *move*. No toggle.

**In code:** `LocalDeliveryWriter`, MediaStore · then `UploadRepository`, `UploadWorker`. **[both paths]**

---

### Flow 6 — Android-first KMP

**Rule:** Ship on Android; `shared` holds contracts; iOS not required for MVP.

**Why:** CameraX + ML Kit path must work before cross-platform parity.

---

### Flow 7 — Standard vs Max-Sensor

**Rule:** Standard ≈ 1920×1080 ×3 Keep-All. Max-Sensor = ×1 at highest resolution. Never ×3 at max sensor.

**Why:** RAM and write latency; 50MP ×3 risks OOM.

**In code:** `CaptureMode`, `CaptureResolutions`. **[legacy v0.1]**

---

### Flow 8 — No thermal auto-throttle (still true, but the reason no longer is)

**Rule:** Show load readout; do not automatically reduce capture or analysis rate.

**Why (original):** Operator decides when to pause; silent throttle would miss runners.

**⚠️ That reasoning does not survive Plan B.** It was argued against a *real-time* pipeline, where
slowing detection means a runner passes unphotographed. Plan B has no detection rate to slow — the
video is already recorded. Stretching the sample interval or pausing the upload worker misses
**nobody**; it only lengthens the backlog.

So the status of this Flow should read **"not done yet"**, not **"deliberately not done"**.
Tracked in [ROADMAP.md](./ROADMAP.md).

**In code:** `DeviceLoadReader` — display only. **[both paths]**

---

### Flow 9 — Bounded queue backpressure

**Rule:** Async bounded queue (capacity **8**) between producers and slow consumers.

**Why:** Bursts / large frames / extract lag must not block the camera thread or OOM the app.

**In code:** `WriteQueue` (delivery); `videoQueue` in `CapturePipelineCoordinator` (Plan B). **[both paths]**

---

### Flow 10 — Arm starts face-weighted AE (revised)

**Rule:** Crossing Arm drives **AE** (and AF only if `FocusStrategy.FaceAf`) onto Subject Face.

**In code:** `FaceFocusController`. **[legacy v0.1]**

---

### Flow 11 — Device Load Readout

**Rule:** Show thermal + approx RAM on Operator UI; no automated action.

**In code:** `DeviceLoadReader`, status card. **[both paths]**

---

### Flow 12 — Delivery abstraction

**Rule:** Capture/extract enqueues files; delivery layer owns MediaStore writes.

**In code:** `WriteQueue` + `LocalDeliveryWriter`. **[both paths]**

---

### Flow 13 — Sustained lock through Passage

**Rule:** After Arm, do not auto-cancel AE/AF metering on a short timeout.

**In code:** `FaceFocusController`. **[legacy v0.1]**

---

### Flow 14 — Settle gate before Fire

**Rule:** Fire only after a short settle window after Arm (or AE stable).

**In code:** P9/P10 — legacy `OperatorViewModel` path. **[legacy v0.1]**

---

### Flow 15 — Fixed Focus default (tripod)

**Rule:** Default `FocusStrategy.Fixed`: focus distance set once at setup for the Fire sweet-spot.

**In code:** `FocusStrategy` in `shared`. **[legacy v0.1]**

---

### Flow 16 — Proximity-calibrated focus (optional / later)

**Rule:** Never map face box size to diopter without calibration.

**In code:** Not scheduled.

---

### Flow 17 — Capture Zone Fire

**Rule:** Fire when Subject Face center is inside the operator Capture Zone and size ≥ minimum.

**In code:** `CaptureZone` in `shared`. **[legacy v0.1]**

---

### Flow 18 — Face-weighted exposure

**Rule:** AE meters on Subject Face after Arm; optional EV bias.

**In code:** Face metering in legacy path. **[legacy v0.1]**

---

## 5. Defaults — v0.1 stills (legacy, field-tuned)

| Parameter | Value | Notes |
|-----------|--------|--------|
| Focus strategy | Fixed (target) | FaceAf = fallback |
| Arm | ~2.5% | Early Arm |
| Fire min size | ~6% | Floor; zone is primary trigger |
| Burst interval | ~150 ms | 1080p Standard |
| Standard burst | 3 shots | |
| Analysis | ~640×360 | |
| Detector | ML Kit FAST | Live `ImageAnalysis` |

---

## 6. Related docs

- Pipeline (Plan B): [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Operator: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Implementation slices: [IMPLEMENTATION.md](./IMPLEMENTATION.md)
- Field checklist: [FIELD_SETUP.md](./FIELD_SETUP.md)
- APIs: [PLATFORM_APIS.md](./PLATFORM_APIS.md)
- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Later ideas: [ROADMAP.md](./ROADMAP.md)
