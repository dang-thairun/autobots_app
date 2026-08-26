# AutoBots — Operator Flow (v0.1.6)

เอกสารนี้อธิบาย **flow การใช้งานจริง** ของ build ปัจจุบัน (Plan B: video chunk pipeline)
Sync กับ `appVersionName` = **0.1.6**

> เอกสารเก่า (P5 burst / face overlay / Observation grid) ยังอยู่ใน repo แต่ **ไม่ตรงกับ build นี้** — ใช้ไฟล์นี้เป็นหลักสำหรับ operator
> รายละเอียดเทคนิค: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · Layout UI: [SCREEN.md](./SCREEN.md) · เทียบกับสเปคเดิม: [DESIGN_FLOW.md](./DESIGN_FLOW.md)

---

## สรุปสั้นๆ

แอปเป็น **กล้องวิดีโอบน tripod** ที่มี **สามทางเข้า** และ **หนึ่งทางออก**:

| ทางเข้า | ทำอะไร |
|---------|--------|
| **Live** | อัดวิดีโอต่อเนื่องเป็น MP4 chunks |
| **Browse** | เลือกไฟล์ในเครื่อง → ดู metadata + ตัดช่วง → เข้า pipeline เดียวกัน |
| **Network** | พิมพ์ URL หรือสแกน QR → สตรีมไฟล์เข้ามา |

ทุกทาง:

1. **แบ่ง/อัด** เป็น chunk ตามขนาดไฟล์ (**50 MB** ทุก resolution)
2. **แยกเฟรม** ทุก **120 ms** → detector ที่เปิดไว้ (Face · Pose · Person) → zone + ขนาด → ความคม
3. **ให้คะแนน** ทุกเฟรมที่ผ่าน แล้ว **จัดกลุ่มตามคน** เก็บดีที่สุด 3 เฟรมต่อ 1 วินาทีของคนคนนั้น
4. **บันทึกรูป** full-frame JPEG ลง `DCIM/AutoBots/{subfolder}/` พร้อมไฟล์วัดผลลง `Download/AutoBots/{subfolder}/`
5. **อัปโหลด** เป็นสำเนาขึ้นแพลตฟอร์มโดยอัตโนมัติ — **ไม่ลบไฟล์ในเครื่อง**

**ไม่มี** live face overlay ขณะอัด · **ไม่มี** burst stills / Passage Gate ใน build นี้

---

## สิ่งที่ใช้งานได้ (v0.1.6)

| ฟีเจอร์ | สถานะ |
|--------|--------|
| เลือก **1080p** / **4K** ก่อน Start (live) | ✅ |
| เลือก **Face · Pose · Person** — เปิดพร้อมกันได้ เกตเป็น **AND** | ✅ |
| เลือก detector backend (ML Kit / LiteRT CPU · GPU · **NPU** = default) | ✅ |
| **Capture Zone** ลากเองบนภาพจริง | ✅ |
| **เพดานชัตเตอร์ + EV** — กันเบลอตอนแสงน้อย | ✅ |
| Preview ติดทันทีที่เข้าหน้า Live (ไม่ต้องกด Start ก่อน) | ✅ |
| อัดวิดีโอต่อเนื่อง + rotate chunk อัตโนมัติ (50 MB) | ✅ |
| **Browse** ไฟล์ในเครื่อง + **ดู metadata และตัดช่วงก่อน extract** | ✅ |
| **Network URL / QR** — สตรีมไฟล์เข้ามา | ✅ |
| Stop กลาง chunk → partial chunk ยัง extract ได้ | ✅ |
| Offline extraction + **คะแนนรวม 5 ด้าน** (ไม่ใช่ความคมอย่างเดียว) | ✅ |
| **Tracking ต่อคน** — dedup เป็นต่อคน ไม่ใช่ต่อวินาที | ✅ |
| บันทึกรูปลง Gallery + `session_log.txt` · `photos.csv` · **`tracks.csv`** | ✅ |
| **Upload อัตโนมัติขึ้นแพลตฟอร์ม** (Room queue + WorkManager + foreground service) | ✅ |
| ตั้งค่า endpoint/token ด้วย **QR** + เลือก event จากรายการ | ✅ |
| **Session history** + realtime ratio + จำนวนคน | ✅ |
| กู้รายงานหลังโปรเซสตาย (`perf_stream.jsonl`) | ✅ |
| HTTP server + WebSocket remote control | ✅ |
| Thermal + RAM readout | ✅ (**แสดงผลอย่างเดียว ไม่ลดงานให้อัตโนมัติ**) |
| ดึงรูป/log กลับ Mac (`sync_gallery.sh`) | ✅ |
| Live face box บน preview | ❌ |
| ลบไฟล์ในเครื่องหลังอัปโหลด | ❌ **โดยตั้งใจ — ไม่มี toggle** |

---

## หน้าจอหลัก

**Home menu** (ตั้งแต่ v0.1.5) — ทีละหน้า ไม่ใช่ pager แล้ว · **portrait only**
กล้องถูก bind **เฉพาะหน้า Live capture** เท่านั้น

| กลุ่ม | ปุ่ม | ไปไหน |
|------|------|-------|
| **CAPTURE** | ▶ Live · 📁 Browse · 🔗 Network | สามทางเข้าของ pipeline |
| **REVIEW** | 🕘 History · 🖼 Gallery · ☁ Upload | ของที่ผลิตออกมาแล้ว |

ปุ่ม Back ของระบบกลับ Home ได้จากทุกหน้า · ออกจากหน้า Live ขณะกำลังอัด = **หยุดอัดให้ก่อน**
ตัวเลขบนปุ่ม Upload นับเฉพาะ **แถวที่ยังค้าง** ไม่ใช่ทั้งคิว

รายละเอียด layout: [SCREEN.md](./SCREEN.md)

---

## ก่อน Start หรือ Import

### 1. เลือก extraction target + resolution

การ์ดบน → **Video pipeline · Show**

| ตั้งค่า | รายละเอียด |
|---------|------------|
| **Face · Pose · Person** | `ExtractionTarget` — **สาม flag อิสระ เปิดพร้อมกันได้** · เกตเป็น **AND**: เปิดสองตัวแล้วผ่านตัวเดียวคือไม่ผ่าน · เปลี่ยนได้เฉพาะตอน **IDLE** |
| **Backend** | ML Kit FAST/ACCURATE · `face_det_lite` CPU/GPU/**NPU** (default) · ตัวที่รันไม่ได้จะบอกเหตุผล |
| **Capture Zone** | ลากกรอบเองบนภาพจริง — จุดกึ่งกลางของคนต้องอยู่ในกรอบนี้ |
| **เพดานชัตเตอร์ · EV** | ตั้งก่อนอัด — ที่แสงน้อย AE จะยืดชัตเตอร์จนเบลอแล้วตกด่านความคมทั้ง session |
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
  → โฟลเดอร์ gallery: DCIM/AutoBots/v0_1_6_yyyyMMdd_HHmmss/
```

### สิ่งที่เห็นขณะอัด

- บรรทัด **REC #N · ขนาด / 50 MB · วินาที · ~KB/s**
- Progress bar แดง (ความคืบหน้าต่อ chunk ปัจจุบัน)
- Chips อัปเดตสด: **Ch**, **VQ**, detector ที่เปิด, **K**, **realtime ratio**, **Th**, **Disk**
- การ์ด extraction: throughput `Nx realtime · photo in ~Xs` (live เท่านั้น)

> **`realtime ratio` คือตัวเลขที่ต้องดู** — ต่ำกว่า 1.0 คือถอดเฟรมทันกล้อง อัดยาวแค่ไหนก็ได้
> สูงกว่า 1.0 คือคิวโตขึ้นเรื่อยๆ แล้วตัวอัดจะหยุดพักเอง

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

## กด Browse → ประมวลผลวิดีโอจากเครื่อง

```
Browse
  → เปิด file picker (OpenDocument)
  → Import Preview — เห็น ขนาด · resolution · ความยาว · fps · เวลาที่คาดว่าจะใช้
       เลือก detector + backend · ตัดช่วงเวลา · แก้ Capture Zone ได้
  → กด Extract
  → ImportedVideoSplitter remux แบ่ง chunk 50 MB (ไม่ re-encode)
  → แต่ละ chunk เข้า videoQueue → Worker 2 เหมือน live
  → โฟลเดอร์ gallery: DCIM/AutoBots/ext_v0_1_6_DDMMYYYY_HHMM/
```

> ตั้งแต่ v0.1.5 การเลือกไฟล์**ไม่ extract ทันทีอีกแล้ว** — เดิมเป็น fire-and-forget แตะแล้วเริ่มเลย
> ไม่มีจังหวะให้ดูก่อนว่าคลิปนี้ยาวแค่ไหนหรือจะเอาแค่ช่วงไหน

### ทางเข้าที่สาม — Network URL

```
Network
  → พิมพ์ URL หรือสแกน QR (รับเฉพาะ URL ของไฟล์วิดีโอตรงๆ)
  → สตรีมด้วย byte-range เป็นค่าเริ่มต้น (ไม่ต้องโหลดครบก่อน)
  → ดาวน์โหลดทั้งไฟล์เฉพาะเมื่อ CDN ปฏิเสธ MediaHTTPConnection
  → เข้า Import Preview เหมือน Browse
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
| Upload | เริ่มเอง ไม่ต้องรอ session จบ — รูปเข้าคิวทันทีที่ publish ลง MediaStore สำเร็จ |

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
  → scale กว้าง 640px  (decode ครั้งเดียว ทุก detector ใช้ร่วมกัน)
  → detector ที่เปิดไว้ — ต้องผ่าน ทุกตัว (AND)
  → Capture Zone + ขนาดขั้นต่ำ + ต้องไม่ติดขอบเฟรม
  → Laplacian sharpness บน crop 128×128 (FHD ≥80, UHD ≥65)
  → ให้คะแนนรวม 5 ด้าน (FrameQuality)
  → จัดกลุ่มตามคน (SubjectTracker) → เก็บดีที่สุด 3 เฟรม / 1 วินาทีของคนนั้น
  → decode เฟรมเต็มความละเอียด → save JPEG q95
  → WriteQueue → DCIM/AutoBots/{subfolder}/ → เข้าคิว upload
```

| Detector | เงื่อนไขผ่าน |
|--------|-------------|
| **Face** | หน้าสูง ≥ 3.5% (FHD) · 3.0% (4K) ของความสูงเฟรม |
| **Pose** | ลำตัวสูง ≥ 25% (ไหล่+สะโพกครบ 4 จุด) |
| **Person** | ตัวสูง ≥ 15% ของความสูงเฟรม |

**คะแนนรวม 5 ด้าน** — ความคม `.40` · ขนาด `.20` · กลางเฟรม `.15` · confidence `.15` · ระยะห่างขอบ `.10`
ทุกด้าน normalise เป็น 0..1 ก่อนถ่วงน้ำหนัก · บันทึกทุกค่าลง `photos.csv` เพื่อ re-fit น้ำหนักกับหลักฐานจริง

> **dedup นับต่อคน ไม่ใช่ต่อวินาที** — สองคนวิ่งผ่านในวินาทีเดียวกันได้คนละ 3 รูป ไม่ใช่แบ่งกัน 3 รูป
> ก่อน v0.1.6 คนหลังไม่ได้รูปเลยโดยที่ระบบรายงานว่าปกติ

### Stat chips ที่เกี่ยวข้อง

| Chip | ความหมาย |
|------|----------|
| **Ch** | จำนวน chunk ที่ finalize แล้ว |
| **VQ** | chunk รออยู่ใน queue extract |
| detector | เฟรมที่ผ่าน filter ทั้ง session |
| **K** | รูปที่ส่งเข้า Gallery แล้ว |
| **realtime** | เวลาประมวลผล ÷ ความยาวคลิป — **ต่ำกว่า 1.0 คือทันกล้อง** |
| **Th** | ความร้อน — **แสดงผลอย่างเดียว แอปไม่ลดงานให้เอง** |

---

## Session history

Home → 🕘 **History** — แสดง **session cards** เรียงจากล่าสุด

### Session card

```
aa11.mp4 — Done
Import · 4K · 3840×2160 · Face
Video 5:30 · 1.2 GB
8 chunks · 12 shots · 3m 45s
~9 people · 7 photographed · 2 others
Found 12 from 2500 frames (0%) · avg 31ms/frame · sample 120ms · 0.50x realtime
Gallery: DCIM/AutoBots/ext_v0_1_6_07082026_1415
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
| คะแนนต่อรูป | `Download/AutoBots/{subfolder}/photos.csv` |
| **1 แถวต่อคน รวมคนที่ไม่ได้รูป** | `Download/AutoBots/{subfolder}/tracks.csv` |
| perf (debug build) | `Download/AutoBots/{subfolder}/perf_report.json` |

> **ดู `tracks.csv` ก่อนสรุปว่า session ผ่าน** — จำนวนรูปที่เก็บได้บอกไม่ได้ว่ามีคนเดินผ่านหน้ากล้อง
> แล้วไม่ได้รูปกี่คน ไฟล์นี้เป็นไฟล์เดียวที่บอกได้

| โฟลเดอร์ | รูปแบบ | ใช้เมื่อ |
|----------|--------|---------|
| Live | `v0_1_6_yyyyMMdd_HHmmss` | กด Start |
| Import | `ext_v0_1_6_DDMMYYYY_HHMM` | Browse / Network |

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
  v0_1_6_yyyyMMdd_HHmmss/       ← live session
  ext_v0_1_6_DDMMYYYY_HHMM/     ← import session
    *.jpg

Download/AutoBots/
  {subfolder}/
    session_log.txt          ← API 29+ · อ่านด้วยตาเปล่าได้
    photos.csv               ← คะแนน 5 ด้านต่อรูป
    tracks.csv               ← 1 แถวต่อคน รวมคนที่ไม่ได้รูป
    perf_report.json         ← debug build เท่านั้น
    perf_stream.jsonl        ← เขียนสด กู้รายงานได้ถ้าโปรเซสตาย
```

Video chunk **เก็บไว้** ใน cache (ยังไม่ลบอัตโนมัติ)
**รูปที่อัปโหลดแล้วก็ไม่ถูกลบ** — upload คือการคัดลอก ไม่ใช่การย้าย และไม่มี toggle

---

## สถานะปุ่มหลัก

| สถานะ | Start/Stop | Browse / Network | History / Upload |
|-------|------------|------------------|------------------|
| IDLE | **Start** | เปิดได้ | เปิดได้ |
| กำลังอัด | **Stop** | disabled | เปิดได้ |
| กำลัง processing | **Processing…** | disabled | เปิดได้ |
| กำลัง import | disabled | **Importing…** | เปิดได้ |
| หลัง drain เสร็จ | **Start** (session ใหม่) | เปิดได้ | เปิดได้ |

History กับ Upload เปิดได้เสมอ — เป็นหน้าอ่านอย่างเดียว และเป็นสิ่งที่ operator อยากดูระหว่างรอพอดี

---

## Flow ทั้ง session (diagram)

```
[IDLE]
  │
  ├─ เลือก detector + backend + zone + 1080p/4K (live)
  │
  ├─ [Start] ──► [Recording] ──► rotate 50 MB ──► extract queue ──┐
  │         │                          ▲                         │
  │         └── [Stop] → partial chunk ┘                         │
  │                                                              │
  ├─ [Browse] ─► [Import Preview] ─► trim ─► chunks ──────────┤
  │                                                              │
  └─ [Network] ► [สตรีม URL] ────► [Import Preview] ──────────┤
                                                                 │
                                                                 ▼
                                                          [Processing]
                                                                 │
       sample 120ms → detect (AND) → zone → sharpness → score
              → track → dedup ต่อคน → JPEG → Gallery + CSVs
                                                                 │
                                                                 ▼
                                                    [Upload queue] ──► platform
                                                                 │
                                                                 ▼
                                                              [IDLE]
```

---

## ข้อจำกัด / สิ่งที่ควรรู้

1. **4K extract** — เคยเจอหน้าน้อยกว่า 1080p ในบางเครื่อง · แก้แล้วด้วย threshold แยกตามความละเอียด + detector บน NPU (B2 ปิดแล้ว) · ถ้ายังเจอในสนาม ให้เก็บ `perf_report.json` มาด้วย
2. **ไม่มี live feedback** ว่าเจอคนหรือไม่ขณะอัด — ดูผลจาก Session history หลัง extract
3. **ออกจากแอพ** (`onStop`) → Stop อัดอัตโนมัติ · ออกจากหน้า Live ขณะอัดก็หยุดอัดให้เช่นกัน
4. **Session ใหม่** ทุกครั้งที่กด Start — history ของ session ก่อนหน้าหายจาก UI (ไฟล์บนเครื่องยังอยู่)
5. **ความร้อนไม่ถูกจัดการให้อัตโนมัติ** — แอปรายงานอย่างเดียว การตัดสินใจพักเครื่องเป็นของ operator
6. **ความละเอียดปลายทางคือเฟรมวิดีโอ** — 4K = 8.3 MP ไม่ใช่ความละเอียดสูงสุดของเซนเซอร์ ครอปได้จำกัดกว่าการถ่ายภาพนิ่ง
7. **Token หมดอายุจะรู้ตอน complete** คือหลังไฟล์ถูกส่งขึ้นไปแล้ว — ทดสอบ upload หนึ่งรูปก่อนออกงานเสมอ

---

## เอกสารที่เกี่ยวข้อง

| ไฟล์ | เนื้อหา |
|------|---------|
| [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) | **Pipeline เทคนิค** — workers, thresholds, storage |
| [SCREEN.md](./SCREEN.md) | Layout UI ปัจจุบัน (Home menu) |
| [DESIGN_FLOW.md](./DESIGN_FLOW.md) | เทียบกับสเปค Flow Design v1 ทีละข้อ |
| [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) | Sequence diagram รวม upload |
| [CHANGELOG.md](./CHANGELOG.md) | release notes |
| [BUILD.md](./BUILD.md) | Build + install APK |
| [SCRCPY.md](./SCRCPY.md) | Mirror หน้าจอมือถือบน Mac |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | สถานะ phase ทั้งหมด · B4 คือสิ่งเดียวที่ยังค้าง |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Design Flows — **ส่วนใหญ่เป็น v0.1 stills legacy** |

### ความต่าง v0.1 → ปัจจุบัน (สรุป)

| | v0.1 | v0.1.6 |
|---|------|--------|
| โหมดหลัก | Stills burst + Passage Gate | Video chunk + offline extract |
| ทางเข้า | Live เท่านั้น | Live + **Browse** + **Network URL** |
| Preview ขณะอัด | Face overlay + Arm/Fire | Preview + Capture Zone |
| Trigger ถ่ายรูป | Capture Zone + Early Arm | อัตโนมัติจากวิดีโอหลังอัด |
| navigation | swipe 3 หน้า | **Home menu** ทีละหน้า |
| ตรวจจับ | face อย่างเดียว | **face + pose + person** เกตเป็น AND |
| เกณฑ์เลือกรูป | ลำดับชัตเตอร์ | **คะแนนรวม 5 ด้าน** จัดอันดับต่อคน |
| ปลายทาง | เครื่องอย่างเดียว | เครื่อง **+ อัปโหลดขึ้นแพลตฟอร์ม** |
| วัดว่าพลาดใคร | ไม่มีทางรู้ | **`tracks.csv`** |
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
