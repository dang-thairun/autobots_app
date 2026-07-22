# Changelog

Release notes for AutoBots Sports Camera.  
Version source of truth: **`gradle.properties`** → `appVersionName` / `appVersionCode`  
Also sync: `shared/.../AutobotsApp.kt` → `version` (KMP, docs, non-Android).

---

## v0.1 (current)

**Theme:** MVP complete (P0–P8) + first tripod hardening (partial P9/P10) + operator UI polish.

### Shipped

| Area | What |
|------|------|
| **MVP P0–P8** | KMP shell, Operator UI, CameraX preview, ML Kit faces, Arm/AE, Lean Burst, Passage Gate, Write Queue → `DCIM/AutoBots`, Standard/Max-Sensor, thermal + RAM readout |
| **P9b** | Sustained AE lock (no 3 s auto-cancel); tripod path uses **AE-only** on face (`FocusStrategy.Fixed`) |
| **P10a–b** | **Capture Zone Fire** (`PassageFireEvaluator`); Early Arm ~2.5%; min size ~6%; settle ~100 ms; zone dwell 2 frames |
| **Speed** | Burst gap **150 ms** (1080p Standard); faster Arm/AE metering interval |
| **UI** | Compact status chips; **Start \| Gallery** row; face box **% score** on overlay |
| **Debug** | ML Kit `analyze` timing log (1 s summary) — `adb logcat -s MlKitFaceAnalyzer` |
| **Docs** | `BUILD.md`, `SCREEN.md`, `IMPLEMENTATION.md`, `FIELD_SETUP.md`, `PLATFORM_APIS.md`, Flows 13–18 |

### In progress / not in this build

| Slice | Item | Status |
|-------|------|--------|
| **P9a** | Docs + shared contracts fully aligned | 🔄 mostly done; version label updated here |
| **P9c** | **Fixed Focus** — Camera2 lock distance at setup | ⏳ not started (today: AE-only, lens still continuous AF) |
| **P9d** | **EV compensation** slider | ⏳ not started (face AE on Arm works) |
| **P10c** | Capture Zone drawn on observation grid | ⏳ not started |
| **P10d** | Field-tune defaults on site | ⏳ checklist in `FIELD_SETUP.md` only |

### Known gaps

- Status card shows **v0.1**; internal phase milestone remains **P8** until P9 ships.
- `FocusStrategy.Fixed` does not yet set `LENS_FOCUS_DISTANCE` (P9c).
- Fire uses grid math; operator cannot resize zone in UI yet (P10c).

---

## Version bump checklist

1. Edit `gradle.properties` → `appVersionName` / `appVersionCode`
2. Edit `AutobotsApp.version` in `shared/.../AutobotsApp.kt` (same string)
3. Add section to this file
4. Update `docs/DOCS.md` phase table if a phase completed
5. Rebuild: `./gradlew :androidApp:assembleDebug`

**Do not use `.env`** — Android/KMP standard is `gradle.properties` + optional `AutobotsApp` for shared code.

---

## Earlier

Pre-changelog releases were tracked as **Phase P0–P8** only. See [DOCS.md](./DOCS.md) phase table for milestone history.
