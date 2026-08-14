# Native libraries — QNN (Hexagon NPU) backend

Six arm64 libraries, ~86 MB, from **QAIRT SDK 2.49.0.260730**, targeting **HTP v73**.

**They are gitignored and will disappear on `git reset --hard`, `git clean -x`, or a fresh
clone.** Restore them — plus the SDK headers, which are gitignored for the same reason — with:

```
./tools/restore-qairt.sh
```

Nothing warns you when they are missing. The build succeeds, the APK shrinks from ~106 MB to
~44 MB, and the only sign is one line from the startup probe:

```
litert_npu   UNAVAILABLE   built without the QAIRT SDK headers
```

## What is here

| File | MB | Role |
|------|----|------|
| `libQnnTFLiteDelegate.so` | 1.0 | TFLite ↔ QNN bridge |
| `libQnnHtp.so` | 3.6 | HTP backend |
| `libQnnSystem.so` | 3.9 | |
| `libQnnHtpV73Stub.so` | 0.7 | v73 stub |
| `libQnnHtpV73CalculatorStub.so` | 0.2 | |
| `libQnnHtpPrepare.so` | **75.7** | compiles the graph on device — see below |

**arm64 only.** The Hexagon-side files live in `../assets/qnn/hexagon-v73/` and are unpacked to
app storage at runtime by `QnnDelegate.ensureDspLibraries`. Keeping them apart is not tidiness:
`libQnnSystem.so` ships under the *same file name* for both architectures, and a directory
holding both makes the DSP resolve the dependency to the arm64 ELF and fail. Verified with
`qnn-platform-validator --testBackend`: a clean directory passes, the identical skel beside the
arm64 libraries fails.

## Three things that each cost hours to find

### 1. `<uses-native-library>` is mandatory — this was the real blocker

`AndroidManifest.xml` must declare:

```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
<uses-native-library android:name="libadsprpc.so" android:required="false" />
```

These are FastRPC, the transport QNN uses to reach the DSP. They are listed in
`/vendor/etc/public.libraries.txt`, but since API 30 an app's linker namespace hides any vendor
library it has not asked for. Without the declaration everything *appears* fine — the delegate
is created and returns a valid handle — and the failure surfaces two layers down as:

```
QnnDsp <E> loadRemoteSymbols failed with err 4000
QnnDsp <E> Failed to load skel, error: 4000
```

which TFLite reports only as `Failed to apply delegate`. The way to see it directly is to run
the validator **in the app's own context**, where the libraries show up as missing:

```
adb shell run-as com.autobots.camera sh -c 'cd files/pv && \
  LD_LIBRARY_PATH=$PWD/lib ADSP_LIBRARY_PATH=$PWD/dsp ./pv --backend dsp --testBackend'
```

From a plain `adb shell` they load fine, which is exactly why this hides.

### 2. The HTP architecture is v73, not v75

Ask the hardware; do not infer it. `/vendor/lib/rfsa/adsp/` on SM8635 contains **both**
`libSnpeHtpV73Skel.so` and `libSnpeHtpV75Skel.so` — vendors ship several for compatibility, so
their presence proves nothing. The authoritative answer:

```
$ adb shell .../qnn-platform-validator --backend dsp --coreVersion
Core Version of the backend DSP: Hexagon Architecture V73
```

Supporting a second chip later means adding that arch's `Skel` (to `assets/qnn/hexagon-vNN/`)
and its `V**Stub` here. One APK can cover several; the cost is size, not a rebuild.

### 3. `ADSP_LIBRARY_PATH` must be set before the delegate is loaded

The FastRPC loader reads it while initialising, so `setenv` after `dlopen` is silently too
late. Handled in `src/main/cpp/qnn_delegate_jni.cpp` — the ordering there is deliberate.

## 88% of this is one removable file

`libQnnHtpPrepare.so` (75.7 MB) exists only to compile the `.tflite` into an HTP graph on the
device at load time. That is what lets one portable `.tflite` serve every HTP version, with no
chip-specific artifact.

The alternative is a **QNN context binary**: the same graph compiled ahead of time. Ship that
instead and this directory drops to ~10 MB, at the cost of the binary being tied to one HTP
architecture. `qnn-context-binary-generator` is built for `aarch64-android` in
`tools/qairt/*/bin/aarch64-android/`, so it can be generated **on the phone** — the SDK has no
macOS host tools at all.

Not worth doing while benchmarking: the model is 990 KB, so runtime compilation is quick.
Revisit before anything ships.

## Packaging setting this depends on

```kotlin
packaging { jniLibs { useLegacyPackaging = true } }
```

The libraries must be extracted to disk rather than left compressed inside the APK. Verify:

```
adb shell ls /data/app/*/com.autobots.camera*/lib/arm64/ | grep -i qnn   # expect 6
```

Side effect worth knowing: this also compresses ML Kit's libraries, which were previously
stored uncompressed — the debug APK went **137 MB → 106 MB** even after adding QNN. Install
size on device goes up instead.

## Related

- Model files, quantisation, and the 640×480 geometry problem:
  [../assets/models/README.md](../assets/models/README.md)
- Why the NPU is being evaluated: [RELEASE_0_1_4.md](../../../../docs/RELEASE_0_1_4.md)
