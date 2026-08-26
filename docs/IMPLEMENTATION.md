# Implementation plan

**Current release:** [CHANGELOG.md](./CHANGELOG.md) (**v0.1.6**).  
Spec-by-spec comparison against the original Flow Design v1: [DESIGN_FLOW.md](./DESIGN_FLOW.md).  
**Operator flow:** [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · **Pipeline:** [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

The **P9 / P10** sections below describe the **v0.1 stills** path (burst + Passage Gate). That code remains in the repo but is **not wired** in the current operator shell.

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

## Plan B — next slices

| Slice | Work | Status |
|-------|------|--------|
| **B2** | **ปิดแล้ว** — 4K extract tuning · ดู "B2 ปิดยังไง" ข้างล่าง | ✅ ปิด 26/08/2026 |
| **B3** | HTTP upload worker — เหตุผลและสัญญากับ backend: **[PHASES.md](./PHASES.md)** | ✅ **v0.1.5** · B3a–B3f-1 ยิง production ผ่าน · เหลือ field test บน 4G จริงและงานยาว 4 ชม. |

### B2 ปิดยังไง

B2 เกิดจาก **OQ-01** ใน [reports/v0.1.2/report.md](../reports/v0.1.2/report.md): *ทำไม 4K ถึงรายงาน "No face"
บน chunk ที่มองด้วยตาก็เห็นว่ามีหน้าคน* ตอนนั้นมันบล็อก 4K ไม่ให้ใช้งานได้จริง

**ตัวปัญหาถูกแก้หมดแล้ว แต่ด้วยเส้นทางที่ไม่ตรงกับที่วางไว้** — ตารางเดิมจึงค้างเป็น ⏳ อยู่ 4 บรรทัด
ข้ามสี่ release โดยที่งานเสร็จไปแล้ว นี่คือบันทึกว่าแต่ละ slice จบยังไง

| Slice เดิม | จบยังไง |
|--|--|
| **B2a** per-reject logging | ✅ **ทำตามที่วาง** — `noSubject` · `tooSmall` · `tooSoft` · `roiInvalid` · `decodeFail` อยู่ใน `perf_report.json` |
| **B2b** sharpness บน crop ขนาดคงที่ | ✅ **ทำตามที่วาง** — `FaceSharpnessScorer.scoreNormalized` ย่อเป็น 128×128 ก่อนวัดเสมอ · threshold จึงเทียบกันได้ข้ามความละเอียด |
| **B2c** `detectBitmapWidth` 1280 ที่ 4K | ❌ **ไม่ทำ และไม่ควรทำแล้ว** — detect bitmap ยัง 640 ทั้งสองโหมด · v0.1.5 เปลี่ยน default เป็น **NPU** ซึ่งเห็นหน้ามากกว่า ML Kit 8% โดยไม่ต้องเพิ่มขนาดภาพ · ถ้า recall ยังไม่พอ คันโยกที่ข้อมูลชี้คือ **เพิ่มจำนวน tile** ไม่ใช่เพิ่มความกว้าง ([RELEASE_0_1_4.md](./RELEASE_0_1_4.md)) |
| **B2d** ข้าม YUV→JPEG roundtrip | ⚠️ **แก้ด้วยเส้นทางอื่น** — roundtrip ยังอยู่ (JPEG 92) แต่ v0.1.5 ไปแก้ `yuv420ToNv21` แทน (−85%) ทำให้ `realtimeRatio` ลง 0.881 → **0.502** · ตัวคอขวดหายแล้ว การรื้อ roundtrip จึงไม่มีอะไรให้ได้คืน |

**ยังเปิดอยู่จาก OQ-01 ไหม** — ไม่ · 4K ใช้งานได้จริงแล้ว (`realtimeRatio` 0.502, sharpness threshold
แยกตามความละเอียด, เหตุผลที่เฟรมตกด่านนับแยกได้) ถ้าเจอ recall ตกที่ 4K อีกในสนาม ให้เปิด**คำถามใหม่
พร้อมตัวเลขจากรอบนั้น** ไม่ใช่รื้อ B2 กลับมา — เพราะสาเหตุที่วางสมมติฐานไว้ตอน v0.1.2 ถูกพิสูจน์ไปแล้วว่าไม่ใช่
| **B4** | Re-wire or retire v0.1 stills path (`LeanBurstCapturer`, live overlay, Passage Gate) | ⏳ **ค้างอยู่จริง** — ไฟล์ยัง compile แต่ไม่มีใครเรียก · ตัดสินใจได้เมื่อรู้ว่า 8.3 MP พอไหม |

---

## Plan B — v0.1.5 / v0.1.6 (shipped after the table above)

| Slice | Work | Status |
|-------|------|--------|
| **B5a** | Home menu แทน pager · Import Preview + trim · Network URL ingest | ✅ v0.1.5 |
| **B5b** | default detector → LiteRT NPU (fallback NPU → GPU → ML Kit) | ✅ v0.1.5 |
| **B5c** | Face + Pose + Person เป็น 3 flags · เกตเป็น AND | ✅ v0.1.6 |
| **B5d** | `SubjectTracker` — dedup ต่อคน ไม่ใช่ต่อวินาที | ✅ v0.1.6 |
| **B5e** | `FrameQuality` 5 ด้าน + `photos.csv` · `tracks.csv` | ✅ v0.1.6 |
| **B5f** | Capture Zone editor · เพดานชัตเตอร์ + EV · `perf_stream.jsonl` | ✅ v0.1.6 |

### ยังไม่ได้ทำ — เรียงตามที่ข้อมูลชี้

| # | งาน | ทำไมอยู่ลำดับนี้ |
|--|--|--|
| **1** | ยืนยันความละเอียดปลายทางกับลูกค้า | 8.3 MP · เป็นข้อเดียวที่ **แก้ทีหลังไม่ได้** และเป็นตัวตัดสินว่า B4 จะ retire หรือ re-wire |
| **2** | ThermalGuard auto-throttle | เงื่อนไขของ "อัดต่อเนื่องหลายชั่วโมง" · เหตุผลเดิมที่ไม่ทำใช้ไม่ได้กับ Plan B แล้ว (Flow 8) |
| **3** | Field test — 4G จริง + งานยาว 4 ชม. | ตัวเลข upload ทั้งหมดวัดบน Wi-Fi/loopback · เพดาน `dataSync` 6 ชม./วันยังไม่รู้ว่าชนเมื่อไหร่ |
| **4** | B4 — retire หรือ re-wire v0.1 stills | รอผลข้อ 1 |

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
| YOLO, smile scoring, iPad | [ROADMAP.md](./ROADMAP.md) — cloud upload กับ frame scoring ทำไปแล้ว |

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
