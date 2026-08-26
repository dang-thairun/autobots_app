# AutoBots Sports Camera

Edge-AI sports camera for marathon / running-event photography on a tripod-mounted Android phone.

**Plan B** terms are listed first (current build **v0.1.6**). **v0.1 stills** terms follow — still used in legacy code and Design Flows.

---

## Plan B — active operator build (v0.1.6)

**Pipeline Session** (or **Session**):
One live capture run or one video import run from Start/Import through extract drain and `session_log.txt` write. Aggregated as `PipelineSessionRecord` in UI history.
_Avoid_: conflating with a single MP4 chunk or a v0.1 Passage

**Video Chunk**:
A segment of MP4 produced by `VideoChunkRecorder` (live) or `ImportedVideoSplitter` (import), rotated at **50 MB**. Internal cache artifact — not the gallery deliverable.
_Avoid_: calling chunks "photos", treating chunk count as final face count

**Extraction Target**:
Which detectors Worker 2 runs. Since v0.1.6 it is **three independent flags**, not one mode:
**Face** · **Pose** · **Person** (`foot_track_net`). Any combination may be on, and the gates are
**AND** — a frame must satisfy every enabled detector. Selected before Start/Import only.
_Avoid_: calling it a single mode · treating `FaceAndPose` as an enum value (it was one until 0.1.6)
· assuming the gates are OR

**Kept Frame / Extracted Image**:
A sampled video frame that passed every enabled detector, the zone and size gates, sharpness, and
per-track dedup — then was written as a full-frame JPEG to gallery. Counted in chip **K** and session `facesKept`.
_Avoid_: every decoded frame, every detected face before filter

**Sample Interval**:
Time between decoded frames in Worker 2 — **120 ms** for both 1080p and 4K (`FRAME_SAMPLE_INTERVAL_MS`).
_Avoid_: conflating with video frame rate or chunk duration

**Video Queue (VQ)**:
Bounded channel (capacity **8**) of finalized chunks waiting for Worker 2. When full, live recorder pauses.
_Avoid_: Write Queue (that is post-extract JPEG delivery)

**Session Log**:
Text file `session_log.txt` with per-chunk metrics (`PipelineSessionRecord.toLogText()`). Written to `Download/AutoBots/{subfolder}/` when session drains.
_Avoid_: assuming log lives only in DCIM

**Album Subfolder**:
Per-session directory under `DCIM/AutoBots/`, carrying the app version in its **name** rather than an
extra directory level. Import: `ext_v0_1_6_DDMMYYYY_HHMM`. Live: `v0_1_6_yyyyMMdd_HHmmss`.
The tag comes from `appVersionName` with `.` replaced by `_`, so a field run can always be traced to
the build that produced it.
_Avoid_: flat `DCIM/AutoBots` with no subfolder · quoting a path without the version tag · nesting the
version as its own folder (rejected — it doubles the depth an operator has to tap through)

**Import Session**:
Session where Worker 1 is `ImportedVideoSplitter` (remux split) instead of live `VideoChunkRecorder`. Same extract path after chunks enter `videoQueue`.
_Avoid_: "upload", treating import as a different product

**Still Photo Product** (Plan B):
Operator-deliverable output remains **still JPEGs** in gallery. MP4 recording is an **internal** capture strategy, not a user-facing video product.
_Avoid_: "video recording is out of scope" without this qualifier (that phrase was v0.1-only)

**Subject Track** (v0.1.6):
One person followed across sampled frames by `SubjectTracker`, carrying an id, centre, velocity and
predicted box. **Dedup windows are per track, not per clock second** — two runners passing in the same
second get their own keep budget instead of sharing one.
_Avoid_: assuming one second of footage is one runner (that was the pre-0.1.6 assumption, and it
silently lost the second person)

**Frame Quality** (v0.1.6):
The single 0..1 score that ranks frames inside a track's dedup window: sharpness 0.40 · subject size
0.20 · centre 0.15 · confidence 0.15 · edge margin 0.10, each normalised before weighting. Recorded
per kept photo in `photos.csv` so the weights can be re-fitted against evidence.
_Avoid_: ranking on sharpness alone (that was the rule up to 0.1.6) · treating the weights as measured

**Track Report** (`tracks.csv`):
One row per person seen in a session, **including people who produced no photo**. The only artefact
that measures what the pipeline *missed* rather than what it kept.
_Avoid_: judging a session by kept count alone

---

## v0.1 stills — legacy domain language

**Passage**:
One time a runner moves through the camera's capture zone and the system attempts to produce Kept Photos.
_Avoid_: pass, trigger event, session

**Burst**:
A short sequence of Candidate Shots taken for one Passage.
_Avoid_: multi-shot, continuous shooting (as the product outcome)

**Candidate Shot**:
One full-resolution still inside a Burst, before it is written as a Kept Photo.
_Avoid_: frame (when meaning a saved photo), capture (ambiguous)

**Kept Photo**:
A Candidate Shot written to disk as a deliverable JPEG for Local Delivery.
_Avoid_: best frame only, export (the write action), final image (vague)

**Passage Outcome**:
The set of Kept Photos for one Passage — under Keep-All Policy this is the full Lean Burst (about 3 JPEGs).
_Avoid_: album, gallery, export batch, single best frame

**Lean Burst**:
A Burst sized to match the desired Passage Outcome (about 3 Candidate Shots), not oversized for extra discard room — chosen to minimize capture, RAM, heat, and scoring cost.
_Avoid_: oversample burst, safety burst (5–6+)

**Keep-All Policy**:
Every Candidate Shot in a Lean Burst becomes a Kept Photo (typically 3). Scoring may rank or label them, but does not discard for the default Passage Outcome.
_Avoid_: top-1 keep, quality-floor keep (as the default product rule)

**Passage Gate**:
A Passage allows at most one Lean Burst. After that burst, the system stays closed until the tracked face leaves the capture zone (or drops below the arming threshold); only then may a new Passage open.
_Avoid_: time-only cooldown as the sole Passage boundary, re-trigger while face still large in frame

**Subject Face**:
The single face that owns the current Passage — always the largest face in the analysis frame (highest proximity / face area). AE, Fire timing, and Passage Gate all follow this face only.
_Avoid_: first-seen face, multi-subject Passage, runner ID (no identity in MVP)

**Face Proximity**:
How large the Subject Face is in the analysis frame, as a fraction of frame area. Used for **Early Arm** (and min size checks). Fire timing prefers **Capture Zone** over proximity alone once P10 is wired.
_Avoid_: distance in meters, confidence score, tracking ID

**Capture Zone**:
A rectangular band of cells on the observation grid (default 9×11) that defines the composition sweet spot. Fire when the Subject Face center is inside this zone (and size ≥ minimum).
_Avoid_: using the full grid as the zone, treating the grid as hardware PDAF

**Focus Strategy**:
How focus is obtained. **Fixed** (default on tripod): distance locked at setup for the Fire sweet-spot. **FaceAf** (fallback): hardware AF on Subject Face after Arm.
_Avoid_: estimating focus distance from face size without calibration

**Arm Threshold**:
Face Proximity level at which face-weighted AE begins (and AF if FaceAf). Target ~2–3% for short zones; current field default may still be ~4%.
_Avoid_: soft trigger, pre-capture (vague)

**Fire Threshold**:
Legacy proximity level for Lean Burst. Being superseded by Capture Zone Fire (Flow 17); may remain as a minimum size floor.
_Avoid_: shutter threshold, capture threshold (ambiguous with ImageCapture API)

**Frame Scoring**:
Out of MVP scope for the default path. Kept Photos are the Lean Burst shots in shutter order; no on-device score-and-rank step before disk write.
_Avoid_: FrameScorer as required for Passage Outcome, smile/pose scoring in MVP

**Deferred Score Flags**:
Smile and pose (and related score weights) may remain as feature flags defaulting OFF and unwired from the live pipeline until an optional ranking mode exists. They are not part of the MVP Passage Outcome.
_Avoid_: enabling smile/pose on the default capture path

**Still Photo Product**:
The operator-facing deliverable is still JPEGs. In v0.1, no video was recorded at all. In Plan B, video MP4 is internal only.
_Avoid_: VideoCapture as a gallery product, clip, highlight reel

**Local Delivery**:
Session success ends when Kept Photos are written to on-device storage (e.g. DCIM/AutoBots). This is
still the definition of a successful session — **Remote Delivery runs after it and never gates it**.
_Avoid_: treating a session with no network as failed · cloud sync as the success condition

**Remote Delivery** (shipped v0.1.5 — was "Future Remote Delivery"):
Kept Photos are copied to the event platform after local delivery: a Room queue, one WorkManager job,
GraphQL presign, PUT to Google Cloud Storage, then a completion call that commits the metadata.
**Local files are never deleted** — upload is a *copy*, not a *move*, and there is no toggle for it.
_Avoid_: calling it "future" · treating upload as part of Passage Outcome · assuming Cloudflare R2
(the platform uses GCS) · assuming we wrote the backend (we did not)

**Android-First Runtime**:
MVP ships and is validated on Android. Shared domain/pipeline contracts may live in a KMP common layer, but iOS is not an MVP delivery target.
_Avoid_: iOS parity in MVP, dropping KMP structure solely to go Android-only

**Standard Capture Mode**:
Default capture mode: Lean Burst with Keep-All (~3 Kept Photos per Passage) at the device’s normal full-quality still resolution (not forced max 50MP).
_Avoid_: calling this “low res”, preview resolution

**Max-Sensor Capture Mode**:
Optional A/B mode that captures at maximum sensor resolution (e.g. 50MP). Burst shrinks to one Candidate Shot per Passage because of RAM/cost — Passage Outcome is one Kept Photo.
_Avoid_: using max-sensor mode with a 3-shot Lean Burst

**Capture Mode Option**:
An operator-selectable setting that chooses Standard vs Max-Sensor Capture Mode. Factory default is Standard; the operator may switch modes before or during a deployment without changing Passage Gate / Subject Face rules.
_Avoid_: hard-coding a single capture mode, bundling mode switch into Passage logic

**Thermal Throttling**:
Not implemented — readout only. The original reason ("throttling would miss runners") was argued for
the real-time v0.1 path and **does not apply to Plan B**, where the video is already recorded and
slowing extraction costs backlog rather than people. Status is *"not done yet"*, not *"decided against"*.
_Avoid_: requiring ThermalGuard for session success · quoting the v0.1 reasoning as if it still holds

**Operator Preview**:
The on-device live view and basic status the operator uses while aiming the tripod (capture mode, armed/fired state, kept-photo count). Required in MVP — not a headless-only product for first setup.
_Avoid_: dark/headless as the only MVP UI, rich editing gallery

**Device Load Readout**:
A lightweight MVP overlay on Operator Preview showing how hard the device is working — at least thermal status and approximate memory use; CPU/GPU detail only if cheap to sample. Display-only in MVP (no automatic thermal throttling).
_Avoid_: full performance graphs as the main UI, treating readout as ThermalGuard

**Operator Controls** (Plan B):
MVP on-screen actions: **Start/Stop**, **Import**, **Gallery**, and **Video pipeline** settings (Face/Pose, 1080p/4K). v0.1 stills path also had Capture Mode (Standard/Max-Sensor) — not in current shell.
_Avoid_: describing only Start/Stop as if Import does not exist

**Write Queue**:
A bounded on-device queue that drains Kept Photos to Local Delivery storage asynchronously so burst bursts and Max-Sensor frames do not OOM the capture path.
_Avoid_: blocking the camera thread on disk I/O, unbounded in-memory photo buffers

**Face Lock**:
Once Face Proximity crosses the Arm Threshold, face-weighted AE is driven to the Subject Face (and AF if Focus Strategy is FaceAf). On Fixed Focus, Arm does not re-AF — exposure settles on the face before Fire.
_Avoid_: relying only on default full-frame AE at shutter time, arm-without-metering

---

## Related

- Doc index: [docs/DOCS.md](docs/DOCS.md)
- **ศัพท์เทคนิค/การวัดผล (ไทย)**: [docs/GLOSSARY_TH.md](docs/GLOSSARY_TH.md) — DVFS, TC-XX, realtimeRatio, YUV, backpressure · เล่มนี้เก็บเฉพาะศัพท์เชิงธุรกิจ
- Plan B pipeline: [docs/PIPELINE_FLOW.md](docs/PIPELINE_FLOW.md)
- Product requirements: [docs/PRD.md](docs/PRD.md)
