# Build & install (Android)

How to build the APK and install on a device via USB.  
After install: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · Field tripod (v0.1 stills): [FIELD_SETUP.md](./FIELD_SETUP.md)

---

## Requirements

| Item | Version / notes |
|------|-----------------|
| **JDK** | 17 (matches `androidApp` `jvmTarget`) |
| **Android device** | API **26+** (Android 8.0+) — see `minSdk` in `androidApp/build.gradle.kts` |
| **adb** | [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) on `PATH` |
| **USB** | Cable + **USB debugging** enabled (Developer options) |

No Play Store or extra signing setup is needed for **debug** builds.

---

## 1. Build debug APK

From the repo root:

```bash
./gradlew :androidApp:assembleDebug
```

Output:

```
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

**App id:** `com.autobots.camera`  
**Version:** `appVersionName` in root `gradle.properties` (currently **0.1.2**). Sync `AutobotsApp.version` in shared. See [CHANGELOG.md](./CHANGELOG.md).

### Install directly (skip manual `adb install`)

```bash
./gradlew :androidApp:installDebug
```

Requires a device in `adb devices`.

### Optional: release APK

```bash
./gradlew :androidApp:assembleRelease
```

Release APK is unsigned unless you add a `signingConfig` — use **debug** for local / field testing unless you have release keys configured.

---

## 2. Connect the device

1. On the phone: **Settings → Developer options → USB debugging** → On.
2. Plug in USB; accept **Allow USB debugging** if prompted.
3. Verify:

```bash
adb devices
```

Expected: one line ending in `device` (not `unauthorized` or `offline`).

### Wireless adb (optional)

```bash
adb pair <ip>:<pairing-port>    # once, Android 11+
adb connect <ip>:5555
adb devices
```

See [SCRCPY.md](./SCRCPY.md) for Wi‑Fi setup details.

### Build + install over Wi‑Fi (specific device)

After `adb connect <ip>:5555` and `adb devices` shows the device:

```bash
./gradlew :androidApp:assembleDebug
adb -s <ip>:5555 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Example:

```bash
adb connect 192.168.1.147:5555
./gradlew :androidApp:assembleDebug
adb -s 192.168.1.147:5555 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Use `-s <serial>` when more than one device is attached. Shortcut: `./gradlew :androidApp:installDebug` works if only one device is listed.

---

## 3. Install

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

| Flag | When |
|------|------|
| `-r` | Replace existing install (upgrade) |
| (none) | First install on a clean device |

### Uninstall (signature conflict or clean reinstall)

```bash
adb uninstall com.autobots.camera
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

---

## 4. First launch

1. Open **AutoBots** on the device.
2. Grant **Camera** when prompted (required for live capture).
3. On Operator screen: expand **Video pipeline** → choose **Face/Pose** and **1080p/4K**.
4. Tap **Start** to record video chunks, or **Import** to process an existing video file.
5. JPEGs appear in **`DCIM/AutoBots/{subfolder}/`** after offline extract completes.
6. Session log: **`Download/AutoBots/{subfolder}/session_log.txt`** (Android 10+).

Operator workflow: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)

---

## 5. Helper scripts (repo root)

### `install_with_log.sh`

Build, install, launch app, and capture logcat to `crash.log`:

```bash
./install_with_log.sh
```

Reproduce the issue, then **Ctrl+C** — script greps fatal lines from `crash.log`.

### `sync_gallery.sh`

Pull JPEGs and session logs from the device to Mac (incremental):

```bash
./sync_gallery.sh
# or custom destination:
./sync_gallery.sh ~/Downloads/my-export
```

Sources: `DCIM/AutoBots/`, `Download/AutoBots/`, app cache mirrors.

### Doc drift check

```bash
./scripts/check_docs_drift.sh
```

See [CONVENTIONS.md](./CONVENTIONS.md) §7 — catches stale patterns (e.g. old chunk sizes) in docs and UI tooltips.

---

## 6. Logs (debug)

Plan B pipeline:

```bash
adb logcat -s CapturePipeline VideoFrameProcessor VideoChunkRecorder ImportedVideoSplitter
```

Broader app filter:

```bash
adb logcat | grep -E 'CapturePipeline|VideoFrame|VideoChunk|OperatorViewModel|LocalDelivery'
```

Crash / fatal:

```bash
adb logcat -d -v time | grep -E 'com\.autobots\.camera|AndroidRuntime|FATAL' | tail -100
```

Legacy v0.1 stills path (if debugging old code):

```bash
adb logcat -s MlKitFaceAnalyzer
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `adb: command not found` | Install platform-tools; add to `PATH` |
| `unauthorized` | Unplug → replug → tap **Allow** on phone |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Debug vs release signature mismatch → `adb uninstall com.autobots.camera` then reinstall |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Use `adb install -r` or uninstall first |
| Gradle / JDK errors | Use **JDK 17**; run `./gradlew :androidApp:assembleDebug` from repo root |
| Camera black / permission denied | Settings → Apps → AutoBots → Permissions → Camera → Allow |
| Gallery empty | Wait for processing to finish; check chip **K** > 0; run `./sync_gallery.sh` to verify on device |

---

## One-liner (build + install)

```bash
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

---

## Related

- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Platform APIs: [PLATFORM_APIS.md](./PLATFORM_APIS.md)
- Doc index: [DOCS.md](./DOCS.md)
- Mirror UI on Mac: [SCRCPY.md](./SCRCPY.md)
