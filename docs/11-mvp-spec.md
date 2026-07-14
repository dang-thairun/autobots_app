# 11 — MVP Spec

Spec for the first shippable AutoBots Sports Camera, derived from grilling + [`../CONTEXT.md`](../CONTEXT.md) + [`decisions.md`](./decisions.md).

## Goal

On a tripod-mounted Android phone, automatically capture still JPEGs of marathon runners as they approach, with focus/exposure locked on the face before shutter, and save them locally for later retrieval.

## Actors

| Actor | Role |
|-------|------|
| **Operator** | Sets up tripod, aims camera, Start/Stop, selects Capture Mode, watches status + Device Load Readout |
| **Runner** | Subject; no app interaction |
| **Future uploader** | Out of MVP; will consume local Kept Photos later |

## In scope (MVP)

1. **Still Photo Product** — JPEG only; no video  
2. **Android-First Runtime** — KMP common contracts OK; iOS not required to ship  
3. **Operator Preview** + **Operator Controls**: Start/Stop Capture, Capture Mode  
4. **Device Load Readout** — thermal status + approx RAM (CPU/GPU if cheap); display only  
5. **Subject Face** — largest face in analysis frame  
6. **Arm Threshold** (~10%) → **Face Lock** (AF + AE)  
7. **Fire Threshold** (~40%) → one **Lean Burst** per **Passage**  
8. **Capture Mode Option**
   - **Standard** (default): ~3 shots, **Keep-All Policy**  
   - **Max-Sensor**: 1 shot at max sensor resolution  
9. **Passage Gate** — after fire, closed until Subject Face leaves zone (or drops below arm)  
10. **Write Queue** — bounded async drain to disk  
11. **Local Delivery** — e.g. `DCIM/AutoBots/`; success = files on device  

## Out of scope (MVP)

| Item | Notes |
|------|--------|
| Frame scoring / keep-top-1 | ADR 0001 |
| Smile / Pose on live path | Flags may exist OFF + unwired |
| Thermal auto-throttle | ADR 0008; readout only |
| Cloud / Remote Delivery | ADR 0005; future phase |
| VideoCapture | ADR 0004 |
| iOS shipping | ADR 0006 |
| Pause, manual shutter, in-app gallery/upload | Not Operator Controls |

## Behavioral acceptance

### Passage (Standard mode)

1. Operator taps **Start Capture** with mode **Standard**  
2. When Subject Face proximity ≥ Arm → AF/AE lock on that face  
3. When proximity ≥ Fire → exactly one burst of 3 ImageCapture shots  
4. All 3 become Kept Photos and enter Write Queue  
5. Further Fire while same face remains large does **not** start another burst  
6. After face leaves zone, a new Passage may fire again  
7. JPEGs appear under Local Delivery path  

### Passage (Max-Sensor mode)

Same as above, but Fire produces **1** Kept Photo at max sensor resolution.

### Operator UI

- Live preview visible while aiming  
- Controls: Start/Stop, Capture Mode only  
- Status: Armed / Fired (last), kept-photo count, Device Load Readout  

### Non-goals checks

- No video files produced  
- No upload required for “success”  
- No automatic reduction of AI/capture rate due to heat in MVP  

## Default parameters (tunable later)

| Parameter | Default |
|-----------|---------|
| Arm Threshold | 0.10 |
| Fire Threshold | 0.40 |
| Standard burst count | 3 |
| Standard burst interval | ~200 ms |
| Capture Mode | Standard |
| Smile / Pose flags | OFF, unwired |

## Implementation pointers

| Area | Doc |
|------|-----|
| Architecture (as built) | [architecture.md](./architecture.md) |
| Decisions | [decisions.md](./decisions.md) |
| Phases | [12-implementation-phases.md](./12-implementation-phases.md) |
| Post-MVP | [post-mvp.md](./post-mvp.md) |
| Historical drafts | [archive/](./archive/) |

## Status

MVP slice **P0–P8 implemented** — see [12-implementation-phases.md](./12-implementation-phases.md).
