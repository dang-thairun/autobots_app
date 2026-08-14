# Detector models

Model files for the LiteRT detector bench (v0.1.4+). **The models themselves are not in git** —
they are third-party artifacts under their own licences, and the QNN binaries are large. Each
person building the bench downloads them once.

## Expected files

| Path | Source | Runs on | In git |
|------|--------|---------|--------|
| `face_det_lite-tflite-w8a8/face_det_lite.tflite` | [Qualcomm AI Hub — Lightweight Face Detection](https://aihub.qualcomm.com/mobile/models/face_det_lite) | CPU (XNNPACK) · GPU (OpenCL) · **NPU (QNN HTP)** | no |
| `face_det_lite-onnx-w8a8/` | same page, ONNX export | *(unused — see below)* | no |

**One `.tflite` serves all three backends.** No chip-specific artifact is needed: the QNN
delegate compiles the graph on the device at load time. See
[../../jniLibs/README.md](../../jniLibs/README.md) for what that costs.

### The ONNX export is kept but not wired up

Same weights, same toolchain (`qairt 2.45.0`), so it computes identical numbers. Using it on
Android would mean adding ONNX Runtime (~20–40 MB per ABI) and building its QNN execution
provider by hand — a large bill for arithmetic we already have. The variable worth measuring is
the **backend**, not the file format. The detector interface leaves room for it if that changes.

## Model shape — read this before touching the postprocess

```
input     [1, 480, 640, 1]  uint8  grayscale   scale 0.003921208  zero_point 0
heatmap   [1,  60,  80, 1]  uint8              scale 0.027428148  zero_point 191
bbox      [1,  60,  80, 4]  uint8              scale 0.323010385  zero_point 9
landmark  [1,  60,  80,10]  uint8              scale 0.159863740  zero_point 102
```

Anchor-free, centre-based, **stride 8** (640/80 = 480/60 = 8). There are no ready-made boxes:
thresholding the heatmap, decoding `bbox`, and NMS are all ours to write. `landmark` is unused.

Two consequences that shaped the design:

**Grayscale input means detection never needs YUV→RGB.** The Y plane of a `YUV_420_888` frame
*is* this tensor's content. That is why `yuv_jpeg_argb` — 89.5 % of wall time in v0.1.4 — may be
removable for all but the frames that pass the gate and need a full-colour JPEG saved.

**The fixed 640×480 is 4:3 landscape; a frame is 9:16 portrait.** Letterboxing the whole frame
scales by 0.125, leaving a runner's face ~17 px tall — worse than the ~40 px ML Kit sees today.
Tiling is mandatory, and tile count is the recall knob:

| tiles | scale | face at the size gate |
|-------|-------|-----------------------|
| 1 (letterbox) | 0.125 | 17 px |
| 3 (2160×1620) | 0.296 | 40 px — parity with ML Kit |
| 10 (1080×810) | 0.593 | **79 px — twice what ML Kit sees** |

Cheap inference is what makes a high tile count affordable, and a high tile count is what buys
recall on distant runners. **That is the actual case for the NPU** — not raw model speed.

## Two things that will bite

**The `.tflite` must stay uncompressed in the APK.** LiteRT memory-maps the model straight out
of the APK, which fails if aapt has deflated it. Handled by `androidResources { noCompress }`
in `androidApp/build.gradle.kts` — do not remove it. The failure mode is an interpreter that
will not load, with no useful message.

**No chip-specific artifact is needed.** One `.tflite` serves CPU, GPU and NPU: the QNN
delegate compiles it for whatever HTP the device has, at load time. What that costs, and how
to trade it for 75 MB, is in [../../jniLibs/README.md](../../jniLibs/README.md).

## Measured on device — SM8635, synthetic 640×1138 input, 3 tiles

| backend | warm ms |
|---------|---------|
| ML Kit FAST (baseline) | 87 |
| ML Kit ACCURATE | 117 |
| `face_det_lite` CPU | 61 |
| `face_det_lite` GPU | 70 (noisy: 42–79 across runs) |
| **`face_det_lite` NPU** | **25** |

Two things worth noting before reading too much into these. **LiteRT on CPU already beats ML
Kit FAST** while running three inferences per frame. And **the GPU delegate is not reliably
better than CPU** — a fully-quantised uint8 graph is not what an OpenCL delegate is good at —
which makes the NPU more important to this decision, not less.

Timings are a smoke test on a synthetic gradient: they answer "does it run, roughly how fast",
never "does it detect". They also include the Kotlin grayscale conversion, so the model itself
is faster than these numbers suggest. Real answers come from `detector_compare.json`.
## Reading `detector_compare.json`

Produced by the **Compare all** detector mode: every backend runs over the same frames while
ML Kit FAST alone decides what the session keeps. Two questions it answers that per-backend
runs cannot, because those compare different frames rather than different detectors:

| field | question |
|-------|----------|
| `iouVsMlKitFast` | **Is the box decode right?** Near 1 means both detectors drew the same rectangle. Low, on frames where both fired, means the decode is wrong — not that recall differs. |
| `summary[].framesWithDetection` | **Does anything see the runners ML Kit misses?** `no_subject` was 64% of sampled frames in 0.1.4 and the footage says people were there. |

`realtimeRatio` is meaningless in that mode — the chunk does several times the detection work.
Throughput comes from single-backend runs.

## Related

- Bench rationale: [RELEASE_0_1_4.md](../../../../docs/RELEASE_0_1_4.md) · Native libs: [../../jniLibs/README.md](../../jniLibs/README.md)
- Device capability survey: same document, NPU section
