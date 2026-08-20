# Implementation plan

**Current release:** [CHANGELOG.md](./CHANGELOG.md) (**v0.1.2** — Plan B video pipeline).  
**Operator flow:** [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · **Pipeline:** [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

The **P9 / P10** sections below describe the **v0.1 stills** path (burst + Passage Gate). That code remains in the repo but is **not wired** in the v0.1.2 operator shell.

---

## Plan B — B1 shipped (v0.1.2)

| Slice | Work | Status |
|-------|------|--------|
| **B1a** | Live video chunk record (`VideoChunkRecorder`, 50 MB rotate) | ✅ |
| **B1b** | Offline extract (`VideoFrameProcessor`, 120 ms sample, sharpness, dedup) | ✅ |
| **B1c** | Gallery delivery (`WriteQueue`, `LocalDeliveryWriter`, subfolders) | ✅ |
| **B1d** | Partial chunk on Stop + queue backpressure (cap 8) | ✅ |
| **B1e** | **Import video** (`ImportedVideoSplitter`, OpenDocument) | ✅ |
| **B1f** | **ExtractionTarget** Face + Pose (experimental) | ✅ |
| **B1g** | **Session history** (`PipelineSessionRecord`, `ChunkHistoryPage`) | ✅ |
| **B1h** | **`session_log.txt`** → Download/AutoBots + cache mirror | ✅ |
| **B1i** | Operator UI shell (3-page pager, processing card, Import button) | ✅ |

---

## Plan B — next slices (v0.1.2+)

| Slice | Work | Status |
|-------|------|--------|
| **B2a** | Per-reject logging in `VideoFrameProcessor` (no_face / small / soft / decode_fail) | ⏳ |
| **B2b** | Sharpness on fixed-size face crop (resolution-agnostic threshold) | ⏳ |
| **B2c** | 4K: optional `detectBitmapWidth` 1280 + ML Kit ACCURATE offline | ⏳ |
| **B2d** | Skip YUV→JPEG roundtrip in `VideoFrameSampler` for 4K | ⏳ |
| **B3** | HTTP upload worker (extend local-only `WriteQueue`) — แผนละเอียด: **[PHASES.md](./PHASES.md)** | ⏳ |
| **B4** | Re-wire or retire v0.1 stills path (`LeanBurstCapturer`, live overlay, Passage Gate) | ⏳ |

---

## Legacy — P9 / P10 (v0.1 stills)

Tripod-mounted, fixed shooting point. Runner in frame ~**1.5–3 s**.  
Keep each slice small — do not merge P9 + P10 into one change set.

Naming: [CONVENTIONS.md](./CONVENTIONS.md) · Rules: [ARCHITECTURE.md](./ARCHITECTURE.md) · Field: [FIELD_SETUP.md](./FIELD_SETUP.md)

---

## Assumptions (v0.1 stills)

| Assumption | Implication |
|------------|-------------|
| Tripod stays put | **Fixed Focus = default** |
| Short zone 1.5–3 s | Early Arm; no AF hunt before Fire |
| Composition matters | **Capture Zone** (grid) decides Fire timing |
| Outdoor light changes | Face AE + EV; focus distance stays fixed |

---

## Phase P9 — Tripod focus & exposure

**Goal:** Sharp + well-exposed stills without waiting on AF each Passage.

| Slice | Work | Status |
|-------|------|--------|
| **P9a** | Docs + `FocusStrategy` / `CaptureZone` in shared; version **v0.1** label | 🔄 mostly done |
| **P9b** | Sustained lock; AE-only on Arm | ✅ in v0.1 |
| **P9c** | Fixed Focus runtime (Camera2 distance) + setup control | ⏳ |
| **P9d** | EV slider + Face AE on Arm | ⏳ AE yes; slider no |

---

## Phase P10 — Capture Zone timing

**Goal:** Fire when face is in the composition sweet spot — not proximity % alone.

| Slice | Work | Status |
|-------|------|--------|
| **P10a** | Wire `CaptureZone` into Fire decision | ✅ in v0.1 |
| **P10b** | Early Arm (~2.5%) independent of zone | ✅ in v0.1 |
| **P10c** | Zone overlay on grid + optional hysteresis tune | ⏳ |
| **P10d** | Field defaults aligned with [FIELD_SETUP.md](./FIELD_SETUP.md) | ⏳ |

---

## Later (unscheduled)

| Item | Notes |
|------|--------|
| Face AF as selectable fallback | Moving tripod / uncalibrated |
| Denser grid (11×15+) | Only if 9×11 feels coarse |
| Thermal throttle, YOLO, scoring, cloud, iPad | [ROADMAP.md](./ROADMAP.md) |

---

## Code hygiene

- Domain types in `shared/` first; Android wires later.
- One slice ≈ one focused change (camera **or** UI).
- Prefer small helpers over growing legacy `PreviewCameraController`.
- No half-wired toggles left in the UI.

---

## Related

- Doc index: [DOCS.md](./DOCS.md)
- Plan B pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Phase table: [DOCS.md § Implementation phases](./DOCS.md#implementation-phases)
- Design rules: [ARCHITECTURE.md](./ARCHITECTURE.md)
- Later ideas: [ROADMAP.md](./ROADMAP.md)
