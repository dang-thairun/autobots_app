# AutoBots Sports Camera — Product Requirements Document (PRD)

Edge-AI still camera for marathon and running-event photography on a tripod-mounted Android phone.

---

## 1. Objective & Product Goals

The goal of the AutoBots Sports Camera is to automatically capture high-quality, focused, and well-exposed still photos of marathon runners as they approach the camera capture zone, saving them locally on the device for operators to retrieve post-event.

### Key Objectives
* **Automated approach-based capture**: Capture photos when runners enter the designated proximity zone.
* **Proactive focus/exposure settling**: Lock focus and exposure onto the runner's face before triggering the shutter.
* **Low on-device processing footprint**: Minimize capture delays, RAM spikes, battery drain, and thermal buildup by using a keep-all policy for small bursts.

---

## 2. Actors & User Roles

| Actor | Role | Description |
|-------|------|-------------|
| **Operator** | System Manager | Sets up the tripod, aligns the camera view, adjusts thresholds, starts/stops capture session, selects capture modes, and monitors device status. |
| **Runner** | Photographic Subject | Runs through the capture zone; does not interact with the application. |
| **Future Uploader** | Data Consumer | (Out of MVP Scope) Retrieves kept photos from the device post-event for remote publishing. |

---

## 3. Domain Dictionary

To align development and product design, the following terminology must be strictly used:

### Capture Lifecycle
* **Passage**: One instance of a runner moving through the camera's capture zone where the system attempts to capture kept photos.
* **Burst**: A short sequence of candidate shots taken during a single passage.
* **Candidate Shot**: One full-resolution still photo inside a burst before it is committed to storage.
* **Kept Photo**: A candidate shot committed to local storage as a deliverable JPEG.
* **Passage Outcome**: The set of kept photos for a single passage. Under the keep-all policy, this is the full lean burst (typically 3 JPEGs).
* **Lean Burst**: A burst sized to match the desired passage outcome (about 3 candidate shots) without oversized safety buffers, reducing CPU and thermal load.

### Policies & Thresholds
* **Keep-All Policy**: Every candidate shot taken in a lean burst becomes a kept photo. Frame scoring is disabled in the default MVP path.
* **Passage Gate**: A gate that limits capture to at most one lean burst per passage. The gate remains closed until the tracked face leaves the capture zone or drops below the arm threshold.
* **Subject Face**: The single face that owns the current passage — always the largest face in the analysis frame (highest proximity). All tracking, lock, and trigger logic follows this face.
* **Face Proximity**: How large the subject face is in the analysis frame, expressed as a fraction of the frame area (e.g., 0.10 for 10% frame area).
* **Arm Threshold**: The face proximity level (default 10%) at which Auto Focus (AF) and Auto Exposure (AE) lock onto the subject face.
* **Fire Threshold**: The face proximity level (default 40%) at which the lean burst triggers, after focus and exposure have settled.
* **Face Lock**: The action of driving AF and AE to the subject face bounding box once the arm threshold is crossed.

### Operations & Output
* **Still Photo Product**: The system captures and stores still JPEGs only. Video recording is out of scope.
* **Standard Capture Mode**: Lean burst of ~3 still JPEGs per passage at normal full quality.
* **Max-Sensor Capture Mode**: Single high-resolution shot (e.g. 50MP) per passage to respect RAM and I/O constraints.
* **Capture Mode Option**: Operator-selectable toggle between Standard and Max-Sensor modes.
* **Local Delivery**: The write phase committing JPEGs directly into the device's external storage gallery path (`DCIM/AutoBots`).
* **Write Queue**: A bounded asynchronous write queue that drains kept photos to storage, preventing UI freeze and OOM crashes.
* **Operator Preview**: The on-screen live camera view displaying status indicators (armed, fired, thermal levels, photo counts).
* **Device Load Readout**: Lightweight performance indicators (thermal level, RAM usage) displayed to the operator.
* **Operator Controls**: Simple interactive triggers allowing starting/stopping capture and toggling the capture mode.
* **Thermal Throttling**: (Out of MVP Scope) Automated backoff of frame analysis or capture frequency in response to thermal conditions.

---

## 4. MVP Scope Definition

### In Scope
1. **Still Photo Output**: Only JPEGs are written.
2. **Android-First**: Validated on Android using CameraX and ML Kit. Shared contracts are ready in KMP.
3. **Operator Preview & Controls**: Start/stop triggers, capture mode selector.
4. **Device Load Readout**: Thermal status + RAM indicators (display-only).
5. **Subject Face Selection**: Largest face bounds tracking.
6. **Arm Threshold & Face Lock**: Triggering AF/AE lock on the runner's face.
7. **Fire Threshold & Lean Burst**: Burst capture triggered automatically on proximity.
8. **Capture Mode Option**: Standard (3 JPEGs, keep-all) vs Max-Sensor (1 JPEG, max-res).
9. **Passage Gate**: One burst per runner.
10. **Bounded Write Queue**: Bounded buffer to write files asynchronously.
11. **Local Delivery**: JPEGs written directly to `DCIM/AutoBots`.

### Out of Scope (MVP)
* **Frame Scoring**: No ranking or quality-based discarding (all burst shots are kept).
* **Thermal Auto-Throttle**: No automatic shutdown/throttling; readout only.
* **Cloud/Remote Delivery**: No upload logic or network sync; local retrieval only.
* **Video Recording**: Video capture is fully excluded.
* **iOS Support**: iOS target compilation is deferred.

---

## 5. Behavioral Acceptance Criteria

### Standard Passage Flow
1. Operator taps **Start Capture**.
2. Runner approaches → Face Proximity crosses **Arm Threshold**.
3. System applies **Face Lock** (AF/AE targeted at the face bounding box).
4. Runner gets closer → Face Proximity crosses **Fire Threshold**.
5. System triggers **Lean Burst** (captures 3 JPEGs at ~200ms interval).
6. 3 Candidate Shots are queued in the **Write Queue**.
7. **Passage Gate** closes; no further burst is allowed for this passage.
8. Bounded Write Queue asynchronously writes JPEGs to `DCIM/AutoBots` (**Local Delivery**).
9. Runner leaves the frame → Face Proximity falls below Arm Threshold.
10. **Passage Gate** re-opens for the next runner.

### Max-Sensor Passage Flow
1. Flow matches Standard Passage Flow except Step 5 and 6:
2. System captures **1 high-resolution Candidate Shot** instead of a burst.
3. 1 Candidate Shot is queued in the Write Queue.
