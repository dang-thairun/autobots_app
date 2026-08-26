# Field setup

Tripod and operator checklist for AutoBots on site.

| Build | Guide |
|-------|--------|
| **Plan B (current, v0.1.6)** | [§ Plan B below](#plan-b--current-v016) + [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) |
| **v0.1 stills (legacy)** | [§ v0.1 stills below](#v01-stills--legacy) — not active in current operator shell |

Phases: [IMPLEMENTATION.md](./IMPLEMENTATION.md) · Rules: [ARCHITECTURE.md](./ARCHITECTURE.md)

---

## Plan B — current (v0.1.6)

### Setup — once per device, before you leave

Done indoors on Wi-Fi, not at 4 a.m. in a car park.

1. **Upload settings** → scan the event **QR** (endpoint + token), sign in, **pick the event** from
   the list. Nothing here should ever be typed by hand.
2. Send **one test photo** all the way through and confirm it appears on the platform. A token that
   is wrong fails at the **completion** call — *after* a whole JPEG has been uploaded — so this is
   not something to discover on mobile data.
3. Check free storage: **≥ 2 GB** to start, and more for a long session.

### Before the event — on site

1. **Tripod** — stable legs; phone height aimed at runner chest–head in the lane.
2. **Aim** — subject approaches camera; keep the lane centred. Preview is live as soon as you open
   the Live page, so you can aim without recording.
3. **Capture Zone** — draw it on the real image (Edit zone). This is where a runner has to be for a
   frame to count, so put it where you actually want them composed.
4. **Detectors** — Face · Pose · Person. **All the gates are AND**: turning on a second detector
   makes the filter stricter, not more forgiving. Start with Face, add Person when you want everyone
   in frame to be tracked.
5. **Resolution** — 1080p or 4K.
6. **Light** — at dawn, set a **shutter ceiling** before the race. Left alone, auto-exposure stretches
   the shutter until every runner is a smear and the whole session fails the sharpness gate with
   nothing to show for it. Nudge **EV** if faces are backlit.
7. **Test** — a short Start → Stop, then check Session history and the Gallery **before** the race.

> Settings are **IDLE-only** — you cannot change resolution, detectors or backend mid-session.

### During the event

- **Live:** tap **Start**; watch the chips — **Ch** (chunks) · **VQ** (video queue) · **K** (kept) ·
  **realtime ratio** · **thermal**.
- **`realtime ratio` is the number that matters.** Below 1.0 means extraction is keeping up with the
  camera and you can record indefinitely. Above 1.0 means the queue is growing and the recorder will
  eventually pause itself.
- If **VQ** fills → the recorder auto-pauses until extract catches up. Normal under heavy load.
- **Thermal is display-only** — the app will *not* slow itself down. If it climbs and stays high, that
  is your decision to make, not the app's.
- **Upload runs on its own.** It needs no attention and does not block anything. The badge on the
  Upload tile counts only what is still owed.
- Screen can be off; the upload worker holds a foreground service.

### After / retrieve files

- JPEGs: `DCIM/AutoBots/{subfolder}/` — e.g. `v0_1_6_26082026_1228/`
- Session artefacts: `Download/AutoBots/{subfolder}/`
  — `session_log.txt` (readable on the phone) · `photos.csv` · **`tracks.csv`** · `perf_report.json`
- **Check `tracks.csv` before calling a session good.** Kept count alone cannot tell you how many
  people walked past and got nothing; this is the only file that can.
- Confirm the Upload queue has drained before wiping anything — and note that **nothing is ever
  deleted locally by the app**, so a full card stays full until you clear it yourself.
- Pull to Mac: `./sync_gallery.sh` (repo root) · Mirror UI: [SCRCPY.md](./SCRCPY.md)

### What you set vs what the app does

| You (setup) | App (runtime) |
|-------------|----------------|
| Tripod aim, height, lane | Record MP4 chunks (rotate at 50 MB) |
| Capture Zone, detectors, 1080p/4K | Sample 120 ms → detect → zone + size gate → sharpness |
| Shutter ceiling, EV | Track each person → score → keep best 3 per track-second |
| Event + token (once, indoors) | Write JPEGs + CSVs, then upload as a background copy |
| Decide when it is too hot | Report thermal — but never throttle itself |

---

## v0.1 stills — legacy

> **Not wired** in the current operator shell. Retained for reference if B4 re-wires burst capture.

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
