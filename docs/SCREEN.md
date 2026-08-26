# Operator screen layout

UI structure for the main Operator shell (Plan B — video chunk → offline extract).  
Code: `androidApp/.../ui/OperatorShellScreen.kt`, `CameraPreviewPane.kt`, `ChunkHistoryPage.kt`

---

## Home menu — the shell (v0.1.5)

Up to v0.1.4 the shell was a **3-page `HorizontalPager`** stacked over a permanently bound camera
preview. v0.1.5 replaced it with a **Home menu** and one destination at a time. The camera is now
bound **only on the Live capture page**, which is the reason the change was worth making: a shell
that keeps the preview alive holds the camera open on every screen, including the ones that have
nothing to do with capture.

```
                     ┌──────────────────┐
                     │       Home       │
                     └────────┬─────────┘
        ┌─────────────────────┼─────────────────────┐
        │  CAPTURE            │            REVIEW   │
   ┌────┴────┐ ┌────────┐ ┌───┴────┐   ┌────────┐ ┌─┴──────┐ ┌────────┐
   │ ▶ Live  │ │📁Browse│ │🔗Network│   │🕘History│ │🖼Gallery│ │☁ Upload│
   └─────────┘ └────┬───┘ └────────┘   └────────┘ └────────┘ └───┬────┘
                    │                                            │
              ┌─────┴──────┐                              ┌───────┴──────┐
              │ImportPreview│──── Edit zone ──▶│ZoneEditor│ │UploadSettings│
              └────────────┘                              └──────────────┘
```

**`OperatorDestination`** (source of truth: `OperatorShellScreen.kt`):
`Home` · `LiveCapture` · `SessionHistory` · `ImportPreview` · `ZoneEditor` · `NetworkUrl` ·
`UploadQueue` · `UploadSettings`

Six tiles, two captioned groups — because the destinations answer two different questions:
**what should the phone ingest next**, and **what has it already produced**.

| Group | Tile | Goes to | Badge |
|-------|------|---------|-------|
| CAPTURE | ▶ **Live** | `LiveCapture` — the only page that binds the camera | — |
| CAPTURE | 📁 **Browse** | system file picker → `ImportPreview` | — |
| CAPTURE | 🔗 **Network** | `NetworkUrl` — stream a clip by URL or QR | — |
| REVIEW | 🕘 **History** | `SessionHistory` | number of sessions |
| REVIEW | 🖼 **Gallery** | system gallery at `DCIM/AutoBots` | kept photo count |
| REVIEW | ☁ **Upload** | `UploadQueue` | **outstanding rows only** |

The upload badge deliberately counts only what is still owed — a queue holding 5,000 already-uploaded
photos is not news, and a badge that shows it trains the operator to ignore the badge.

**Disabled while the pipeline is busy** (`isImporting || isProcessing || isDownloading`):
Live, Browse, Network, Gallery. History and Upload stay reachable — they are read-only and are exactly
what an operator wants to look at while waiting.

**Back button** returns to Home from anywhere. Leaving `LiveCapture` while recording **stops the
recording first** — a session must never keep rolling on a screen that no longer shows it.

---

## Live capture page

Two layers: camera preview underneath, status and controls on top.

```
┌─────────────────────────────────────────┐
│  ← Back                                 │
│  ┌───────────────────────────────────┐  │
│  │ Status chips                      │  │
│  │ Ch · VQ · K · realtime · thermal  │  │
│  └───────────────────────────────────┘  │
│                                         │
│         CameraX PreviewView             │
│         + Capture Zone overlay          │
│         + VideoChunkRecorder            │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │ Resolution · Detectors · Backend  │  │
│  │ Shutter ceiling · EV              │  │
│  └───────────────────────────────────┘  │
│            [ Start / Stop ]             │
└─────────────────────────────────────────┘
```

**Preview binds on entering the page, not on Start** (v0.1.6). Aiming the tripod and drawing the
capture zone no longer requires recording first.

| State | Scrim |
|-------|-------|
| Recording | none — live preview |
| Stopped, queue still draining | **Processing chunks…** |
| Fully stopped | **Stopped** |

Settings are **IDLE-only** — resolution, detectors and backend cannot change mid-session, because a
session log that describes two different configurations describes neither.

> Portrait only (`screenOrientation=portrait`).

---

## Other destinations

| Page | Code | What it is for |
|------|------|----------------|
| **Import preview** | `ImportPreviewPage.kt` | Inspect a clip before spending time on it: size, resolution, length, fps, estimated extract time. Choose detectors + backend, **trim a range**, then Extract. Replaced fire-and-forget importing in v0.1.5 |
| **Network URL** | `NetworkUrlPage.kt` | Third ingest path — type a URL or scan a QR. Streams by byte-range; downloads whole only when the CDN refuses `MediaHTTPConnection` |
| **Zone editor** | `ZoneEditorPage.kt` | Draw the capture zone **on the real image** rather than on a grid abstraction. Back discards the edit |
| **Session history** | `ChunkHistoryPage.kt` | One card per session: kept photos, people counts, realtime ratio, expandable per-chunk detail |
| **Upload queue** | `UploadQueuePage.kt` | Per-row state, retry, pause. Six states — see [SEQUENCE_FLOW.md §2](./SEQUENCE_FLOW.md) |
| **Upload settings** | `UploadSettingsPage.kt`, `QrScanPreview.kt` | Sign in, pick the event from a list, provision endpoint + token by QR |

---

## Related

- Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Operator workflow: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Field placement: [FIELD_SETUP.md](./FIELD_SETUP.md)
- Build & install: [BUILD.md](./BUILD.md)
- ดึงรูป/log กลับ Mac: `sync_gallery.sh` (repo root)
