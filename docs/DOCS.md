# AutoBots Sports Camera — Documentation

Edge-AI sports camera on tripod-mounted Android.  
**Current build (v0.1.6):** video chunk pipeline → offline Face/Pose/Person extract → per-track ranking → gallery → upload. Start at [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).

**Start here** → pick one guide below. Naming rules: [CONVENTIONS.md](./CONVENTIONS.md).

---

## Guides

| Doc | Purpose |
|-----|---------|
| [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) | **Operator flow (v0.1.6)** — live · browse · network URL · upload |
| [DESIGN_FLOW.md](./DESIGN_FLOW.md) | **ตอบ Flow Design v1 ทีละข้อ** — video pipeline 13 ขั้น · upload 6 สถานะ · backend 3 host · ตัวเลข perf ที่วัดแล้ว/ที่ยังไม่มี · เริ่มที่นี่ถ้าถือสเปค v1 อยู่ในมือ |
| [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) | **Pipeline เทคนิค** — workers, thresholds, storage, session log |
| [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) | **Sequence diagram (Mermaid)** — Live/Browse/Network URL → chunk → Worker 2 → Gallery → upload queue → Runx + backpressure |
| [RELEASE_0_1_3.md](./RELEASE_0_1_3.md) | **v0.1.3** — เหตุผล/ตัวเลขเบื้องหลังการแก้ Worker 2 (perf + yield) |
| [RELEASE_0_1_4.md](./RELEASE_0_1_4.md) | **v0.1.4** — แยก Worker 2 เป็นสองเธรด (perf: realtimeRatio 2.196 → 1.010) |
| [RELEASE_0_1_5.md](./RELEASE_0_1_5.md) | **v0.1.5** — Home menu แทน pager · Import Preview + trim · Network URL ingest · default = NPU |
| [RELEASE_0_1_6.md](./RELEASE_0_1_6.md) | **v0.1.6** — Face+Pose gate · Capture Zone · เพดานชัตเตอร์ + EV · perf stream กู้รายงานหลังโปรเซสตาย |
| [V_0_1_7_PLAN.md](./V_0_1_7_PLAN.md) | **v0.1.7 (แผน · ตัดสินครบ พร้อมเริ่ม S0)** — **เปลี่ยนแกนจากเฟรมเป็นคน** · ByteTrack (DIoU ที่ 8.3 fps) · quality ต่อคน · แยก W1/W2 · `sightings.csv` เป็นตารางหลัก · guardrail ดิสก์ · ส่ง 4 ก้อน · §12 = การตอบกลับรีวิว |
| [V_0_1_7_PHASES.md](./V_0_1_7_PHASES.md) | **v0.1.7 — ลำดับงานที่ต้องทำ** · S0 วัดก่อน → 4 ก้อนส่งแยกได้ → กฎการถอย · เริ่มที่นี่ถ้าจะลงมือ |
| [SCREEN.md](./SCREEN.md) | Operator UI layout (3-page pager, session history) |
| [CONVENTIONS.md](./CONVENTIONS.md) | How to write docs; Phase vs Flow vs Passage step |
| [PRD.md](./PRD.md) | Product scope — **Plan B (active)** + v0.1 stills baseline |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Plan B runtime + Design Flows (v0.1 legacy marked) |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | B1–B3 + B5 shipped · **B4 คือสิ่งเดียวที่ยังค้าง** · legacy P9/P10 |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Field checklist — Plan B + v0.1 legacy |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | CV + camera + native APIs (Plan B active + v0.1 legacy) |
| [STRUCTURE.md](./STRUCTURE.md) | Repo layout, packages, Plan B pipeline modules |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac (adb + scrcpy) |
| [BUILD.md](./BUILD.md) | Build APK + `adb install` |
| [CHANGELOG.md](./CHANGELOG.md) | Release notes (v0.1.2, …) |
| [REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md) | How to write a version summary report · ไทย: [REPORT_GUIDELINE_TH.md](./REPORT_GUIDELINE_TH.md) |
| [../reports/](../reports/) | Version reports — test inputs, results, score (`reports/vX.Y.Z/report.md`) |
| [PHASES.md](./PHASES.md) | **บันทึกการตัดสินใจของ upload pipeline** — ไม่ใช่แผนงานแล้ว · เหตุผลเบื้องหลัง 6 สถานะ · สัญญากับแพลตฟอร์ม (§10) · **โค้ด 19 ไฟล์อ้างถึงหัวข้อในนี้** |
| [ROADMAP.md](./ROADMAP.md) | Unscheduled ideas past B4 |
| [../CONTEXT.md](../CONTEXT.md) | Ubiquitous language — ศัพท์เชิงธุรกิจ (Passage, Kept Frame) |
| [GLOSSARY_TH.md](./GLOSSARY_TH.md) | **ศัพท์เทคนิค/การวัดผลอธิบายภาษาไทย** — DVFS, TC-XX, realtimeRatio, YUV, backpressure, `@Volatile` · เริ่มที่นี่ถ้าอ่านรายงานแล้วสะดุดศัพท์ |

Doc maintenance: [CONVENTIONS.md](./CONVENTIONS.md) §7 · drift check: `./scripts/check_docs_drift.sh`

---

## Implementation phases

### Plan B — video pipeline (active, v0.1.6)

| Phase | Description | Status |
|-------|-------------|--------|
| **B1** | Video chunk record (live) + import → offline Face/Pose extract → gallery; session history; `session_log.txt`; partial chunk on Stop | ✅ shipped in **v0.1.2** |
| **B2** | 4K extract tuning (OQ-01 — 4K รายงาน No face) | ✅ **ปิด 26/08/2026** · B2a/B2b ทำตามแผน · B2c ไม่ทำ (NPU แทน) · B2d แก้ด้วยเส้นทางอื่น — [IMPLEMENTATION.md](./IMPLEMENTATION.md) |
| **B5** | Frame ranking + per-runner tracking (`FrameQuality`, `SubjectTracker`, `tracks.csv`) | ✅ shipped in **v0.1.6** |
| **B3** | HTTP upload / remote gallery delivery — Room queue → WorkManager → GraphQL presign → GCS | ✅ shipped on **v0.1.5** · B3a–B3f · [PHASES.md](./PHASES.md) |
| **B4** | Re-wire or retire v0.1 stills path (`LeanBurstCapturer`, live overlay) | ⏳ |

Slice detail: [CHANGELOG.md § v0.1.2](./CHANGELOG.md) · Operator: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md).

### MVP (complete — v0.1 stills path)

| Phase | Description | Status |
|-------|-------------|--------|
| P0 | KMP shell + Android app runs | ✅ |
| P1 | Operator UI shell | ✅ |
| P2 | CameraX Preview | ✅ |
| P3 | Face detect + Subject Face overlay | ✅ |
| P4 | Arm → Face Lock (AF/AE) | ✅ |
| P5 | Fire → Lean Burst + Passage Gate | ✅ |
| P6 | Write Queue + `DCIM/AutoBots` | ✅ |
| P7 | Standard / Max-Sensor modes | ✅ |
| P8 | Device Load Readout (thermal + RAM) | ✅ |

### Tripod hardening (paused — superseded by Plan B in operator UI)

| Phase | Description | Status |
|-------|-------------|--------|
| P9 | Fixed Focus + sustained AE + EV | 🔄 partial in **v0.1** code; not active in the current shell |
| P10 | Capture Zone Fire + Early Arm | 🔄 P10a/b in v0.1; c/d pending |

Slice detail: [IMPLEMENTATION.md](./IMPLEMENTATION.md).

---

## Quick pipeline (Plan B)

```
Live: VideoChunkRecorder  ─┐
Import: ImportedVideoSplitter ─┤→ videoQueue (cap 8)
                               │
                               ▼
  sample 120 ms → ML Kit Face/Pose + sharpness filter
  → dedup 1/sec → JPEG → DCIM/AutoBots/{subfolder}/
  → session_log.txt → Download/AutoBots/{subfolder}/
```

| พารามิเตอร์ | ค่า |
|------------|-----|
| Chunk size | **50 MB** (FHD + UHD) |
| Sample interval | **120 ms** (FHD + UHD) |
| Gallery folder (live) | `yyyyMMdd_HHmmss` |
| Gallery folder (import) | `ext_DDMMYYYY_HHMM` |

Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · Technical: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)

### Legacy (v0.1 stills — not active in current build)

```
Setup: Fixed Focus + EV + Capture Zone
Runtime: Face detect → Early Arm (AE) → Zone Fire (Burst) → Write Queue → DCIM/AutoBots
                                    └─ Passage Gate: one burst until face leaves
```
