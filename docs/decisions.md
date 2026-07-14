# Architecture decisions (MVP)

Merged from former `docs/adr/0001`–`0011`. Full originals: [`archive/adr/`](./archive/adr/).

| ID | Decision |
|----|----------|
| 0001 | **Keep-All** Lean Burst — no on-device frame scoring on default path |
| 0002 | **Passage Gate** re-opens only after Subject Face leaves (not time-only cooldown) |
| 0003 | **Subject Face** = largest face in analysis frame only |
| 0004 | **Still JPEG only** — no VideoCapture |
| 0005 | **Local Delivery** to disk; cloud upload later |
| 0006 | **Android-first** on KMP-shaped repo; iOS not required for MVP |
| 0007 | **Capture Mode** Standard (×3) vs Max-Sensor (×1); default Standard |
| 0008 | **Defer thermal auto-throttle** past MVP |
| 0009 | **Bounded Write Queue** before Local Delivery |
| 0010 | **Face Lock** AF+AE on Subject Face at Arm |
| 0011 | **Device Load Readout** (thermal + RAM) display-only |

---

### 0001 — Keep-All, no scoring
For each Passage we take a Lean Burst (~3 shots) and keep every Candidate Shot as a Kept Photo. Frame scoring is out of the default MVP path.

### 0002 — Passage Gate = face exit
After Fire, the Passage stays closed until the Subject Face leaves the zone (or drops below arm release). Time-only cooldown alone is not the boundary.

### 0003 — Subject Face = largest
AF/AE, Fire, and Passage Gate follow the largest face only. No multi-runner Passages / no face tracking IDs in MVP.

### 0004 — Still photos only
No video recording in product scope (thermal / I/O / battery for multi-hour tripod runs).

### 0005 — Local delivery MVP
Success = Kept Photos on device (e.g. `DCIM/AutoBots`). Cloud upload is a later phase.

### 0006 — Android-first
Ship and prove on Android (CameraX + on-device face detect). KMP `shared` holds contracts; iOS optional later.

### 0007 — Capture Mode A/B
Standard ≈ 1920×1080 ×3 Keep-All. Max-Sensor = 1 shot at highest available resolution. Never Max-Sensor ×3.

### 0008 — Defer thermal throttle
No ThermalGuard / auto backoff in MVP. Readout only (0011).

### 0009 — Bounded Write Queue
Async bounded queue drains JPEGs to Local Delivery so the camera path does not block on disk.

### 0010 — Face Lock at Arm
Crossing Arm starts AF+AE on Subject Face before Fire.

### 0011 — Device Load Readout
Show thermal + approx RAM on Operator UI. Display-only — does not throttle capture.
