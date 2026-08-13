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

**Pick the right QNN export.** A QNN context binary is compiled for one Snapdragon family. The
test device is `SM8635` (Snapdragon 8s Gen 3, `ro.board.platform = pineapple`); a binary built
for a different chip will not load. Confirm with:

```
adb shell getprop ro.soc.model     # expect SM8635
```

**The `.tflite` must stay uncompressed in the APK.** LiteRT memory-maps the model out of the
APK, which fails if aapt has deflated it. Handled by `androidResources { noCompress += ... }`
in `androidApp/build.gradle.kts` — do not remove it.

## Why the GPU column matters more than it looks

`libOpenCL.so` and `libOpenCL_adreno.so` are already on the device, so the **GPU delegate costs
nothing extra to ship**. The QNN path needs its backend `.so` bundled into an APK that is
already 137 MB and where `installDebug` is unreliable. If GPU turns out to be fast enough, the
NPU path buys nothing but size — which is exactly what the bench is there to decide.

## Related

- Bench rationale and the decision it feeds: [RELEASE_0_1_4.md](../../../../../docs/RELEASE_0_1_4.md)
- Device capability survey: same document, NPU section
