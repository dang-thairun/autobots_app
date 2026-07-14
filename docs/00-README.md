# AutoBots Sports Camera — Docs

Edge-AI still camera for marathon / running-event photography (Android-first, KMP-shaped).

## Start here (active)

| File | Role |
|------|------|
| [`../CONTEXT.md`](../CONTEXT.md) | Domain language (Passage, Subject Face, Keep-All, …) |
| [`architecture.md`](./architecture.md) | System overview **as built** |
| [`11-mvp-spec.md`](./11-mvp-spec.md) | MVP behavior / acceptance |
| [`12-implementation-phases.md`](./12-implementation-phases.md) | P0–P8 checklist (MVP slice complete) |
| [`decisions.md`](./decisions.md) | Architecture decisions (merged ADRs) |
| [`post-mvp.md`](./post-mvp.md) | Deferred work (iPad, YOLO, throttle, scoring, …) |

## Archive

Long pre-implementation guides and the original one-file-per-ADR copies live under [`archive/`](./archive/).  
**If archive conflicts with CONTEXT / decisions / mvp-spec → active docs win.**

## Pipeline (short)

```
Face detect → Arm (Face Lock) → Fire (Lean Burst) → Write Queue → DCIM/AutoBots
                 └─ Passage Gate: one burst until face leaves
```
