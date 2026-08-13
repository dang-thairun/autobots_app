# Changelog

Release notes for AutoBots Sports Camera.  
Version source of truth: **`gradle.properties`** → `appVersionName` / `appVersionCode`  
Also sync: `shared/.../AutobotsApp.kt` → `version` (KMP, docs, non-Android).

---

## v0.1.4 (current)

**Theme:** Worker 2 เป็นสองเธรด — ทำให้ decode กับ detect ทำงานทับซ้อนกันแทนที่จะรอกัน
**Phase:** B1 (ไม่เปลี่ยน UI หรือ operator flow)

เหตุผลเต็มพร้อมคำทำนายที่รอผลวัด: **[RELEASE_0_1_4.md](./RELEASE_0_1_4.md)**

### วัดบนเครื่องแล้ว — `run4mins.mp4` UHD, Xiaomi peridot (SM8635)

| | v0.1.3 | **v0.1.4** | |
|--|--|--|--|
| **realtimeRatio** | 2.196× | **1.499×** | **−32%** |
| wall time | 588.5 s | **401.7 s** | −31.7% |

invariant ทั้งสามยังตรงเป๊ะที่ 302 / 579 / 550 · `decodeFailures` 0 · thermal OK ตลอด · `splitActiveMs` = **19.9 s** สำหรับ remux 1.93 GB (จากที่เคยรายงาน 455.8 s)

**pipeline เป็น producer-bound แบบสุดขั้วตามที่ออกแบบ** — `worker_idle` 373.5 s เทียบ `queue_wait` 0.89 s (420 เท่า) และ `yuv_jpeg_argb` ตัวเดียวกินไป **89.5% ของ wall** งานที่เหลือจึงเหลือเป้าหมายเดียว และตัวเลขบอกว่า**ลดมันครึ่งเดียวก็ข้ามเส้น 1.0 แล้ว** (consumer จะกลายเป็นเพดานที่ 0.77×)

**สองสิ่งที่ไม่เป็นไปตามคาด:**
- 📉 **สมมติฐาน aliasing ผิด** — `noSubject` 1,441 → 1,458 ไม่ลด การย่อแบบ halving ไม่ช่วย recall เลย ทั้งที่ `scale_for_detect` แพงขึ้น 4.1 เท่า (ฟรีในแง่ wall เพราะอยู่ฝั่งที่ว่าง 46.5%) OQ-01 กลับไปเป็นคำถามเปิด
- 🐛 **`enableTracking()` ถูกผ่าครึ่งโดยไม่ตั้งใจ** — detector 2 ตัวเห็นเฟรมคนละครึ่ง ช่องว่างเวลาโตเป็น 2 เท่า `roiInvalid` จึงเพิ่มจาก 16 → 29 เกือบเท่าตัว เป็นตัวแปรแฝงที่ทำให้ผลของ halving แยกไม่ออก

**ราคาของการทับซ้อน:** ทุก stage ช้าลงต่อเฟรม 17–78% จากการแย่ง CPU/memory bandwidth (`rotate` +78% · `mlkit_face` +29% · `yuv_jpeg_argb` +17%) แต่ wall ยังลง 32% — overlap ชนะ contention ขาดลอย เพดานทฤษฎี 1.09× จึงไปไม่ถึง

### Why

v0.1.3 จบที่ `realtimeRatio` 2.196× และสรุปว่าทางเดียวที่เหลือคือแก้ `yuv_jpeg_argb` (49.5%) ซึ่งลองแล้วล้มเหลว ข้อสรุปนั้นมองข้ามไปว่า **`yuv_jpeg_argb` ไม่ได้ทำงานพร้อมกับอะไรเลย** — `runBlocking { onFrame(...) }` ในลูป decode ทำให้ Worker 2 ทั้งตัวรันเรียงกับ decoder เวลาของ chunk จึงเป็น *ผลบวก* ของทั้งสองฝั่ง ทั้งที่เครื่องมี 8 คอร์และ thermal OK ตลอด

### Shipped

| Area | What | Why |
|------|------|-----|
| **Pipeline** | Worker 2 แยกเป็น producer (decode + YUV→Bitmap) กับ consumer (`DETECT_WORKERS = 2`) คั่นด้วย `Channel(2)` · worker แต่ละตัวถือ ML Kit detector ของตัวเอง (lazy ตาม target) | wall เปลี่ยนจาก `producer + consumer` (93%) เป็น `max(producer, consumer/2)` → คาด ratio 2.196 → **~1.3–1.5×** และทำให้ pipeline เป็น **producer-bound** ซึ่งแปลว่าต้นทุน detection ที่เพิ่มหลังจากนี้แทบไม่กระทบ wall |
| **Pipeline** | dedup ย้ายไปทำครั้งเดียวท้าย chunk หลังเรียง candidate ตาม PTS (`selectKeepers`) | worker เสร็จไม่เรียงลำดับ — การคัดแบบ streaming จึงใช้ไม่ได้อีก กติกาเดิมทุกประการ แต่ไม่ขึ้นกับลำดับที่ worker ทำเสร็จ |
| **Yield** | `downscale()` ย่อแบบ halving (4K → 1080 → 640) แทนขั้นเดียว · สวิตช์ `MULTISTEP_DOWNSCALE` + ฟิลด์ `downscaleMode` | `createScaledBitmap(filter=true)` เป็น bilinear อ่านแค่ 2×2 — ย่อ 3.4× ในขั้นเดียวทำให้ aliasing กินรายละเอียดที่ detector ใช้ หน้าที่เกณฑ์ขนาดเหลือ ~40 px พอดี **สมมติฐานที่ทดสอบได้สำหรับ OQ-01 (`no_subject` 63–79%)** |
| **Instrumentation** | stage ใหม่ `worker_idle` · `decoder_blocked` → `queue_wait` | คู่นี้บอกตรงๆ ว่าฝั่งไหนคือคอขวด: `queue_wait` สูง → เพิ่ม worker · `worker_idle` สูง → มีแต่งาน `yuv_jpeg_argb` ที่ช่วยได้ |
| **Instrumentation** | 🐛 `sharePercent` หารด้วย wall clock จริงแทนผลบวกของ stage · stage ติดป้าย `thread` · `SCHEMA_VERSION` 1 → **2** | `NESTED_STAGES` ของ v0.1.3 **ตก `rotate`** ทำให้ตัวหารเฟ้อ ~5% และ `sharePercent` ทุกตัวใน v0.1.3 ต่ำกว่าจริง วิธีเดิมต้องรู้ว่า stage ไหนซ้อนใน stage ไหน ซึ่งพลาดมาสองครั้งแล้วและใช้ไม่ได้อีกหลังแยกเธรด |
| **Instrumentation** | `splitDurationMs` แยกเป็น `splitActiveMs` / `splitBlockedMs` (NA-03) | v0.1.3 รายงาน 2,912 ms กับ 455,841 ms สำหรับ remux ที่ควรใช้ ~20 s ทั้งคู่ — ค่าเดิมวัดทั้ง pipeline ไม่ใช่ความเร็ว remux |

### ยังไม่ได้แก้ (ตั้งใจ)

`yuv_jpeg_argb` 49.5% ยังอยู่ (NA-02 · OQ-02) — แต่ตอนนี้เป็น**คอขวดตัวเดียวที่เหลือ** ซึ่งทำให้วัดผลงานชิ้นนั้นง่ายขึ้นมาก · `detectBitmapWidth` 640→960 เก็บไว้เป็นการทดลองตัวแปรเดียวรอบถัดไป · **NPU / LiteRT ยังไม่ใช่รอบนี้** — detection เป็นแค่ 30.9% ของ wall และหลังแยกเธรดแล้วยิ่งได้ผลตอบแทนน้อยลงอีก เหตุผลเต็มอยู่ใน RELEASE_0_1_4.md · live capture ยังไม่ได้ทดสอบตั้งแต่ v0.1.3 (NA-06)

---

## v0.1.3

**Theme:** Worker 2 performance & yield — make **UHD faster than realtime** and stop discarding runners that were merely one step short of the size gate.
**Phase:** B1 (no change to pipeline shape, operator flow, or UI).

Full rationale with measured before/after: **[RELEASE_0_1_3.md](./RELEASE_0_1_3.md)**

### Why

`perf_report.json` from a real UHD import (60.8 s, Xiaomi peridot) showed v0.1.2 running at **realtimeRatio 2.35×** and keeping **3 photos from 507 sampled frames**. The cause was not detection, decode, or thermal throttling — **65.6% of all processing time was image format conversion**, and 96 of the discarded frames were rejected purely on face size (`tooSoft` was 0).

### Shipped

| Area | What | Why |
|------|------|-----|
| **Decode** | Surface/`ImageReader` decode written, tested on device, **found unsupported, and disabled** (`SURFACE_DECODE_ENABLED = false`). Probe now aborts after 3 frames and is remembered per process. | `c2.qti.avc.decoder` does not render into an `RGBA_8888` ImageReader — 0 frames, 54 s wasted per chunk, ratio **8.62×**. The probe+memo makes a wrong guess cost ~360 ms once instead. |
| **Rotation** | Sampler emits frames **unrotated**; detect input is scaled *then* rotated at ~640 px; full-res rotate happens only for frames that pass the size gate | `rotate` was **19.5%** of runtime (52 ms/frame on 4K). Detect bitmap dimensions are unchanged, so detection sees an identical image. |
| **Yield** | `MIN_FACE_HEIGHT_RATIO` 0.05 → **0.035** (and `setMinFaceSize` 0.05 → 0.025) | 42 of 96 rejected frames measured ≥ 0.035 — runners approaching the lens, cut one step short. Measured: `tooSmall` 96 → 50, candidates 9 → 42. The `setMinFaceSize` change turned out to make **no** difference (`noSubject` 402 → 404); the pipeline gate was the only binding one. |
| **Yield** | Dedup keeps the **top 3** frames per 1 s window instead of 1. Candidates are written to JPEG on arrival and ranked from disk, so memory stays at one bitmap. `IMAGE_QUEUE_CAPACITY` 16 → 48. | With the size gate opened, dedup became the new limiter: **42 candidates → 7 photos**. One runner's 16-frame pass yielded 3 photos. Matches Keep-All Policy (~3 per passage). Costs ~3.5 s (3% of runtime) in extra JPEG writes. |
| **Correctness** | `mapRect()` clamps the ROI to frame bounds; an unusable ROI is now counted as `roiInvalid`, not `tooSoft` | 4 frames scored exactly `0` with a 169 px-tall subject — ML Kit with tracking returns boxes past the frame edge, and the scorer returns 0.0 for an empty region. Those frames were being rejected as "blurry" when they were actually "not measurable". |
| **Correctness** | `WriteQueue` fires `onDelivered` **after** decrementing `pending`, not before | A session whose final chunk produced photos never wrote `session_log.txt` or `perf_report.json`: the drain check reads `pendingCount`, and the last file was still counted as pending when the callback ran. Present since v0.1.2 but masked — every 1-minute test happened to end on a chunk with `kept = 0`. Found only on a 4-minute file (158 photos, no logs). |
| **Decode** | Hardware decoder picked via `MediaCodecInfo.isHardwareAccelerated()` on API 29+ | The old `"omx"`/`"hw"` name heuristic never matches Codec2 names (`c2.qti.*`), so it always returned null and logged "using software" incorrectly |
| **Instrumentation** | `perf_report.json`: fixed double-counted `sharePercent`, removed duplicate `session_end`, added `chunks[].decodePath` | A measurement tool that reports wrong numbers is worse than none |

### Measured on device — two UHD imports, Xiaomi peridot / Android 16

Same file both versions (`run1mins.mp4`, 60.8 s, 507 frames):

| | v0.1.2 | v0.1.3 |
|--|--|--|
| **Photos kept** | **3** | **16 — 5.3×** |
| realtimeRatio | 2.35× | **2.017×** |
| Process time | 140.0 s | **120.1 s** |
| `rotate` | 26,458 ms (n=507) | **2,969 ms (n=55)** |
| candidates | 9 | **40** |

Then a longer file (`run4mins.mp4`, 273.7 s, 1.93 GB, 36 chunks): **149 photos · ratio 2.196× · 2,281 frames**. That is **0.54 photos per second of video vs 0.05 in v0.1.2 — roughly 10×**.

Three structural invariants held exactly on both runs — `save_jpeg n` = candidates, `rotate n` = candidates + tooSoft + roiInvalid, `sharpness n` = rotate n − roiInvalid (40/55/54 and 315/574/558). Holding across a 4.5× scale change is not coincidence.

`MIN_SHARPNESS = 65` is now **validated, not just untuned**: with n=558 the cutoff sits mid-distribution and cleanly separates sharp passages (chunk 23: 13% below) from soft ones (chunk 31: 100% below, a whole runner lost to blur in the source footage).

Still above 1.0×, so **live UHD is not yet viable**. That needs `yuv_jpeg_argb` (49.5% of the remaining runtime) solved, which this release attempted and failed. Thermal stayed OK and RAM flat across a 9.8-minute run — every remaining limit is in the code, not the hardware.

**Two caveats for reading future reports:**
- `enableTracking()` makes detection depend on frame history, so per-category reject counts vary ±2–3 between runs of the same file. Trust the structural counts and totals, not small deltas.
- `splitDurationMs` measures wall time including backpressure waits, not remux speed (2,912 ms vs 455,841 ms for the two runs). The two numbers are not comparable; splitting the field is queued for the next release.

### Not in this release (deliberate)

`yuv_jpeg_argb` remains unfixed — the largest outstanding item, needs an `ImageFormat.PRIVATE` + `HardwareBuffer` redesign verified on hardware. `MIN_SHARPNESS` re-tune waits for data from a top-3 run. Exposure control is live-capture-only. `noSubject` (404 of 507 frames, unchanged across both versions) is still undiagnosed. See RELEASE_0_1_3.md.

### Verify after install

`env.appVersion` = `0.1.3` · no `surface_rgba` stage · `save_jpeg n` = candidate count · `rotate n` = candidates + tooSoft + roiInvalid · no `sharpness: 0` anywhere · `realtimeRatio` ≈ 2.0–2.2

A session must always produce `session_log.txt` **and** `perf_report.json` — including one whose final chunk yielded photos, and including a 4-minute import. If either is missing, `adb logcat -s CamPerf | grep drain` names the stuck flag.

---

## v0.1.2

**Theme:** Plan B — continuous **video chunk** recording (live + import) → offline Face/Pose extraction → local gallery + session log.  
**Phase:** **B1** (replaces active stills/burst path in operator UI; legacy P5 code remains in repo but is not wired).

Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)

### Shipped

| Area | What |
|------|------|
| **Pipeline** | Workers: **Record** (`VideoChunkRecorder`) or **Import split** (`ImportedVideoSplitter`) → **Extract** (`VideoFrameProcessor`) → **Deliver** (`WriteQueue` → `DCIM/AutoBots`) |
| **Camera bind** | CameraX **Preview + VideoCapture** only — no live `ImageAnalysis` / face overlay while recording |
| **Chunk rotate** | Auto-finalize MP4 at **50 MB** (`StreamResolution.CHUNK_TARGET_BYTES`); immediate next chunk while camera keeps rolling |
| **Sample interval** | **120 ms** for both 1080p and 4K (`FRAME_SAMPLE_INTERVAL_MS`) |
| **Sharpness** | FHD ≥ 80 · UHD ≥ 65 (`FaceSharpnessScorer`) |
| **Extraction** | `MediaCodec` frame sampler → scale 640px → ML Kit offline Face (FAST) or Pose (experimental) → dedup **1 JPEG/sec** → full-frame JPEG |
| **Import video** | `OpenDocument` → remux split 50 MB chunks → same `videoQueue` as live |
| **Gallery folders** | Live `yyyyMMdd_HHmmss` · Import `ext_DDMMYYYY_HHMM` (`SessionAlbumNaming`) |
| **Session log** | `session_log.txt` → `Download/AutoBots/{subfolder}/` (API 29+) + cache mirror |
| **Stop behavior** | Stop = end record only; partial chunk finalized and queued; processing drains before IDLE |
| **Queue backpressure** | Video queue cap **8** — recorder pauses when full, resumes when worker catches up |
| **UI — status** | Compact chips: Ch / VQ / Face·Pose / K / Th / Disk; REC progress bar (size / 50 MB / elapsed / KB/s) |
| **UI — processing** | Face/Pose extraction card; throughput line on live only |
| **UI — history** | Swipe page **Session history** — `PipelineSessionRecord` cards, expand chunks, detection summary |
| **UI — settings** | Collapsible **Video pipeline** — Face/Pose + 1080p/4K (locked while recording) |
| **UI — actions** | Start/Stop, **Import**, Gallery |
| **Shared models** | `StreamResolution`, `ExtractionTarget`, `PipelineSessionRecord`, `ChunkRecord`, `PipelineStats`, `ChunkRecordingProgress`, `ExtractedFaceImage` |
| Remote | Ktor server: Start/Stop + resolution via WebSocket; state broadcast includes pipeline stats |
| **Screen mirror** | ใช้ **scrcpy** บน Mac — ดู [SCRCPY.md](./SCRCPY.md) (in-app preview stream ยังไม่ส่งเฟรม) |
| **Device load** | Thermal + RAM readout (unchanged from v0.1) |
| **Sync script** | `sync_gallery.sh` — ดึง JPEG + session log จากเครื่องกลับ Mac |

### Bug fixes (B1)

| Fix | Detail |
|-----|--------|
| **Partial chunk on Stop** | `awaitingRecorderFinalize` — pipeline no longer closes before camera finalizes last partial file |
| **Stop / unbind race** | `CameraPreviewPane` waits for recorder finalize before `unbindCamera()` |
| **Single-chunk Stop** | Start → Stop before first rotate (e.g. 8/50 MB) now produces Chunk #1 and runs extract |

### Not in this build

| Item | Notes |
|------|--------|
| Live face overlay on preview | Preview only during record |
| Burst stills / Passage Gate / Capture Zone Fire | v0.1 path not wired in operator shell |
| HTTP upload / cloud delivery | Local gallery only |
| Session JSON persistence | Session log as text file only; UI history in-memory per app run |
| Thumbnail preview in Session history | Path list + metadata only |

### Known gaps

| Topic | Detail |
|-------|--------|
| **4K extract recall** | 1080p field tests OK; 4K may report **No face** on chunks that visibly contain faces — likely sharpness threshold + decode path (tuning in B2) |
| **Legacy code** | `MlKitFaceAnalyzer`, `LeanBurstCapturer`, `PreviewCameraController`, `FaceOverlay` etc. remain for reference / future re-wire |
| **Docs drift (remaining)** | None tracked — Phase 1–3 doc sync complete (v0.1 sections marked legacy where applicable) |

### Storage layout (session)

```
cache/autobots/{sessionId}/video/chunk_NNN.mp4
cache/autobots/{sessionId}/faces/face_{timestampUs}.jpg  →  DCIM/AutoBots/{subfolder}/
Download/AutoBots/{subfolder}/session_log.txt            ←  API 29+
```

### Key files

| Component | Path |
|-----------|------|
| Coordinator | `androidApp/.../pipeline/CapturePipelineCoordinator.kt` |
| Record | `androidApp/.../capture/VideoChunkRecorder.kt` |
| Import split | `androidApp/.../capture/ImportedVideoSplitter.kt` |
| Extract | `androidApp/.../pipeline/VideoFrameProcessor.kt`, `VideoFrameSampler.kt` |
| Preview + record | `androidApp/.../VideoPreviewController.kt` |
| UI | `OperatorShellScreen.kt`, `ChunkHistoryPage.kt`, `OperatorViewModel.kt` |
| Models | `shared/.../StreamResolution.kt`, `PipelineSessionRecord.kt`, `ExtractionTarget.kt` |

### Documentation (Phase 1 sync)

| File | Change |
|------|--------|
| [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) | Rewritten — live + import, 50 MB / 120 ms, session history, session log |
| [DOCS.md](./DOCS.md) | Added `PIPELINE_FLOW.md`; updated B1 + quick pipeline |
| [SCREEN.md](./SCREEN.md) | Already aligned with current shell (prior update) |

### Documentation (Phase 2 sync)

| File | Change |
|------|--------|
| [STRUCTURE.md](./STRUCTURE.md) | Plan B tree — pipeline/, import, session models; legacy marked |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | Active vs legacy quick map; VideoCapture, MediaCodec, offline ML Kit |
| [BUILD.md](./BUILD.md) | Plan B first launch, logcat tags, `install_with_log.sh`, `sync_gallery.sh` |
| [README.md](../README.md) | Project summary + link to `docs/DOCS.md` |

### Documentation (Phase 3 sync)

| File | Change |
|------|--------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Plan B pipeline §2 active; v0.1 legacy §3; Flow 4 updated; defaults split |
| [PRD.md](./PRD.md) | Plan B scope + acceptance criteria; v0.1 sections labeled legacy |
| [CONTEXT.md](../CONTEXT.md) | Plan B glossary first; video-out-of-scope qualified |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Plan B field checklist + v0.1 legacy section |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | B1 shipped slices B1a–B1i; B2+ next |
| [CONVENTIONS.md](./CONVENTIONS.md) | PIPELINE_FLOW, OPERATOR_FLOW, Phase B1–B4 in tables |
| [ROADMAP.md](./ROADMAP.md) | Pose experimental shipped; body pre-filter still future |

### Documentation (Phase 4 sync)

| File | Change |
|------|--------|
| [BUILD.md](./BUILD.md) | Wireless install example (`adb -s <ip>:5555`); drift check script |
| [SCRCPY.md](./SCRCPY.md) | `VideoFrameProcessor` log tag; Related links |
| `OperatorShellScreen.kt` | Ch chip tooltip → 50 MB unified |
| [STRUCTURE.md](./STRUCTURE.md) | PRD tree label; `scripts/check_docs_drift.sh` |
| [PRD.md](./PRD.md), [IMPLEMENTATION.md](./IMPLEMENTATION.md), [FIELD_SETUP.md](./FIELD_SETUP.md), [PIPELINE_FLOW.md](./PIPELINE_FLOW.md), [ROADMAP.md](./ROADMAP.md), [CONTEXT.md](../CONTEXT.md) | § Related cross-links |
| [CONVENTIONS.md](./CONVENTIONS.md) | §7 Plan B doc sync checklist |
| [README.md](../README.md) | Helper scripts table |
| `scripts/check_docs_drift.sh` | New — drift guard for docs + UI tooltips |
| `docs/install.md` | **Removed** — content merged into BUILD.md |
| `architecture.md` → `ARCHITECTURE.md` | Rename for consistent doc naming |

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
