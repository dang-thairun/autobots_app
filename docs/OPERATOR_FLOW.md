# AutoBots — Operator Flow (v0.1.2)

เอกสารนี้อธิบาย **flow การใช้งานจริง** ของ build ปัจจุบัน (Plan B: video chunk pipeline)  
Sync กับ `AutobotsApp.version` = **0.1.2** · phase **B1**

> เอกสารเก่า (P5 burst / face overlay / Observation grid) ยังอยู่ใน repo แต่ **ไม่ตรงกับ build นี้** — ใช้ไฟล์นี้เป็นหลักสำหรับ operator  
> รายละเอียดเทคนิค: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · Layout UI: [SCREEN.md](./SCREEN.md)

---

## สรุปสั้นๆ

แอพเป็น **กล้องวิดีโอบน tripod** ที่มี **สองทางเข้า**:

| ทางเข้า | ทำอะไร |
|---------|--------|
| **Live** — กด Start | อัดวิดีโอต่อเนื่องเป็น MP4 chunks |
| **Import** — กด Import | เลือกไฟล์วิดีโอจากเครื่อง → แบ่ง chunk แล้วเข้า pipeline เดียวกัน |

ทั้งสองทาง:

1. **แบ่ง/อัด** เป็น chunk ตามขนาดไฟล์ (**50 MB** ทุก resolution)
2. **แยกเฟรม** ทุก **120 ms** → ML Kit offline (Face หรือ Pose) → คัดเฉพาะเฟรมที่ชัด
3. **บันทึกรูป** full-frame JPEG ลง `DCIM/AutoBots/{subfolder}/` + `session_log.txt` ลง `Download/AutoBots/{subfolder}/`

**ไม่มี** live face overlay ขณะอัด · **ไม่มี** burst stills / Passage Gate ใน build นี้

---

## สิ่งที่ใช้งานได้ (v0.1.2)

| ฟีเจอร์ | สถานะ |
|--------|--------|
| เลือก **1080p** / **4K** ก่อน Start (live) | ✅ |
| เลือก **Face** / **Pose** ก่อน Start หรือ Import | ✅ (Pose = experimental) |
| อัดวิดีโอต่อเนื่อง + rotate chunk อัตโนมัติ (50 MB) | ✅ |
| **Import video** จากเครื่อง (`OpenDocument`) | ✅ |
| Stop กลาง chunk → partial chunk ยัง extract ได้ | ✅ |
| Offline extraction (ML Kit + sharpness filter) | ✅ |
| บันทึกรูปลง Gallery (`DCIM/AutoBots/…`) | ✅ |
| `session_log.txt` ลง `Download/AutoBots/…` | ✅ |
| **Session history** (session cards + expand chunks) | ✅ |
| Stat chips (Ch / VQ / Face·Pose / K / Th / Disk) | ✅ |
| Processing status card + throughput (live only) | ✅ |
| Pause record เมื่อ video queue เต็ม | ✅ |
| HTTP server + WebSocket remote control | ✅ |
| Thermal + RAM readout | ✅ |
| ดึงรูป/log กลับ Mac (`sync_gallery.sh`) | ✅ |
| Upload รูปออก cloud / HTTP delivery | ❌ |
| Live face box บน preview | ❌ |

---

## หน้าจอหลัก

พื้นหลัง = **กล้องเต็มจอ** · overlay = **3 หน้า swipe** (จุด indicator ด้านล่าง) · **portrait only**

| หน้า | ชื่อ | ใช้ทำอะไร |
|-----|------|-----------|
| **0** | Controls | สถานะ, ตั้งค่า, Start/Stop, **Import**, Gallery |
| **1** | Clean Preview | ดู preview เต็มจอ ไม่มี UI บัง |
| **2** | Session history | รายการ session + chunk (`ChunkHistoryPage`) |

เมื่อ **ไม่อัด** → preview มืด แสดง `Stopped` หรือ `Processing chunks…`

รายละเอียด layout: [SCREEN.md](./SCREEN.md)

---

## ก่อน Start หรือ Import

### 1. เลือก extraction target + resolution

การ์ดบน → **Video pipeline · Show**

| ตั้งค่า | รายละเอียด |
|---------|------------|
| **Face / Pose** | `ExtractionTarget` — เปลี่ยนได้เฉพาะตอน **IDLE** |
| **1080p / 4K** | ใช้กับ **live capture** เท่านั้น — import auto-detect จากไฟล์ |

| โหมด | Chunk rotate | Sample ตอน extract | Sharpness threshold |
|------|--------------|-------------------|---------------------|
| **1080p** | **50 MB** | ทุก **120 ms** | ≥ 80 |
| **4K** | **50 MB** | ทุก **120 ms** | ≥ 65 (compensate ISP NR) |

> 4K สลับ chunk **บ่อยกว่า** 1080p เพราะ bitrate สูงกว่า — เป้าคือขนาดไฟล์ ไม่ใช่เวลา

### 2. เช็คพื้นที่ว่าง (live เท่านั้น)

- Chip **Disk** แสดง MB ว่าง
- ต้องมี ≥ **2 GB** free ถึงจะ Start ได้
- ถ้าไม่พอ → `Storage low — need 2 GB free to record`

### 3. Camera permission (live เท่านั้น)

- ครั้งแรกกด **Allow & Start** → ขอ permission แล้ว Start อัตโนมัติ

---

## กด Start → อัดวิดีโอ (Live)

```
Start
  → สร้าง session ใหม่ (reset counter / session history ใน UI)
  → เปิด Preview + VideoCapture
  → อัด chunk_001.mp4, chunk_002.mp4, … ต่อเนื่อง
  → โฟลเดอร์ gallery: DCIM/AutoBots/yyyyMMdd_HHmmss/
```

### สิ่งที่เห็นขณะอัด

- บรรทัด **REC #N · ขนาด / 50 MB · วินาที · ~KB/s**
- Progress bar แดง (ความคืบหน้าต่อ chunk ปัจจุบัน)
- Chips อัปเดตสด: **Ch**, **VQ**, **Face/Pose**, **K**, **Th**, **Disk**
- การ์ด extraction: throughput `Nx realtime · photo in ~Xs` (live เท่านั้น)

### Rotate chunk (อัตโนมัติ)

```
chunk_001.mp4 ครบ 50 MB
  → finalize ไฟล์
  → ส่งเข้า extraction queue
  → เริ่ม chunk_002.mp4 ทันที (กล้องไม่หยุด)
```

### Pause อัตโนมัติ

- Video queue รอ extract ได้สูงสุด **8 chunk**
- ถ้าเต็ม → `PAUSED · queue full — waiting to resume`
- เมื่อ worker ทัน → อัดต่อเอง

---

## กด Import → ประมวลผลวิดีโอจากเครื่อง

```
Import
  → เปิด file picker (OpenDocument)
  → ImportedVideoSplitter remux แบ่ง chunk 50 MB (ไม่ re-encode)
  → แต่ละ chunk เข้า videoQueue → Worker 2 เหมือน live
  → โฟลเดอร์ gallery: DCIM/AutoBots/ext_DDMMYYYY_HHMM/
```

| รายการ | รายละเอียด |
|--------|------------|
| ใช้ได้เมื่อ | pipeline ว่าง (ไม่ capture / ไม่ processing / ไม่ import อยู่) |
| Resolution | auto จากไฟล์ (`long edge ≥ 2160` → 4K profile) |
| UI ขณะ split | `Importing {name} · splitting N%` + progress bar |
| Throughput line | **ซ่อน** — ไม่ใช่ live capture |

---

## กด Stop (live)

```
Stop
  → หยุดอัดทันที
  → finalize chunk ปัจจุบัน (แม้ไม่ครบ 50 MB = partial)
  → preview มืดลง
  → extraction + gallery delivery ทำงานต่อจน queue หมด
  → เขียน session_log.txt เมื่อ drain เสร็จ
```

| พฤติกรรม | รายละเอียด |
|----------|-------------|
| Stop ≠ ยกเลิก processing | Worker 2/3 ทำงานต่อ |
| Partial chunk | ขึ้นใน history พร้อม `(partial)` |
| ปุ่ม Start | เป็น `Processing…` จนกว่า pipeline จะ drain เสร็จ |

**ตัวอย่าง:** อัดครบ 50 MB → Chunk #1 → อัดต่อ → Stop ที่ 6/50 MB → Chunk #2 (partial) → **รวม 2 chunks**

---

## Extraction (หลัง chunk พร้อม)

การ์ด **Face / Pose extraction** (ล่างหน้า Controls):

```
Processing chunk_001 · 1/2 chunks · scan 72%
```

### ขั้นตอนภายใน (ต่อ 1 chunk)

```
MP4 chunk
  → MediaCodec decode เฟรมทุก 120 ms
  → scale กว้าง 640px
  → ML Kit Face (FAST) หรือ Pose (SINGLE_IMAGE)
  → กรองขนาด + Laplacian sharpness (FHD ≥80, UHD ≥65)
  → dedup 1 รูป / วินาที (เลือกเฟรมคมที่สุด)
  → save JPEG full frame → cache/.../faces/
  → WriteQueue → DCIM/AutoBots/{subfolder}/
```

| Target | เงื่อนไขผ่าน |
|--------|-------------|
| **Face** | หน้าสูง ≥ 5% ความสูงเฟรม |
| **Pose** | ลำตัวสูง ≥ 25% (ไหล่+สะโพกครบ 4 จุด) |

### Stat chips ที่เกี่ยวข้อง

| Chip | ความหมาย |
|------|----------|
| **Ch** | จำนวน chunk ที่ finalize แล้ว |
| **VQ** | chunk รออยู่ใน queue extract |
| **Face** / **Pose** | เฟรมที่ผ่าน filter ทั้ง session |
| **K** | รูปที่ส่งเข้า Gallery แล้ว |

---

## Session history (หน้า 2)

Swipe ไปหน้าที่ 3 (จุดสุดท้าย) — แสดง **session cards** เรียงจากล่าสุด

### Session card

```
aa11.mp4 — Done
Import · 4K · 3840×2160 · Face
Video 5:30 · 1.2 GB
8 chunks · 12 faces · 3m 45s
Found 12 from 2500 frames (0%) · avg 31ms/frame · sample 120ms
Gallery: DCIM/AutoBots/ext_07082026_1415
[Show chunks ▼]
```

| รายการ | หมายเหตุ |
|--------|----------|
| Import sessions | expand แสดงเฉพาะ chunk ที่ `facesKept > 0` |
| Live sessions | expand แสดงทุก chunk |
| Chunk expand | duration `SS.mmm s`, frames sampled, รายชื่อ JPEG + ขนาด |

### สถานะ extract (ต่อ chunk)

| สถานะ | ความหมาย |
|-------|----------|
| Waiting / Processing | ยังไม่เสร็จ |
| Found N from M frames … | เสร็จ มีรูป |
| No face / No pose | เสร็จ แต่ไม่มีเฟรมผ่าน filter |
| Failed | process error |

---

## Gallery และ session log

| ผลลัพธ์ | ตำแหน่ง |
|---------|---------|
| JPEG รูป | `DCIM/AutoBots/{subfolder}/` |
| Session log | `Download/AutoBots/{subfolder}/session_log.txt` (API 29+) |

| โฟลเดอร์ | รูปแบบ | ใช้เมื่อ |
|----------|--------|---------|
| Live | `yyyyMMdd_HHmmss` | กด Start |
| Import | `ext_DDMMYYYY_HHMM` | Import video |

- ปุ่ม **Gallery (N)** — เปิดรูปล่าสุดใน system gallery (ใช้ได้เมื่อ `K` > 0)
- รูปที่ deliver แล้วลบจาก cache
- ดึงกลับ Mac: `./sync_gallery.sh` (repo root)

---

## Remote control (HTTP)

แอพเปิด server ที่ **IP:8080** (แสดงมุมขวาบนการ์ดสถานะ)

| Endpoint | ใช้ทำอะไร |
|----------|-----------|
| `WS /ws/control` | Start/Stop, เปลี่ยน 1080p/4K, รับ state push |
| `WS /ws/preview` | preview stream (ยังไม่ส่งเฟรมใน build นี้) |
| `GET /photos/{id}` | ดาวน์โหลด JPEG จาก MediaStore |

ดู UI บน Mac: [SCRCPY.md](./SCRCPY.md)

---

## ไฟล์บนเครื่อง

```
cache/autobots/{sessionId}/
  video/
    chunk_001.mp4          ← live
    import_000.mp4         ← import
  faces/
    face_{timestampUs}.jpg ← ก่อนส่ง gallery
  session_log.txt          ← mirror ใน cache

DCIM/AutoBots/
  yyyyMMdd_HHmmss/         ← live session
  ext_DDMMYYYY_HHMM/       ← import session
    *.jpg

Download/AutoBots/
  {subfolder}/
    session_log.txt          ← API 29+
```

Video chunk **เก็บไว้** ใน cache (ยังไม่ลบอัตโนมัติ)

---

## สถานะปุ่มหลัก

| สถานะ | Start/Stop | Import |
|-------|------------|--------|
| IDLE | **Start** | **Import** |
| กำลังอัด | **Stop** | disabled |
| กำลัง processing | **Processing…** | disabled |
| กำลัง import | disabled | **Importing…** |
| หลัง drain เสร็จ | **Start** (session ใหม่) | **Import** |

---

## Flow ทั้ง session (diagram)

```
[IDLE]
  │
  ├─ เลือก Face/Pose + 1080p/4K (live)
  │
  ├─ [Start] ──► [Recording] ──► rotate 50 MB ──► extract queue ──┐
  │         │                          ▲                         │
  │         └── [Stop] → partial chunk ┘                         │
  │                                                              │
  └─ [Import] ──► [Splitting] ──► chunks ──► extract queue ────┤
                                                                 │
                                                                 ▼
                                                          [Processing]
                                                                 │
                    scan 120ms → ML Kit → JPEG → Gallery + session_log
                                                                 │
                                                                 ▼
                                                              [IDLE]
```

---

## ข้อจำกัด / สิ่งที่ควรรู้

1. **4K extract** — อาจเจอหน้าน้อยกว่า 1080p ในบางเครื่อง (ISP NR + sharpness) — กำลัง tune (B2)
2. **ไม่มี live feedback** ว่าเจอหน้าหรือไม่ขณะอัด — ดูผลจาก Session history / chip Face หลัง extract
3. **ออกจากแอพ** (`onStop`) → Stop อัดอัตโนมัติ
4. **Session ใหม่** ทุกครั้งที่กด Start — history ของ session ก่อนหน้าหายจาก UI (ไฟล์บนเครื่องยังอยู่)
5. **Pose mode** — experimental; ใช้ทดสอบ field ไม่ใช่ production default

---

## เอกสารที่เกี่ยวข้อง

| ไฟล์ | เนื้อหา |
|------|---------|
| [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) | **Pipeline เทคนิค** — workers, thresholds, storage |
| [SCREEN.md](./SCREEN.md) | Layout UI ปัจจุบัน (3 หน้า pager) |
| [CHANGELOG.md](./CHANGELOG.md) | v0.1.2 release notes |
| [BUILD.md](./BUILD.md) | Build + install APK |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | Plan B slices ถัดไป (B2–B4) |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Design Flows — **ส่วนใหญ่เป็น v0.1 stills legacy** |

### ความต่าง v0.1 → v0.1.2 (สรุป)

| | v0.1 | v0.1.2 |
|---|------|--------|
| โหมดหลัก | Stills burst + Passage Gate | Video chunk + offline extract |
| ทางเข้า | Live เท่านั้น | Live + **Import video** |
| Preview ขณะอัด | Face overlay + Arm/Fire | Preview อย่างเดียว |
| Trigger ถ่ายรูป | Capture Zone + Early Arm | อัตโนมัติจากวิดีโอหลังอัด |
| หน้า swipe ที่ 3 | Observation grid | **Session history** |
| Chunk / sample | — | **50 MB** · **120 ms** ทุก resolution |

---

## Code อ้างอิง

| ส่วน | ไฟล์หลัก |
|------|----------|
| UI shell | `OperatorShellScreen.kt`, `ChunkHistoryPage.kt` |
| ViewModel | `OperatorViewModel.kt` |
| Record chunk | `VideoChunkRecorder.kt`, `VideoPreviewController.kt` |
| Import split | `ImportedVideoSplitter.kt` |
| Pipeline | `CapturePipelineCoordinator.kt` |
| Frame extract | `VideoFrameProcessor.kt`, `VideoFrameSampler.kt` |
| Resolution config | `StreamResolution.kt`, `ExtractionTarget.kt` |
| Session models | `PipelineSessionRecord.kt`, `ChunkRecord.kt` |
| Gallery + log | `LocalDeliveryWriter.kt`, `WriteQueue.kt`, `SessionAlbumNaming.kt` |
| Remote | `AutobotsServer.kt` |
