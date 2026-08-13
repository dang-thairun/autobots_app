# AutoBots Android Camera

Edge-AI sports camera on a tripod-mounted Android phone.  
**Current build (v0.1.2):** record or import video → offline Face/Pose extract → save JPEGs to gallery.

## Quick start

```bash
./gradlew :androidApp:installDebug
```

## Helper scripts

| Script | Purpose |
|--------|---------|
| `./install_with_log.sh` | Install debug APK + capture logcat to `crash.log` |
| `./sync_gallery.sh` | Pull JPEGs + `session_log.txt` from device → Mac |
| `./scripts/check_docs_drift.sh` | Guard against stale doc/UI strings (see [CONVENTIONS.md](docs/CONVENTIONS.md)) |

## Documentation

Start at **[docs/DOCS.md](docs/DOCS.md)** — operator guide, pipeline reference, build instructions.

| Doc | Purpose |
|-----|---------|
| [OPERATOR_FLOW.md](docs/OPERATOR_FLOW.md) | How to use the app in the field |
| [PIPELINE_FLOW.md](docs/PIPELINE_FLOW.md) | Technical pipeline (workers, thresholds, storage) |
| [BUILD.md](docs/BUILD.md) | Build, install, logs, wireless adb |

## Stack

Kotlin Multiplatform (`shared/`) + Android app (`androidApp/`) · CameraX · ML Kit · Jetpack Compose
