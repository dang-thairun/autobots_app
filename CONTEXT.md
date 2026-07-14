# AutoBots Sports Camera

Edge-AI still camera for marathon / running-event photography on a tripod-mounted Android phone.

## Language

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
The single face that owns the current Passage — always the largest face in the analysis frame (highest proximity / face area). AF/AE lock, burst trigger, and Passage Gate all follow this face only.
_Avoid_: first-seen face, multi-subject Passage, runner ID (no identity in MVP)

**Face Proximity**:
How large the Subject Face is in the analysis frame, as a fraction of frame area. Used only to arm focus and to fire the burst — not a runner identity signal.
_Avoid_: distance in meters, confidence score, tracking ID

**Arm Threshold**:
Face Proximity level at which AF/AE begin locking onto the Subject Face (default ~10%), before any Burst fires.
_Avoid_: soft trigger, pre-capture (vague)

**Fire Threshold**:
Face Proximity level at which a Lean Burst fires for the open Passage (default ~40%), expected after Arm has had time to settle.
_Avoid_: shutter threshold, capture threshold (ambiguous with ImageCapture API)

**Frame Scoring**:
Out of MVP scope for the default path. Kept Photos are the Lean Burst shots in shutter order; no on-device score-and-rank step before disk write.
_Avoid_: FrameScorer as required for Passage Outcome, smile/pose scoring in MVP

**Deferred Score Flags**:
Smile and pose (and related score weights) may remain as feature flags defaulting OFF and unwired from the live pipeline until an optional ranking mode exists. They are not part of the MVP Passage Outcome.
_Avoid_: enabling smile/pose on the default capture path

**Still Photo Product**:
The system captures and stores still JPEGs only. Video recording is out of product scope.
_Avoid_: VideoCapture, clip, highlight reel (as capture modes)

**Local Delivery**:
MVP success ends when Kept Photos are written to on-device storage (e.g. DCIM/AutoBots). Operators retrieve files later by cable or file copy — no upload in the MVP path.
_Avoid_: cloud sync, event server, in-app share as MVP delivery

**Future Remote Delivery**:
A later phase may upload Kept Photos to an event/cloud backend. That is a separate delivery path, not required for MVP Passage success.
_Avoid_: treating upload as part of current Passage Outcome

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
Out of MVP scope. The first shippable path prioritizes correct Passage capture over adaptive thermal backoff; thermal management may return after MVP.
_Avoid_: requiring ThermalGuard for MVP Passage success

**Operator Preview**:
The on-device live view and basic status the operator uses while aiming the tripod (capture mode, armed/fired state, kept-photo count). Required in MVP — not a headless-only product for first setup.
_Avoid_: dark/headless as the only MVP UI, rich editing gallery

**Device Load Readout**:
A lightweight MVP overlay on Operator Preview showing how hard the device is working — at least thermal status and approximate memory use; CPU/GPU detail only if cheap to sample. Display-only in MVP (no automatic thermal throttling).
_Avoid_: full performance graphs as the main UI, treating readout as ThermalGuard

**Operator Controls**:
MVP on-screen actions are only Start/Stop Capture and Capture Mode (Standard vs Max-Sensor). Everything else on the preview is status readout (armed/fired, kept-photo count, Device Load Readout) — not extra action buttons.
_Avoid_: Pause, manual shutter, gallery, upload, or settings sprawl as MVP primary controls

**Write Queue**:
A bounded on-device queue that drains Kept Photos to Local Delivery storage asynchronously so burst bursts and Max-Sensor frames do not OOM the capture path.
_Avoid_: blocking the camera thread on disk I/O, unbounded in-memory photo buffers

**Face Lock**:
Once Face Proximity crosses the Arm Threshold, AF and AE are driven to the Subject Face so focus and exposure can settle before Fire. Required in MVP.
_Avoid_: relying only on default camera AF/AE at shutter time, arm-without-lock
