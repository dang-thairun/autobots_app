# S3 — Split Worker 1 / Worker 2 · on-device verification

Device `24069PC21G` · `run4mins.mp4` (4m33s, 3840×2160, 25 fps) · Face + Person · NPU · Whole frame · Full length
Baseline build = `66d2a39` (S2). Both runs identical settings, back to back, 2026-09-01.

Raw data: `s3_baseline/`, `s3_after/`.

## Photos — 106 of 109 identical, 3 dropped, 0 added

| | baseline | S3 |
|---|---|---|
| photos kept | 109 | **106** (−2.8%) |
| files in common | — | **106** |
| files only in baseline | — | 3 |
| files only in S3 | — | **0** |

Dropped: `person_c026_7320000.jpg`, `person_c026_7440000.jpg`, `person_c028_7560000.jpg` — all at 7.3–7.6 s
into ~8.5 s chunks, i.e. **at chunk tails**, which is exactly where a boundary-split runner used to be
handed a second budget of three. Nothing new was kept, so the change removed surplus rather than
re-deciding anybody.

Predicted −4.2% (S0.2 seam rate); measured −2.8% on 13 merges.

## Tracks — 13 passages merged across chunk boundaries

| | baseline | S3 |
|---|---|---|
| track rows | 97 | 103 |
| track rows, restricted to the 23 chunks the baseline recorded | 97 | **84** (−13) |
| tracks spanning a chunk boundary | 0 (impossible) | **16** |
| frames per track, mean | 8.90 | 9.74 |
| duplicate track ids | 10 | **0** |

The headline count went *up* for a reason that is not a regression. Before S3, `selectKeepers` returned
early when a chunk produced no candidates, so the tracker never ran and **six chunks contributed no
rows at all** — 19 passages nobody could see. Worker 2 runs over every chunk's sightings, so those
passages are now recorded with `captured=0`, which is what a passage row is for.

Restricted to the chunks both runs recorded, the count falls 97 → 84 and total frames are unchanged
(863 vs 867), which is the merge and nothing else. Track ids are unique for the whole session now.

## Disk — 12–15 MB held, 7–9 candidates

`CANDIDATE_RETENTION_CHUNKS = 1`, sampled from the app's cache during a run:

```
candidates=9  KB=15408
candidates=7  KB=12172
candidates=9  KB=15460
candidates=0  KB=4      <- session finished, faces/ empty
```

Real JPEG size, from 109 delivered photos: **mean 1.72 MB** (1.48–1.87), not the 2–5 MB assumed when
the retention constant was chosen. Holding a whole 3.5-hour session would be ~14 GB, not 17–42 GB;
holding one chunk is ~15 MB either way.

## Crash — nothing lost, leftovers bounded

Force-stopped 100 s into a run:

| | |
|---|---|
| `chunks.csv.part` | 19 rows |
| `tracks.csv.part` | 26 rows ← written by Worker 2, so its writes are crash-durable too |
| `photos.csv.part` | 36 rows |
| leftover candidates | 15 files, 26 MB, in `cacheDir` |

After the 120 s grace and a relaunch, all three `.part` files were swept and `perf_report.json` was
rebuilt from the stream. Leftover candidate JPEGs are deliberately **not** swept: they live in
`cacheDir`, they are bounded by the retention window, and `WriteQueue` keeps a file on purpose when
delivery fails — an age-based sweep would delete photos that are waiting to be re-sent.

## Speed

| | baseline | S3 |
|---|---|---|
| realtimeRatio mean | 0.602 | 0.651 |
| realtimeRatio max | 0.908 | 0.983 |
| wall clock | — | 2m55s for a 4m33s clip (0.63×) |

+8% on the mean. Two causes, both expected: Worker 2 now runs the tracker over *every* chunk instead
of skipping candidate-less ones, and the handoff queue's capacity of 1 lets Worker 1 block on
Worker 2. `realtimeRatio` is an observation, not a gate (PLAN §8.1), but the max at 0.983 is close
enough to 1.0 to watch on the 3–4 hour run in S11.

## Not verified here

- **Live capture.** Both runs are imports, where `sourceOffsetUs` is exact. Live chunks fall back to
  the recorder's wall clock, and whether the gap between two live chunks stays under
  `SubjectTracker.maxGapUs` (600 ms) is what S0.1 measures — and what decides whether S9 is needed.
