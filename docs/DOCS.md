# AutoBots Sports Camera — Documentation

Edge-AI sports camera on tripod-mounted Android.  
**Current build (v0.1.2):** video chunk pipeline + offline face extract → [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).

**Start here** → pick one guide below. Naming rules: [CONVENTIONS.md](./CONVENTIONS.md).

---

## Guides

| Doc | Purpose |
|-----|---------|
| [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) | **v0.1.2 operator flow** — ใช้งานจริง, Plan B pipeline |
| [CONVENTIONS.md](./CONVENTIONS.md) | How to write docs; Phase vs Flow vs Passage step |
| [PRD.md](./PRD.md) | Product scope, domain dictionary, acceptance criteria |
| [architecture.md](./architecture.md) | Runtime pipeline, subsystems, **Design Flows** |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | **P9 / P10 slices** — what to build next |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Tripod setup checklist for sharp stills |
| [SCREEN.md](./SCREEN.md) | Operator UI layout (ASCII) |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | CV + camera + native APIs in use |
| [STRUCTURE.md](./STRUCTURE.md) | Repo layout, packages, dependencies |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac (adb + scrcpy) |
| [BUILD.md](./BUILD.md) | Build APK + `adb install` |
| [CHANGELOG.md](./CHANGELOG.md) | Release notes (v0.1.2, …) |
| [ROADMAP.md](./ROADMAP.md) | Unscheduled ideas past P9/P10 |
| [../CONTEXT.md](../CONTEXT.md) | Ubiquitous language |

---

## Implementation phases

### Plan B — video pipeline (active, v0.1.2)

| Phase | Description | Status |
|-------|-------------|--------|
| **B1** | Video chunk record → offline face extract → gallery; Chunk History UI; partial chunk on Stop | ✅ shipped in **v0.1.2** |
| **B2** | 4K extract tuning (sharpness normalize, reject-reason logs, optional ACCURATE ML Kit) | 🔄 field issue |
| **B3** | HTTP upload / remote gallery delivery | ⏳ |
| **B4** | Re-wire or retire v0.1 stills path (`LeanBurstCapturer`, live overlay) | ⏳ |

Slice detail: [CHANGELOG.md § v0.1.2](./CHANGELOG.md) · Operator: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).

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
Record video chunks (1080p 20 MB / 4K 50 MB)
  → queueVideo → sample frames → ML Kit face + sharpness filter
  → dedup 1 face/sec → JPEG full frame → DCIM/AutoBots
```

Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)

### Legacy (v0.1 stills — not active in current build)

```
Setup: Fixed Focus + EV + Capture Zone
Runtime: Face detect → Early Arm (AE) → Zone Fire (Burst) → Write Queue → DCIM/AutoBots
                                    └─ Passage Gate: one burst until face leaves
```
