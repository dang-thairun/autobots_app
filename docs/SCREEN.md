# Operator screen layout

UI structure for the main Operator shell (Plan B — video chunk → offline extract).  
Code: `androidApp/.../ui/OperatorShellScreen.kt`, `CameraPreviewPane.kt`, `ChunkHistoryPage.kt`

---

## Two layers

The screen stacks **camera preview** (fixed) under **swipeable overlay pages**.

```
┌─────────────────────────────────────────┐
│  LAYER 2 — HorizontalPager (swipe ↔)   │
│  ┌───────────────────────────────────┐  │
│  │ Page 0 / 1 / 2 (see below)        │  │
│  │         (semi-transparent cards)   │  │
│  └───────────────────────────────────┘  │
│              ● ○ ○   page dots          │
├─────────────────────────────────────────┤
│  LAYER 1 — CameraPreviewPane (fixed)    │
│  ┌───────────────────────────────────┐  │
│  │  CameraX PreviewView              │  │
│  │  + VideoChunkRecorder (live only) │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**Layer 1 scrim** (เมื่อไม่ได้ live preview):

| สถานะ | ข้อความ |
|--------|---------|
| กำลัง capture | ไม่มี scrim — เห็น preview สด |
| หยุด capture แต่ยัง process queue | **Processing chunks…** |
| หยุดทั้งหมด | **Stopped** |

> แอปล็อค **portrait only** (`screenOrientation=portrait`)

---

## Pager pages (swipe left / right)

| Index | Name | What you see |
|-------|------|----------------|
| **0** | Controls | Status card (top) + extraction card + buttons (bottom) |
| **1** | Clean preview | Fully transparent — preview only |
| **2** | Session history | รายการ session + chunk (`ChunkHistoryPage`) |

```
     Page 0              Page 1              Page 2
  (Controls)         (Clean preview)      (Session history)

┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ ┌──────────┐ │   │              │   │ Session      │
│ │ Status   │ │   │   preview    │   │ history      │
│ │ chips    │ │   │   only       │   │ ┌──────────┐ │
│ └──────────┘ │   │              │   │ │ session  │ │
│              │   │  (no overlay)│   │ │ card     │ │
│   preview    │   │              │   │ └──────────┘ │
│   shows      │   │              │   │ ┌──────────┐ │
│   through    │   │              │   │ │ chunk    │ │
│              │   │              │   │ │ cards    │ │
│ ┌──────────┐ │   │              │   │ └──────────┘ │
│ │ Face     │ │   │              │   │              │
│ │ extract  │ │   │              │   │              │
│ │ card     │ │   │              │   │              │
│ ┌──────────┐ │   │              │   │              │
│ │Start│Imp│Gal│   │              │   │              │
│ └──────────┘ │   │              │   │              │
│    ● ○ ○     │   │    ○ ● ○     │   │    ○ ○ ●     │
└──────────────┘   └──────────────┘   └──────────────┘
```

---

## Page 0 — Controls (detail)

```
┌─────────────────────────────────────────┐
│ ┌─ Compact status ─────────────────────┐ │
│ │ AutoBots v0.1.2 · IDLE   1080p·Face·IP │ │
│ │ REC #3 · 12 MB / 50 MB · 28s  [bar]  │ │  ← เมื่อกำลัง capture
│ │ [Ch][VQ][Fc][K][Th][Disk]            │ │
│ │ RAM 4.2G/11G (free 3.1G)             │ │
│ │ Video pipeline · Show/Hide           │ │
│ │ ── expanded ─────────────────────    │ │
│ │ Record chunks → extract…             │ │
│ │ [ Face ] [ Pose ]                    │ │
│ │ [ 1080p ] [ 4K ]                     │ │
│ └──────────────────────────────────────┘ │
│                                         │
│            (preview space)              │
│                                         │
│ ┌─ Face / Pose extraction ───────────┐ │
│ │ Face extraction                    │ │
│ │ Processing chunk_003 · 3/5 chunks  │ │
│ │ 1.02x realtime · photo in ~2.1s    │ │  ← live only
│ │ [━━━━━━━━━━━━░░░░] progress        │ │
│ │ 60% overall · faces found 12       │ │
│ └──────────────────────────────────────┘ │
│ ┌────────┐ ┌────────┐ ┌────────────┐  │
│ │ Start/ │ │ Import │ │ Gallery(N) │  │
│ │ Stop   │ │        │ │            │  │
│ └────────┘ └────────┘ └────────────┘  │
│              ● ○ ○                      │
└─────────────────────────────────────────┘
```

### Status chips (`CompactStatusCard`)

| Chip | ความหมาย |
|------|----------|
| **Ch** | จำนวน video chunk ที่อัดเสร็จ |
| **VQ** | Video queue — chunk รอ Worker 2 |
| **Fc** / **Ps** | จำนวนเฟรมที่ผ่าน filter (Face / Pose) |
| **K** | รูปที่ส่งเข้า Gallery แล้ว |
| **Th** | Thermal |
| **Disk** | พื้นที่ว่าง (MB) |

แตะ chip เพื่อดู tooltip สั้นๆ ด้านล่าง

### Extraction card (`ProcessingStatusCard`)

| สถานะ | บรรทัดหลัก |
|--------|------------|
| Idle | `No processing` |
| Import | `Importing {name} · splitting N%` |
| Processing | `Processing {chunk} · X/Y chunks · scan N%` |
| Done (idle) | `Idle · faces found N` |

- Progress bar: import % หรือ overall processing %
- **Throughput line** (`Nx realtime`) แสดงเฉพาะ **live capture** — ซ่อนเมื่อมี import session ใน history
- ไม่แสดง `VQ` ซ้ำใน processing line (มีใน chip แล้ว)

### ปุ่มหลัก

| ปุ่ม | สถานะ | การทำงาน |
|------|--------|----------|
| **Start** | idle + permission | เริ่ม live capture |
| **Stop** | กำลัง capture | หยุดอัด (process ต่อจน queue หมด) |
| **Processing…** | หลัง stop แต่ยัง process | disabled label |
| **Allow & Start** | ไม่มี camera permission | ขอ permission |
| **Import** | pipeline ว่าง | เปิด file picker (`OpenDocument`) |
| **Importing…** | กำลัง split | disabled |
| **Gallery (N)** | มีรูปใน gallery | เปิดรูปล่าสุดในแอป Gallery |

### Video pipeline (expand)

- **Face / Pose** — `ExtractionTarget` (เปลี่ยนได้ก่อน Start/Import เท่านั้น)
- **1080p / 4K** — `StreamResolution` สำหรับ live capture  
  (import ใช้ auto-detect จากไฟล์ ไม่อิงค่า UI)

---

## Page 1 — Clean preview

- `Box` โปร่งใสเต็มจอ — เห็น preview ล้วนๆ ไม่มี card บัง
- ไม่มี face overlay / AF grid ใน shell ปัจจุบัน

---

## Page 2 — Session history (`ChunkHistoryPage`)

แสดง `PipelineSessionRecord` เรียงจาก session ล่าสุด

### Session card

```
┌─ aa11.mp4 ─────────────── Done ─┐
│ Import · 4K · 3840×2160 · Face   │
│ Video 5:30 · 1.2 GB              │
│ 8 chunks · 12 faces · 3m 45s     │
│ Found 12 from 2500 frames (0%)   │
│   · avg 31ms/frame · sample 120ms│
│ Gallery: DCIM/AutoBots/ext_…     │
│ [Show chunks ▼]                  │
│   Chunk #4 · 45.123 s            │
│   Found 3 from 375 frames …      │
└──────────────────────────────────┘
```

| รายการ | หมายเหตุ |
|--------|----------|
| Import sessions | แสดงเฉพาะ chunk ที่ `facesKept > 0` เมื่อ expand |
| Live sessions | แสดงทุก chunk |
| Chunk expand | รายชื่อ JPEG + ขนาดไฟล์ |

---

## Component map

| UI piece | Composable / file | Role |
|----------|-------------------|------|
| Shell + pager | `OperatorShellScreen` | 3 overlay pages + dots |
| Live camera + record | `CameraPreviewPane` | PreviewView, `VideoChunkRecorder` bind |
| Status + pipeline settings | `CompactStatusCard` | Chips, recording line, Face/Pose, 1080p/4K |
| Extraction progress | `ProcessingStatusCard` | Import/process progress, throughput |
| Session list | `ChunkHistoryPage` | Session + chunk history |
| State | `OperatorViewModel` | Pipeline stats, import, session history |
| Coordinator | `CapturePipelineCoordinator` | Workers, gallery delivery |

**ไม่ได้ใช้ใน shell ปัจจุบัน** (ยังมีไฟล์ใน repo): `FaceOverlay.kt`, `AfGridOverlay.kt` — จาก MVP burst/zone รุ่นเก่า

---

## Primary actions

| Control | Action |
|---------|--------|
| **Start / Stop** | Live capture — อัด MP4 chunks → extract → Gallery |
| **Import** | เลือกวิดีโอจากเครื่อง → split → pipeline เดียวกับ live |
| **Gallery** | เปิดรูปล่าสุดใน system gallery |
| **Video pipeline · Show** | Face/Pose + 1080p/4K |
| **Swipe pager** | Controls ↔ Clean preview ↔ Session history |

ไม่มี manual shutter — live mode อัดวิดีโอต่อเนื่อง; รูปมาจาก offline extract หลัง chunk เสร็จ

---

## Remote / HTTP

- Status card แสดง `IP host:8080` (`AutobotsServer`)
- Remote start/stop + status ผ่าน WebSocket — **ไม่มี screen mirror** (ใช้ scrcpy ดู UI แยก)

---

## Related

- Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Operator workflow: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Field placement: [FIELD_SETUP.md](./FIELD_SETUP.md)
- Build & install: [BUILD.md](./BUILD.md)
- ดึงรูป/log กลับ Mac: `sync_gallery.sh` (repo root)
