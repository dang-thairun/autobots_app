# Changelog

Release notes for AutoBots Sports Camera.  
Version source of truth: **`gradle.properties`** → `appVersionName` / `appVersionCode`  
Also sync: `shared/.../AutobotsApp.kt` → `version` (KMP, docs, non-Android).

---

## v0.1.2 (current)

**Theme:** Plan B — continuous **video chunk** recording → offline face extraction → local gallery.  
**Phase:** **B1** (replaces active stills/burst path in operator UI; legacy P5 code remains in repo but is not wired).

Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)

### Shipped

| Area | What |
|------|------|
| **Pipeline** | 3 parallel workers: **Record** (`VideoChunkRecorder`) → **Extract** (`VideoFaceProcessor`) → **Deliver** (`WriteQueue` → `DCIM/AutoBots`) |
| **Camera bind** | CameraX **Preview + VideoCapture** only — no live `ImageAnalysis` / face overlay while recording |
| **Chunk rotate** | Auto-finalize MP4 at size target; immediate next chunk while camera keeps rolling |
| **Resolution** | **1080p:** 20 MB/chunk, sample every **300 ms** · **4K:** 50 MB/chunk, sample every **120 ms** (`StreamResolution`) |
| **Face extract** | `MediaCodec` frame sampler → scale 640px → ML Kit offline (FAST) → face ≥ 5% height → Laplacian sharpness ≥ 80 → dedup **1 JPEG/sec** (best sharpness) → full-frame JPEG q95 |
| **Stop behavior** | Stop = end record only; partial chunk finalized and queued; processing drains before IDLE |
| **Queue backpressure** | Video queue cap **8** — recorder pauses when full, resumes when worker catches up |
| **UI — status** | Compact chips: Ch / VQ / Face / K / Th / Disk; REC progress bar (size / target / elapsed / KB/s) |
| **UI — processing** | Always-visible **Face extraction** card with overall % + scan % per chunk |
| **UI — history** | Swipe page **Chunk History** — per-chunk metadata, `(partial)` flag, Show/Hide file list + total size on extract line |
| **UI — settings** | Collapsible **Video pipeline** — 1080p / 4K chips (locked while recording) |
| **Shared models** | `StreamResolution`, `PipelineStats`, `ChunkRecord`, `ChunkRecordingProgress`, `ExtractedFaceImage` |
| Remote | Existing Ktor server: Start/Stop + resolution via WebSocket; state broadcast includes pipeline stats |
| **Screen mirror** | ใช้ **scrcpy** บน Mac — ดู [SCRCPY.md](./SCRCPY.md) (in-app preview stream ยังไม่ส่งเฟรม) |
| **Device load** | Thermal + RAM readout (unchanged from v0.1) |

### Bug fixes (B1)

| Fix | Detail |
|-----|--------|
| **Partial chunk on Stop** | `awaitingRecorderFinalize` — pipeline no longer closes before camera finalizes last partial file |
| **Stop / unbind race** | `CameraPreviewPane` waits for recorder finalize before `unbindCamera()` |
| **Single-chunk Stop** | Start → Stop before first rotate (e.g. 8/20 MB) now produces Chunk #1 and runs face extract |

### Not in this build

| Item | Notes |
|------|--------|
| Live face overlay on preview | Preview only during record |
| Burst stills / Passage Gate / Capture Zone Fire | v0.1 path not wired in operator shell |
| HTTP upload / cloud delivery | Local gallery only |
| Session JSON persistence | Chunk log in-memory for session |
| Thumbnail preview in Chunk History | Path list only |
| Body / pose pre-filter | Noted for future — see [ROADMAP.md](./ROADMAP.md) |

### Known gaps

| Topic | Detail |
|-------|--------|
| **4K extract recall** | 1080p field tests OK; 4K may report **No face** on chunks that visibly contain faces — likely sharpness threshold + decode path on full-resolution frames (tuning in progress) |
| **Docs drift** | [SCREEN.md](./SCREEN.md) still describes v0.1 Observation grid / face overlay — use [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) for current UI |
| **Legacy code** | `MlKitFaceAnalyzer`, `LeanBurstCapturer`, `PreviewCameraController` etc. remain for reference / future re-wire |

### Storage layout (session)

```
cache/autobots/{sessionId}/video/chunk_NNN.mp4
cache/autobots/{sessionId}/faces/face_{timestampUs}.jpg  →  DCIM/AutoBots
```

### Key files

| Component | Path |
|-----------|------|
| Coordinator | `androidApp/.../pipeline/CapturePipelineCoordinator.kt` |
| Record | `androidApp/.../capture/VideoChunkRecorder.kt` |
| Extract | `androidApp/.../pipeline/VideoFaceProcessor.kt`, `VideoFrameSampler.kt` |
| Preview + record | `androidApp/.../VideoPreviewController.kt` |
| UI | `OperatorShellScreen.kt`, `ChunkHistoryPage.kt`, `OperatorViewModel.kt` |
| Resolution config | `shared/.../StreamResolution.kt` |

---

## v0.1

**Theme:** MVP complete (P0–P8) + first tripod hardening (partial P9/P10) + operator UI polish.

### Shipped

| Area | What |
|------|------|
| **MVP P0–P8** | KMP shell, Operator UI, CameraX preview, ML Kit faces, Arm/AE, Lean Burst, Passage Gate, Write Queue → `DCIM/AutoBots`, Standard/Max-Sensor, thermal + RAM readout |
| **P9b** | Sustained AE lock (no 3 s auto-cancel); tripod path uses **AE-only** on face (`FocusStrategy.Fixed`) |
| **P10a–b** | **Capture Zone Fire** (`PassageFireEvaluator`); Early Arm ~2.5%; min size ~6%; settle ~100 ms; zone dwell 2 frames |
| **Speed** | Burst gap **150 ms** (1080p Standard); faster Arm/AE metering interval |
| **UI** | Compact status chips; **Start \| Gallery** row; face box **% score** on overlay |
| **Debug** | ML Kit `analyze` timing log (1 s summary) — `adb logcat -s MlKitFaceAnalyzer` |
| **Docs** | `BUILD.md`, `SCREEN.md`, `IMPLEMENTATION.md`, `FIELD_SETUP.md`, `PLATFORM_APIS.md`, Flows 13–18 |

### In progress / not shipped in v0.1

| Slice | Item | Status |
|-------|------|--------|
| **P9a** | Docs + shared contracts fully aligned | 🔄 mostly done |
| **P9c** | **Fixed Focus** — Camera2 lock distance at setup | ⏳ |
| **P9d** | **EV compensation** slider | ⏳ |
| **P10c** | Capture Zone drawn on observation grid | ⏳ |
| **P10d** | Field-tune defaults on site | ⏳ |

---

## Version bump checklist

1. Edit `gradle.properties` → `appVersionName` / `appVersionCode`
2. Edit `AutobotsApp.version` in `shared/.../AutobotsApp.kt` (same string)
3. Add section to this file
4. Update `docs/DOCS.md` phase table if a phase completed
5. Rebuild: `./gradlew :androidApp:assembleDebug`

**Do not use `.env`** — Android/KMP standard is `gradle.properties` + optional `AutobotsApp` for shared code.

---

## Earlier

Pre-changelog releases were tracked as **Phase P0–P8** only. See [DOCS.md](./DOCS.md) phase table for milestone history.
