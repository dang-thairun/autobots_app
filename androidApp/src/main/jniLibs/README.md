# Native libraries — QNN (Hexagon NPU) backend

Seven files, ~96 MB, taken from **QAIRT SDK 2.49.0.260730** and targeting **HTP v75**, which is
what `SM8635` (Snapdragon 8s Gen 3) runs. The `.so` files are gitignored — Qualcomm
redistributables are not ours to commit. The pruned SDK they came from lives in `/tools/qairt/`
(also gitignored); re-extract with the table below if it is ever lost.

## What is here and why

| File | Side | MB | Role |
|------|------|----|------|
| `libQnnTFLiteDelegate.so` | CPU | 1.0 | TFLite ↔ QNN bridge — the entry point |
| `libQnnHtp.so` | CPU | 3.6 | HTP backend |
| `libQnnSystem.so` | CPU | 3.9 | |
| `libQnnHtpV75Stub.so` | CPU | 0.7 | v75 stub |
| `libQnnHtpV75CalculatorStub.so` | CPU | 0.2 | |
| **`libQnnHtpPrepare.so`** | CPU | **75.7** | **compiles the graph at runtime** — see below |
| `libQnnHtpV75Skel.so` | **DSP** | 11.3 | executes on the Hexagon itself |

Source paths inside the SDK: everything above is `lib/aarch64-android/` except the skel, which
is `lib/hexagon-v75/unsigned/`.

## 79 % of this is one file, and it is removable

`libQnnHtpPrepare.so` exists only to compile a `.tflite` into an HTP graph **on the device, at
runtime**. That is what makes a single portable `.tflite` work on any HTP version — no
chip-specific artifact required. The price is 75.7 MB.

The alternative is a **QNN context binary**: the same graph, compiled ahead of time and
serialised. Ship that instead of `HtpPrepare` and this directory drops from ~96 MB to ~20 MB —
at the cost of the binary being tied to one HTP architecture.

We can generate one ourselves without downloading anything: `qnn-context-binary-generator` is
built for `aarch64-android` in `/tools/qairt/*/bin/aarch64-android/`, so it runs **on the
phone**. (The SDK ships no macOS host tools at all — Linux and Windows only — so on-device is
the only route from this machine.)

**Not worth doing yet.** While benchmarking, runtime compilation keeps the loop short and the
model is 990 KB, so it is fast. Revisit before anything ships.

## Two packaging settings this depends on

Both in `androidApp/build.gradle.kts`; removing either breaks the NPU backend silently.

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true                          // (1)
        keepDebugSymbols += "**/libQnnHtpV75Skel.so"        // (2)
    }
}
```

1. The HTP backend hands the skel to the DSP **by file path** via `ADSP_LIBRARY_PATH`, so the
   libraries must be extracted to disk instead of staying compressed inside the APK. Verify:
   ```
   adb shell ls -l /data/app/*/com.autobots.camera*/lib/arm64/ | grep -i qnn
   ```
   Seven files must be listed. Empty means the NPU backend cannot start.
2. `libQnnHtpV75Skel.so` is a **Hexagon** ELF, not arm64. The NDK strip tool does not recognise
   it and fails the build. There is nothing to strip in it regardless.

Side effect worth knowing: `useLegacyPackaging` also compresses ML Kit's libraries, which were
previously stored uncompressed. The debug APK went **137 MB → 106 MB** even after adding 96 MB
of QNN — install size on device goes up instead.

## Supporting a second chip later

The skel is the only genuinely architecture-bound file, because it runs on the DSP. The SDK
ships every version under `lib/hexagon-v*/unsigned/`; add the matching `Skel` plus its
`V**Stub` and one APK covers both. That is what makes this portable — weight, not rebuilding.

Device evidence for the current target:

```
$ adb shell ls /vendor/lib/rfsa/adsp/ | grep -i skel
libSnpeHtpV73Skel.so
libSnpeHtpV75Skel.so          ← v75
$ adb shell getprop ro.boot.vendor.qspa.npu
enabled
```

## Related

- Model files, quantisation params, and the 640×480 geometry problem:
  [../assets/models/README.md](../assets/models/README.md)
- Why the NPU is being evaluated at all: [RELEASE_0_1_4.md](../../../../docs/RELEASE_0_1_4.md)
