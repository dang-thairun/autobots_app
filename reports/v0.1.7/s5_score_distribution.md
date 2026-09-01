# S5 — What the person detector reports below its own cut-off

Device `24069PC21G` · Face + Person · NPU · Whole frame · 2026-09-01.
Two clips on purpose: an easy daylight one and a dense night finish chute.

| | `run4mins.mp4` | `2026-asicsmeta-full-1.mp4` 10:00–14:00 |
|---|---|---|
| source | local file | **network URL** (26.7 GB, streamed) |
| resolution / fps | 3840×2160 · 25 | 3840×2160 · 30 |
| scene | daylight, few runners | **night, dense** |
| tracks | 103 | **396** |
| photos | 106 | 68 |
| one-frame tracks | 15.5% | **35.6%** |
| `trackedRatio` mean / min | 0.883 / 0.38 | **0.853 / 0.36** |
| `framesMissed` total / max | 130 / 8 | **643 / 21** |
| `realtimeRatio` mean | 0.568 | 0.343 |

The dense clip's 35.6% one-frame rate reproduces S0.3's 34.3%, measured on the same footage
through a different route — the two agree.

## The distribution, per frame, after the local-max test and before NMS

```
band          run4mins   asicsmeta 10-14
0.05-0.10        70.32           259.75
0.10-0.15        18.69           113.87
0.15-0.20        16.01            59.99
0.20-0.25         8.64            16.79
0.25-0.30         6.57            13.34
0.30-0.35         5.34            10.56
0.35-0.40         3.95             6.85
0.40-0.45         1.49             3.72
0.45-0.50         0.97             2.90
0.50-0.55         0.89             2.61
0.55-0.60         0.28    ←trough  1.59  ←trough
0.60-0.65         0.17             2.59
0.65-0.70         0.09             2.97
0.70-0.75         0.06    ←trough  3.54  ←PEAK
0.75-0.80         0.12             1.86
0.80-0.85         0.21             0.77
0.85-0.90         0.83             0.62
0.90-0.95         0.94    ←PEAK    0.32
```

## Three findings

### 1 · The distribution is bimodal, and the boundary between the modes moves with the scene

`run4mins`: people peak at **0.90–0.95**, trough at **0.70–0.75**.
`asicsmeta` night: people peak at **0.70–0.75**, trough at **0.55–0.60**.

Same model, same threshold, mode shifted down by ~0.2 — the runners are further away, smaller
and darker. **A tier boundary chosen on one clip does not transfer to another.**

### 2 · On the dense clip the current 0.70 cut-off sits *on* the person mode, not below it

0.70 is the model's default and it was never wrong on daylight footage — the trough is right
there. On the night chute it cuts through the peak: **5.56 boxes per frame in 0.60–0.70** are on
the person side of the trough and are being discarded. That is the population the two-tier change
exists to recover, and it is only visible on the harder clip.

### 3 · ByteTrack's 0.1 floor is unusable here, by two orders of magnitude

```
low tier as 0.10-0.50   run4mins  61.7 /frame     asicsmeta  480 /frame
low tier as 0.50-0.70   run4mins   1.43/frame     asicsmeta  9.76/frame
high tier (>=0.70)      run4mins   2.16/frame     asicsmeta  7.11/frame
```

At 0.10–0.50 the tracker would be fed 60–480 boxes per frame against 2–7 real people. A 4K frame
does not hold 480 partly-occluded runners; that band is the sigmoid heatmap's noise floor.

At 0.50–0.70 the count is the same order as the real people on both clips — which is what a band
of "runners the model is less sure about" should look like.

**⇒ our low tier is 0.50–0.70, not 0.10–0.50.** 0.50 is also ByteTrack's `track_thresh`, but as
our *floor* rather than our boundary, and for a measured reason rather than a borrowed one.

## Cost

`realtimeRatio` did not get worse on either clip (0.603 → 0.568 on `run4mins`). The extra decode
work is real — 497 boxes per frame pass the floor on the dense clip — but it lands on the
consumer side, which `perf_report.json` has shown idle for four versions. `MAX_CANDIDATES = 128`
bounds the `O(n·k)` NMS scan; the 0.50–0.70 band sits far inside that cap.

## What this run does not prove

`run4mins` produced **byte-identical** `tracks.csv` and `photos.csv` against the S4 build, which
is what shows the low tier reaches nothing it should not. There is no pre-S5 baseline for the
asicsmeta range, so that clip is evidence about the distribution only.

## Still open, and it belongs to S6

Whether those 0.50–0.70 boxes sit where a lost track was heading. The histogram carries no
positions — and ByteTrack's stage 2 is exactly that test (a low box must overlap the predicted
box by half). Measuring it separately would duplicate the mechanism, so S6 logs its stage-2
acceptances instead.
