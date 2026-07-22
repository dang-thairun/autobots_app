# AutoBots Sports Camera — Documentation

Edge-AI still camera for marathon photography on tripod-mounted Android.

**Start here** → pick one guide below. Naming rules: [CONVENTIONS.md](./CONVENTIONS.md).

---

## Guides

| Doc | Purpose |
|-----|---------|
| [CONVENTIONS.md](./CONVENTIONS.md) | How to write docs; Phase vs Flow vs Passage step |
| [PRD.md](./PRD.md) | Product scope, domain dictionary, acceptance criteria |
| [architecture.md](./architecture.md) | Runtime pipeline, subsystems, **Design Flows** |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | **P9 / P10 slices** — what to build next |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Tripod setup checklist for sharp stills |
| [SCREEN.md](./SCREEN.md) | Operator UI layout (ASCII) |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | CV + camera + native APIs in use |
| [STRUCTURE.md](./STRUCTURE.md) | Repo layout, packages, dependencies |
| [BUILD.md](./BUILD.md) | Build APK + `adb install` |
| [CHANGELOG.md](./CHANGELOG.md) | Release notes (v0.1, …) |
| [ROADMAP.md](./ROADMAP.md) | Unscheduled ideas past P9/P10 |
| [../CONTEXT.md](../CONTEXT.md) | Ubiquitous language |

---

## Implementation phases

### MVP (complete)

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

### Active (tripod hardening)

| Phase | Description | Status |
|-------|-------------|--------|
| P9 | Fixed Focus + sustained AE + EV | 🔄 partial — see [CHANGELOG.md](./CHANGELOG.md) |
| P10 | Capture Zone Fire + Early Arm | 🔄 P10a/b in v0.1; c/d pending |

Slice detail: [IMPLEMENTATION.md](./IMPLEMENTATION.md).

---

## Quick pipeline (target)

```
Setup: Fixed Focus + EV + Capture Zone
Runtime: Face detect → Early Arm (AE) → Zone Fire (Burst) → Write Queue → DCIM/AutoBots
                                    └─ Passage Gate: one burst until face leaves
```

Design rules: [architecture.md § Design Flows](./architecture.md#4-design-flows).
