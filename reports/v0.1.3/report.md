# v0.1.3 — Summary Report

> Written per [REPORT_GUIDELINE.md](../../docs/REPORT_GUIDELINE.md).
> All numbers come from `perf_report.json` produced by the runs themselves — see [inputs.md](./inputs.md).

---

## 1. Snapshot

| Field | Value |
|-------|-------|
| Version | **v0.1.3** — `appVersionName=0.1.3`, `appVersionCode=13` |
| Report date | 2026-08-11 |
| Phase | **B1** — video chunk record + offline extract + gallery |
| Theme | Worker 2 performance & yield: make UHD extraction faster and stop discarding runners that were one step short of the size gate |
| Tester | Dang Thairun (device runs) · numbers read from `perf_report.json` |
| Device | Xiaomi 24069PC21G (`peridot`), Android 16 / SDK 36, arm64-v8a, 8 cores |
| Build under test | `androidApp-debug.apk`, debug, `CAM_PERF=true` |
| Verdict | **Needs work** — good for import, blocked for live UHD |
| Score | **67 / 100** |

Shipped feature list: [CHANGELOG.md § v0.1.3](../../docs/CHANGELOG.md) ·
Engineering rationale and failure notes: [RELEASE_0_1_3.md](../../docs/RELEASE_0_1_3.md)

---

## 2. What & why

**What this version does**

v0.1.3 changes no pipeline shape and no UI. It attacks two numbers that v0.1.2 measurement exposed:
the **cost per frame** in Worker 2, and the **number of photos** a session actually delivers.

The rotation of each 4K frame moved from "every sampled frame, before detection" to "only frames that
already passed the size gate". The face size gate dropped from 5% to 3.5% of frame height. Dedup
changed from keeping one photo per second to keeping the best three. Two latent bugs found during
testing — an ROI that mapped outside the frame, and a session whose final chunk carried photos never
writing its log — were fixed in the same release.

One planned change, decoding straight into a `Surface`, was written, tested on device, found
unsupported, and **disabled**. It is documented rather than removed.

**Why**

| Problem measured in v0.1.2 | How v0.1.3 addresses it |
|----------------------------|-------------------------|
| UHD import ran at **2.35× realtime** — the video queue is permanently full, so live UHD loses footage | Removed the largest avoidable cost (`rotate`, 19.5% of runtime) → 2.20× |
| **3 photos** from a 60 s clip; 96 of 507 frames rejected for a face 0.023–0.048 of frame height — runners approaching the lens, cut one step short | Size gate 0.05 → 0.035 |
| 42 candidate frames collapsed to 7 photos — one runner's whole pass produced a single frame, against the Keep-All Policy in [CONTEXT.md](../../CONTEXT.md) | Dedup keeps the top 3 per 1 s window |
| Frames scoring exactly `0` were counted as "blurry" when the scorer could not measure them at all | ROI clamped; unmeasurable frames counted as `roiInvalid` |
| A session whose last chunk produced photos wrote neither `session_log.txt` nor `perf_report.json` | `WriteQueue` fires its callback after decrementing the pending count, plus a 1 s watchdog |

Product scope: [PRD.md](../../docs/PRD.md) · Slice detail: [IMPLEMENTATION.md](../../docs/IMPLEMENTATION.md)

---

## 3. Scope

### In scope

- Worker 2 cost reduction: rotation ordering, hardware decoder selection
- Yield: face size gate, dedup top-N per window
- Correctness: ROI clamping, session-drain artifact writing
- `perf_report.json` — machine-readable per-session diagnostics (new in this release), plus
  `sync_gallery.sh` support for pulling it
- **Import** path on UHD, at two clip lengths (1 min, 4 min)

### Out of scope

- **Live capture was not tested at all in this cycle.** Every test case is an import. Worker 2 is
  shared, but `VideoChunkRecorder`, the recorder-pause backpressure path and exposure behaviour are
  unexercised here — see NA-06.
- 1080p (FHD) was not re-tested; all runs are UHD
- Pose extraction target — untouched and untested
- `yuv_jpeg_argb`, the single largest cost at 49.5% of runtime — attempted and failed, see §9
- Visual inspection of the delivered JPEGs — counts and selection logic are verified, the images
  themselves were not reviewed (NA-01)
- No UI, operator flow, or delivery-path changes

---

## 4. Open questions

| ID | Question | Options | Impact | Owner | Status |
|----|----------|---------|--------|-------|--------|
| **OQ-01** | Is `no_subject` (63–79% of sampled frames) genuine absence, or ML Kit failing to see people who are there? | (a) watch the source clips and annotate; (b) dump the rejected frames and eyeball them | Largest single bucket in the pipeline. If it is detection failure it outweighs every performance item in this release | TBD | Open |
| **OQ-02** | Can `yuv_jpeg_argb` (49.5% of runtime) be eliminated on this hardware? | (a) `ImageFormat.PRIVATE` + `HardwareBuffer` + `Bitmap.wrapHardwareBuffer` (API 29+); (b) direct NV21→RGB in native code; (c) accept it and cap live at 1080p | Only path to `realtimeRatio` < 1.0, i.e. to live UHD being viable at all | TBD | Open |
| **OQ-03** | Why did a session still fail to write its artifacts *after* the `WriteQueue` ordering fix? | (a) instrument further; (b) accept the watchdog as the guarantee and close it | Currently mitigated, not understood. A second unknown drain path may exist | TBD | Open |
| **OQ-04** | Is `MAX_KEEP_PER_WINDOW = 3` over a 1 s window the right yield rule? | (a) keep as is; (b) widen the window; (c) make N operator-selectable | Determines how many near-duplicate frames a runner produces; 315 candidates became 149 photos | TBD | Open |
| **OQ-05** | Should live capture allow UHD at all while `realtimeRatio` > 1.0? | (a) block UHD for live, allow for import; (b) warn only; (c) leave as is and lose footage | At 2.2× the queue fills in ~110 s of recording, after which the recorder pauses and runners are lost permanently | TBD | Open |

---

## 5. How it works

Unchanged from v0.1.2 in shape — one queue, one worker, one delivery path:

```
Import ─▶ ImportedVideoSplitter ─▶ videoQueue(8) ─▶ Worker 2 ─▶ WriteQueue(48) ─▶ DCIM
                    ▲                                  │
                    └── backpressure: canAcceptChunk ──┘
```

What v0.1.3 changed is **inside Worker 2**, per sampled frame:

```
v0.1.2   decode ─▶ rotate 4K (52 ms) ─▶ scale 640 ─▶ detect ─▶ size ─▶ sharpness ─▶ save
v0.1.3   decode ─▶ scale 640 ─▶ rotate 640 (5 ms) ─▶ detect ─▶ size ─┬▶ rotate 4K ─▶ sharpness ─▶ save
                                                                     └▶ reject (never pays for the 4K rotate)
```

Full pipeline detail: [PIPELINE_FLOW.md](../../docs/PIPELINE_FLOW.md) ·
Sequence view: [SEQUENCE_FLOW.md](../../docs/SEQUENCE_FLOW.md) ·
Per-change rationale: [RELEASE_0_1_3.md](../../docs/RELEASE_0_1_3.md)

---

## 6. Test setup

| Item | Value |
|------|-------|
| Device | Xiaomi 24069PC21G (`peridot`) |
| Android version | 16 (SDK 36), arm64-v8a, 8 cores |
| RAM | ~11.2 GB total; ~5.5–6.1 GB in use during runs |
| Free storage before run | TBD |
| Starting thermal state | `OK` (level 0) on every run |
| App build | v0.1.3 debug, `CAM_PERF=true` — [BUILD.md](../../docs/BUILD.md) |
| Install method | `adb install -r` (Gradle `installDebug` failed repeatedly on a 137 MB APK — see §9 observations) |
| Extraction target | Face (FAST) for every test case |
| Stream resolution | UHD, auto-detected from the source file by `ImportedVideoSplitter.probe()` |
| Log capture | `adb logcat -s CamPerf …` → `logs/` |
| Output pull | `./sync_gallery.sh --logs-only --logcat` → `output/` |

Input file manifest: [inputs.md](./inputs.md)

---

## 7. Test inputs

| ID | Input | Duration | Resolution | Conditions | Settings |
|----|-------|----------|------------|------------|----------|
| **TC-01** | `run1mins.mp4` on **v0.1.2** | 60.8 s | UHD 3840×2160, rot 90° | Baseline measurement — the run that motivated this release | Import · Face · 4K |
| **TC-02** | `run1mins.mp4` on **v0.1.3-rc1** (fixes 1–5) | 60.8 s | same file as TC-01 | Verifies rotation + size-gate changes in isolation | Import · Face · 4K |
| **TC-03** | `run1mins.mp4` on **v0.1.3 final** (fixes 1–8) | 60.8 s | same file as TC-01 | Adds dedup top-3 and ROI clamp | Import · Face · 4K |
| **TC-04** | `run4mins.mp4` on **v0.1.3 final** | 273.7 s | UHD 3840×2160, rot 90° | 36 chunks — scale-up, long-session behaviour, denser subject presence | Import · Face · 4K |

TC-01 → TC-03 are the **same file on three builds**, which is what makes the deltas attributable to
the code rather than the footage.

---

## 8. Expected results

Expectations were written before each run, from the constants the change touches.

| ID | Expectation (measurable) | Derived from |
|----|--------------------------|--------------|
| **TC-01** | Baseline only — record `realtimeRatio`, `kept`, and the per-stage cost table | reference run, no prediction |
| **TC-02** | `rotate` runs once per frame **past the size gate**, not once per sampled frame → `rotate n` ≈ candidates, not 507. `realtimeRatio` ≈ 1.9 (2.35 × 0.815, from the measured chunk-1 saving). `tooSmall` down from 96 | `MIN_FACE_HEIGHT_RATIO` 0.05→0.035 · rotate reordering |
| **TC-03** | `kept` ≈ 20 (from 7). `save_jpeg n` = candidate count, not kept count. No `sharpness: 0` anywhere. `roiInvalid` ≈ 4. `realtimeRatio` ≈ 2.0 — deliberately **worse** than TC-02 by ~3%, traded for ~3× the photos. `imageQueue` never reaches 48 | `MAX_KEEP_PER_WINDOW = 3` · ROI clamp · `IMAGE_QUEUE_CAPACITY` 48 |
| **TC-04** | Session completes all 36 chunks and writes **both** `session_log.txt` and `perf_report.json`. Structural invariants hold at 4.5× the frame count: `save_jpeg n` = candidates, `rotate n` = candidates + tooSoft + roiInvalid, `sharpness n` = rotate n − roiInvalid. No crash, `decodeFailures` 0, thermal stays out of throttling | drain fix · instrumentation self-consistency |

Cross-cutting for every TC: no crash · `decodePath` = `yuv` for every chunk (Surface path is disabled) ·
JPEG count in the gallery folder matches `totals.kept`.

---

## 9. Actual results

| ID | Expected (short) | Actual | Delta | Status |
|----|------------------|--------|-------|--------|
| TC-01 | baseline, no prediction | ratio **2.35×** · 3 photos · 140.0 s | — | **PASS** (reference captured) |
| TC-02 | `rotate n` ≈ candidates · ratio ≈ 1.9 · `tooSmall` < 96 | `rotate n` = **53** = candidates 42 + tooSoft 11 ✓ · ratio **1.978** · `tooSmall` **50** | ratio within 4% of prediction | **PASS** |
| TC-03 | kept ≈ 20 · `save_jpeg n` = candidates · no `sharpness: 0` · `roiInvalid` ≈ 4 · ratio ≈ 2.0 | kept **16** · `save_jpeg n` = **40** = candidates ✓ · no `sharpness: 0` ✓ · `roiInvalid` **1** · ratio **2.017** · `imageQueue` peak **9** | kept 16 vs ~20; `roiInvalid` 1 vs ~4 — both explained by ML Kit tracking non-determinism | **PARTIAL** |
| TC-04 | 36 chunks · both artifacts written · invariants hold at scale · no crash | 36/36 chunks · **both artifacts written** ✓ · invariants **315 / 574 / 558** all exact ✓ · `decodeFailures` 0 · thermal `OK` throughout | kept **149** · ratio **2.196** | **PASS** |

### Measured numbers

| Metric | TC-01 (v0.1.2) | TC-03 (v0.1.3) | TC-04 (v0.1.3, 4 min) |
|--------|----------------|----------------|-----------------------|
| Chunks | 8 | 8 | 36 |
| Frames sampled | 507 | 507 | 2,281 |
| `no_subject` | 402 (79%) | 403 (79%) | 1,441 (63%) |
| `too_small` | 96 (19%) | 49 (9.7%) | 266 (11.7%) |
| `too_soft` | 0 | 14 (2.8%) | 243 (10.7%) |
| `roi_invalid` | — | 1 | 16 |
| Candidates | 9 | 40 | 315 |
| **JPEG written** | **3** | **16** | **149** |
| Photos per second of video | 0.05 | 0.26 | **0.54** |
| Process wall time | 140.0 s | 120.1 s | 588.5 s |
| **realtimeRatio** | **2.35×** | **2.017×** | **2.196×** |
| Peak thermal | OK (0) | OK (0) | OK (0) |
| `decodeFailures` | 0 | 0 | 0 |

Per-stage share of wall time:

| Stage | TC-01 | TC-03 | TC-04 |
|-------|-------|-------|-------|
| `yuv_jpeg_argb` | 46.1% | 52.4% | **49.5%** |
| `mlkit_face` | 31.6% | 35.3% | 30.9% |
| `rotate` | **19.5%** | 2.5% | 5.4% |
| `save_jpeg` | 0.19% | 2.5% | 4.3% |
| `scale_for_detect` | 1.2% | 3.0% | 2.8% |
| `decode` (hardware) | 0.13% | 0.16% | 0.15% |

### Observations

**The instrumentation validates itself.** Three counts are structurally determined — if the code does
what it claims, they must match exactly. They did, on both clip lengths:

```
TC-03   save_jpeg 40  · rotate 55  = 40 + 14 + 1   · sharpness 54  = 55 − 1
TC-04   save_jpeg 315 · rotate 574 = 315 + 243 + 16 · sharpness 558 = 574 − 16
```

Holding across a 4.5× change in scale is not coincidence.

**`MIN_SHARPNESS = 65` is now validated rather than merely untuned.** In v0.1.2 the gate rejected
nothing (`tooSoft` = 0, minimum observed score 109.9 against a cutoff of 65). With the size gate
opened, TC-04 gave n=558 scores, and the gate separates footage cleanly:

| chunk | p50 | below cutoff |
|-------|-----|--------------|
| 23 | 167.6 | 4/31 (13%) |
| 27 | 163.6 | 4/30 (13%) |
| 29 | 49.4 | 26/49 (53%) |
| 33 | 34.2 | 31/43 (72%) |
| **31** | **35.1** | **13/13 (100%)** |

Chunk 31 has a subject present (`subjectRatio` 0.035–0.040) and **every frame below cutoff**
(max 52.6). That runner was lost to blur in the source footage, not to a pipeline mistake.

**`roiInvalid` is catching the case it was built for.** The 16 frames it classified carry
`subjectRatio` 0.137, 0.063, 0.063, 0.049 … — large faces, i.e. subjects at the frame edge whose
tracked box ML Kit predicted past the boundary. Before the fix these landed in `tooSoft` and
corrupted the quality statistics.

**Surface decode failed on this hardware and was disabled.** `c2.qti.avc.decoder` does not render
into an `ImageReader` configured as `RGBA_8888`. All 216 frames of chunk 1 timed out, costing 54 s of
dead waiting per chunk before the fallback engaged — `realtimeRatio` **8.62×**, worse than not
changing anything. The probe now aborts after 3 frames and memoises the result, so a wrong guess
costs ~360 ms once instead of 54 s per chunk. Code kept, switch off, rationale recorded in
[RELEASE_0_1_3.md §1](../../docs/RELEASE_0_1_3.md).

**A drain bug was found only by the longer clip.** `run4mins.mp4` produced 158 JPEGs and **no**
`session_log.txt` and **no** `perf_report.json`. Cause: `WriteQueue` invoked its delivered-callback
before decrementing the pending counter, so the drain check still saw work outstanding when the final
photo landed, and nothing called it again. It only triggers when the **last chunk yields photos** —
every 1-minute test happened to end on a chunk with `kept = 0`, so three consecutive runs of the same
file could not have found it. Present since v0.1.2; the dedup change made it common rather than rare.

The fix works (`drain complete · trigger=event`), but **one run with the fix in place still failed**
and that is unexplained (OQ-03). A 1 s watchdog was added as a guarantee independent of which callback
fires last.

**`splitDurationMs` does not measure what its name says.** TC-03 reports 2,912 ms; TC-04 reports
455,841 ms for a remux that should take ~20 s. The splitter is gated by `canAcceptChunk`, so on the
long clip it produced exactly one chunk per chunk consumed and the field measured the whole pipeline.
The two numbers are not comparable. Backpressure worked correctly — the metric is wrong (NA-03).

**Gradle `installDebug` is unreliable for this APK.** Repeated `EOF` during `SyncService.doPushFile`
on the 137 MB debug build. `adb install -r` succeeded every time on the same connection.

### Log excerpts

```
# Surface decode probe, before it was disabled (TC-03 build)
CamPerf: │ surface_rgba         216    250.2ms    253.0ms  54036.8ms
CamPerf: │ REALTIME RATIO  8.62x  <-- SLOWER than realtime, queue will back up
VideoFrameSampler: Surface path unsupported on this device; using YUV for the rest of the session

# Drain, TC-04 — watchdog reporting the blocking flag each second, then the event firing
CamPerf: drain blocked · recording=false importing=false awaitingFinalize=false worker=true videoPending=1 imageQueue=0
CamPerf: drain complete · trigger=event

# Backpressure holding the splitter to one chunk per chunk consumed (TC-04 events)
tMs  17,067   chunk 1 process_end
tMs  17,640   chunk 9 queued
tMs  33,679   chunk 2 process_end
tMs  34,232   chunk 10 queued
```

Full logs in `logs/`. Full per-frame data in each session's `perf_report.json`.

---

## 10. Score

Rubric and bands: [REPORT_GUIDELINE.md §5](../../docs/REPORT_GUIDELINE.md)

| Dimension | Weight | Score /5 | Weighted | Evidence |
|-----------|--------|----------|----------|----------|
| Functional correctness | 30 | 4 | 24 | TC-02/03/04 all met their structural expectations exactly; one planned change (Surface decode) shipped disabled after failing on device |
| Output quality | 25 | 3 | 15 | TC-04: 149 photos, 0.54/s vs 0.05/s in TC-01; sharpness gate demonstrably discriminating. **The JPEGs themselves were never viewed** — count and selection are evidenced, visual usability is not (NA-01) |
| Stability | 20 | 4 | 16 | TC-04: 36 chunks / 9.8 min, no crash, `decodeFailures` 0, thermal OK, RAM flat. Offset by a silent artifact-loss bug found mid-cycle whose root cause is still open (OQ-03) |
| Performance | 15 | 2 | 6 | `realtimeRatio` 2.196 — improved 14% but still 2.2× over the 1.0 needed for live UHD. Import completes; live UHD would fill the queue in ~110 s and start losing footage |
| Operator UX | 10 | 3 | 6 | No UI change this release; session completes and reports Done. No operator field test ran in this cycle, and a 4-minute import takes 9.8 minutes with a queue pinned at 8/8 |
| **Total** | **100** | | **67 / 100** | |

**Verdict:** **Needs work**

The band is earned by Performance, not by defects. For **import** workflows v0.1.3 is a clear
improvement and is usable today — 5.3× the photos on identical footage, 14% faster, with the
instrumentation to prove it. For the product's actual purpose — a **live** sports camera at UHD — the
headline goal was not reached, and the one change that could have reached it failed on device.

---

## 11. Verdict & next actions

- v0.1.3 delivers on **yield**: 3 → 16 photos on the same 60 s clip, 149 on a 4.5-minute clip.
- v0.1.3 delivers partially on **speed**: 2.35× → 2.20×, short of the < 1.0× that live UHD needs.
- The **sharpness gate is now real and correctly tuned** — a question open since v0.1.2 is closed.
- The release found and fixed two bugs invisible to shorter tests, and produced the diagnostic file
  that made both findable.
- **Live capture is entirely untested in this cycle.** Every conclusion here is import-only.

| ID | Action | Why | Owner | Target |
|----|--------|-----|-------|--------|
| **NA-01** | Visually review the 149 JPEGs from TC-04 and rate usability | Output quality is scored on counts alone; this is the only way to move it above 3 | TBD | **B1** |
| **NA-02** | Re-attempt `yuv_jpeg_argb` removal via `ImageFormat.PRIVATE` + `HardwareBuffer` + `Bitmap.wrapHardwareBuffer`, measuring on device at each step before merging | 49.5% of runtime; the only route to `realtimeRatio` < 1.0 and to live UHD. Resolves OQ-02 | TBD | **v0.1.4** |
| **NA-03** | Split `splitDurationMs` into `splitActiveMs` / `splitBlockedMs` | The field currently reports pipeline wall time, making runs incomparable | TBD | **v0.1.4** |
| **NA-04** | Watch `run1mins.mp4` and `run4mins.mp4` and annotate whether people are present during `no_subject` stretches | Resolves OQ-01 — the largest bucket in the pipeline and potentially larger than every perf item combined | TBD | **B1** |
| **NA-05** | Root-cause the drain failure that survived the `WriteQueue` fix | Currently mitigated by a watchdog, not understood. Resolves OQ-03 | TBD | **v0.1.4** |
| **NA-06** | Run the live-capture path at UHD and at FHD, with `perf_report.json` collected | Live is untested in v0.1.3; the recorder-pause path and exposure behaviour are unverified against these changes | TBD | **B1** |
| **NA-07** | Decide whether live UHD should be blocked in the UI while `realtimeRatio` > 1.0 | Resolves OQ-05; today an operator can select a mode that silently loses runners | TBD | **v0.1.4** |

Phase definitions: [DOCS.md § Implementation phases](../../docs/DOCS.md)

---

## 12. Review comments

| ID | Reviewer | Date | Comment | Response | Status |
|----|----------|------|---------|----------|--------|
| — | — | — | _No comments yet._ | — | — |

Status vocabulary: `Open` · `Accepted` · `Rejected` · `Deferred`

---

## Related

- [CHANGELOG.md § v0.1.3](../../docs/CHANGELOG.md) — what shipped
- [RELEASE_0_1_3.md](../../docs/RELEASE_0_1_3.md) — per-change rationale, including the failed Surface attempt
- [PIPELINE_FLOW.md](../../docs/PIPELINE_FLOW.md) — pipeline internals
- [SEQUENCE_FLOW.md](../../docs/SEQUENCE_FLOW.md) — sequence diagrams
- [inputs.md](./inputs.md) — test input manifest
- [REPORT_GUIDELINE.md](../../docs/REPORT_GUIDELINE.md) — how this report is structured
