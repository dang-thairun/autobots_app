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

## 0. Upload backend defaults — `.env` (optional)

The app can be built with a backend already configured. Copy the template and fill in what
you have:

```bash
cp .env.example .env
```

| Key | Meaning |
|-----|---------|
| `UPLOAD_GRAPHQL_URL` | `https://api.<host>/graphql` — the `photoUpload` mutation |
| `UPLOAD_COMPLETE_URL` | `https://upload.<host>/success` — the completion call (different host, form-encoded) |
| `UPLOAD_PLATFORM` | `x-runx-platform` header value, e.g. `thai` |
| `UPLOAD_EVENT_ID` | which event the photos belong to — usually left empty, see below |
| `UPLOAD_TOKEN` | optional; normally empty, see below |

**Any key left empty just means the operator fills it in** on the app's Upload settings
screen — an empty `.env`, or no `.env` at all, is a perfectly normal build.

**The last two keys are usually empty.** The operator signs in on the Upload settings screen
with a username and password, and picks the event from a dropdown the backend fills in; that
is where `UPLOAD_TOKEN` and `UPLOAD_EVENT_ID` normally come from. The token is held **in
memory only** — it is never written to the device, so signing in is needed once per app
start. A token set in `.env` skips that for this build and is still not persisted.

The values are read by Gradle at configure time and baked into `BuildConfig`, then applied to
the app's settings on launch under two different rules:

- **`UPLOAD_GRAPHQL_URL` and `UPLOAD_COMPLETE_URL` win from the build on every launch.** A URL
  in `.env` decides which backend this APK talks to; a stale value typed on a phone weeks ago
  should not outrank it. Editing those two fields on a build that sets them therefore lasts
  only until the next launch.
- **`UPLOAD_PLATFORM` and `UPLOAD_EVENT_ID` are seeded on first run only**, so a correction the
  operator makes is never silently reverted. *Clear configuration* puts the build's defaults
  back on the next launch.

A key left empty in `.env` changes nothing under either rule.

> ⚠️ **`.env` is gitignored and must stay that way** — `UPLOAD_TOKEN` is an admin credential.
> It is also **readable inside any APK built with it**, so leave the token blank for builds
> you do not control end to end and let the operator sign in instead (see `docs/PHASES.md` B3e).

Changing `.env` requires a rebuild; it is a build input, not a runtime file.

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

## 6b. Diagnosing a run that died (v0.1.5+)

A session writes `perf_report.json` only at drain, so a crash used to leave nothing. Three
artifacts now cover it — read them in this order.

**1. Why the process died.** The platform's own record, and the only source that sees a
native crash or a low-memory kill. Works with no app code involved:

```bash
adb shell dumpsys activity exit-info com.autobots.camera
```

The app reads the same thing at startup and writes it to `cache/autobots/diag/last_exit.json`.
`reason` of `CRASH_NATIVE` or `LOW_MEMORY` means no Kotlin handler ran and there is **no**
`crash.txt` — that is expected, not a second bug.

**2. What the pipeline was doing.** `perf_stream.jsonl` in the session's cache directory, one
JSON record per line, flushed as it goes:

```bash
adb shell run-as com.autobots.camera ls cache/autobots
adb shell run-as com.autobots.camera cat cache/autobots/<sessionId>/perf_stream.jsonl > stream.jsonl
tail -3 stream.jsonl | python3 -m json.tool   # last line may be truncated; that is normal
```

**3. The report itself.** Relaunching the app rebuilds one for any session that has a stream
but no report, and publishes it:

```bash
adb logcat -d -s SessionRecovery PerfRecovery CrashDiagnostics
adb shell ls /sdcard/Download/AutoBots/ | grep recovered_
```

A recovered report carries `session.recovered = true` and is otherwise the same shape as a
normal one.

### Verifying the telemetry itself

After any capture, check the new schema-5 blocks are populated:

```bash
S=$(adb shell ls /sdcard/Download/AutoBots/ | tail -1 | tr -d '\r')
adb pull /sdcard/Download/AutoBots/$S/perf_report.json
python3 -c "
import json; r=json.load(open('perf_report.json'))
print('schema', r['schema'], '| probes', r['env'].get('probes'))
for k in ('cpu','memory','thermal','power'): print(k, json.dumps(r['totals'][k])[:200])
"
```

`totals.power.energyMeasured` will be `false` with a charging note on any run done with the
cable in — that is correct behaviour, not a missing figure. **Energy requires an unplugged
run.** `env.probes` says whether this phone reports SoC temperature and GPU busy at all;
both are absent on many builds and that is recorded rather than silently zero.

---

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
