# v0.1.7 — Person ID เป็นแกน

**สถานะ:** วางแผน · ยังไม่เริ่มเขียนโค้ด

---

## ทำอะไร

> วันนี้ pipeline ถาม **"เฟรมนี้เก็บไหม"**
> v0.1.7 ให้มันถาม **"คนนี้ได้รูปที่ดีที่สุดของเขาหรือยัง"**

**เปลี่ยนแกนของ pipeline จาก "เฟรม" เป็น "คน"** — ทุกอย่างในเอกสารนี้ตามมาจากประโยคเดียวนั้น

## ภาพที่ชัดที่สุด — Tracking ย้ายจากท้ายมาอยู่ต้น

```
วันนี้    Detect → Gate → Sharpness → Quality → เซฟ → [Tracking] → Dedup → ลบตัวแพ้
                                                        ↑
                                          ตัดสินทุกอย่างไปแล้ว ค่อยรู้ว่าใครเป็นใคร

v0.1.7   Detect → [ByteTrack] → Gate → Sharpness → Quality → เซฟ ┊ Dedup → ลบตัวแพ้
                       ↑                ต่อคน      ต่อคน     ต่อคน  ┊
                รู้ว่าใครเป็นใครก่อน                              W2
```

**ย้ายบรรทัดเดียว แต่มันเปลี่ยนทุกด่านที่อยู่หลังมัน** — เพราะทุกด่านมีคนให้อ้างถึงแล้ว

ด่านที่เคยตัดสินว่า *"เฟรมนี้ผ่านไหม"* กลายเป็น *"คนนี้ในเฟรมนี้ผ่านไหม"* และเฟรมเดียวกันให้คำตอบต่างกันได้สำหรับคนต่างคน — ซึ่งเป็นสิ่งที่ frame-centric ทำไม่ได้เลยโดยโครงสร้าง

## ทำแล้วได้อะไรที่จับต้องได้

| | วันนี้ | v0.1.7 |
|---|---|---|
| คนที่วิ่งคู่กับคนตัวใหญ่กว่า | **0 รูป** — ไม่เคยชนะการเป็น subject | ได้รูปของตัวเอง |
| "ทำไมคนนี้ไม่ได้รูป" | ตอบไม่ได้ | `reject` ของเขาเอง |
| "detector หลุดไหม" | ตอบไม่ได้ | `framesMissed` |
| crash นาทีที่ 80 | ได้ **ศูนย์** | ได้ 80 นาที |
| นักวิ่งคร่อมรอยต่อ chunk | นับเป็นสองคน | คนเดียว (`personId`) |
| เอาไป overlay บนคอม | ไม่ได้ | ได้ (ผ่าน import path — [§3.5](#35--ข้อจำกัดสำหรับ-overlay-บนคอม--วิดีโอถูกลบ)) |

**ที่ไม่เปลี่ยน:** decode ยังรอบเดียว · `realtimeRatio` ไม่ควรขยับ (ต้นทุนใหม่คือ Laplacian ต่อคนเท่านั้น) · โควตา 3 รูป/หน้าต่าง 1 วิ เหมือนเดิม · `foot_track_net` ตัวเดิม · **จำนวนรูปที่เก็บได้ห้ามลด**

---

**ตัดสินใจแล้ว 6 ข้อ**

| # | ตัดสินว่า | เหตุผลอยู่ที่ |
|---|---|---|
| 1 | **แกนคือ person ID** ไม่ใช่เฟรม | [§1](#1-การเปลี่ยนแกน) |
| 2 | ใช้ `foot_track_net` ต่อ — ไม่เอา YOLO11n/YOLOv8n/EfficientDet-Lite/mmpose | [§9](#9-ทำไมไม่เปลี่ยน-detector) |
| 3 | port [FoundationVision/ByteTrack](https://github.com/FoundationVision/ByteTrack) (MIT) ให้ครบโครงสร้าง แต่เปลี่ยน metric เป็น DIoU ให้ทนอัตราสุ่ม 120 ms | [§5](#5-bytetrack-ที่-83-fps) |
| 4 | แยก 2 worker · **รอยต่ออยู่หลัง sharpness** — W1 แตะ pixel, W2 metadata ล้วน | [§4](#4-worker-1--worker-2--รอยต่ออยู่ตรงไหน) |
| 5 | เย็บ track ข้ามรอยต่อ chunk **ที่ metadata (ทาง B)** ไม่ใช่ถือ tracker state ข้าม chunk (A) และยังไม่ขยาย chunk (C) | [§6](#6-เย็บ-track-ข้ามรอยต่อ-chunk) |
| 6 | **ส่งเป็น 3 ก้อน** ไม่มัดรวม — ก้อนบันทึกคุ้มทำไม่ว่าก้อน tracker จะได้ผลหรือไม่ | [§7](#7-งานที่จะทำ) |

---

## 1. การเปลี่ยนแกน

### 1.1 วันนี้ pipeline เป็น frame-centric ไม่ใช่ person-centric

```kotlin
// VideoFrameProcessor.kt — หนึ่งเฟรม หนึ่ง subject หนึ่งคะแนน
val subjectBox = faceBox ?: torsoBox ?: personBox ?: return null
val sharpness  = scoreSharpness(upright, roi)               // ROI เดียว
return FrameCandidate(timestampUs, upright, sharpness, ...) // 1 candidate ต่อเฟรม
```

หน่วยของ pipeline คือ **เฟรม** ทุกด่านวนรอบคำถาม *"เฟรมนี้เก็บไหม"* ไม่ใช่ *"คนนี้ได้รูปหรือยัง"*

และ tracker ที่เพิ่มเข้ามาใน v0.1.6 เป็น **ผู้บริโภค ไม่ใช่แกน**:

```kotlin
// selectKeepers() — tracker รันหลังทุกอย่างเสร็จ เอา id ไปจัดกลุ่มเฉย ๆ
val tracker = SubjectTracker()
for ((_, frames) in byPts.groupBy { trackOf[it.timestampUs] ?: UNTRACKED })
```

track id ถูกใช้อย่างเดียว: **เป็น key แบ่งหน้าต่าง dedup** ถอด tracker ออก pipeline ยังเดินครบ แค่ dedup กลับไปนับตามนาฬิกา — นั่นคือนิยามของ "ไม่ใช่แกน"

### 1.2 ผลที่ตามมาซึ่งเป็นบั๊กจริง

**คนที่วิ่งคู่มากับคนที่ตัวใหญ่กว่าเสมอ จะไม่เคยได้เป็น subject** ⇒ ไม่เคยถูกให้คะแนน ⇒ **ได้ 0 รูป**
ทั้งที่ tracker เห็นเขาครบทุกเฟรม และ `tracks.csv` จะรายงานเขาเป็น `captured=false` โดยดูน่าเชื่อถือ

นี่คือปัญหาเดียวกับที่ v0.1.6 ตั้งใจแก้ (สองคนในวินาทีเดียว หน้าต่าง dedup ยุบทิ้ง) **มันไม่ได้หายไป มันย้ายจากระดับหน้าต่างลงไปอยู่ระดับเฟรม**

และมีอีกชั้นที่ทำให้เจ็บกว่าเดิม:

```kotlin
candidate.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)  // bitmap = upright = เฟรมเต็ม
```

**รูปที่เซฟคือเฟรมเต็ม ไม่ใช่ครอป** ⇒ รูปของคน A **มีคน B อยู่ในนั้นแล้ว** ข้อมูลไม่ได้หาย ระบบแค่มองไม่เห็น

### 1.3 person-centric เปลี่ยนอะไร

| | frame-centric (วันนี้) | **person-centric (v0.1.7)** |
|---|---|---|
| หน่วยของงาน | เฟรม | **คน** |
| detect | เลือก subject 1 คน | **ทุกคนเป็น subject** |
| gate | ตก ⇒ **ทิ้งทั้งเฟรม** | ตกเป็น**รายคน** — คนอื่นในเฟรมยังผ่านได้ |
| quality | 1 คะแนน/เฟรม | **1 คะแนน/(คน × เฟรม)** |
| เลือกรูป | จัดอันดับเฟรม → ค่อยจัดกลุ่มตาม track | **จัดอันดับเฟรมของคนนั้นโดยตรง** |
| `sightings.csv` | — | **ตารางหลักของระบบ** |
| คำถามที่ระบบตอบ | "เฟรมนี้ดีไหม" | **"คนนี้ได้รูปที่ดีที่สุดของเขาหรือยัง"** |

### 1.4 ราคา — ถูกกว่าที่ควรจะเป็น

| งาน | ต่อคนแล้วแพงขึ้นไหม |
|---|---|
| `uprightFullFrame()` full-res decode | ❌ **ฟรี** — ถอดครั้งเดียวต่อเฟรม ใช้ร่วมกันทุกคน |
| `centreOffset` · `edgeMargin` · `subjectRatio` | ❌ **ฟรี** — เลขคณิตล้วน คิดจากกล่องแต่ละคนได้ทันที |
| `confidence` | ❌ **ฟรี** — score ต่อคนมีอยู่แล้วหลัง S1 |
| `scoreSharpness()` Laplacian | ⚠️ **× จำนวนคน** — ROI เล็กบน bitmap ที่ถอดไปแล้ว |

**4 ใน 5 เทอมของ `FrameQuality` คิดต่อคนได้ฟรี** เหลือ sharpness ตัวเดียวที่ต้องจ่าย

> **จำนวนเฟรมที่ถึง full-res ไม่ควรเปลี่ยน** — gate ที่กันไว้เป็น size-based และ subject วันนี้คือ*คนที่ใหญ่ที่สุด* ถ้าคนใหญ่สุดตก `too_small` คนอื่นก็ตกหมด เซตของเฟรมที่ผ่านด่าน detect-space จึงเกือบเท่าเดิม **ต้นทุนที่เพิ่มคือ Laplacian ต่อคน ไม่ใช่ decode**

และ sharpness ต่อคนไม่ใช่ความละเอียดเกินจำเป็น — **motion blur เป็นคุณสมบัติของวัตถุ ไม่ใช่ของเฟรม** นักวิ่งเบลอขณะที่มาร์แชลข้าง ๆ คมชัด ค่าเดียวทั้งเฟรมคือการวัดผิดตั้งแต่ต้น

### 1.5 สัญญาณว่าการเปลี่ยนแกนนี้ถูก

ระหว่างออกแบบ เราเคยจะเพิ่มคอลัมน์ `is_subject` เพื่อแยก *"ไม่ได้รูปเพราะเบลอ"* ออกจาก *"ไม่เคยถูกพิจารณา"*

**พอเปลี่ยนแกนเป็นคน คอลัมน์นั้นหายไปเอง** เพราะทุกคนถูกพิจารณา — การออกแบบที่ถูกทำให้คอลัมน์**หายไป** ไม่ใช่เพิ่มขึ้น

---

## 2. Flow — ทีละขั้น

### Worker 1 · ติดตาม (ต่อ chunk · แตะ pixel · decode รอบเดียว)

#### 1. Frame Sampling
```
สุ่มทุก           120 ms (≈ 8.3 fps)
decoder          ถอดทุกเฟรม — เฟรมที่ไม่สุ่มแค่ไม่ render
```
> ไม่เปลี่ยน แต่ 8.3 fps คือสาเหตุที่ ByteTrack ตรง ๆ ใช้ไม่ได้ (§5)

#### 2. Detection — 🆕 คืนสองชั้น
```
โมเดล            foot_track_net (NPU) · face_det_lite (NPU)
ชั้นสูง            score > 0.5   → tracker + gate
ชั้นต่ำ            0.1 – 0.5     → tracker เท่านั้น
```
> ชั้นต่ำคือคนที่โดนบังครึ่งตัว/วิ่งไกล วันนี้ถูกกรองทิ้งใน detector **กล่องคะแนนต่ำคือทั้งหมดที่ ByteTrack เพิ่มจาก SORT**

#### 3. ByteTrack → person ID — 🆕 **แกนของทั้งระบบ**
```
รอบ 1            ชั้นสูง vs tracked + lost      · DIoU · fuse score
รอบ 2            ชั้นต่ำ vs ตัวที่รอบ 1 ไม่ได้คู่   · DIoU
รอบ 3            unconfirmed vs ชั้นสูงที่เหลือ

เปิด track ใหม่    ชั้นสูงเท่านั้น
track หายได้       ~1 วินาที แล้วกลับมาใช้ id เดิม
```
> ต่างจาก v0.1.6 ตรงที่ **id ถูกกำหนดก่อนทุกด่าน** ไม่ใช่หลัง — ทุกอย่างต่อจากนี้อ้างถึง id

#### 4. หน้า ↔ ตัว — 🆕 จับคู่ทุกคน
```
วันนี้             หน้าใหญ่สุด 1 กล่อง → หาตัวที่ครอบมัน → subject
v0.1.7           หน้าทุกกล่อง → จับคู่กับตัวทุกกล่อง → ทุกคนมี faceScore ของตัวเอง
```
> **นี่คืองานจริง ไม่ใช่การเปลี่ยนชื่อตัวแปร** — และข้ามไม่ได้ เพราะหน้าคือสิ่งที่ทำให้รูปขายได้

#### 5. Gate + Quality — 🆕 ต่อคน
```
gate ต่อคน        zone · size · cropped · no_face
sharpness        ROI ของแต่ละคน (Laplacian × จำนวนคน)
quality          FrameQuality 5 เทอม  น้ำหนัก 0.40 / 0.20 / 0.15 / 0.15 / 0.10
                 sharpness  size  centre  confidence  framing
```
> เฟรมไม่ถูก "ปฏิเสธ" อีกต่อไป — **คนในเฟรมต่างหากที่ถูกปฏิเสธ** และคนอื่นในเฟรมเดียวกันยังผ่านได้

#### 6. เขียนบันทึก — 🆕
```
sightings.csv    1 แถว = คน × เฟรม   ← ตารางหลัก
tracks.csv       1 แถว = 1 passage
chunks.csv       1 แถว = 1 chunk     ← กุญแจแปลพิกัดกลับ
                                      → ส่งคิว Worker 2
```
> **จุดที่เปลี่ยนสถาปัตยกรรม** — เดิมข้อมูลนี้อยู่ในตัวแปรแล้วหายไป เขียนลงไฟล์แล้ว crash นาทีที่ 80 ยังได้ 80 นาที

### Worker 2 · เลือกรูป (ต่อ chunk · metadata ล้วน · ไม่แตะ pixel)

#### 7. เลือก 3 รูปดีที่สุด **ของแต่ละคน**
```
อ่าน              csv ของ chunk ตัวเอง
จัดกลุ่ม            ตาม person ID
หน้าต่าง           1 วินาที · เก็บ 3
เกณฑ์             FrameQuality qTotal ของคนนั้น
```
> ตรรกะนี้ส่งไปแล้วใน v0.1.6 — เปลี่ยนสองอย่าง: อ่าน input จากไฟล์ และ **qTotal เป็นของคนนั้นจริง ๆ ไม่ใช่ของเฟรม**

#### 8. ลบตัวที่แพ้ — 🆕 มีข้อยกเว้น
```
ลบทันที           person ที่ไม่แตะขอบ chunk
เก็บค้างไว้         person ที่แตะขอบ → รอขั้นที่ 9
```
> ถ้าลบหมด นักวิ่งที่ถูกหั่นครึ่งจะได้ **6 รูป** (3 จากแต่ละฝั่ง) — บั๊กที่หน้าตาเหมือนทำงานถูก

### Stage 3 · เย็บ (ตอนจบ session · ไม่แตะ pixel)

#### 9. เย็บรอยต่อ chunk
```
ปัญหา             chunk ที่ 4K ยาว ~10–12 วิ (50 MB ไม่ใช่หน่วยเวลา)
วิธี               DIoU + extrapolate ด้วย velocity ข้าม gap — ฟังก์ชันเดียวกับขั้นที่ 3
ผลลัพธ์            personId — คอลัมน์เพิ่ม ไม่เขียนทับ track
```

#### 10. เขียนไฟล์รวม
```
sightings.csv · tracks.csv · photos.csv · chunks.csv    (streaming write ไม่ใช่ buildString)
```

---

## 3. ไฟล์ — 3 ตาราง + 1 กุญแจ

### 3.1 `sightings.csv` — ตารางหลัก · 1 แถว = คน × เฟรม

```
chunk, track, ptsUs, left, top, right, bottom, score, tier, faceScore,
reject, sharpness, quality, qSharpness, qSize, qCentre, qConfidence, qFraming, is_capture
```

| กลุ่ม | คอลัมน์ | ว่างได้ไหม |
|---|---|---|
| ระบุตัว | `chunk` `track` `ptsUs` | ไม่ |
| ByteTrack | `left` `top` `right` `bottom` `score` `tier` | ไม่ — normalised 0..1 · `tier` = `high`/`low` |
| หน้า | `faceScore` | ว่างถ้าจับคู่หน้าไม่ได้ |
| การตัดสิน | `reject` | ไม่ — `candidate` · `out_of_zone` · `too_small` · `cropped` · `no_face` · `too_soft` · `roi_invalid` |
| คะแนน | `sharpness` `quality` + 5 เทอม | **ว่างเมื่อตกด่าน detect-space** (ยังไม่ถึง full-res) |
| ผล | `is_capture` | ไม่ — 0/1 |

**ทำไมเก็บ 5 เทอมด้วยทั้งที่แถวเยอะ:** นี่คือโอกาสเดียวที่จะเห็น**ตัวที่แพ้** `photos.csv` เก็บแต่ผู้ชนะ ⇒ จูนน้ำหนักไม่ได้เลยถ้าไม่รู้คะแนนของตัวที่ถูกทิ้ง เหตุผลเดียวกับที่ `photos.csv` เก็บส่วนประกอบไว้แต่แรก

> **`is_subject` ไม่มี** — ใน person-centric ทุกคนเป็น subject คอลัมน์นี้จึงไม่มีความหมาย (§1.5)

### 3.2 `tracks.csv` — 1 แถว = 1 passage

| กลุ่ม | ✅ มีแล้ว | 🆕 v0.1.7 |
|---|---|---|
| ระบุตัว | `chunk` `track` | **`personId`** — session-scoped หลังเย็บ |
| เวลา | `firstUs` `lastUs` `durationUs` | **`enteredMs` `exitedMs` `visibleMs` `sessionEnteredMs`** |
| เฟรม | `frames` | **`framesSeen`**(เปลี่ยนชื่อ) **`framesSpan` `framesMissed` `trackedRatio`** |
| ตำแหน่ง | `firstX/Y` `lastX/Y` `closestToCentre` `meanHeight` | — |
| การเคลื่อนที่ | `velX` `velY` `speed` `directionDeg` `direction` `displacement` | **`speedMps`** |
| detector | — | **`meanScore` `lowTierFrames`** |
| ผล | `movedThrough` `likelySubject` `captured` `photos` | — |

### 3.3 `photos.csv` — 🆕 เปลี่ยนความหมาย

```
1 แถว = (file × personId)   ไม่ใช่ 1 แถว = 1 file
```

เฟรมเดียวที่มีนักวิ่งดี ๆ 3 คน = **1 ไฟล์ที่ 3 คนอ้างถึง** ไม่ใช่ 3 ไฟล์ ⇒ ดิสก์ไม่โต แต่ semantics เปลี่ยน
คอลัมน์เดิมครบ + `personId`

### 3.4 `chunks.csv` — กุญแจ ไม่ใช่ข้อมูล

```
chunk, videoFileName, recordedAtEpochMs, durationMs, sourceOffsetUs,
rotationDeg, detectW, detectH, sampleIntervalMs
```

~547 แถวสำหรับคลิป 93 นาที · **~45 KB** — มีไว้ให้อีกสามไฟล์แปลกลับเป็นพิกัดวิดีโอได้

| คอลัมน์ | ทำไมต้องมี |
|---|---|
| `rotationDeg` | `uprightDetectBitmap()` **หมุนภาพ**ก่อน detect ⇒ ถ้าฝั่งคอมไม่รู้ กล่องจะไปคนละที่ · chunk ที่อัดเองรายงาน 0° แต่คลิป import ไม่แน่ |
| `detectW` `detectH` | ยืนยัน aspect ที่ normalise มา (x หาร width, y หาร height — ไม่ใช่จตุรัส) |
| `sourceOffsetUs` | chunk นี้เริ่มที่วินาทีไหนของต้นฉบับ ⇒ overlay บนต้นฉบับได้โดยไม่ต้องเก็บ chunk |
| `sampleIntervalMs` | ฝั่งคอมรู้ว่าต้อง interpolate กี่เฟรมระหว่าง sample |

> ⚠️ `sourceOffsetUs` **ยังไม่มีในโค้ด** ต้องไปตรวจที่ splitter ว่าตอนตัดรู้ offset ไหม ถ้าไม่รู้แล้วประมาณจาก `durationMs` สะสม จะคลาดเคลื่อนสะสม — **ตรวจก่อน ไม่ใช่สมมติ**

### 3.5 ⛔ ข้อจำกัดสำหรับ overlay บนคอม — วิดีโอถูกลบ

```kotlin
// CapturePipelineCoordinator.kt:417
private fun releaseChunkFile(item: ChunkWorkItem) {
    if (KEEP_PROCESSED_CHUNKS) return    // ← private const = false
    file.delete()
}
```

ไม่ใช่บั๊ก — [RELEASE_0_1_4](./RELEASE_0_1_4.md) แก้เข้ามาเพราะไม่ลบแล้วกอง **~25 GB ที่ 1 ชม.**

| เส้นทาง | ยังมีวิดีโอไหม |
|---|---|
| **Live** | ❌ chunk คือสำเนาเดียว ลบแล้วเหลือแต่ JPEG |
| **Import / Network URL** | ✅ ไฟล์ต้นฉบับยังอยู่ — ใช้ `sourceOffsetUs` seek ได้ |

⇒ **ทำ overlay จากเส้นทาง import** ไม่ต้องเก็บ chunk เลย · ถ้าอยากได้จาก live ต้องเปลี่ยน `KEEP_PROCESSED_CHUNKS` เป็น toggle ตอนรันแล้วเปิดเฉพาะ session ทดสอบสั้น ๆ — **เป็น workflow ของ dev ไม่ใช่ของงานจริง**

### 3.6 ⚠️ ต้องเขียนต่อ chunk แบบ streaming ไม่ใช่สะสมในหน่วยความจำ

วันนี้ `trackRows` / `photoRows` สะสมทั้ง session ในหน่วยความจำแล้วเขียนครั้งเดียวตอน drain ผ่าน `buildString`

ที่ scale ปัจจุบันไม่มีปัญหา (`photos.csv` ~2,200 แถว) แต่ **`sightings.csv` ~144,000 แถว ≈ 15 MB** ⇒ String เป็น UTF-16 ⇒ 30 MB ⇒ `StringBuilder` ขยายแบบเบิ้ล ⇒ **peak ~60–90 MB heap ตอน drain** คือหลังเครื่องร้อนมา 93 นาทีและยังถือ bitmap 4K อยู่

และปัญหาที่ใหญ่กว่าขนาด: **เขียนตอนจบ session อย่างเดียว = crash นาทีที่ 80 ได้ศูนย์** ซึ่งทำลายเหตุผลข้อแรกที่แยก worker และ [CHANGELOG](./CHANGELOG.md) บันทึก **NA-05** ไว้แล้วว่า drain ที่ไม่ทำงาน = session ที่ไม่เขียนไฟล์อะไรเลย

```
session/
  sightings/c0007.csv   ← W1 เขียนจบ chunk แล้วปิด → enqueue W2
  tracks/c0007.csv
  chunks.csv            ← append ต่อ chunk
  sightings.csv         ← stage 3 merge ตอนจบ (BufferedWriter ไล่แถว)
```

> **ใครลบ `sightings/` `tracks/` และ staging candidate** ต้องเขียนไว้ให้ชัดและผูกกับ cleanup ตัวเดียวกัน — repo นี้โดนบั๊ก "ไม่มีใครลบ" มาแล้ว 2 ครั้ง

---

## 4. Worker 1 / Worker 2 — รอยต่ออยู่ตรงไหน

**รอยต่ออยู่หลัง sharpness ไม่ใช่หลัง detect** ด้วยเหตุผลสองข้อที่แยกกัน:

**(ก) `score` ของ detector ไม่ใช่คุณภาพรูป** — `foot_track_net` score บอกว่า *"มั่นใจว่านี่คือคน"* ไม่ได้บอกว่า *"รูปนี้ชัด"* นักวิ่งเบลอได้ score สูงได้สบาย ตัวที่ตัดสินคือ sharpness ซึ่ง **ต้องใช้ pixel เต็มความละเอียด**

> **ห้ามวัด sharpness บน detect bitmap ที่ย่อแล้ว** — การย่อคือ low-pass filter มันทำลายความถี่สูงซึ่งเป็นสิ่งเดียวที่ sharpness วัด และ `MIN_SHARPNESS` ทุกค่าจูนบน full-res

**(ข) decode รอบสองแพงเกินไป** — `jpeg_argb_full` คือ 89.5% ของ wall time ใน v0.1.4 · `realtimeRatio` ตอนนี้ **0.502** · decode pass ที่สองดันไป ~1.0 ซึ่ง [SEQUENCE_FLOW](./SEQUENCE_FLOW.md) เขียนว่า **"≥ 1.0 = คิวจะบวมแน่นอน"** และ seek ทีละ PTS แพงกว่า decode เรียง

**ราคาที่จ่ายแทน:** พื้นที่ดิสก์ชั่วคราวของ candidate ที่จะถูกลบ — วันนี้ก็เขียนแล้วลบอยู่แล้ว แค่ลบช้าลง

**สิ่งที่ได้จากการแยก:**
1. บันทึกอยู่รอดแม้ capture ล้ม
2. ทำให้เย็บรอยต่อได้ (§6)
3. เลือกรูปใหม่ได้โดยไม่ต้อง detect ใหม่ — รัน W2 ซ้ำบน csv เดิม

---

## 5. ByteTrack ที่ 8.3 fps

### 5.1 `BYTETracker.update()` ของจริง (ตรวจกับ repo แล้ว)

| ขั้น | pool | metric | thresh | fuse_score |
|---|---|---|---|---|
| แบ่งชั้น | — | high `> track_thresh` (**0.5**) · low `0.1 < s < 0.5` | — | — |
| Stage 1 | `tracked + lost` | `iou_distance` | `match_thresh` = **0.8** | ✅ |
| Stage 2 | track state=Tracked ที่ stage 1 ไม่ได้คู่ | `iou_distance` | **0.5** | ❌ |
| Stage 3 | **unconfirmed** vs high dets ที่เหลือ | `iou_distance` | **0.7** | ✅ |

- assignment = **`lap.lapjv()` Hungarian** · motion = **Kalman 8 มิติ** `(x, y, a, h, ẋ, ẏ, ȧ, ḣ)`
- `fuse_score`: `cost = 1 − (iou_sim × det_score)` ⇒ **score ถูกใช้สองที่** ทั้งแบ่งชั้นและถ่วง cost
- track ใหม่จาก high tier เท่านั้น: `score ≥ det_thresh = track_thresh + 0.1 = 0.6`
- lost อยู่ใน pool stage 1 ได้ `max_time_lost = int(fps/30 × track_buffer)`, `track_buffer=30` ⇒ **~1 วินาที** แล้ว `re_activate()` **คืน id เดิม**
- **unconfirmed**: track จาก detection แรกยังไม่ยืนยัน ไม่ match เฟรมถัดไป = **ลบทิ้ง**

### 5.2 ปัญหา — คำนวณได้ ไม่ต้องเดา

นักวิ่ง 3 m/s ที่ 120 ms ขยับ **0.36 m** · กล่องคนกว้าง ~0.5–0.6 m:

```
overlap = (0.6 − 0.36) / 0.6 = 0.40
IoU     = 0.40 / (2 − 0.40) ≈ 0.15 – 0.25
```

**อัตราส่วนนี้ไม่ขึ้นกับระยะ** — คนไกลกล่องเล็กลง แต่ระยะที่ขยับใน pixel เล็กลงตามอัตราเดียวกัน

เทียบกับ `match_thresh = 0.8` (⇒ ต้องการ IoU ≥ 0.2) และ `fuse_score` ทำให้ยากขึ้นอีก: `cost = 1 − (0.2 × 0.9) = 0.82 > 0.8` ⇒ **ไม่ผ่าน**

> **หลักฐานยืนยัน:** `SubjectTracker.DEFAULT_IOU = 0.2f` พร้อมคอมเมนต์ *"Low on purpose"* — โค้ดที่มีอยู่คลำเจอ 0.2 จากการวัดจริง **ตรงกับที่เรขาคณิตทำนาย**

⇒ ByteTrack ที่ 120 ms **ไม่ได้พังเพราะ IoU = 0** มันพังเพราะ **IoU ≈ 0.2 อยู่ตรงเส้นพอดี**

### 5.3 ทางแก้ 1 ⭐ — `iou_distance` → **DIoU**

```
DIoU = IoU − ρ²(c₁, c₂) / d²      (ρ = ระยะ centre, d = เส้นทแยงมุมกล่องครอบ)
```

`SubjectTracker` pass 2 (centre distance) กับ DIoU **คือไอเดียเดียวกัน** — repo นี้คิดขึ้นเองเพราะเจอปัญหาเดียวกัน DIoU แค่เขียนเป็น **metric เดียวต่อเนื่อง** แทน pass ที่สอง

⇒ โครงสร้าง ByteTrack **คงไว้ครบ** และ **ไม่ต้องมี stage ที่ 4 นอกสเปก** · แก้ฟังก์ชันเดียว ต้นทุน throughput = 0

### 5.4 ทางแก้ 2 — แก้ค่าคงที่ที่ผูกกับ 30 fps

| ที่ | ปัญหาที่ 8.3 fps | แก้ |
|---|---|---|
| `max_time_lost` | parameterize ไว้แล้ว | ป้อน fps จริง (8.33) → 8 เฟรม ≈ 1 วิ |
| Kalman `_std_weight_position=1/20` · `_std_weight_velocity=1/160` | **per-frame noise** · dt ยาวขึ้น 3.6× แต่ noise เท่าเดิม ⇒ ฟิลเตอร์**มั่นใจเกินจริง** ⇒ gate แคบ ⇒ หลุด match | คูณ process noise ตาม dt |
| `match_thresh = 0.8` | จูนบน distribution ของ 30 fps | จูนใหม่ — §5.2 บอกว่าต้องหลวมกว่า |

**ข้อกลางอันตรายที่สุดเพราะพังเงียบ** — ฟิลเตอร์ยังทำงาน ตัวเลขยังออก แค่ match น้อยลง

### 5.5 ทางแก้ 3 — ลด sample interval (ไม่ทำใน v0.1.7)

decoder **ถอดทุกเฟรมอยู่แล้ว** ([VideoFrameSampler.kt:328](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameSampler.kt#L328)) ⇒ สุ่มถี่ขึ้นไม่ต้องจ่ายค่า decode เพิ่ม จ่ายแค่ conversion + detect (~0 ms บน NPU)

**แต่แก้คนละกลุ่มกับ §5.3:**
- §5.3 แก้ **คนที่ถูกเห็นหลายเฟรมแต่ match ไม่ติด**
- §5.5 แก้ **คนที่ถูกเห็นเฟรมเดียว** — ซึ่ง **tracker ตัวไหนก็ช่วยไม่ได้** เพราะเป็นข้อจำกัดของการสุ่ม

⇒ ทำ §5.3 + §5.4 ก่อน **วัด** แล้วค่อยเถียงด้วยตัวเลข

### 5.6 สิ่งที่ ByteTrack เพิ่มจริง — พูดให้ตรง

| | มีใน `SubjectTracker` แล้ว | ByteTrack เพิ่ม |
|---|---|---|
| ทำนายด้วย velocity · IoU กับกล่องทำนาย · เก็บตกด้วยระยะ centre | ✅ | DIoU แค่เขียนใหม่เป็น metric เดียว |
| Hungarian · Kalman 8 มิติ | ❌ | **เราไม่ทำใน v0.1.7** |
| **ชั้นคะแนนต่ำ** · **`unconfirmed`** · **`lost` + คืน id เดิม** | ❌ | ✅ **นี่คือของจริง 3 อย่าง** |

**ในสามอย่างนั้น ตัวที่ตรงกับปัญหาที่วัดได้มากที่สุดคือ `unconfirmed`** (ฆ่า track ที่เห็นเฟรมเดียว) ไม่ใช่ชั้นคะแนนต่ำ

คุณค่าที่เหลือของการ port เต็มรูปคือ **maintainability** — มีเปเปอร์ มีคนรู้จัก คนที่มาอ่านโค้ดทีหลังเข้าใจได้เร็ว **เป็นเหตุผลที่ดี แต่ไม่ใช่เหตุผลเรื่องคุณภาพ ควรเรียกให้ถูก**

---

## 6. เย็บ track ข้ามรอยต่อ chunk

`CHUNK_TARGET_BYTES = 50 MB` เป็น **หน่วยขนาด ไม่ใช่เวลา** ⇒ ที่ 4K30 chunk ยาว **~10–12 วินาที**

```
run4mins      273 s / 23 chunk = 11.9 s/chunk
คลิปกลางคืน    5,580 s / ~547   = 10.2 s/chunk
```

**เคยเขียนไว้ผิดและแก้แล้ว:** เดิมอ้างว่าแก้ไม่ได้เพราะ worker รันขนาน **ไม่จริง** — [CapturePipelineCoordinator.kt:245](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt#L245) เป็น `for (item in videoQueue)` consumer เดียว **ประมวลผล chunk ทีละอันตามลำดับ** ที่รันขนานคือ detect worker *ภายใน* chunk

| | ทำอะไร | ตัดสิน |
|---|---|---|
| **A** | ไม่ reset tracker ระหว่าง chunk — `lost` + `re_activate()` คืน id เดิมให้เอง | ❌ แม่นกว่าและทำได้จริง **แต่มัด chunk เข้าด้วยกัน** (chunk 5 ล้ม → chunk 6 ผิด) และทำให้ W1 ไม่เป็น per-chunk |
| **B** ⭐ | เย็บที่ metadata หลังทุก chunk เสร็จ — DIoU + extrapolate ด้วย velocity **โค้ดตัวเดียวกับ §5** | ✅ **เลือกข้อนี้** — chunk ยังอิสระ · ไม่ขึ้นกับลำดับ · รันซ้ำได้ · ได้มาเกือบฟรีเพราะ `sightings.csv` มีอยู่แล้ว |
| **C** | ขยาย `CHUNK_TARGET_BYTES` 50 → 200 MB ⇒ chunk ~45 วิ ⇒ รอยต่อลดลง 4 เท่า | ⏸ **[§11](#11-ค้างไว้--ต้องกลับมาดู)** |

**ออกแบบ:** อย่าเขียนทับ `track` — **เพิ่ม `personId` ที่เป็น session-scoped** แล้ว map `(chunk, track) → personId` ⇒ ตรวจย้อนได้ว่าเย็บถูกไหม และรันเย็บใหม่ได้โดยไม่เสียข้อมูลต้นทาง

---

## 7. งานที่จะทำ

### S0 · วัด 3 อย่างก่อนเขียนโค้ดบรรทัดแรก 📏

ไม่ต้องแก้โค้ด ข้อมูลอยู่ในไฟล์ที่ pipeline เขียนไว้แล้ว

**(ก) gap ที่รอยต่อ chunk = กี่ ms** — จาก `session_log.txt`: `chunk[N].recordedAtEpochMs + recordDurationMs` เทียบ `chunk[N+1].recordedAtEpochMs`

| ผล | แปลว่า |
|---|---|
| < 120 ms | เหมือน sample หายไปหนึ่งอัน — เดินหน้า S8 ได้ |
| 120–600 ms | เย็บได้ แต่ `max_time_lost` ต้องครอบถึง |
| **> 600 ms** | ⛔ **B เย็บไม่ติด** ⇒ ต้องไป C ก่อน |

**(ข) วันนี้ถูกหั่นกี่คน** — จาก `tracks.csv`: track ที่ `lastUs` ห่างท้าย chunk < 120 ms คู่กับ track ใน chunk ถัดไปที่ `firstUs` < 120 ms

**(ค) วันนี้ track แตกแค่ไหน** — จาก `tracks.csv`: นับ `frames == 1` เทียบจำนวน track ทั้งหมด

> **(ค) คือตัววัดว่า S2/S3 คุ้มไหม** ถ้าต่ำอยู่แล้ว **ByteTrack แทบไม่มีอะไรให้แก้** และควรทำแค่ก้อนที่ 1 — ประหยัดงานครึ่งหนึ่งโดยไม่เสียอะไร

---

### 📦 ก้อนที่ 1 — บันทึกและหลักฐาน · ความเสี่ยงต่ำ คุณค่าแน่นอน

**คุ้มทำไม่ว่าก้อนที่ 2 จะได้ผลหรือไม่ และไม่ต้องพึ่งกัน**

| slice | ทำอะไร | ทดสอบ |
|---|---|---|
| **S1** | `score` เดินทางครบสาย — `TrackedBox` เพิ่ม `score` · `Sighting.boxes` รับ `DetectedPerson` แทน `Rect` เปล่า (แก้ [บรรทัด 613](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt#L613)) | ล็อก score ทุกกล่องหนึ่ง chunk ต้องไม่มีตัวไหนเป็นค่า default ปลอม |
| **S2** | ไฟล์ต่อ chunk + **streaming write** (§3.6) · `chunks.csv` · ผูก cleanup ให้ชัด | crash กลาง session แล้วต้องได้ข้อมูลถึง chunk ล่าสุด · peak heap ไม่ขึ้นตามความยาว session |
| **S3** | แยก W1/W2 (§4) — W2 อ่าน csv ของ chunk ตัวเอง | จำนวนรูปที่เก็บได้ **ไม่เปลี่ยนเลย** |
| **S4** | `tracks.csv` คอลัมน์ใหม่ (§3.2) · `frames` → `framesSeen` + บรรทัด `#` เตือนการเปลี่ยนชื่อ · `speedMps` | ตัวเลขบนจอกับในไฟล์ตรงกัน |

---

### 📦 ก้อนที่ 2 — เปลี่ยนแกนเป็น person · ความเสี่ยงกลาง คุณค่ายังเป็นสมมติฐาน

| slice | ทำอะไร | ทดสอบ |
|---|---|---|
| **S5** | detector คืนสองชั้น — `lowScoreFloor` = **absolute 0.1 ตาม ByteTrack** · เฉพาะ tracker เห็นชั้นล่าง | จำนวนรูป **ไม่เปลี่ยนเลย** ถ้าเปลี่ยน = ชั้นล่างรั่วเข้า gate |
| **S6** | `SubjectTracker` → ByteTrack (§5) — สามชั้น · lost/`re_activate` · unconfirmed · `fuse_score` · **DIoU** · ค่าคงที่ตาม fps จริง · **ยังไม่ทำ Hungarian/Kalman** | S0(ค) ต้องลดลง |
| **S7** | **หน้า ↔ ตัว จับคู่ทุกคน** — เลิกยุบ face เหลือกล่องใหญ่สุด | ทุก person ที่มีหน้าในเฟรมต้องได้ `faceScore` ของตัวเอง |
| **S8** | **quality ต่อคน** (§1.3–1.4) — gate ต่อคน · sharpness ต่อ ROI · `FrameQuality` ต่อคน · `sightings.csv` เต็มรูป (§3.1) · `photos.csv` เป็น (file × personId) | คนที่วิ่งคู่กับคนตัวใหญ่กว่า **ต้องได้รูป** · `realtimeRatio` ห้ามแย่ลงเกิน 5% |

---

### 📦 ก้อนที่ 3 — เย็บรอยต่อ · ทำเมื่อ S0(ก) ผ่านเกณฑ์เท่านั้น

| slice | ทำอะไร | ทดสอบ |
|---|---|---|
| **S9** | เย็บรอยต่อ (§6) — stage 3 ตอนจบ session · ออก `personId` เป็นคอลัมน์เพิ่ม · W2 ค้าง candidate ของ person ที่แตะขอบไว้ก่อน | นักวิ่งที่ S0(ข) ระบุว่าถูกหั่น ต้องได้ `personId` เดียวกัน **และได้ 3 รูป ไม่ใช่ 6** |
| **S10** | อัปเดต [DOCS.md](./DOCS.md) · [ROADMAP.md](./ROADMAP.md) · [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) · [DESIGN_FLOW.md](./DESIGN_FLOW.md) | drift check ผ่าน |

---

## 8. เกณฑ์ว่าสำเร็จ

รันซ้ำบนคลิปที่ v0.1.6 วัดไว้ (`run4mins` 4:33 · 108 รูป · 23 chunk · ช่วง 10–13 นาทีของคลิปเส้นชัยกลางคืน: `passages=309 movedThrough=48 likelySubject=49 captured=33`)

| ตัววัด | ทิศทางที่ถูก | ทำไม |
|---|---|---|
| **คนที่ `captured=false` ทั้งที่อยู่ในเฟรมชัด ๆ** | **ลดลง** | เป้าหมายหลักของการเปลี่ยนแกน |
| track ที่ `framesSeen == 1` | **ลดลง** | `unconfirmed` ฆ่าโดยตรง |
| `passages` รวม | **ลดลง** | คนเดิมถูกรวมกลับเป็น track เดียว |
| จำนวนรูปที่เก็บได้ | **ไม่ลด** | tracking ไม่ควรแตะจำนวนรูป |
| รูปต่อ `personId` | **ไม่เกิน 3 ต่อหน้าต่าง** | ถ้าเกิน = §6 พลาด คนที่ถูกหั่นได้ 6 รูป |
| `realtimeRatio` | **ไม่แย่ลงเกิน 5%** | Laplacian ต่อคนเป็นต้นทุนเดียวที่เพิ่ม |
| **peak heap** | **ไม่ขึ้นตามความยาว session** | §3.6 |
| **พื้นที่ staging สูงสุด** | **ต้องวัดและมีเพดาน** | ผลข้างเคียงใหม่จากการแยก worker |

**ตัวตัดสินว่าคุ้ม:** `framesMissed` รวมทั้ง session ก่อน/หลัง — ถ้าไม่ลด แปลว่า detector ไม่ได้หลุดตั้งแต่แรก **ให้ถอย ไม่ใช่จูนต่อ**

> ⚠️ **ทางที่ v0.1.7 น่าจะพังในสนามมากที่สุดคือดิสก์เต็ม ไม่ใช่ tracking ผิด** — v0.1.7 เพิ่มจุดที่ต้องลบไฟล์อีก 2 จุด (staging candidate · csv ต่อ chunk) และ repo นี้โดนบั๊ก "ไม่มีใครลบ" มาแล้ว 2 ครั้ง โดย `hasStorageForRecording()` เช็คครั้งเดียวตอนเริ่มและไม่มีอะไรหยุดมันกลางคัน

---

## 9. ทำไมไม่เปลี่ยน detector

| ตัวเลือก | เหตุผลที่ไม่เอา |
|---|---|
| YOLO11n / YOLOv8n | **AGPL-3.0** — ต้องเคลียร์ license ก่อนคุย mAP · postprocess DFL 16-bin quantize แล้วเพี้ยนง่าย · ยังไม่มีหลักฐานว่า recall ไม่พอ |
| EfficientDet-Lite | `TFLite_Detection_PostProcess` เป็น custom op ที่ **QNN delegate ไม่รับ** ⇒ ตกลง CPU ทิ้งข้อได้เปรียบ NPU ทั้งหมด · โมเดลปี 2020 |
| mmpose | framework ฝั่ง Python ไม่ใช่ runtime บนมือถือ · top-down ต้องมี person detector นำอยู่ดี · **17 keypoints + visibility อยู่ในกราฟ `foot_track_net` แล้ว** แค่ยังไม่ decode |

**เหตุผลหลัก:** `foot_track_net` บน NPU ต้นทุนเพิ่ม **0 ms** (30,484 vs 30,498 ms คลิปเดียวกัน — pipeline ติดที่ decoder) และคืนกล่อง+score ของ**ทุกคนในเฟรม** ซึ่งคือ input ทั้งหมดที่ ByteTrack ต้องการ **ByteTrack ไม่แคร์ว่า detector เป็นตัวไหน**

---

## 10. สิ่งที่ v0.1.7 จะ**ไม่**แก้

1. **การเย็บรอยต่อเป็นค่าประมาณ** — คนที่เปลี่ยนความเร็ว/ถูกบังพอดีตรงรอยต่อจะยังถูกหั่น และถ้า S0(ก) วัดได้ gap > 600 ms **S9 จะไม่ถูกทำเลย**
2. **ไม่มี re-identification** — หายนานกว่า `max_time_lost` กลับมาเป็นคนใหม่ · **ByteTrack ตัวจริงก็ไม่มี ReID** (นั่นคือ BoT-SORT / Deep OC-SORT)
3. **คนยืนนิ่งยังถูก track** — `movedThrough` / `likelySubject` ยังเป็นตัวกรองเดิม
4. **รูปยังเป็นเฟรมเต็ม ไม่ครอปต่อคน** — person-centric เปิดทางไว้แล้ว แต่ยังไม่ทำ

⇒ `tracks.csv` ยังนับ **passages ไม่ใช่คน** และยังนับสูงกว่าจริง — คงบรรทัด `#` เตือนไว้

---

## 11. ค้างไว้ — ต้องกลับมาดู

### 🔁 C · ขยาย `CHUNK_TARGET_BYTES` — เลื่อนไว้ ไม่ใช่ปฏิเสธ

**อยู่ที่:** [StreamResolution.kt:26](../shared/src/commonMain/kotlin/com/autobots/camera/StreamResolution.kt#L26)

50 → 200 MB ⇒ chunk ~45 วิ ⇒ **รอยต่อลดลง 4 เท่า** ⇒ S9 เย็บน้อยลง 4 เท่า พลาดน้อยลงตาม · **แก้ค่าคงที่ตัวเดียว**

**ทำไมยังไม่ทำ:** chunk ไม่ได้มีไว้เพื่อ tracker มันแบก 4 อย่าง — ไฟล์ที่เปิดได้เมื่อ crash (MP4 เขียน `moov` **ตอนจบ** ⇒ ตายกลางคัน = เสียทั้งก้อน) · ดิสก์ไม่โต · อัดไปประมวลผลไป · หน่วย upload
**ราคา:** crash แล้วเสียฟุตเทจ ~45 วิแทน ~10 วิ — **เป็นการตัดสินใจเรื่องความเสี่ยงของงาน ไม่ใช่เรื่อง tracker**

**กลับมาดูเมื่อ — ข้อใดข้อหนึ่ง:**

| ทริกเกอร์ | ที่มา |
|---|---|
| S0(ก) วัดได้ **gap > 600 ms** | S9 เย็บไม่ติดตั้งแต่ต้น ⇒ ลดจำนวนรอยต่อคือทางเดียวที่เหลือ |
| S9 ส่งแล้วแต่ S0(ข) ยังสูง | เย็บแล้วยังพลาดบ่อย ⇒ ลดโอกาสต้องเย็บ |
| staging บวมเกินเพดาน | chunk ยาวขึ้น = person แตะขอบน้อยลง = ค้าง candidate น้อยลง |
| ไปทำ §5.5 (ลด sample interval) | จะเพิ่มทั้ง track และรอยต่อพร้อมกัน ควรทบทวนคู่กัน |

**ก่อนเปลี่ยนต้องวัด:** ฟุตเทจที่ยอมเสียได้เมื่อ crash · พฤติกรรม upload retry ที่ก้อน 200 MB · `hasStorageForRecording()` ยังคุมพอไหม

### อื่น ๆ

- **ทาง A (ถือ tracker state ข้าม chunk)** — ปฏิเสธเพราะมัด chunk เข้าด้วยกัน (§6) · กลับมาดูถ้า pipeline เลิกต้องการ chunk ที่อิสระ
- **ครอปต่อคน** — person-centric เปิดทางไว้แล้ว: เฟรมเดียวถอดครั้งเดียว ครอปได้ N รูป
- **decode `landmark` + `landmark_visibility`** — 17 keypoints ฟรีบน NPU · ยืนยัน COCO index กับ output จริงก่อน · ได้แล้วเลิกใช้ ML Kit Pose (ที่คืนคนเดียวต่อเฟรม) ได้
- **Hungarian + Kalman เต็มรูป** — ถ้า S6 วัดแล้วพบว่า greedy/linear คือคอขวดจริง
- **ลด sample interval** (§5.5) — ถ้า `framesSeen == 1` ยังเยอะหลัง S6
- **`KEEP_PROCESSED_CHUNKS` เป็น toggle ตอนรัน** — สำหรับ overlay จาก live path (§3.5)
- **วัด recall ของ person detector บนนักวิ่งไกล** — Compare all → `detector_compare.json` · **ต้องมีก่อนจะเถียงเรื่อง YOLO ได้**

---

## Related

- [SubjectTracker.kt](../shared/src/commonMain/kotlin/com/autobots/camera/SubjectTracker.kt) · [TrackSummary.kt](../shared/src/commonMain/kotlin/com/autobots/camera/TrackSummary.kt) · [FrameQuality.kt](../shared/src/commonMain/kotlin/com/autobots/camera/FrameQuality.kt) · [PersonFootDetector.kt](../androidApp/src/main/kotlin/com/autobots/camera/detection/PersonFootDetector.kt) · [VideoFrameProcessor.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt)
- [FoundationVision/ByteTrack](https://github.com/FoundationVision/ByteTrack) — MIT (Yifu Zhang, 2021) · ECCV 2022
- [RELEASE_0_1_6.md §10–11](./RELEASE_0_1_6.md) — tracking + `tracks.csv` ของเดิม
- [DESIGN_FLOW.md](./DESIGN_FLOW.md) — Video Pipeline 13 ขั้นของวันนี้ · [ROADMAP.md](./ROADMAP.md) · [DOCS.md](./DOCS.md)
