# Field setup

Tripod and operator checklist for AutoBots on site.

| Build | Guide |
|-------|--------|
| **v0.1.2 Plan B (current)** | [§ Plan B below](#plan-b--v012-current) + [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) |
| **v0.1 stills (legacy)** | [§ v0.1 stills below](#v01-stills--legacy) — not active in current operator shell |

Phases: [IMPLEMENTATION.md](./IMPLEMENTATION.md) · Rules: [ARCHITECTURE.md](./ARCHITECTURE.md)

---

## Plan B — v0.1.2 (current)

### Before the event

1. **Tripod** — stable legs; phone height aimed at runner chest–head in the lane.
2. **Aim** — subject approaches camera; keep lane centered in frame.
3. **Storage** — ensure ≥ **2 GB** free (Disk chip); more for long sessions.
4. **Mode** — expand **Video pipeline**: choose **Face** (default) or **Pose** (experimental); **1080p** vs **4K** for live capture.
5. **Test** — short Start → Stop; check Session history and Gallery for JPEGs before the race.

### During the event

- **Live:** tap **Start**; monitor **Ch**, **VQ**, **K** chips; Stop when segment ends.
- If **VQ** fills → recorder auto-pauses until extract catches up (normal under heavy load).
- **Import:** use for pre-recorded footage; same extract path, folder `ext_DDMMYYYY_HHMM`.
- Re-check **Disk** chip if storage is tight.

### After / retrieve files

- JPEGs: `DCIM/AutoBots/{subfolder}/` — e.g. `ext_v0_1_3_11082026_1228/`
- Session log: `Download/AutoBots/{subfolder}/session_log.txt`
- Pull to Mac: `./sync_gallery.sh` (repo root)
- Mirror UI on laptop: [SCRCPY.md](./SCRCPY.md)

### What you set vs what the app does (Plan B)

| You (setup) | App (runtime) |
|-------------|----------------|
| Tripod aim, height, lane | Record or import video chunks |
| Face/Pose, 1080p/4K (live) | Sample 120 ms → ML Kit → sharpness → dedup |
| — | Write JPEGs + session log |

---

## v0.1 stills — legacy

> **Not wired** in v0.1.2 operator shell. Retained for reference if B4 re-wires burst capture.

### Before the race

1. **Tripod** — fully spread legs; phone height ≈ runner chest–head at the Fire point.
2. **Aim** — road coming toward camera; subject grows in frame over ~1.5–3 s.
3. **Fixed Focus** — place a person (or target) at the **Fire / sweet-spot** distance → lock focus. Do **not** lock on a distant small face.
4. **Capture Zone** — center zone on where you want the face in the final frame (usually mid-frame).
5. **EV** — start at 0; + if backlit faces; − if pavement/sky blows highlights.
6. **Test shot** — one volunteer run-through; zoom the gallery on the face before going live.

### During the event

- Re-check EV if light changes a lot (clouds / sun angle).
- If shots are soft → re-lock Fixed Focus at the Fire distance.
- If Fire is late → widen Capture Zone or lower Arm (do not raise Fire % blindly).

### What you set vs what the app does (v0.1)

| You (setup) | App (runtime) |
|-------------|----------------|
| Focus distance, EV, zone placement | Detect face, Arm AE, Zone Fire, burst, gate |
| Tripod aim / height | Write to `DCIM/AutoBots` |

---

## Related

- Operator workflow (Plan B): [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Build + pull files from device: [BUILD.md](./BUILD.md) (`sync_gallery.sh`)
- Mirror UI on Mac: [SCRCPY.md](./SCRCPY.md)
- Doc index: [DOCS.md](./DOCS.md)
