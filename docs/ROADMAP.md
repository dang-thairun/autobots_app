# Roadmap (unscheduled)

Ideas past the active **Plan B** slices in [IMPLEMENTATION.md](./IMPLEMENTATION.md).  
Current operator build: **v0.1.2** — [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).  
Naming: [CONVENTIONS.md](./CONVENTIONS.md).

| Topic | Notes |
|-------|--------|
| **4K face extract tuning** | Sharpness normalize, decode path, ML Kit ACCURATE — see B2 in [IMPLEMENTATION.md](./IMPLEMENTATION.md) |
| **Body / person pre-filter** | ML Kit Object Detection — skip frames before face extract (Pose target shipped as experimental in B1f; not a full body filter) |
| ThermalGuard auto-throttle | Adaptive backoff from Device Load Readout |
| On-device frame scoring | Smile / pose rank / sharpness ranking (optional) |
| YOLO / TFLite detector | Replace ML Kit if field recall needs it |
| Cloud / remote upload | **วางแผนแล้ว → [PHASES.md](./PHASES.md)** (B3) — Room queue + WorkManager + presigned R2 |
| iOS / iPad operator | Wi‑Fi preview + remote controls |
| Face AF fallback UI | When tripod moves / focus not calibrated (v0.1 P9) |
| Denser Capture Zone grid | 11×15+ only if 9×11 is too coarse (v0.1 P10) |
| Face tracking IDs | Stable person # across frames |
| In-app screen mirror | Use scrcpy today; `/ws/preview` reserved |

**Already shipped (not roadmap):** Plan B live + import pipeline, session history, `session_log.txt`, Face/Pose extraction toggle (Pose = experimental).

When starting one of these, add a Phase slice to [IMPLEMENTATION.md](./IMPLEMENTATION.md) or [DOCS.md](./DOCS.md) — do not resurrect archived scaffolds blindly.

---

## Related

- Doc index: [DOCS.md](./DOCS.md)
- Active slices: [IMPLEMENTATION.md](./IMPLEMENTATION.md)
- Pipeline reference: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
