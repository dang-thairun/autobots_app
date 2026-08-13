# AutoBots Sports Camera — Documentation

Edge-AI sports camera on tripod-mounted Android.  
**Current build (v0.1.2):** video chunk pipeline + offline face/pose extract → [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).

**Start here** → pick one guide below. Naming rules: [CONVENTIONS.md](./CONVENTIONS.md).

---

## Guides

| Doc | Purpose |
|-----|---------|
| [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) | **v0.1.2 operator flow** — ใช้งานจริง, live + import |
| [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) | **Pipeline เทคนิค** — workers, thresholds, storage, session log |
| [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) | **Sequence diagram (Mermaid)** — Start/Import → chunk → Worker 2 → Gallery + backpressure |
| [RELEASE_0_1_3.md](./RELEASE_0_1_3.md) | **v0.1.3** — เหตุผล/ตัวเลขเบื้องหลังการแก้ Worker 2 (perf + yield) |
| [SCREEN.md](./SCREEN.md) | Operator UI layout (3-page pager, session history) |
| [CONVENTIONS.md](./CONVENTIONS.md) | How to write docs; Phase vs Flow vs Passage step |
| [PRD.md](./PRD.md) | Product scope — **Plan B (active)** + v0.1 stills baseline |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Plan B runtime + Design Flows (v0.1 legacy marked) |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | B1 shipped + B2–B4 next; legacy P9/P10 |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Field checklist — Plan B + v0.1 legacy |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | CV + camera + native APIs (Plan B active + v0.1 legacy) |
| [STRUCTURE.md](./STRUCTURE.md) | Repo layout, packages, Plan B pipeline modules |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac (adb + scrcpy) |
| [BUILD.md](./BUILD.md) | Build APK + `adb install` |
| [CHANGELOG.md](./CHANGELOG.md) | Release notes (v0.1.2, …) |
| [REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md) | How to write a version summary report · ไทย: [REPORT_GUIDELINE_TH.md](./REPORT_GUIDELINE_TH.md) |
| [../reports/](../reports/) | Version reports — test inputs, results, score (`reports/vX.Y.Z/report.md`) |
| [ROADMAP.md](./ROADMAP.md) | Unscheduled ideas past B4 |
| [../CONTEXT.md](../CONTEXT.md) | Ubiquitous language |

Doc maintenance: [CONVENTIONS.md](./CONVENTIONS.md) §7 · drift check: `./scripts/check_docs_drift.sh`

---

## Implementation phases

### Plan B — video pipeline (active, v0.1.2)

| Phase | Description | Status |
|-------|-------------|--------|
| **B1** | Video chunk record (live) + import → offline Face/Pose extract → gallery; session history; `session_log.txt`; partial chunk on Stop | ✅ shipped in **v0.1.2** |
| **B2** | 4K extract tuning (sharpness normalize, reject-reason logs, optional ACCURATE ML Kit) | 🔄 field issue |
| **B3** | HTTP upload / remote gallery delivery | ⏳ |
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
| P9 | Fixed Focus + sustained AE + EV | 🔄 partial in **v0.1** code; not active in v0.1.2 shell |
| P10 | Capture Zone Fire + Early Arm | 🔄 P10a/b in v0.1; c/d pending |

Slice detail: [IMPLEMENTATION.md](./IMPLEMENTATION.md).

---

## Quick pipeline (v0.1.2 — Plan B)

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
