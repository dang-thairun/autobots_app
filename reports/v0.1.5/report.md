# v0.1.5 — Upload, and two long 4K jobs back to back

> Written per [REPORT_GUIDELINE.md](../../docs/REPORT_GUIDELINE.md).
> All numbers come from `perf_report.json`, `session_log.txt` and the upload queue database
> produced by the run itself — see [inputs.md](./inputs.md).

---

## 1. Snapshot

| Field | Value |
|-------|-------|
| Version | **v0.1.5** — `appVersionName=0.1.5`, `appVersionCode=15`, plus the upload work (B3a–B3f) built on top of it |
| Report date | 2026-08-21 |
| Phase | **B3** — upload queue, Runx transport, foreground service |
| Theme | Two long, realistic jobs end to end: a 50-minute landscape clip over Network URL with live uploads, then a 30-minute **portrait** clip from local storage — the most expensive configuration measured so far |
| Tester | Dang Thairun (device run) · numbers read from the session artifacts |
| Device | Xiaomi 24069PC21G (`peridot`), Android 16 / SDK 36, arm64-v8a, 8 cores |
| Build under test | `androidApp-debug.apk`, debug, `CAM_PERF=true`, commit `12a33e8`. TC-18 ran with upload-UI changes on top; **nothing in the extraction path differed between the two runs** |
| Verdict | **Ship** for the path tested — see [§11](#11-verdict--next-actions) for what remains untested |
| Score | **95 / 100** |

---

## 2. What & why

v0.1.5 opened a third way to get work into the pipeline (Network URL) but left the output at
the local gallery. The upload work built on top of it (B3a–B3f, see
[PHASES.md](../../docs/PHASES.md)) closes the other end: every delivered photo joins a durable
queue and WorkManager drains it to the Runx platform as a foreground service.

Every part had been proven separately — against a mock backend, on short clips, and under a
forced Doze. **Nothing had been run at the size of a real job.** This test is that run: a
50-minute 4K clip, 14.6 GB, fetched over the network, with uploads to the live platform
running concurrently.

It also re-checks the `yuv420ToNv21` optimisation landed the same morning
(`realtimeRatio` 0.881 → 0.502 on a one-minute clip) at 380× that scale.

---

## 3. Scope

**In scope**
- Network URL ingest of a 4K clip, full length, no trim
- Face extraction on NPU (`face_det_lite.tflite`), 120 ms sampling
- Upload of every kept photo to the live Runx platform, event `test-upload`
- Behaviour over a 31-minute extraction and an 82-minute upload tail

- Local file ingest (Browse Video) of a portrait 4K clip, full length

**Out of scope — not exercised at all**
- **Live capture → upload.** Photos reach the queue from a different call site
  (`CapturePipelineCoordinator.enqueueForUpload`) which has only ever been run against the
  fake transport
- **Mobile data.** The whole run was on Wi-Fi
- **Doze during upload.** The device stayed plugged in and awake
- 1080p, Pose, ML Kit and GPU backends
- Crash recovery — nothing crashed, so nothing was learned about it

---

## 4. Open questions

| ID | Question | Options | Impact | Decider |
|----|----------|---------|--------|---------|
| OQ-01 | 5,752 of 12,330 JPEG encodes are discarded by per-chunk dedup **after** being written. Can the dedup decision move ahead of the encode? | (a) leave it (b) dedup on a streaming window (c) hold candidates as bitmaps until chunk end | ~10 min of a 31-min run. Option (c) needs ~1.3 GB of 4K bitmaps in memory, so it is not free | Dev |
| OQ-02 | 5,082 frames get a full-size decode + sharpness before being rejected as too soft. Could sharpness be pre-filtered on the small detect image? | (a) leave it (b) pre-filter and accept that the kept set changes | ~4.4 min per run, but it changes which photos survive | Dev + whoever owns photo quality |
| OQ-03 | `cpuMaxFreqKhz` fell 38% while `thermal` reported `OK` for the entire run. Should the app watch the clock instead of the thermal API and warn the operator? | (a) ignore (b) surface it in the UI (c) log only | A 4-hour event is 8× this run; the margin halved in 31 minutes | Dev + operator |
| OQ-04 | Session artifacts land in `Download/AutoBots/`, photos in `DCIM/AutoBots/`. Should they sit together? | (a) leave it, MediaStore forbids text under DCIM (b) mirror a copy elsewhere (c) document it | Anyone collecting logs looks in the wrong place first — the author of this report did | Dev |
| OQ-06 | Clearing the queue deletes the only record of what was uploaded and how it went. Should the app keep an upload log, or offer an export before clearing? | (a) accept it (b) append an upload log alongside the session artifacts (c) confirm-with-export | It cost this report TC-19's numbers, and it would cost an event post-mortem the same | Dev |
| OQ-05 | Portrait 4K costs roughly double landscape: the detector runs `tileCount 3` instead of `1`, and `rotate` appears at 117 ms/frame. Is that acceptable, or should portrait be handled differently? | (a) accept it (b) rotate once before detection instead of per candidate (c) advise shooting landscape | TC-18 landed at `realtimeRatio` 1.046 versus 0.615 for landscape. It is the difference between finishing ahead of the clip and finishing behind it | Dev + operator |

---

## 5. How it works

```
Network URL ──HTTP byte-range──▶ splitter ──chunks──▶ sampler ──frames──▶ detect workers ×N
                                    │                                          │
                              (blocked 22m8s                              candidates
                               by backpressure)                                │
                                                                        per-chunk dedup
                                                                               │
                                                                          MediaStore
                                                                               │
                                                                      Room upload queue
                                                                               │
                                                        WorkManager ──presign→PUT→complete──▶ Runx
```

Full detail in [PIPELINE_FLOW.md](../../docs/PIPELINE_FLOW.md) and
[PHASES.md §10](../../docs/PHASES.md).

---

## 6. Test setup

| | |
|--|--|
| Device | Xiaomi 24069PC21G (`peridot`), HyperOS, Android 16 / SDK 36 |
| Build | `androidApp-debug.apk` from commit `12a33e8`, `CAM_PERF=true` |
| Resolution | 4K (source resolution, no downscale) |
| Target / backend | Face · LiteRT NPU · `face_det_lite.tflite` |
| Sampling | 120 ms · dedup 3 per 1-second window |
| Ingest | Network URL, full length, streamed |
| Backend | `api.photo.thai.run` · event `test-upload` · signed in as `thairundev` |
| Power / network | Plugged in, Wi-Fi, screen on |

---

## 7. Test inputs

| ID | Input | Detail |
|----|-------|--------|
| TC-16 | `2026-asicsmeta-full-1.mp4` over Network URL | 3840×2160, 50:00, 14.6 GB, rotation 0 |
| TC-17 | The 6,578 photos TC-16 produced | uploaded to event `test-upload` while TC-16 was still running |
| TC-18 | `input_1.MP4` from local storage (Browse Video) | 3840×2160 **rotation 90 (portrait)**, 29:50, 11.9 GB. Started immediately after TC-16, on a device that had not cooled down |
| TC-19 | The 1,829 photos TC-18 produced | uploaded to the same event, again concurrently with extraction |

---

## 8. Expected results

> ⚠️ **These expectations were written after the run, not before it.** The guideline
> ([§3](../../docs/REPORT_GUIDELINE_TH.md)) is explicit that this makes them weaker, so each
> one is tied to a number measured *before* this test rather than to the run's own output.

| ID | Expectation | Where the number comes from |
|----|-------------|------------------------------|
| TC-16 | Session reaches `Done`, `decodeFailures = 0` | every prior import session |
| TC-16 | `realtimeRatio < 1.0` sustained over 50 minutes | TC-12 measured 1.010 before the worker split; 0.502 after the chroma fix on a 1-minute clip |
| TC-16 | `yuv_nv21` stays well under its old 63.9 ms/frame | measured 9.5 ms/frame on a 1-minute clip the same morning |
| TC-16 | No report truncation below 30,000 frames | `frameDiagBudget` in the report schema |
| TC-17 | Every photo reaches `Success` with no manual intervention | mock-backend drains completed 15/15 and 16/16 |
| TC-17 | Retries stay rare | the `Uploaded → Success` path exists precisely because retries were expected |
| TC-18 | `realtimeRatio` at least as good as the 1.010 measured at TC-12 | TC-12 is the last landscape figure taken before the worker split; portrait was never measured, so this is the only comparable number that exists |
| TC-19 | Every photo reaches `Success`, as TC-17 did | TC-17 finished 6,566 of 6,578 with zero retries |

---

## 9. Actual results

| ID | Result | Status |
|----|--------|--------|
| TC-16 | `Done` · `decodeFailures 0` · `roiInvalid 0` · 273/273 chunks | **PASS** |
| TC-16 | `realtimeRatio` **0.615** — 30m 57s of work for 49m 52s of video | **PASS** |
| TC-16 | `yuv_nv21` **12.9 ms/frame, 15.9% of wall** (was 63.9 ms, 61.7%) | **PASS** |
| TC-16 | `frameDiagsDropped 0`, `eventsDropped 0` — 22,643 frames, budget 30,000 | **PASS** |
| TC-17 | **6,566 of 6,578 uploaded**, 7,349 MB, 11 `Pending` + 1 `Uploaded` still draining when sampled | **PASS** |
| TC-17 | **`attemptCount = 0` on all 6,578 rows** — not one retry in 7.2 GB | **PASS** |
| TC-18 | `Done` · `decodeFailures 0` · `roiInvalid 0` · 232/232 chunks | **PASS** |
| TC-18 | `realtimeRatio` **1.046** — better than the 1.010 bar only if the extra cost of portrait is discounted; taken at face value it is 3.6% over | **PARTIAL** |
| TC-19 | **1,829 of 1,829 reached `Success`**; queue held 8,407 rows and nothing in any other state | **PASS** |
| TC-19 | Retry count and throughput | **TBD** — records deleted before they were read |

### Yield

```
22,643 frames sampled → 12,330 candidates → 6,578 kept (29%)
rejects: noSubject 4,988 · tooSoft 5,082 · tooSmall 243 · roiInvalid 0
```

### Where the time went

| Stage | n | avg ms | total s | % of wall |
|-------|---|--------|---------|-----------|
| `save_jpeg` | 12,330 | 106.0 | 1,307 | 71.0% |
| `nv21_jpeg` | 22,643 | 44.2 | 1,001 | 54.4% |
| `jpeg_argb_full` | 17,412 | 51.9 | 904 | 49.1% |
| `jpeg_argb_detect` | 22,643 | 22.7 | 514 | 27.9% |
| `worker_idle` | 22,643 | 19.2 | 435 | 23.6% |
| `detect` | 22,643 | 13.9 | 315 | 17.1% |
| `yuv_nv21` | 22,643 | 12.9 | 292 | 15.9% |
| `queue_wait` | 22,643 | 3.9 | 89 | 4.8% |
| `scale_for_detect` | 22,643 | 2.3 | 53 | 2.9% |
| `decode` | 90,299 | 0.3 | 26 | 1.4% |
| `sharpness` | 17,412 | 1.2 | 21 | 1.1% |

Percentages exceed 100% because the producer thread and the detect workers run at the same
time; read them as relative cost, not as shares of a single timeline.

**`save_jpeg` ran 12,330 times and produced 6,578 files.** The other 5,752 were encoded at
quality 95 from a full 4K bitmap and then dropped by the per-chunk dedup (3 sharpest per
1-second window). That is deliberate behaviour and **no photo was lost** — but the dedup
decision uses only sharpness and timestamp, both known before the encode. ≈ 10 minutes of the
31-minute run (OQ-01).

**`jpeg_argb_full` ran 17,412 times** = 12,330 candidates + 5,082 frames later rejected as too
soft, because sharpness is measured on the full-size decode. ≈ 4.4 minutes (OQ-02).

### TC-18 — portrait 4K, the most expensive configuration measured

```
14,920 frames sampled → 4,275 candidates → 1,829 kept (12%)
rejects: noSubject 6,911 · tooSmall 2,641 · tooSoft 1,093 · roiInvalid 0
30m 34s of work for 29m 13s of video · realtimeRatio 1.046
```

| Stage | n | avg ms | total s | % of wall |
|-------|---|--------|---------|-----------|
| `nv21_jpeg` | 14,920 | 53.7 | 801 | 43.7% |
| `jpeg_argb_detect` | 14,920 | 46.1 | 688 | 37.5% |
| `detect` | 14,920 | 43.3 | 647 | 35.2% |
| `rotate` | 5,368 | 117.0 | 628 | 34.2% |
| `save_jpeg` | 4,275 | 144.3 | 617 | 33.6% |
| `queue_wait` | 14,920 | 32.8 | 489 | 26.7% |
| `worker_idle` | 14,920 | 27.6 | 411 | 22.4% |
| `jpeg_argb_full` | 5,368 | 69.3 | 372 | 20.3% |
| `yuv_nv21` | 14,920 | 12.1 | 181 | 9.9% |

**Three separate costs, all absent from TC-16:**

1. **`tileCount 3` instead of `1`.** A 2160×3840 frame is too tall for one pass, so the
   detector runs three tiles. `detect` goes 13.9 → 43.3 ms/frame — almost exactly 3×.
2. **`rotate` at 117 ms/frame, 34% of wall.** TC-16's source had rotation 0 and never entered
   this stage at all. It runs 5,368 times: every candidate plus every frame later rejected as
   too soft.
3. **The device started throttled.** `cpuMaxFreqKhz` opened at 2,016,000 against TC-16's
   2,572,800 — 22% down before the first frame, because TC-16 had just finished.

**The bottleneck moved to the consumer.** `queue_wait` averaged 32.8 ms/frame and rose
4.3 → 29.9 ms across the run (+590%): the producer now waits on the detect workers, the
reverse of every earlier report.

`yuv_nv21` held at 12.1 ms/frame (9.9%) — the chroma fix is not what limits this run.

### Where 1.046 sits historically

| Version | Case | `realtimeRatio` |
|---------|------|-----------------|
| v0.1.3 | TC-04, landscape 4K, single-threaded Worker 2 | 2.196 |
| v0.1.4 | TC-10, landscape 4K, before deferred decode | 1.430 |
| v0.1.4 | TC-12, landscape 4K, after deferred decode | 1.010 |
| v0.1.5 | **TC-16**, landscape 4K, after the chroma fix | **0.615** |
| v0.1.5 | **TC-18**, **portrait** 4K, hot device | **1.046** |

TC-18 is marked `PARTIAL` because 1.046 is over 1.0 and the report's own rule is that
expectations are numbers, not excuses. It is worth saying plainly what the number means
though: this is the **hardest configuration anyone has measured** — triple detection, a rotate
stage that landscape never pays, and a device that started 22% down on clock — and it still
landed within 5% of real time, better than landscape managed one release ago.

### Upload (TC-17 and TC-19)

| | |
|--|--|
| Uploaded | 6,566 photos · 7,349 MB |
| Window | 13:45:29 → 15:07:44 (82 minutes), started 1 minute into extraction |
| Throughput | 1.33 photos/s · 1.49 MB/s (~12 Mbit/s) |
| Retries | **zero** |

**TC-19 — TC-18's photos.** By 16:16 the queue held **8,407 rows, all `Success`** — 6,578 from
TC-16 plus 1,829 from TC-18, with no row in `Pending`, `Failed`, `Uploaded` or `Abandoned`.
Rows from `ext_v0_1_5_21082026_1532` show `Success` at 16:03, and the last `COMPLETE` in
logcat lands at 16:11:14, about eight minutes after that extraction ended. Photo sizes ran
1.4–2.3 MB.

> ⚠️ **The per-row detail for TC-19 no longer exists.** The queue was cleared at 16:16 while
> testing the *Clear queue* button, which deletes exactly the records this row depends on. What
> survives is a screenshot taken a minute earlier and the tail of logcat. So TC-19's outcome is
> solid — every photo reached `Success`, nothing was left behind — but its **retry count and
> throughput are unrecoverable**, marked TBD below rather than estimated.

| | TC-17 | TC-19 |
|--|--|--|
| Photos | 6,566 of 6,578 at the time of sampling | **1,829 of 1,829** |
| Volume | 7,349 MB | TBD — sizes destroyed with the rows |
| Window | 13:45:29 → 15:07:44 | ≈15:33 → 16:11 (from logcat, uploads overlapped extraction) |
| Throughput | 1.33 photos/s · 1.49 MB/s | TBD |
| Retries | zero | TBD — `attemptCount` destroyed with the rows |

### Thermal

| | first 20 chunks | last 20 chunks | drift |
|--|--|--|--|
| `cpuProbeMs` | 3.21 | 4.61 | +44% |
| `realtimeRatio` | 0.454 | 0.601 | +32% |
| `yuv_nv21` | 10.6 | 13.1 | +24% |
| `nv21_jpeg` | 32.5 | 46.5 | +43% |
| `detect` | 11.3 | 15.0 | +32% |
| `jpeg_argb_detect` | 15.9 | 26.0 | +64% |
| `save_jpeg` | 75.9 | 117.6 | +55% |
| `worker_idle` | 19.4 | 39.4 | +104% |

`cpuMaxFreqKhz` fell **2,572,800 → 1,593,600 (−38%)** while `thermal` reported `OK` and
`thermalLevel 0` for the entire run. RAM was flat (5,790 → 5,190 MB used, no leak).

---

## 10. Score

| Dimension | Weight | Score | Weighted | Evidence |
|-----------|--------|-------|----------|----------|
| Functional correctness | 30 | 5 | 30 | TC-16, TC-17 PASS · TC-18 PASS on correctness (232/232 chunks, 0 decode failures) |
| Output quality | 25 | 5 | 25 | 8,407 usable photos across both runs, `roiInvalid 0` and `decodeFailures 0` in each |
| Stability | 20 | 5 | 20 | 61 minutes of extraction across two jobs + 82 minutes of upload, no crash, clean `Done` both times, flat RAM |
| Performance | 15 | 4 | 12 | TC-16 at 0.615 with room to spare; TC-18 at 1.046 — over the line, but on the most expensive configuration measured and still ahead of where landscape sat one release ago. Discarded work (OQ-01, OQ-02) and a 38% clock drop (OQ-03) cost more than the video's orientation does |
| Operator UX | 10 | 4 | 8 | Operator ran the whole job unaided; artifacts land where nobody looks (OQ-04) and a crash would still leave no log |
| **Total** | **100** | | **95** | **Ship** for the tested path |

---

## 11. Verdict & next actions

- **The pipeline handles a real job.** 14.6 GB in, 6,578 photos out, 7.2 GB uploaded, nothing
  lost, nothing retried, no crash.
- **The chroma fix holds at scale.** `yuv_nv21` went from the largest cost in the pipeline to
  the seventh, on a run 380× longer than the one it was measured on.
- **The upload path is proven for import.** 8,407 photos across two sessions all reached
  `Success`; TC-17's 7.2 GB went up with zero retries, a stronger result than the design
  assumed — the retry machinery was never needed.
- **Roughly half the processing time produces nothing** — encodes that dedup discards and full
  decodes of frames that fail the sharpness gate. This is now the largest lever.
- **The device throttled 38% without saying so.** TC-16 had margin to spare; a 4-hour event is
  eight times longer, and TC-18 started already 22% down because TC-16 had just finished.
- **Portrait 4K is a different workload, not a variation on one.** Triple detection plus a
  rotate stage landscape never enters. It is the case that decides whether the pipeline keeps
  up, so it is the case to plan around (OQ-05).

| ID | Action | Owner | Target |
|----|--------|-------|--------|
| NA-01 | Live capture → upload, end to end, to the live platform. The only untested call site into the queue | Dev | before first event |
| NA-02 | Repeat on mobile data — no upload number in this report was measured off Wi-Fi | Dev | before first event |
| NA-03 | Move the dedup decision ahead of the encode, or reduce the cost of the discarded ones (OQ-01) | Dev | v0.1.6 |
| NA-04 | Pre-filter sharpness before the full decode (OQ-02) | Dev | v0.1.6 |
| NA-05 | Write a crash log and flush session artifacts incrementally — a crash at minute 55 currently leaves nothing at all | Dev | before a longer test |
| NA-06 | Decide whether the operator should see the clock falling (OQ-03) | Dev + operator | v0.1.6 |
| NA-07 | Ask the backend owner for `capturedAt` on `/success` — this run proves the queue can lag the shoot by an hour | Dev | when backend is available |
| NA-08 | Decide how portrait is handled (OQ-05). Rotating once before detection instead of per candidate is the obvious first look — `rotate` is 34% of TC-18 | Dev | v0.1.6 |
| NA-10 | Write upload outcomes to the session artifacts, not only to a table the operator can wipe (OQ-06). Pairs with NA-05 | Dev | v0.1.6 |
| NA-09 | Re-measure TC-18 on a cold device. Starting 22% down on clock makes the number pessimistic by an unknown amount | Dev | v0.1.6 |

---

## 12. Review comments

| ID | Reviewer | Date | Comment | Status |
|----|----------|------|---------|--------|
| RC-01 | — | — | TBD | TBD |
