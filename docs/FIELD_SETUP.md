# Field setup (tripod)

Quick checklist for sharp runner stills.  
Phases: [IMPLEMENTATION.md](./IMPLEMENTATION.md) · Rules: [architecture.md](./architecture.md)

---

## Before the race

1. **Tripod** — fully spread legs; phone height ≈ runner chest–head at the Fire point.
2. **Aim** — road coming toward camera; subject grows in frame over ~1.5–3 s.
3. **Fixed Focus** — place a person (or target) at the **Fire / sweet-spot** distance → lock focus. Do **not** lock on a distant small face.
4. **Capture Zone** — center zone on where you want the face in the final frame (usually mid-frame).
5. **EV** — start at 0; + if backlit faces; − if pavement/sky blows highlights.
6. **Test shot** — one volunteer run-through; zoom the gallery on the face before going live.

---

## During the event

- Re-check EV if light changes a lot (clouds / sun angle).
- If shots are soft → re-lock Fixed Focus at the Fire distance.
- If Fire is late → widen Capture Zone or lower Arm (do not raise Fire % blindly).

---

## What you set vs what the app does

| You (setup) | App (runtime) |
|-------------|----------------|
| Focus distance, EV, zone placement | Detect face, Arm AE, Zone Fire, burst, gate |
| Tripod aim / height | Write to `DCIM/AutoBots` |
