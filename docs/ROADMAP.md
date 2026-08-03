# Roadmap (unscheduled)

Ideas past the active **Plan B** slices in [IMPLEMENTATION.md](./IMPLEMENTATION.md).  
Current operator build: **v0.1.2** — [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).  
Naming: [CONVENTIONS.md](./CONVENTIONS.md).

| Topic | Notes |
|-------|--------|
| **4K face extract tuning** | Sharpness normalize, decode path, ML Kit ACCURATE — see B2 in [IMPLEMENTATION.md](./IMPLEMENTATION.md) |
| **Body / person pre-filter** | ML Kit Object Detection or pose — skip frames before face extract |
| ThermalGuard auto-throttle | Adaptive backoff from Device Load Readout |
| On-device frame scoring | Smile / pose / sharpness ranking (optional) |
| YOLO / TFLite detector | Replace ML Kit if field recall needs it |
| Cloud / remote upload | Background sync to event gallery |
| iOS / iPad operator | Wi‑Fi preview + remote controls |
| Face AF fallback UI | When tripod moves / focus not calibrated |
| Denser Capture Zone grid | 11×15+ only if 9×11 is too coarse |
| Face tracking IDs | Stable person # across frames |

When starting one of these, add a Phase slice to [IMPLEMENTATION.md](./IMPLEMENTATION.md) or [DOCS.md](./DOCS.md) — do not resurrect archived scaffolds blindly.
