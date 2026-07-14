# Post-MVP (deferred)

Not in the P0–P8 MVP slice. See [`decisions.md`](./decisions.md) for why some were deferred.

| Topic | Notes | Old detail (archive) |
|-------|--------|----------------------|
| ThermalGuard auto-throttle | After Device Load Readout proves useful in field | `archive/07-thermal-management.md` |
| Frame scoring / smile / pose | Optional ranking mode; Keep-All remains default | `archive/10-burst-scoring.md` |
| YOLO / TFLite face detector | Swap when faster + better half-body recall than ML Kit | `archive/06-face-detection-ai.md` |
| Cloud / remote upload | After Local Delivery path is stable | ADR 0005 |
| iOS / iPad operator | Wi‑Fi preview + remote controls; KMP shares protocol only | ADR 0006 |
| True AF lock (disable auto-cancel) | Face Lock currently auto-cancels ~3s | — |
| Face tracking IDs | Stable person # across frames | — |

When implementing one of these, promote a short plan into `12-implementation-phases.md` (or a new phase list) rather than resurrecting entire archived scaffolds blindly.
