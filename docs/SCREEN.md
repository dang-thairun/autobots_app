# Operator screen layout

UI structure for the main Operator shell.  
Code: `androidApp/.../ui/OperatorShellScreen.kt`, `CameraPreviewPane.kt`

---

## Two layers

The screen stacks **camera preview** (fixed) under **swipeable overlay pages**.

```
┌─────────────────────────────────────────┐
│  LAYER 2 — HorizontalPager (swipe ↔)   │
│  ┌───────────────────────────────────┐  │
│  │ Page 0 / 1 / 2 (see below)        │  │
│  │                                   │  │
│  │         (may be transparent)      │  │
│  └───────────────────────────────────┘  │
│              ● ○ ○   page dots          │
├─────────────────────────────────────────┤
│  LAYER 1 — CameraPreviewPane (fixed)  │
│  ┌───────────────────────────────────┐  │
│  │  CameraX PreviewView (live)       │  │
│  │  + FaceOverlay (boxes + % score)  │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

When capture is **stopped**, Layer 1 shows a dark **“Stopped”** scrim.

---

## Pager pages (swipe left / right)

| Index | Name | What you see |
|-------|------|----------------|
| **0** | Controls | Status card (top) + settings + buttons (bottom) |
| **1** | Clean preview | Fully transparent — preview only, **no** face boxes |
| **2** | Observation | 9×11 AF grid + small info card (top) |

```
     Page 0              Page 1              Page 2
  (Controls)         (Clean preview)      (Observation)

┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ ┌──────────┐ │   │              │   │ ┌──────────┐ │
│ │ Status   │ │   │   preview    │   │ │ 9×11 grid│ │
│ │ card     │ │   │   only       │   │ │ info card│ │
│ └──────────┘ │   │              │   │ └──────────┘ │
│              │   │              │   │  · · · · ·   │
│   preview    │   │              │   │  · ·██· · ·  │
│   shows      │   │  (no face    │   │  · · · · ·   │
│   through    │   │   overlay)   │   │  (grid over  │
│              │   │              │   │   preview)   │
│ ┌──────────┐ │   │              │   │              │
│ │ Settings │ │   │              │   │              │
│ │ Start/   │ │   │              │   │              │
│ │ Gallery  │ │   │              │   │              │
│ └──────────┘ │   │              │   │              │
│    ● ○ ○     │   │    ○ ● ○     │   │    ○ ○ ●     │
└──────────────┘   └──────────────┘   └──────────────┘
```

---

## Page 0 — Controls (detail)

```
┌─────────────────────────────────────────┐
│ ┌─ Compact status (chips) ─────────────┐ │
│ │ AutoBots v0.1            IP x.x.x.x  │ │
│ │ [F][Px][Arm][Fir][Gate][Zn]          │ │
│ │ [K][Th][RAM]                         │ │
│ │ 4.2mm · 1/500 · ISO 200              │ │
│ └──────────────────────────────────────┘ │
│                                         │
│            (more preview space)         │
│                                         │
│ ┌─ Capture settings (collapsible) ────┐ │
│ │ Capture settings          [Show/Hide]│ │
│ │ Standard · Arm N% · Min N% · Zone IN │ │  ← collapsed summary
│ │ ── when expanded: ─────────────────  │ │
│ │ Arm (Face Lock)  [slider]            │ │
│ │ Min size (Fire)  [slider]            │ │
│ │ [ Standard ]  [ Max-Sensor ]         │ │
│ │   1920×1080      max res label       │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────┐ ┌──────────────┐        │
│ │    Start     │ │ Gallery (N)  │        │
│ └──────────────┘ └──────────────┘        │
│              ● ○ ○                      │
└─────────────────────────────────────────┘
```

---

## Face overlay (Layer 1, pages 0 & 2)

Drawn on top of preview when capture is **active** and page ≠ Clean preview.

```
        ┌──8%──┐
        │      │   ← score = face area % of frame (top-left tag)
        │      │
        └──────┘   green stroke = Subject Face (largest)
        
   ┌──5%──┐
   │      │       yellow stroke = other faces
   └──────┘
```

---

## Page 2 — Observation grid

```
┌─────────────────────────────────────────┐
│        ┌─────────────────────┐          │
│        │ 9×11 AF points (visual)│          │
│        │ 4.2mm · 1/500 · ISO 200        │
│        │ Active near Subject / Idle     │
│        └─────────────────────┘          │
│   ┌─┬─┬─┬─┬─┬─┬─┬─┬─┐                   │
│   │ │ │ │ │ │ │ │ │ │  11 rows          │
│   ├─┼─┼─┼─┼─┼─┼─┼─┼─┤  9 columns        │
│   │ │ │ │ │█│ │ │ │ │  █ = cells near   │
│   │ │ │ │ │█│ │ │ │ │      Subject when │
│   └─┴─┴─┴─┴─┴─┴─┴─┴─┘      armed        │
│              ○ ○ ●                      │
└─────────────────────────────────────────┘
```

Grid is **visual only** (not hardware PDAF). Capture Zone Fire uses the same grid math in `CaptureZone` (see [architecture.md](./architecture.md) Flow 17).

---

## Component map

| UI piece | Composable / file | Role |
|----------|-------------------|------|
| Shell + pager | `OperatorShellScreen` | Layers, swipe pages |
| Live camera | `CameraPreviewPane` | PreviewView + controller bind |
| Face boxes + % | `FaceOverlay` | Detection overlay |
| AF grid | `AfGridOverlay` | Observation page |
| Status chips | `CompactStatusCard` | F, Px, Arm, Fir, Gate, Zn, K, Th, RAM |
| Sliders + mode | `CaptureSettingsCard` | Arm, min size, Standard/Max-Sensor |
| State | `OperatorViewModel` | Passage gate, fire logic, counts |

---

## Primary actions (MVP)

| Control | Action |
|---------|--------|
| **Start / Stop Capture** | Bind / unbind camera; run face pipeline |
| **Open Gallery** | View last or any kept photo in system gallery |
| **Capture settings** | Expand for Arm / Min size sliders and capture mode |
| **Swipe pager** | Switch Controls ↔ Clean preview ↔ Observation |

No manual shutter button in MVP — burst fires automatically on zone + thresholds.

---

## Related

- Field placement: [FIELD_SETUP.md](./FIELD_SETUP.md)
- Pipeline logic: [architecture.md](./architecture.md)
- Build & install: [BUILD.md](./BUILD.md)
