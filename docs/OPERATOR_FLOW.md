# AutoBots — Operator Flow (v0.1.2)

เอกสารนี้อธิบาย **flow การใช้งานจริง** ของ build ปัจจุบัน (Plan B: video chunk pipeline)  
Sync กับ `AutobotsApp.version` = **0.1.2** · phase **B1**

> เอกสารเก่า (P5 burst / face overlay / Observation grid) ยังอยู่ใน repo แต่ **ไม่ตรงกับ build นี้** — ใช้ไฟล์นี้เป็นหลักสำหรับ operator

---

## สรุปสั้นๆ

แอพเป็น **กล้องวิดีโอบน tripod** ที่:

1. **อัดวิดีโอ** เป็น chunk ตามขนาดไฟล์
2. **แยกเฟรม** จากวิดีโอ → หาใบหน้า (ML Kit offline) → คัดเฉพาะเฟรมที่ชัด
3. **บันทึกรูป** full-frame JPEG ลง `DCIM/AutoBots` (Gallery)

**ไม่มี** live face overlay ขณะอัด · **ไม่มี** burst stills / Passage Gate ใน build นี้

---

## สิ่งที่ใช้งานได้ (v0.1.2)

| ฟีเจอร์ | สถานะ |
|--------|--------|
| เลือก **1080p** / **4K** ก่อน Start | ✅ |
| อัดวิดีโอต่อเนื่อง + rotate chunk อัตโนมัติ | ✅ |
| Stop กลาง chunk → partial chunk ยัง extract ได้ | ✅ |
| Face extraction จากวิดีโอ (ML Kit + sharpness filter) | ✅ |
| บันทึกรูปลง Gallery (`DCIM/AutoBots`) | ✅ |
| Chunk History (log + expand รายชื่อไฟล์) | ✅ |
| Stat chips (Ch / VQ / Face / K / Th / Disk) | ✅ |
| Processing status card | ✅ |
| Pause record เมื่อ video queue เต็ม | ✅ |
| HTTP server + WebSocket remote control | ✅ |
| Thermal + RAM readout | ✅ |
| Upload รูปออก cloud / HTTP delivery | ❌ |
| Live face box บน preview | ❌ |
| Body / pose detection | ❌ (note ไว้ใน roadmap) |

---

## หน้าจอหลัก

พื้นหลัง = **กล้องเต็มจอ** · overlay = **3 หน้า swipe** (จุด indicator ด้านล่าง)

| หน้า | ชื่อ | ใช้ทำอะไร |
|-----|------|-----------|
| **0** | Controls | สถานะ, ตั้งค่า, Start/Stop, Gallery |
| **1** | Clean Preview | ดู preview เต็มจอ ไม่มี UI บัง |
| **2** | Chunk History | รายการ chunk + ผล extract |

เมื่อ **ไม่อัด** → preview มืด แสดง `Stopped` หรือ `Processing chunks…`

---

## ก่อนกด Start

### 1. เลือก resolution

การ์ดบน → **Video pipeline · Show**

| โหมด | Chunk rotate | Sample ตอน extract |
|------|--------------|-------------------|
| **1080p** | 20 MB | ทุก 300 ms |
| **4K** | 50 MB | ทุก 120 ms |

- เปลี่ยนได้เฉพาะตอน **IDLE** (ยังไม่ Start / ไม่ processing)
- Lock ระหว่างอัด

### 2. เช็คพื้นที่ว่าง

- Chip **Disk** แสดง MB ว่าง
- ต้องมี ≥ **2 GB** free ถึงจะ Start ได้
- ถ้าไม่พอ → ข้อความ `Storage low — need 2 GB free to record`

### 3. Camera permission

- ครั้งแรกกด **Allow & Start** → ขอ permission แล้ว Start อัตโนมัติ

---

## กด Start → อัดวิดีโอ

```
Start
  → สร้าง session ใหม่ (reset counter / chunk history)
  → เปิด Preview + VideoCapture
  → อัด chunk_001.mp4, chunk_002.mp4, … ต่อเนื่อง
```

### สิ่งที่เห็นขณะอัด

- บรรทัด **REC #N · ขนาด / เป้า · วินาที · ~KB/s**
- Progress bar แดง (ความคืบหน้าต่อ chunk ปัจจุบัน)
- Chips อัปเดตสด: **Ch**, **VQ**, **Face**, **K**, **Th**, **Disk**

### Rotate chunk (อัตโนมัติ)

เมื่อไฟล์ปัจจุบันถึงเป้า:

```
chunk_001.mp4 ครบ 20 MB (1080p) หรือ 50 MB (4K)
  → finalize ไฟล์
  → ส่งเข้า face extraction queue
  → เริ่ม chunk_002.mp4 ทันที (กล้องไม่หยุด)
```

### Pause อัตโนมัติ

- Video queue รอ extract ได้สูงสุด **8 chunk**
- ถ้าเต็ม → แสดง `PAUSED · queue full — waiting to resume`
- เมื่อ worker ทัน → อัดต่อเอง

---

## กด Stop

```
Stop
  → หยุดอัดทันที
  → finalize chunk ปัจจุบัน (แม้ไม่ครบเป้า = partial)
  → preview มืดลง
  → face extraction + gallery delivery ทำงานต่อจน queue หมด
```

| พฤติกรรม | รายละเอียด |
|----------|-------------|
| Stop ≠ ยกเลิก processing | Worker 2/3 ทำงานต่อ |
| Partial chunk | ขึ้นใน history พร้อม `(partial)` |
| ปุ่ม Start | เป็น `Processing…` จนกว่า pipeline จะ drain เสร็จ |

**ตัวอย่าง**

- อัดครบ 20 MB → Chunk #1 → อัดต่อ → Stop ที่ 6/20 MB → Chunk #2 (partial) → **รวม 2 chunks**

---

## Face extraction (หลัง chunk พร้อม)

การ์ด **Face extraction** (ล่างหน้า Controls):

```
Processing chunk_001 · 45% · 1/2 chunks · scan 72%
```

### ขั้นตอนภายใน (ต่อ 1 chunk)

```
MP4 chunk
  → decode เฟรมทุก 120 ms (4K) หรือ 300 ms (1080p)
  → ย่อเป็น 640px กว้าง → ML Kit Face (FAST)
  → กรอง: หน้า ≥ 5% ความสูงเฟรม, sharpness ≥ 80
  → dedup 1 รูป / วินาที (เลือกเฟรมคมที่สุด)
  → save JPEG full frame → cache/.../faces/
  → WriteQueue → DCIM/AutoBots
```

### Stat chips ที่เกี่ยวข้อง

| Chip | ความหมาย |
|------|----------|
| **Ch** | จำนวน chunk ที่อัดเสร็จ (finalize แล้ว) |
| **VQ** | chunk รออยู่ใน queue extract |
| **Face** | เฟรมที่ผ่าน filter ทั้ง session |
| **K** | รูปที่ส่งเข้า Gallery แล้ว |

---

## Chunk History (หน้า 2)

Swipe ไปหน้าที่ 3 (จุดสุดท้าย)

### แต่ละการ์ดแสดง

- **Chunk #N** · resolution
- **Started HH:mm:ss**
- **Record Xs · Y MB** — มี `(partial)` ถ้าไม่ครบเป้า
- path ไฟล์วิดีโอ
- **Extract N faces · Xs · Y MB** — กด **Show/Hide** ดูรายชื่อ JPEG + path

### สถานะ extract

| สถานะ | ความหมาย |
|-------|----------|
| Waiting / Processing | ยังไม่เสร็จ |
| Extract N faces · … | เสร็จ มีหน้า |
| No face | เสร็จ แต่ไม่มีเฟรมผ่าน filter |
| Failed | process error |

---

## Gallery

- ปุ่ม **Gallery (N)** — เปิดรูปล่าสุดใน `DCIM/AutoBots`
- ใช้ได้เมื่อมีรูปอย่างน้อย 1 รูป (`K` > 0)
- รูปที่ deliver แล้วลบจาก cache

---

## Remote control (HTTP)

แอพเปิด server ที่ **IP:8080** (แสดงมุมขวาบนการ์ดสถานะ)

| Endpoint | ใช้ทำอะไร |
|----------|-----------|
| `WS /ws/control` | Start/Stop, เปลี่ยน 1080p/4K, รับ state push |
| `WS /ws/preview` | preview stream (ยังไม่ส่งเฟรมใน build นี้) |
| `GET /photos/{id}` | ดาวน์โหลด JPEG จาก MediaStore |

---

## ไฟล์บนเครื่อง

```
cache/autobots/{sessionId}/
  video/
    chunk_001.mp4
    chunk_002.mp4
  faces/
    face_{timestampUs}.jpg   ← ก่อนส่ง gallery

DCIM/AutoBots/               ← รูปที่เห็นใน Gallery
```

Video chunk **เก็บไว้** ใน cache (ยังไม่ลบอัตโนมัติ)

---

## สถานะปุ่ม Start

| สถานะ | ปุ่ม | ทำอะไรได้ |
|-------|------|-----------|
| IDLE | **Start** | เริ่ม session ใหม่ |
| กำลังอัด | **Stop** | หยุดอัด (processing ต่อ) |
| กำลัง processing | **Processing…** | รอ drain — Start ไม่ได้ |
| หลัง drain เสร็จ | **Start** | session ใหม่ |

---

## Flow ทั้ง session (diagram)

```
[IDLE]
  │
  ├─ เลือก 1080p หรือ 4K
  │
  ▼
[Start] ─────────────────────────────────────────────┐
  │                                                   │
  ▼                                                   │
[Recording] ◄──────────────────────────────────┐   │
  │  REC #1 → ครบเป้า → chunk #1 → extract queue │   │
  │  REC #2 → …                                   │   │
  │  (VQ เต็ม → PAUSED → resume)                  │   │
  │                                               │   │
  ├─ [Stop] ──► partial chunk → extract queue     │   │
  │                                               │   │
  └─ rotate ครบเป้า ──────────────────────────────┘   │
                                                      │
[Processing] ◄────────────────────────────────────────┘
  │  scan วิดีโอ → หาหน้า → save JPEG → Gallery
  │
  ▼
[IDLE]  (กด Start session ใหม่ได้)
```

---

## ข้อจำกัด / สิ่งที่ควรรู้

1. **4K extract** — อาจเจอหน้าน้อยกว่า 1080p ในบางเครื่อง (compression + sharpness filter) — กำลัง tune
2. **ไม่มี live feedback** ว่าเจอหน้าหรือไม่ขณะอัด — ดูผลจาก Chunk History / chip Face หลัง extract
3. **ออกจากแอพ** (`onStop`) → Stop อัดอัตโนมัติ
4. **Session ใหม่** ทุกครั้งที่กด Start — history ของ session ก่อนหน้าหายจาก UI (ไฟล์ cache ยังอยู่จนกว่าจะถูกลบ)

---

## เอกสารที่เกี่ยวข้อง

| ไฟล์ | เนื้อหา |
|------|---------|
| [CHANGELOG.md](./CHANGELOG.md) | **v0.1.2 release notes** — shipped / fixes / known gaps |
| [BUILD.md](./BUILD.md) | Build + install APK |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac |
| [SCREEN.md](./SCREEN.md) | Layout เก่า (P5) — **อาจไม่ตรง build นี้** |
| [architecture.md](./architecture.md) | สถาปัตยกรรมรวม |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | Plan B slices ถัดไป (B2–B4) |

### ความต่าง v0.1 → v0.1.2 (สรุป)

| | v0.1 | v0.1.2 |
|---|------|--------|
| โหมดหลัก | Stills burst + Passage Gate | Video chunk + offline extract |
| Preview ขณะอัด | Face overlay + Arm/Fire | Preview อย่างเดียว |
| Trigger ถ่ายรูป | Capture Zone + Early Arm | อัตโนมัติจากวิดีโอหลังอัด |
| หน้า swipe ที่ 3 | Observation grid | Chunk History |
| Chunk size | — | 1080p 20 MB / 4K 50 MB |

---

## Code อ้างอิง

| ส่วน | ไฟล์หลัก |
|------|----------|
| UI shell | `OperatorShellScreen.kt`, `ChunkHistoryPage.kt` |
| ViewModel | `OperatorViewModel.kt` |
| Record chunk | `VideoChunkRecorder.kt`, `VideoPreviewController.kt` |
| Pipeline | `CapturePipelineCoordinator.kt` |
| Face extract | `VideoFaceProcessor.kt`, `VideoFrameSampler.kt` |
| Resolution config | `StreamResolution.kt` |
| Gallery | `LocalDeliveryWriter.kt`, `WriteQueue.kt` |
| Remote | `AutobotsServer.kt` |
