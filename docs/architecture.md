# Architecture overview (as built)

Android-first still camera for marathon / running-event photography.  
Domain language: [`../CONTEXT.md`](../CONTEXT.md) · Decisions: [`decisions.md`](./decisions.md)

## Modules

```
autobots_app/
├── shared/          KMP common (CaptureMode, PassageThresholds, SubjectFaceSelector, …)
└── androidApp/      CameraX + ML Kit + Operator Compose UI + delivery
```

## Runtime pipeline

```
Operator Preview (2-layer UI)
  Layer 1: CameraX Preview + face overlay
  Layer 2 pager: Controls | Clean preview | AF-grid observation
        │
        ▼
ImageAnalysis (~640×360) → ML Kit Face Detection → Subject Face (largest)
        │
        ├── proximity ≥ Arm → Face Lock (AF+AE metering on face)
        └── proximity ≥ Fire → Lean Burst (once per Passage)
                │
                ├── Standard: ImageCapture ×3 @ ~1920×1080, interval ~200ms, Keep-All
                └── Max-Sensor: ImageCapture ×1 @ highest available
                │
                ▼
        Write Queue (bounded) → MediaStore DCIM/AutoBots
                │
                ▼
        Passage Gate closed until face leaves / proximity drops
```

## Operator UI (MVP+)

- Start / Stop Capture (auto-stop on app `onStop`)
- Capture settings card (collapsible): Arm / Fire sliders, Standard | Max-Sensor
- Status: faces, proximity, Armed / Fired / Gate, kept count
- Live exposure readout: focal mm · shutter · ISO
- Device Load: thermal (OK→Critical) + RAM
- Open Gallery
- Pager observation page: visual 9×11 AF grid (not hardware PDAF)

## Defaults (field-tuned)

| Parameter | Value |
|-----------|--------|
| Arm | ~4% face area (half-body start) |
| Fire | ~10% |
| Analysis | ~640×360, KEEP_ONLY_LATEST |
| Detector | ML Kit Face (FAST) — YOLO/TFLite later |
| Burst interval | ~200 ms |

## Out of this doc

- Historical long-form design notes → [`archive/`](./archive/)
- Post-MVP ideas → [`post-mvp.md`](./post-mvp.md)
- Phase checklist → [`12-implementation-phases.md`](./12-implementation-phases.md)
