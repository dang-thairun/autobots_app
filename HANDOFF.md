# Handoff — v0.1.7 planning session

**วันที่ส่งมอบ:** 2026-08-28 · branch `develop`
**สรุปหนึ่งบรรทัด:** วางแผน v0.1.7 เสร็จ ตัดสินครบทุกข้อ **ยังไม่แตะโค้ดแม้บรรทัดเดียว** — งานถัดไปคือ S0 (วัดของเดิม ไม่ต้องเขียนโค้ด)

> ไฟล์นี้เป็นเอกสารส่งมอบชั่วคราว **ไม่ใช่เอกสารโปรเจกต์** ลบได้เมื่อรับงานต่อเรียบร้อย · ไม่ได้ลงทะเบียนใน `docs/DOCS.md`

---

## 1. อ่านอะไรก่อน

| ลำดับ | ไฟล์ | อ่านเพื่อ |
|---|---|---|
| 1 | [docs/V_0_1_7_PHASES.md](docs/V_0_1_7_PHASES.md) (354 บรรทัด) | **ทำอะไร ตามลำดับไหน เสร็จเมื่อไร** — เริ่มที่นี่ |
| 2 | [docs/V_0_1_7_PLAN.md](docs/V_0_1_7_PLAN.md) (840 บรรทัด) | **ทำไม** — เหตุผททุกข้อ · หัวเอกสาร 3 หัวข้อแรกอ่าน 2 นาทีได้ภาพรวม · **§12 = การตอบกลับรีวิวของหัวหน้า** |
| 3 | [docs/DESIGN_FLOW.md](docs/DESIGN_FLOW.md) §Video Pipeline | ของเดิม 13 ขั้น — v0.1.7 เปลี่ยนลำดับขั้นนี้ |

**ห้ามอ่านแล้วรื้อ** — การตัดสินใจ 9 ข้อในหัว PLAN ผ่านการถกกับผู้ใช้มาแล้วทุกข้อ พร้อมเหตุผลและตัวเลข **เปิดใหม่ได้เฉพาะเมื่อมีข้อมูลใหม่** ไม่ใช่เพราะมีความเห็นต่าง

---

## 2. สถานะ

### commit ไปแล้ว

```
adbba76  "add v0.1.7 plan"  (2026-08-27 17:26 · ผู้ใช้ commit เอง)
         docs/V_0_1_7_PLAN.md   +576   ← เวอร์ชันแรก
         docs/DOCS.md            +1
         docs/ROADMAP.md         +4/-1
```

### ยังไม่ commit — ทั้งหมดเป็นงานหลังจาก adbba76

```
 M docs/V_0_1_7_PLAN.md    +296   ← ยุบข้อเสนอจากรีวิวหัวหน้า · ปิด D1/D2 · §3.8 · §8.1 · §12 · §13
 M docs/DOCS.md            +3/-1  ← index 2 แถว
 M docs/CONVENTIONS.md     +1     ← ลงทะเบียน "Slice S0–S11" ใน §2
?? docs/V_0_1_7_PHASES.md  354    ← ใหม่ · ลำดับงาน
?? HANDOFF.md              —      ← ไฟล์นี้
```

`./scripts/check_docs_drift.sh` ผ่าน

> ⚠️ **PLAN.md ที่ commit ไว้ (576 บรรทัด) ยังไม่มีของเหล่านี้:** การตอบกลับรีวิว (§12) · การ size ให้ 3–4 ชม. (§3.8) · `realtimeRatio` เป็นตัวสังเกต (§8.1) · เครื่องมือตรวจสอบ (§13) · **การปิด D1/D2** · Hungarian เข้า scope
> ⇒ **ถ้าใครอ่านจาก git จะได้ของเก่า** ต้องอ่านจาก working tree หรือ commit ก่อน

### ไม่ได้แตะ

**โค้ดทุกไฟล์ · gradle · assets** — ยังเป็น v0.1.6 สมบูรณ์

---

## 3. งานถัดไปทันที — S0

**ไม่ต้องเขียนโค้ด** นับ 3 ตัวเลขจากไฟล์ที่แอปเขียนไว้แล้ว → เขียนลง `reports/v0.1.7/s0_baseline.md`

| | คำถาม | จากไฟล์ | ตัดสินอะไร |
|---|---|---|---|
| ก | ตอนแอปสลับไฟล์ วิดีโอขาดไปนานเท่าไร | `session_log.txt` | **> 0.6 วิ ⇒ ยกเลิก Bundle 3 (S9) ทั้งก้อน** |
| ข | มีนักวิ่งกี่คนถูกนับเป็นสองคนเพราะคร่อมรอยต่อไฟล์ | `tracks.csv` | baseline ที่ S9 ต้องเอาชนะ |
| ค | มีนักวิ่งกี่คนที่ระบบเห็นแค่ภาพเดียวแล้วหลุด | `tracks.csv` | **น้อยอยู่แล้ว ⇒ ทบทวนว่า Bundle 2 คุ้มไหม** |

⚠️ **ยังไม่ได้รัน** เพราะไม่รู้ว่า `session_log.txt` / `tracks.csv` ของรอบที่วัดไว้ (`run4mins` · คลิปเส้นชัยกลางคืน) เก็บอยู่ที่ไหน — **ถามผู้ใช้ก่อน**

---

## 4. การตัดสินใจที่ปิดแล้ว — อย่าเปิดใหม่โดยไม่มีข้อมูลใหม่

| # | ตัดสินว่า | เหตุผลย่อ |
|---|---|---|
| 1 | **แกนคือ person ID ไม่ใช่เฟรม** | วันนี้ 1 เฟรม = 1 subject = 1 คะแนน ⇒ คนที่วิ่งคู่กับคนตัวใหญ่กว่าได้ 0 รูป |
| 2 | ใช้ `foot_track_net` ต่อ | YOLO = AGPL · EfficientDet-Lite = QNN ไม่รับ postprocess op · mmpose ไม่ใช่ runtime มือถือ · ของเดิมต้นทุนเพิ่ม 0 ms บน NPU |
| 3 | port ByteTrack (MIT) แต่เปลี่ยน metric เป็น **DIoU** | ที่ 120 ms IoU ของคนเดียวกันสองเฟรม ≈ 0.2 อยู่ตรงเส้น `match_thresh` พอดี |
| 4 | แยก W1/W2 · **รอยต่อหลัง sharpness** | sharpness ต้องใช้ pixel เต็ม · decode รอบสองจะดัน `realtimeRatio` ไป ~1.0 |
| 5 | เย็บรอยต่อ chunk **ที่ metadata (ทาง B)** | ทาง A (ถือ state ข้าม chunk) มัด chunk เข้าด้วยกัน · ทาง C (ขยาย chunk) เลื่อนไว้ พร้อมทริกเกอร์ใน PLAN §11 |
| 6 | **ส่ง 4 ก้อน แยกกันได้** | Bundle 1 คุ้มทำไม่ว่า Bundle 2 จะได้ผลหรือไม่ |
| 7 | guardrail ดิสก์ = **backpressure ไม่ใช่ทิ้ง candidate** | pipeline เป็น offline ไม่มี deadline · หน่วง W1 จ่ายด้วย `realtimeRatio` ไม่ได้จ่ายด้วยรูป |
| 8 | **D1** — `no_face` ไม่ได้รูป **แต่ยังให้คะแนนเก็บไว้** | ผู้ใช้ตัดสิน: รูปที่ไม่เห็นหน้าขายไม่ได้ · แต่ต้องตอบได้ว่ากฎนี้ทำให้พลาดอะไร |
| 9 | **D2** — **Hungarian แทน greedy เสมอ** ไม่ผูกกับ `N` | ID switch เป็นความผิดเดียวในแผนที่**ไม่มีตัวจับ** และส่งรูปผิดคนให้ลูกค้า · Kalman ยังเลื่อน |

**เกณฑ์ perf:** `realtimeRatio` เป็น**ตัวสังเกต ไม่ใช่ประตู** (ผู้ใช้ตัดสิน) · ประตูเดียวคือ **≤ 0.85** เพราะ ≥ 1.0 คิวไม่ระบาย · เกณฑ์ "ห้ามเกิน 5%" ที่หัวหน้าเขียนใน DoD **ถูกถอดออกโดยเจตนา** เหตุผลอยู่ PLAN §8.1 + §12

---

## 5. ข้อเท็จจริงในโค้ดที่ยืนยันแล้ว — อย่าค้นซ้ำ

| ข้อเท็จจริง | ที่ |
|---|---|
| **chunk ถูกประมวลผลทีละอันตามลำดับ** (consumer เดียว) — ไม่ใช่ขนาน | `CapturePipelineCoordinator.kt:245` |
| chunk `.mp4` **ถูกลบ**หลัง W2 อ่านจบ · `KEEP_PROCESSED_CHUNKS` เป็น `private const = false` | `:417` · `:1314` |
| `trackRows`/`photoRows` **สะสมในหน่วยความจำทั้ง session** เขียนครั้งเดียวตอน drain ผ่าน `buildString` · `writeSessionFile` เขียน 3 ที่ | `:215` · `:893` · `:945` · `:1014` |
| **`score` ถูกทิ้ง 1 บรรทัด** — `observed = inZone.map { it.bounds }` | `VideoFrameProcessor.kt:613` |
| **1 เฟรม = 1 subject = 1 คะแนน** — `subjectBox = faceBox ?: torsoBox ?: personBox` | `:716` |
| sharpness **วัดที่ใบหน้าอยู่แล้ว** พร้อมเหตุผลในคอมเมนต์ | `:712–716` |
| `isUsableRoi()` เช็ค**ขนาดขั้นต่ำเท่านั้น ไม่ได้ clamp ขอบ** | `:986` |
| **รูปที่เซฟคือเฟรมเต็ม ไม่ใช่ครอป** — `candidate.bitmap` = `upright` | `:1001` |
| `DEDUP_WINDOW_US = 1s` · `MAX_KEEP_PER_WINDOW = 3` | `:1181` · `:1228` |
| **decoder ถอดทุกเฟรมอยู่แล้ว** เฟรมที่ไม่สุ่มแค่ไม่ render | `VideoFrameSampler.kt:328` |
| `uprightDetectBitmap` **ย่อ + หมุนตาม `rotationDegrees`** ⇒ พิกัดผูกกับ bitmap นี้ | `:850` |
| `CHUNK_TARGET_BYTES = 50 MB` (หน่วยขนาด ไม่ใช่เวลา) · `FRAME_SAMPLE_INTERVAL_MS = 120` | `StreamResolution.kt:26` · `:28` |
| `DEFAULT_IOU = 0.2f` ("Low on purpose") · `MAX_SIZE_RATIO = 2.0f` · `DEFAULT_MAX_GAP_US = 600_000` · `VELOCITY_GAIN = 0.5` | `SubjectTracker.kt` companion |
| น้ำหนัก `FrameQuality` = 0.40 / 0.20 / 0.15 / 0.15 / 0.10 · **`confidence` null ⇒ drop เทอมแล้ว renormalise** ← แบบแผนที่ D1 ต้องลอก | `FrameQuality.kt:47–59` · KDoc `Score` |
| `logFrame()` มี `if (!CamPerf.enabled) return` ⇒ **reject reason รายเฟรมมีเฉพาะโหมด perf** | `VideoFrameProcessor.kt:157` |
| **ไม่มี test source set ในโปรเจกต์เลย** — `commonTest.dependencies { }` ว่างเปล่า ไม่มีโฟลเดอร์ test | `shared/build.gradle.kts:28` |
| `foot_track_net` มี **landmark 34 (17 kpt) + visibility 17 ที่ยังไม่ decode** | `assets/models/foot_track_net-tflite-w8a8/metadata.json` |

### ตัวเลขที่วัดแล้ว (อ้างได้)

```
realtimeRatio ปัจจุบัน        0.502
person detector ต้นทุนเพิ่ม    30,484 vs 30,498 ms (คลิปเดียวกัน) ⇒ ~0 ms
NPU vs ML Kit FAST            25 ms vs 87 ms
chunk ที่ 4K                   run4mins 273s/23 = 11.9 s · คลิปกลางคืน 5,580s/~547 = 10.2 s
คลิปกลางคืน 10–13 นาที         passages=309 movedThrough=48 likelySubject=49 captured=33
                              direction: R→L 40 · L→R 4 · toward 4 · static 261
```

### ByteTrack — ยืนยันจาก repo จริงแล้ว (ไม่ใช่ความจำ)

```
License        MIT (Yifu Zhang 2021)
แบ่งชั้น        high > track_thresh (0.5) · low 0.1 < s < 0.5
Stage 1        tracked + lost · iou_distance · match_thresh 0.8 · fuse_score ✅
Stage 2        tracked ที่เหลือ · iou_distance · 0.5 · fuse ❌
Stage 3        unconfirmed vs high ที่เหลือ · 0.7 · fuse ✅
assignment     lap.lapjv (Hungarian)
motion         Kalman 8 มิติ (x, y, a, h, ẋ, ẏ, ȧ, ḣ)
fuse_score     cost = 1 − (iou_sim × det_score)
track ใหม่      score ≥ det_thresh = track_thresh + 0.1 = 0.6
max_time_lost  int(frame_rate/30 × track_buffer) · track_buffer = 30
```

---

## 6. ⚠️ ที่ผมเข้าใจผิดแล้วแก้ในเซสชันนี้ — อย่าพลาดซ้ำ

| เคยเชื่อว่า | จริง ๆ คือ |
|---|---|
| chunk ถูกประมวลผล**ขนาน** ⇒ ส่ง track state ข้าม chunk ไม่ได้ | **sequential** (`:245`) · ที่ขนานคือ detect worker *ภายใน* chunk · ทาง A จึงทำได้ แค่ไม่คุ้ม |
| `candidates.csv` เป็นไฟล์ที่ต้องมี | **ซ้ำกับ `FrameDiag`** ที่มีอยู่ + คอลัมน์ `reject`/`no_face` ⇒ ตัดทิ้ง |
| quality คิดต่อคนอยู่แล้ว | **คิดต่อเฟรม ให้ subject คนเดียว** (`:716`) — นี่คือรากของทั้ง v0.1.7 |
| staging = 18 GB/30 นาที (ตามรีวิว) | **~10 GB** และเฉพาะกรณี W2 ไม่ทำงาน · candidate เป็น**ต่อเฟรม ไม่ใช่ต่อคน** |
| ใช้ `S0`–`S11` ได้เลย | **รีโปมี CONVENTIONS §2 กำกับการตั้งชื่ออยู่** (B1–B4, P0–P10) ⇒ ต้องลงทะเบียน (แก้แล้ว) |

**schema `sightings.csv` แก้ 3 รอบ** — เวอร์ชันที่ถูกต้องคือ PLAN §3.1 เท่านั้น (มี `quality` + 5 เทอม · **ไม่มี `is_subject`**)

---

## 7. สิ่งที่ผู้ใช้ระบุไว้ — ทำตามนี้

| เรื่อง | สิ่งที่ผู้ใช้ต้องการ |
|---|---|
| **ภาษา** | เอกสาร**ภาษาไทย** · ชื่อตัวแปร/ไฟล์เป็นอังกฤษ · **ห้ามใช้ตัวย่อโดยไม่นิยามก่อน** (ถูกตำหนิเรื่องนี้ตรง ๆ 2 ครั้ง) |
| **จำนวนเอกสาร** | **ไม่สร้างเอกสารเพิ่ม** — ใช้ PLAN + PHASES เท่านั้น · ปฏิเสธการอ้าง `RUNNER_CAPTURE_PIPELINE.md` / `PIPELINE_GAP_ANALYSIS.md` (**สองไฟล์นี้ไม่มีในรีโป**) |
| **รูปแบบที่ชอบ** | สเต็ปสั้นแบบ `DESIGN_FLOW.md` (code block + `>` หมายเหตุ) · ขอมา 2 ครั้ง |
| **เกณฑ์ perf** | *"ขออย่าช้ามากก็โอเคแล้ว"* ⇒ สังเกต ไม่ใช่ประตู |
| **ชื่อ Bundle** | ตั้งชื่อตาม**ปัญหาที่แก้ ไม่ใช่เทคนิคที่ใช้** — `Save the Tracking Data` · `Score Each Person` · `Fix Split Runners` · `Long Run Test` |
| **ClickUp** | ส่งชื่อ task ไปแล้วเป็น `S0`–`S11` + `PRE-1`–`PRE-3` พร้อม dependency **อย่าเปลี่ยนรหัส** |
| **เครื่องมือ** | ⛔ **ห้ามใช้ AgentTool / Workflow** เว้นแต่ผู้ใช้ขอ (มีใน system instruction) |

---

## 8. ค้างอยู่

| # | เรื่อง | บล็อกอะไร |
|---|---|---|
| 1 | **รัน S0** — ต้องรู้ที่อยู่ของ `session_log.txt` / `tracks.csv` | บล็อกการตัดสินว่า Bundle 3 อยู่ใน scope ไหม |
| 2 | **`N` = คนพร้อมกันในโซนสูงสุด** — ยังว่าง | **ไม่บล็อกอะไร** (D2 ทำเสมอแล้ว) แค่ใช้อ่านผล |
| 3 | **`sourceOffsetUs`** — ยังไม่มีในโค้ด ต้องไปตรวจที่ splitter ว่ารู้ offset ไหม | บล็อก `chunks.csv` ให้สมบูรณ์ (S2) |
| 4 | **Exif privacy** — ยังไม่ตัดสิน · Exif ติดไปกับรูปที่ส่งลูกค้า | บล็อก S8 ส่วน Exif |
| 5 | **PRE-1/2/3** — test source set · คลิปยาว 3–4 ชม. · ทำเฉลยด้วยมือ | PRE-1 บล็อก S6 · PRE-2 บล็อก S11 |

---

## 9. ห้ามทำ

- ❌ **แก้ด้วยการทิ้ง candidate** เมื่อ staging เต็ม — pipeline offline ไม่มี deadline ให้ **backpressure**
- ❌ **วัด sharpness บน detect bitmap ที่ย่อแล้ว** — การย่อคือ low-pass filter ทำลายสิ่งที่กำลังวัด
- ❌ **เขียนไฟล์รวมด้วย `buildString`** — `sightings.csv` ที่ 3.5 ชม. ≈ 325k แถว ⇒ peak heap 60–90 MB
- ❌ **เขียนทับคอลัมน์ `track`** เมื่อเย็บรอยต่อ — เพิ่ม `personId` แยก เพื่อตรวจย้อนได้
- ❌ **ลดชั้น track ที่ยืนยันแล้วกลับเป็น `unconfirmed`** — ByteTrack ไม่มีเส้นทางนั้น ให้ปล่อยเป็น `lost`
- ❌ **สร้างเอกสารใหม่** — ยุบลง PLAN หรือ PHASES
- ❌ **commit/push** โดยไม่ได้รับอนุญาต — ยังไม่ commit อะไรเลยในเซสชันนี้

---

## 10. ประโยคเปิดที่แนะนำสำหรับ agent ถัดไป

> อ่าน `HANDOFF.md` แล้วต่อจาก S0 — ขอที่อยู่ของ `session_log.txt` และ `tracks.csv` จากรอบที่วัด `run4mins` / คลิปเส้นชัยกลางคืน เพื่อนับ 3 ตัวเลขของ S0 แล้วเขียน `reports/v0.1.7/s0_baseline.md`
