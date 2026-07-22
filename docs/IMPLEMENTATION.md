# Implementation plan (post P0–P8)

Tripod-mounted, fixed shooting point. Runner in frame ~**1.5–3 s**.  
Keep each slice small — do not merge P9 + P10 into one change set.

**Release:** [CHANGELOG.md](./CHANGELOG.md) (current **v0.1**)

Naming: [CONVENTIONS.md](./CONVENTIONS.md) · Rules: [architecture.md](./architecture.md) · Field: [FIELD_SETUP.md](./FIELD_SETUP.md)

---

## Assumptions

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
- Prefer small helpers over growing `PreviewCameraController`.
- No half-wired toggles left in the UI.
