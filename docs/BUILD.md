# Build & install (Android)

How to build the APK and install on a device via USB.  
Field use after install: [FIELD_SETUP.md](./FIELD_SETUP.md)

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
**Version:** `appVersionName` in root `gradle.properties` (e.g. `0.1`). Sync `AutobotsApp.version` in shared. See [CHANGELOG.md](./CHANGELOG.md).

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
2. Grant **Camera** when prompted (required).
3. For field timing: **Start** capture on the Operator screen.
4. Photos save to **`DCIM/AutoBots`** after a successful burst.

Tripod and zone setup: [FIELD_SETUP.md](./FIELD_SETUP.md).

---

## 5. Logs (debug)

Face detector timing (1 s summary):

```bash
adb logcat -s MlKitFaceAnalyzer
```

Broader app logs:

```bash
adb logcat | grep -E 'PreviewCamera|LeanBurst|OperatorViewModel|MlKitFaceAnalyzer'
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

---

## One-liner (build + install)

```bash
./gradlew :androidApp:assembleDebug && adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

---

## Related

- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Doc index: [DOCS.md](./DOCS.md)
