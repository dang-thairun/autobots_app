# Architecture

System design for AutoBots Sports Camera — modules, runtime pipeline, and **Design Flows**.

Domain: [CONTEXT.md](../CONTEXT.md) · Requirements: [PRD.md](./PRD.md) · Phases: [IMPLEMENTATION.md](./IMPLEMENTATION.md)

---

## 1. Modules

| Module | Role |
|--------|------|
| `shared/` | Domain contracts: `CaptureMode`, `FocusStrategy`, `CaptureZone`, `PassageThresholds`, `SubjectFaceSelector` |
| `androidApp/` | CameraX, ML Kit, burst, Write Queue, MediaStore, Compose Operator UI |

---

## 2. Runtime pipeline (tripod target)

```
[Camera Sensor]
      │ ImageAnalysis ~640×360
      ▼
[MlKitFaceAnalyzer] → [SubjectFaceSelector] → box + proximity
      │
      ├─ Arm (early, size only) ──► Face AE on subject  (Flow 10 / 18)
      │                              Fixed Focus already set at setup (Flow 15)
      │
      └─ Fire when center ∈ Capture Zone + min size + settle (Flow 17)
                              ▼
                       [LeanBurstCapturer] → JPEGs
                              ▼
                       [WriteQueue] → [LocalDeliveryWriter] → DCIM/AutoBots
```

**Passage Gate** (Flow 2): one burst until Subject Face leaves.

---

## 3. Subsystems

| Subsystem | Key types | Role |
|-----------|-----------|------|
| Camera | `PreviewCameraController` | Bind preview, analysis, capture; exposure readout |
| Detection | `MlKitFaceAnalyzer`, `SubjectFaceSelector` | Faces + largest = Subject |
| Focus | `FocusStrategy`, Fixed Focus / `FaceFocusController` | Default = fixed distance; FaceAf = fallback |
| Zone | `CaptureZone` | Composition sweet spot for Fire |
| Capture | `LeanBurstCapturer` | Sequential stills (~200 ms gap) |
| Delivery | `WriteQueue`, `LocalDeliveryWriter` | Bounded async drain to gallery |
| Load | `DeviceLoadReader` | Thermal + RAM (display only) |

Operator UI: Layer 1 = preview; Layer 2 pager = Controls | Clean preview | Grid / Capture Zone observation.

---

## 4. Design Flows

Product and engineering rules. Use **Flow N**, not `ADR 000X`.

### Flow 1 — Keep-All Lean Burst

**Rule:** Every shot in a Lean Burst is kept; no on-device scoring on the default path.

**Why:** Scoring adds latency, RAM, and heat; burst order is enough for MVP.

**In code:** `LeanBurstCapturer` → all files enqueued.

---

### Flow 2 — Passage Gate on face exit

**Rule:** One burst per Passage; gate re-opens only when Subject Face leaves or drops below arm release — not a time-only cooldown.

**Why:** Prevents duplicate bursts on the same runner still in frame.

**In code:** `OperatorViewModel` `passageGateOpen`.

---

### Flow 3 — Subject Face = largest only

**Rule:** AE, Fire, and Gate follow the single largest face in the analysis frame.

**Why:** One runner at a time on a tripod lane; no tracking IDs.

**In code:** `SubjectFaceSelector` in `shared`.

---

### Flow 4 — Still JPEG only

**Rule:** No video capture in product scope.

**Why:** Thermal, storage, and battery for multi-hour deployment.

---

### Flow 5 — Local delivery = success

**Rule:** Passage success = Kept Photos on device (`DCIM/AutoBots`). Cloud upload is out of scope.

**Why:** Field network is unreliable; operator retrieves files locally.

**In code:** `LocalDeliveryWriter`, MediaStore.

---

### Flow 6 — Android-first KMP

**Rule:** Ship on Android; `shared` holds contracts; iOS not required for MVP.

**Why:** CameraX + ML Kit path must work before cross-platform parity.

---

### Flow 7 — Standard vs Max-Sensor

**Rule:** Standard ≈ 1920×1080 ×3 Keep-All. Max-Sensor = ×1 at highest resolution. Never ×3 at max sensor.

**Why:** RAM and write latency; 50MP ×3 risks OOM.

**In code:** `CaptureMode`, `CaptureResolutions`.

---

### Flow 8 — No thermal auto-throttle (MVP)

**Rule:** Show load readout; do not automatically reduce capture or analysis rate.

**Why:** Operator decides when to pause; silent throttle would miss runners.

**In code:** `DeviceLoadReader` — display only.

---

### Flow 9 — Bounded Write Queue

**Rule:** Async bounded queue (capacity 8) between burst cache and disk.

**Why:** Keep-All ×3 and large Max-Sensor frames must not block the camera thread.

**In code:** `WriteQueue`.

---

### Flow 10 — Arm starts face-weighted AE (revised)

**Rule:** Crossing Arm drives **AE** (and AF only if `FocusStrategy.FaceAf`) onto Subject Face. Detector supplies the metering region — it does not set focus distance.

**Why:** Outdoor backgrounds skew global metering; face must be exposed correctly before Fire.

**In code:** `FaceFocusController` (FaceAf path); Fixed Focus skips AF (Flow 15).

---

### Flow 11 — Device Load Readout

**Rule:** Show thermal + approx RAM on Operator UI; no automated action.

**Why:** Long tripod runs; operator visibility without hidden backoff.

**In code:** `DeviceLoadReader`, status card.

---

### Flow 12 — Delivery abstraction

**Rule:** Camera controller enqueues files; delivery layer owns MediaStore writes.

**Why:** Decouples capture from I/O for testing and future upload path.

**In code:** `WriteQueue` + `LocalDeliveryWriter`.

---

### Flow 13 — Sustained lock through Passage

**Rule:** After Arm, do not auto-cancel AE/AF metering on a short timeout. Cancel only when Passage ends, capture stops, or strategy changes.

**Why:** Runner may stay in the 1.5–3 s zone longer than a 3 s auto-cancel.

**In code:** `FaceFocusController` — no `setAutoCancelDuration` on the live path.

---

### Flow 14 — Settle gate before Fire

**Rule:** Fire only after a short settle window after Arm (or AE stable), e.g. ~150 ms field-tuned — especially important on FaceAf; Fixed Focus mainly waits on AE.

**Why:** Proximity/zone can be ready before exposure converges.

**In code:** P9/P10 — `OperatorViewModel` / focus ready flag (not fully wired yet).

---

### Flow 15 — Fixed Focus default (tripod)

**Rule:** Default `FocusStrategy.Fixed`: focus distance set once at setup for the Fire sweet-spot. Detector does not drive AF each Passage.

**Why:** Tripod + fixed lane — AF hunt wastes the short zone and can miss sharpness.

**In code:** `FocusStrategy` in `shared`; Camera2 / CameraX wiring in P9c.

---

### Flow 16 — Proximity-calibrated focus (optional / later)

**Rule:** If estimating focus distance from face size, require a calibrated curve for that lens + tripod point — never map box size to diopter without calibration.

**Why:** Face size is not depth. Prefer Flow 15 for this product.

**In code:** Not scheduled.

---

### Flow 17 — Capture Zone Fire

**Rule:** Fire when Subject Face center is inside the operator Capture Zone (grid cells) and size ≥ minimum; Arm does **not** require zone entry.

**Why:** Composition like a still photographer — not “face large enough anywhere in frame.”

**In code:** `CaptureZone` / evaluator in `shared`; wire in P10.

---

### Flow 18 — Face-weighted exposure

**Rule:** AE meters on Subject Face after Arm; optional EV bias for the shooting point. Do not rely on full-frame metering for face brightness.

**Why:** Backlight / bright pavement must not crush or blow the face.

**In code:** Face metering today; EV slider in P9d.

---

## 5. Defaults (field-tuned)

| Parameter | Value | Notes |
|-----------|--------|--------|
| Focus strategy | Fixed (target) | FaceAf = fallback |
| Arm | ~2.5% | Early Arm |
| Fire min size | ~6% | Floor; zone is primary trigger |
| Burst interval | ~150 ms | 1080p Standard |
| Standard burst | 3 shots | |
| Analysis | ~640×360 | |
| Detector | ML Kit FAST (interim) | |

---

## 6. Related docs

- Implementation slices: [IMPLEMENTATION.md](./IMPLEMENTATION.md)
- Field checklist: [FIELD_SETUP.md](./FIELD_SETUP.md)
- APIs: [PLATFORM_APIS.md](./PLATFORM_APIS.md)
- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Later ideas: [ROADMAP.md](./ROADMAP.md)
