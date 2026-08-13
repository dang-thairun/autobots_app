# v0.1.4 — Two-Stage Worker 2 *(กำลังดำเนินการ)*

> รายงานการเปลี่ยนแปลงจาก **v0.1.3** → **v0.1.4**
> ตัวเลข "ก่อนแก้" ทั้งหมดมาจาก `perf_report.json` ของ TC-04 ใน [reports/v0.1.3](../reports/v0.1.3/report.md) — import UHD 273.7 s บน Xiaomi peridot (SM8635 · Snapdragon 8s Gen 3 · Android 16)
>
> **สถานะ: วัดแล้วหนึ่งรอบ (TC-06)** — `realtimeRatio` **2.196 → 1.499 (−32%)** · invariant ทั้งสามตรงเป๊ะ · **สมมติฐาน aliasing ในข้อ 4 ถูกหักล้าง** · TC-05 (ตัวควบคุมที่ปิด halving) **ยังไม่ได้รัน** จึงยังแยกผลของข้อ 1 กับข้อ 4 ออกจากกันไม่ได้

---

## สรุปผู้บริหาร

v0.1.3 จบลงที่ `realtimeRatio` **2.196×** — เร็วขึ้น 14% แต่ยังห่างจาก 1.0× ที่ live UHD ต้องการอยู่กว่าเท่าตัว และเอกสารปิดท้ายไว้ว่าทางเดียวที่เหลือคือแก้ `yuv_jpeg_argb` (49.5% ของ runtime) ซึ่งลองแล้วล้มเหลว

**ข้อสรุปนั้นมองข้ามทางที่ถูกกว่าไปหนึ่งทาง** ปัญหาไม่ได้อยู่ที่ `yuv_jpeg_argb` แพงอย่างเดียว แต่อยู่ที่**มันไม่ได้ทำงานพร้อมกับอะไรเลย**

```kotlin
// v0.1.3 · VideoFrameSampler.decodeLoop
CamPerf.timed(perf, "decoder_blocked") {
    runBlocking { onFrame(ptsUs, bitmap, rotationDegrees) }   // ← ทั้ง Worker 2 อยู่ในนี้
}
```

decode กับ detect รันอยู่บนเธรดเดียวกัน เรียงกัน ดังนั้นเวลาของ chunk = **ผลบวก** ของทั้งสองฝั่ง ทั้งที่เครื่องมี 8 คอร์และ `thermal: OK` ตลอด 9.8 นาที

v0.1.4 แยกมันออกเป็นสองฝั่งคั่นด้วยคิวขนาดจำกัด เวลาของ chunk กลายเป็น **ค่ามากกว่า** ไม่ใช่ผลบวก

| | producer (เธรด decoder) | consumer (detect worker × 2) |
|--|--|--|
| stage | `decode` 0.15% · `yuv_jpeg_argb` 49.5% | `scale_for_detect` 2.8% · `mlkit_face` 30.9% · `rotate` 5.4% · `save_jpeg` 4.3% |
| **รวม (สัดส่วนของ wall เดิม)** | **49.7%** | **43.4%** |

```
v0.1.3   wall = 49.7 + 43.4 = 93%      →  ratio 2.196×
v0.1.4   wall = max(49.7, 43.4 / 2)    →  ratio ~1.09× (ทฤษฎี) · ~1.3–1.5× (คาดจริง)
```

**ผลข้างเคียงที่สำคัญกว่าตัวเลข** — ที่ 2 worker ฝั่ง consumer เหลือ 21.7% ทำให้ pipeline กลายเป็น **producer-bound** แปลว่าต้นทุน detection ที่เพิ่มขึ้นหลังจากนี้ (detect bitmap ที่ใหญ่ขึ้น · โมเดลที่หนักขึ้น · NPU delegate) จะถูกกลืนหายไปโดยไม่กระทบ wall เลย จนกว่าจะเกิน 49.7%

นั่นคือเหตุผลที่ข้อนี้มาก่อนทุกเรื่องในแผน รวมถึงก่อนเรื่อง NPU

---

## การเปลี่ยนแปลง

### 1. ⭐ แยก decode ออกจาก detect — สองเธรดคั่นด้วยคิว

**ไฟล์:** [VideoFrameProcessor.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt) · [VideoFrameSampler.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameSampler.kt)

```
sampler thread ──decode + YUV→Bitmap──▶ Channel(2) ──▶ detect worker ×2
   (producer)                                            (consumer)
```

`VideoFrameSampler` ไม่เปลี่ยนหน้าที่ — ยังผลิต bitmap เหมือนเดิม สิ่งที่เปลี่ยนคือ callback ของมันตอนนี้ **แค่ยัดลงคิวแล้วกลับ** ไม่ได้ทำงานต่อในนั้น

**สองอย่างที่ตามมาจากการขนาน และทั้งคู่จำเป็นต่อความถูกต้อง:**

**ก. worker แต่ละตัวถือ detector ของตัวเอง** — ML Kit detector ตัวเดียวที่ถูกเรียกจาก 2 เธรดจะ serialize อยู่ภายใน SDK ซึ่งคือต้นทุนที่เรากำลังพยายามกำจัดพอดี (`DetectorSet` สร้าง face/pose แบบ lazy — session ที่ทำ Face จึงไม่จ่ายค่าโหลดโมเดล pose)

**ข. การคัดเลือกย้ายไปอยู่ท้าย chunk** — worker เสร็จไม่เรียงลำดับ dedup window แบบ streaming จึงใช้ไม่ได้อีก candidate ยังถูกเขียน JPEG ทันทีที่ผ่านด่าน (เหมือน 0.1.3) แต่การจัดหน้าต่าง 1 วินาที + เก็บ top-3 เกิดขึ้น**ครั้งเดียวตอนจบ chunk** หลังเรียงตาม PTS แล้ว

> ข้อ ข. คือแนวคิด **"บันทึกก่อน พอครบ chunk ค่อยตัดทีเดียว"** พอดี — และการขนานไม่ได้แค่เปิดทางให้ทำ มัน**บังคับ**ให้ทำ กติกาการคัดเหมือนเดิมทุกประการ เพียงแต่ตอนนี้ไม่ขึ้นกับลำดับที่ worker บังเอิญทำเสร็จ

**สิ่งที่จงใจ *ไม่* ทำ: ไม่เขียนทุกเฟรมลงดิสก์** เพราะจะเพิ่ม `save_jpeg` จาก n=315 เป็น n=2,281 ≈ **+189 วินาที (+32%)** และกินดิสก์ ~4.5 GB ต่อคลิป 4 นาที โดยไม่ลดงานอะไรลงเลย ประโยชน์อยู่ที่การ**เลื่อนการตัดสินใจ** ไม่ใช่ที่การผ่านดิสก์

**ค่าคงที่ใหม่**

| ค่า | ค่า | เหตุผล |
|--|--|--|
| `DETECT_WORKERS` | 2 | ทำให้ producer-bound แล้ว มากกว่านี้จ่ายแค่ memory จนกว่า `yuv_jpeg_argb` จะถูกแก้ |
| `FRAME_QUEUE_CAPACITY` | 2 | นี่คือ**เพดาน memory ไม่ใช่ปุ่ม throughput** — แต่ละช่องคือ bitmap ARGB_8888 เต็มความละเอียด ~33 MB ที่ UHD |

`Channel(onUndeliveredElement = { it.bitmap.recycle() })` รับผิดชอบกรณี cancel — bitmap ที่ค้างอยู่ในคิวถูกคืนเสมอ

### 2. เครื่องมือวัดใหม่ที่บอกว่า "ฝั่งไหนคือคอขวด"

หลังแยกเธรดแล้ว คำถามเดียวที่สำคัญคือ *ฝั่งไหนรออีกฝั่ง* จึงมีสอง stage ที่ตอบคำถามนี้ตรงๆ

| stage | อยู่ฝั่ง | อ่านว่า |
|--|--|--|
| `queue_wait` | producer | decoder รอ worker ว่าง → **consumer เป็นคอขวด** เพิ่ม `DETECT_WORKERS` |
| `worker_idle` | consumer | worker ไม่มีเฟรมทำ → **decoder เป็นคอขวด** มีแต่งาน `yuv_jpeg_argb` (A3/NPU) ที่ช่วยได้ |

`decoder_blocked` เดิมถูกเปลี่ยนชื่อเป็น `queue_wait` เพราะความหมายเปลี่ยนไปคนละเรื่อง — เดิมคือ "เวลาที่ Worker 2 ใช้" ตอนนี้คือ "เวลาที่ decoder รอคิว"

### 3. 🐛 `sharePercent` หารด้วยตัวหารที่ผิด

**ไฟล์:** [PerfReport.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfReport.kt)

v0.1.3 §5 แก้ปัญหา `sharePercent` นับซ้ำด้วยการยกเว้น stage ที่ซ้อนอยู่ใน `decoder_blocked` ออกจากตัวหาร แต่ `NESTED_STAGES` **ตกไปหนึ่งตัวคือ `rotate`**

`rotate` ถูกเรียกจาก `uprightFullFrame()` ซึ่งอยู่ใน `evaluateFaceFrame` → อยู่ใน `decoder_blocked` มันจึงถูกนับทั้งในฐานะ stage อิสระ **และ** ในฐานะส่วนหนึ่งของ `decoder_blocked` ตัวหารจึงเฟ้อไป ~5% และ `sharePercent` **ทุกตัวใน v0.1.3 ต่ำกว่าความจริงประมาณ 5%**

การรักษาวิธีเดิมต้องรู้ว่า stage ไหนซ้อนใน stage ไหน — ซึ่งพลาดมาแล้วสองครั้ง และหลังแยกเธรดก็ใช้ไม่ได้อีกต่อไปอยู่ดี เพราะ**ไม่มีผลบวกของ stage ใดเท่ากับ wall time**

v0.1.4 จึงหารด้วย **wall clock จริง** (`sum(chunks[].processDurationMs)`) และติดป้าย `thread: producer|consumer` ให้แต่ละ stage แทน `nestedInDecoderBlocked`

> ผลข้างเคียงที่ตั้งใจ: **สัดส่วนจะรวมกันได้เกิน 100%** และส่วนที่เกินคือมูลค่าของการทำงานทับซ้อนพอดี ถ้ารวมได้ 93% แปลว่า overlap ไม่เกิดขึ้นจริง

`SCHEMA_VERSION` 1 → **2** — รายงานจาก v0.1.3 และก่อนหน้า **เทียบ `sharePercent` กับ v0.1.4 ไม่ได้**

### 4. ย่อภาพแบบ halving แทนขั้นเดียว — **ทดสอบแล้ว สมมติฐานผิด**

**ไฟล์:** [VideoFrameProcessor.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt) — `downscale()`

`Bitmap.createScaledBitmap(filter = true)` เป็น **bilinear** — อ่าน source 2×2 พิกเซลต่อ output 1 พิกเซล ย่อเกิน 2× ในขั้นเดียวเมื่อไหร่ก็เริ่ม**ข้าม**พิกเซลต้นทาง และรายละเอียดที่หายไปคือ texture ละเอียดที่ face detector ใช้ตัดสินพอดี

UHD portrait ย่อ **3.4×** (2160 → 640) และเลขมันอยู่ตรงขอบพอดี:

```
หน้าที่ subjectRatio = 0.035  =  สูง 134 px ในภาพ 4K
                              →  เหลือ 40 px ใน detect space
                                 ↑ ขนาดต่ำสุดที่ ML Kit FAST ยังพอทำงานได้ — ก่อนคิด aliasing
```

**นี่คือสมมติฐานที่ทดสอบได้สำหรับ OQ-01** (`no_subject` 63–79% ของทุกเฟรมที่ sample) ถ้าสิ่งที่ป้อนเข้า detector เสียตั้งแต่ต้น การเปลี่ยนโมเดลก็ไม่ช่วย

การแก้: halve จนเหลือ ≤ 2× แล้วค่อย scale ทีสุดท้าย (3840×2160 → 1920×1080 → 1137×640) ทุกขั้นอยู่ที่หรือต่ำกว่า 2× output pixel จึงเป็นค่าเฉลี่ยของเพื่อนบ้านจริง ไม่ใช่การสุ่มจุด

ต้นทุนคือ bitmap กลางหนึ่งใบบนเธรด consumer — ซึ่งหลังข้อ 1 มีที่ว่างเหลืออยู่

คุมด้วยสวิตช์ `MULTISTEP_DOWNSCALE` และบันทึกเป็น `downscaleMode` ใน `perf_report.json` **เพื่อให้ A/B ได้จริง ไม่ใช่เดา**

> **📉 บันทึกความล้มเหลว (TC-06)** — `noSubject` **1,441 → 1,458 ไม่ลดเลย** detect bitmap ขนาดเท่าเดิมเป๊ะ ต่างกันแค่คุณภาพการ resample → **คุณภาพการ resample ไม่ใช่ตัวจำกัด** ปิดเคสนี้ได้ OQ-01 กลับไปเป็นคำถามเปิดที่ต้องใช้ NA-04 (ดูคลิป) ตอบ
>
> ต้นทุนที่จ่ายไป: `scale_for_detect` **7.6 → 31.5 ms/frame (4.1 เท่า)** แต่ **wall ไม่ขยับ** เพราะอยู่บนเธรดที่ว่าง 46.5% — เป็นการยืนยันคำทำนายเรื่อง producer-bound โดยบังเอิญ ไม่ใช่ประโยชน์ของข้อนี้
>
> ⚠️ **มีตัวแปรแฝงที่ยังแยกไม่ออก** ดู [tracking ที่ถูกผ่าครึ่ง](#-tracking-ถูกผ่าครึ่งโดยไม่ตั้งใจ) — ผลของ halving อาจถูกหักล้างด้วยผลลบจากข้อนั้นพอดี

### 5. `splitDurationMs` แยกเป็น active / blocked *(NA-03)*

**ไฟล์:** [ImportedVideoSplitter.kt](../androidApp/src/main/kotlin/com/autobots/camera/capture/ImportedVideoSplitter.kt) · [CapturePipelineCoordinator.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt) · [PipelineSessionRecord.kt](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt)

`awaitQueueSpace()` สะสมเวลาที่จอดรอ backpressure ไว้ แล้ว `perf_report.json` รายงานสามค่า:

| ฟิลด์ | ความหมาย |
|--|--|
| `splitDurationMs` | wall clock (ค่าเดิม เก็บไว้เพื่อเทียบย้อนหลัง) |
| `splitBlockedMs` | ส่วนที่จอดรอคิว |
| `splitActiveMs` | **remux จริง — ตัวเดียวที่เทียบข้าม run ได้** |

v0.1.3 รายงาน 2,912 ms กับ 455,841 ms สำหรับ remux ที่ควรใช้ ~20 s ทั้งคู่ ตอนนี้จะเห็นว่าส่วนต่างคืออะไร · `session_log.txt` ก็แสดงแยกเช่นกัน

---

## แผนการทดสอบ

ไฟล์เดิมทั้งสองไฟล์ เครื่องเดิม เพื่อให้ delta ผูกกับโค้ดไม่ใช่ฟุตเทจ

| ID | Input | Build | เพื่ออะไร |
|--|--|--|--|
| **TC-05** | `run4mins.mp4` | v0.1.4 · `MULTISTEP_DOWNSCALE = false` | **แยกผลของข้อ 1 ล้วนๆ** — detect input เหมือน v0.1.3 เป๊ะ delta ทั้งหมดคือการแยกเธรด |
| **TC-06** | `run4mins.mp4` | v0.1.4 · `MULTISTEP_DOWNSCALE = true` (ค่าที่ตั้งไว้) | **แยกผลของข้อ 4 ล้วนๆ** — เทียบกับ TC-05 ตัวแปรเดียว |
| **TC-07** | `run1mins.mp4` | v0.1.4 (ค่าที่ตั้งไว้) | ต่อ baseline TC-03 |

> **รันสองรอบ ไม่ใช่รอบเดียว** ข้อ 1 ขยับ *ความเร็ว* ข้อ 4 ขยับ *recall* แต่ข้อ 4 ก็กินเวลาบนเธรด consumer ด้วย ถ้ารันรวมกันรอบเดียวจะแยกไม่ออกว่า ratio ที่ได้มาจากอะไร

### ทำนายไว้ / วัดได้

| ทำนาย | ที่มา | **วัดได้ (TC-06)** |
|--|--|--|
| `realtimeRatio` **1.3–1.5×** (จาก 2.196) | `max(producer, consumer/2)` โดย overlap ไม่สมบูรณ์ · เพดานทฤษฎี 1.09 | **1.499** ✅ ขอบบนพอดี |
| `worker_idle` ≫ `queue_wait` | producer-bound ตามการคำนวณ | **373.5 s vs 0.89 s — 420 เท่า** ✅ |
| invariant ทั้งสามยังตรง | `save_jpeg n` = candidates · `rotate n` = candidates + tooSoft + roiInvalid · `sharpness n` = `rotate n` − roiInvalid | **302 / 579 / 550 ตรงเป๊ะ** ✅ |
| stage shares **รวมกันเกิน 100%** | ส่วนเกิน = มูลค่าของ overlap ถ้ารวมได้ ~93% แปลว่า overlap ไม่เกิด | **194%** ✅ |
| `splitActiveMs` ~20 s | remux ไม่ขึ้นกับ backpressure อีกต่อไป | **19,943 ms** (blocked 293,814 ms) ✅ |
| ไม่มี crash · `decodeFailures` 0 | — | ✅ · thermal OK ตลอด |
| **`noSubject` < 1,441** | สมมติฐาน aliasing (ข้อ 4) | **1,458 — ไม่ลด** ❌ **สมมติฐานผิด** |
| `kept` = **149 เป๊ะ** ใน TC-05 | ต่างเมื่อไหร่แปลว่าการย้ายไปคัดท้าย chunk **ไม่** เทียบเท่าของเดิม | ⚠️ **ยังไม่ได้ทดสอบ** — รอบนี้เป็น TC-06 detect input เปลี่ยนไปด้วย `kept` = 144 จึงตีความไม่ได้ |
| RAM peak +100–200 MB | คิว 2 ใบ + 2 worker × bitmap 4K ~33 MB | ⚠️ ยืนยันไม่ได้ — ฟิลด์นี้เป็น RAM ระดับเครื่อง ไม่ใช่ของแอป (6,314 → 5,681 MB) ที่ยืนยันได้คือ **ไม่มี OOM ไม่มีอาการบวม** |

**เกณฑ์ตกที่ต้องระวังเป็นพิเศษ:** ถ้า TC-05 ได้ `kept` ≠ 149 อย่าเพิ่งดีใจว่า "ได้รูปเพิ่ม" — มันแปลว่าการคัดเลือกท้าย chunk ไม่เทียบเท่าของเดิม ต้องหาสาเหตุก่อน (ระวัง `enableTracking()` ที่ทำให้ผลไม่ deterministic ระหว่างรัน ±2–3 อยู่แล้ว)

---

## ผลการทดสอบ — TC-06

`run4mins.mp4` · UHD 3840×2160 rot 90° · 36 chunks · Xiaomi peridot (SM8635) · `downscaleMode = halving`

| | v0.1.3 (TC-04) | **v0.1.4 (TC-06)** | |
|--|--|--|--|
| **realtimeRatio** | 2.196× | **1.499×** | **−32%** |
| wall time | 588.5 s | **401.7 s** | −31.7% |
| kept | 149 | 144 | เทียบไม่ได้ (detect input ต่าง) |
| framesSampled | 2,281 | 2,281 | |
| candidates | 315 | 302 | |
| noSubject | 1,441 | **1,458** | ไม่ลด |
| tooSmall / tooSoft | 266 / 243 | 244 / 248 | |
| **roiInvalid** | 16 | **29** | **เกือบเท่าตัว** |
| decodeFailures · thermal | 0 · OK | 0 · OK | |

**invariant ทั้งสามยังตรงเป๊ะ** — `2281 − 1458 − 244 − 248 − 29 = 302 = save_jpeg n` · `302 + 248 + 29 = 579 = rotate n` · `579 − 29 = 550 = sharpness n`

### pipeline เป็น producer-bound แบบสุดขั้ว — ตรงตามที่ออกแบบ

```
producer   364.0 s = 90.6% ของ wall     ← ทำงานเกือบตลอดเวลา
consumer   411.9 s ÷ 2 worker = 51.3%   ← ว่าง 46.5%
queue_wait   0.89 s = 0.22%             ← decoder แทบไม่เคยรอ worker
worker_idle 373.5 s = 46.5% (÷2)        ← 420 เท่าของ queue_wait
```

`yuv_jpeg_argb` ตัวเดียว = **89.5% ของ wall** — pipeline ตอนนี้**คือ**ปัญหา `yuv_jpeg_argb` ไม่มีอย่างอื่นเหลือแล้ว นี่คือผลลัพธ์ที่มีค่าที่สุดของ release นี้: มันบีบให้เหลือเป้าหมายเดียวที่ชัดเจน

### ⚠️ ราคาของการทำงานทับซ้อน — ทุก stage ช้าลงต่อเฟรม

สิ่งที่**ไม่ได้ทำนายไว้** และต้องเอาไปคิดในทุกการประเมินหลังจากนี้:

| stage | v0.1.3 (ประมาณ) | v0.1.4 | |
|--|--|--|--|
| `yuv_jpeg_argb` | ~135.0 ms | **157.6 ms** | +17% |
| `mlkit_face` | ~84.3 ms | **108.8 ms** | +29% |
| `rotate` | ~58.5 ms | **104.3 ms** | +78% |
| `save_jpeg` | ~84.9 ms | **101.9 ms** | +20% |

3 เธรดแย่ง CPU และ memory bandwidth กัน งานเดียวกันจึงช้าลง 17–78% **แต่ wall ยังลด 32%** — overlap ชนะ contention ขาดลอย ผลคือ **เพดานทฤษฎี 1.09× ไปไม่ถึงแน่นอน** ต้องเผื่อ contention tax ~20–30% ทุกครั้ง

> ค่า v0.1.3 ถอดกลับจาก `sharePercent` schema-1 โดยหักตัวหารที่เฟ้อด้วย `rotate` (ดู [ข้อ 3](#3--sharepercent-หารด้วยตัวหารที่ผิด)) เป็นค่าประมาณ แต่ทิศทางชัด — และเป็นตัวอย่างว่าทำไมการแก้ schema ถึงจำเป็น

### 🐛 tracking ถูกผ่าครึ่งโดยไม่ตั้งใจ

`DETECT_WORKERS = 2` แปลว่ามี `OfflineFaceDetector` **สองตัว** ที่เปิด `enableTracking()` และเฟรมถูกแจกสลับกันไปคนละตัว — **detector แต่ละตัวจึงเห็นเฟรมเว้นเฟรม ช่องว่างเวลาโตจาก 120 ms เป็น ~240 ms และลำดับไม่การันตี** tracking ที่อาศัยประวัติเฟรมก่อนหน้าย่อมแย่ลง

หลักฐาน: **`roiInvalid` 16 → 29** ซึ่งตามนิยามคือ "ML Kit ที่เปิด tracking ทำนายกล่องล้ำขอบเฟรม" การเพิ่มเกือบเท่าตัวบนฟุตเทจเดียวกันบอกว่าการทำนายของ tracker เพี้ยนลงจริง

**ผลข้างเคียง:** ตัวเลข `noSubject` ที่ "ไม่ขยับ" อาจเป็นผลบวกของ halving หักล้างกับผลลบของ tracking ที่ถูกผ่าครึ่งพอดี — แยกไม่ออกจากรอบเดียว

### เครื่องช้าลงเรื่อยๆ ตลอด session

เทียบเฉพาะ chunk ที่ `kept = 0` (ไม่มีงาน rotate/save เลย จึงเทียบกันได้ตรงๆ):

| chunk | ms/frame |
|--|--|
| 2 | 142.4 |
| 11 | 168.6 |
| 21 | 174.5 |

**+23% ตลอด 6.7 นาที** ทั้งที่ `thermal` รายงาน `OK` (level 0) ตลอด · RAM ไม่โต · `decodeFailures` 0 — น่าจะเป็น DVFS ที่ต่ำกว่าเกณฑ์รายงานของ thermal API

### `splitActiveMs` ทำงานตามที่ออกแบบ

| | ค่า |
|--|--|
| `splitDurationMs` | 313,757 ms |
| `splitBlockedMs` | 293,814 ms |
| **`splitActiveMs`** | **19,943 ms** |

remux 1.93 GB ใน **19.9 วินาที** — ตรงกับที่ v0.1.3 ประเมินไว้ว่า "ควรใช้ ~20 s" เทียบข้าม run ได้แล้วจริง

### เส้นทางสู่ < 1.0 — ตอนนี้คำนวณได้แม่นแล้ว

```
วันนี้          wall = max(producer 364 s, consumer 206 s) = 402 s  →  1.499×
yuv ลดครึ่ง     wall = max(182 s, 206 s) = 206 s                    →  0.77×
yuv = 0         wall = consumer 206 s                               →  0.77×
```

**ลด `yuv_jpeg_argb` ลงครึ่งเดียวก็ข้ามเส้นแล้ว** ไม่ต้องกำจัดทิ้ง — เพราะ consumer จะกลายเป็นเพดานที่ 0.77× และหลังจากนั้น `mlkit_face` (60% ของงานฝั่ง consumer) คือเพดานถัดไป **นั่นคือช่องที่ NPU จะมีความหมายจริง แต่ยังไม่ใช่ตอนนี้**

---

## แล้ว NPU / LiteRT ล่ะ — ยังไม่ใช่รอบนี้ และนี่คือเหตุผล

เครื่องทดสอบยืนยันแล้วว่าเป็น **SM8635 (Snapdragon 8s Gen 3)** — `ro.board.platform = pineapple` มี Hexagon NPU และ QNN ใช้ได้จริง ทางเปิด แต่ลำดับสำคัญ

**1. detection ไม่ใช่คอขวด** `mlkit_face` = 30.9% ของ wall ต่อให้โมเดลใหม่เร็วเป็นอนันต์ ratio ก็ลงได้แค่ 2.196 → **1.52** ยังไม่ข้ามเส้น ส่วน `yuv_jpeg_argb` 49.5% ไม่ถูกแตะเลย

**2. หลังข้อ 1 ผลตอบแทนยิ่งน้อยลงอีก** เมื่อ pipeline เป็น producer-bound การทำ detection ให้เร็วขึ้นจะ **ไม่ขยับ wall เลย** จนกว่าจะแก้ฝั่ง producer ได้ก่อน — `worker_idle` ใน TC-05 จะเป็นตัวยืนยันข้อนี้ด้วยตัวเลข

**3. ราคาที่ต้องจ่ายสูง** QNN `.so` หนักหลายสิบ MB ต่อ arch บน APK ที่ 137 MB และติดตั้งพังอยู่แล้ว · เกิดสองเส้นทาง detection ถาวร (Snapdragon NPU / ที่เหลือ GPU-CPU) ทำให้สถิติ reject ขึ้นกับเครื่อง ซึ่งจะทำลายวินัยการวัดที่โปรเจกต์นี้สร้างมา

**4. คุณค่าจริงของมันคือ recall ไม่ใช่ speed** ML Kit face detection ออกแบบมาสำหรับหน้าคนใหญ่ค่อนข้างตรง งานนี้คือนักวิ่งไกล กึ่งด้านข้าง เบลอจากการเคลื่อนไหว — อยู่นอกกรอบที่โมเดลนั้นออกแบบมา นั่นเป็นเหตุผลเชิงหลักการที่ควรคาดว่า recall จะต่ำ **แต่ข้อ 4 ของ release นี้เป็นคำอธิบายที่ถูกกว่าและถูกกว่ามาก** ต้องตัดตัวนั้นออกก่อน

ข้อจำกัดของทางเลือก NPU ตอบตรงๆ ตามชั้น:

| ชั้น | ผูกกับ Snapdragon ไหม |
|--|--|
| LiteRT runtime | **ไม่** |
| CPU (XNNPACK) · GPU delegate | **ไม่** — Adreno / Mali / Xclipse / PowerVR ได้หมด `.tflite` ไฟล์เดียวรันได้ทุกเครื่อง |
| **NPU** | **ใช่ โดยพฤตินัย** — Qualcomm ใช้ QNN · MediaTek ใช้ NeuroPilot · Tensor ใช้ EdgeTPU · **NNAPI ถูก deprecate ตั้งแต่ Android 15** |
| QNN context binary จาก AI Hub | **ผูกถึงระดับตระกูลชิป** — binary ของ 8 Gen 3 ไม่รันบน 8 Gen 2 |

**HRNetPose พักไว้** — เป็นสาย high-accuracy หนัก และเป็น **top-down single-person** ต้องมี person detector ป้อน crop ให้ก่อน = 2 โมเดล ไม่ใช่ drop-in แทน ML Kit Pose ที่เป็น pipeline ครบในตัว ยิ่งกว่านั้น **pose ยังไม่เคยถูกทดสอบเลย** จะยกเครื่องก่อนมี baseline ไม่ได้

**สิ่งที่ควรทำแทน (v0.1.5):** bench harness นอก pipeline ป้อนเฟรมชุดเดียวกัน — โดยเฉพาะช่วงที่ NA-04 ยืนยันว่า **มีคนแต่ได้ `no_subject`** — เข้า ML Kit FAST / ML Kit ACCURATE / `face_det_lite` (LiteRT CPU) / `face_det_lite` (GPU delegate) แล้ววัด **recall + ms/frame บนเครื่องจริง** ไม่ integrate อะไรทั้งสิ้น ให้ข้อมูลตัดสินใจด้วยวินัยเดียวกับที่ v0.1.3 ใช้

และถ้าจะไปทางโมเดลจริงๆ **person/body detector น่าจะคุ้มกว่า face detector ที่ดีกว่า** — สำหรับกล้องกีฬา ของที่ส่งมอบคือ "รูปนักวิ่งที่จำได้" ตรวจตัวคนแล้วค่อยยืนยันหน้าจะได้ recall สูงกว่ามากที่ระยะไกล (ROADMAP.md ลิสต์ไว้แล้วทั้ง "Body / person pre-filter" และ "YOLO / TFLite detector")

---

## ยังไม่ได้แก้ใน 0.1.4

| เรื่อง | ทำไมเว้นไว้ |
|--|--|
| **`yuv_jpeg_argb` — วัดได้ 89.5% ของ wall** *(NA-02 · OQ-02)* | **คอขวดตัวเดียวที่เหลือจริงๆ แล้ว** ทางที่วางไว้คือ `ImageFormat.PRIVATE` + `HardwareBuffer` + `Bitmap.wrapHardwareBuffer()` ซึ่งเคยล้มเหลวมาแล้วครั้งหนึ่ง · **แต่ตัวเลขบอกว่าลดครึ่งเดียวก็พอ** จึงมีทางที่เสี่ยงน้อยกว่ารออยู่ ดูแถวถัดไป |
| **เลื่อน full-res decode ไปทำเฉพาะเฟรมที่ผ่านด่าน** | ตอนนี้สร้าง bitmap ARGB **4K เต็ม** ให้ทุกเฟรมที่ sample ทั้งที่มีแค่ **579/2281 (25%)** ที่ต้องใช้ภาพเต็ม ที่เหลือใช้แค่ 640px · ถ้า sampler เก็บ JPEG bytes ไว้ แล้ว decode ด้วย `inSampleSize` สำหรับ detect และ decode เต็มขนาดเฉพาะเฟรมที่ผ่าน จะตัด full-res decode ทิ้ง 75% — **แก้ในไฟล์เดียว ไม่แตะ MediaCodec** ควรลองก่อน HardwareBuffer |
| **`detectBitmapWidth` 640 → 960** | เป็นปุ่ม recall ตัวที่สอง ถ้าเปลี่ยนพร้อมข้อ 4 จะแยกไม่ออกว่าอะไรได้ผล — และข้อ 1 ทำให้มันเกือบฟรีแล้ว จึงควรเป็นการทดลองถัดไปที่มีตัวแปรเดียว |
| **TC-05 (`MULTISTEP_DOWNSCALE = false`)** | **ยังไม่ได้รัน** — เป็นตัวควบคุมที่จะแยกผลของข้อ 1 ออกจากข้อ 4 และเป็นที่เดียวที่ตรวจได้ว่า `kept` = 149 เป๊ะ คือ "การคัดท้าย chunk เทียบเท่าของเดิม" ยังพิสูจน์ไม่ได้ |
| **ปิด `enableTracking()`** | ตัวแปรแฝงตัวเดียวที่เหลือของการขนาน · เป็นต้นเหตุของ `roiInvalid` ทั้งหมด · v0.1.3 บันทึกเองว่ามันทำให้ผลไม่ deterministic ซึ่งเป็นปัญหาไม่ใช่ประโยชน์ — ปิดแล้วจะได้ผลที่ทำซ้ำได้ ซึ่งจำเป็นต่อทุกการทดลองหลังจากนี้ |
| **DVFS drift 23% ต่อ session** | เพิ่งค้นพบจาก TC-06 · `thermal` API รายงาน OK ตลอดจึงมองไม่เห็น ควรเก็บ CPU freq หรืออย่างน้อยบันทึก ms/frame ของ chunk ที่ `kept=0` ไว้เทียบ |
| **NA-04 · ดูคลิปยืนยัน `no_subject`** | ยังเป็นงานที่ควรทำก่อนทุกอย่างที่เกี่ยวกับ detection ข้อ 4 เป็นสมมติฐานคู่ขนาน ไม่ใช่ตัวแทน |
| **NA-05 · ต้นเหตุบั๊ก drain** *(OQ-03)* | watchdog ยังคุมความเสี่ยงอยู่ · **ระวัง: การแยกเธรดเปลี่ยนจังหวะการจบ chunk** ซึ่งเป็นเงื่อนไขที่บั๊กนี้รออยู่พอดี ต้องดู `drain complete · trigger=` ในทุก run |
| **NA-06 · ทดสอบ live capture** | ยังไม่ได้ทดสอบตั้งแต่ v0.1.3 — เป็นช่องว่าง scope ที่ใหญ่ที่สุด และตอนนี้ Worker 2 เพิ่งถูกรื้อ |
| **NA-07 · บล็อก live UHD ใน UI** *(OQ-05)* | รอดูว่า v0.1.4 พา ratio ลงถึงไหนก่อนจะตัดสินว่าต้องบล็อกจริงไหม |
| **OQ-04 · `MAX_KEEP_PER_WINDOW`** | ยังไม่มี NA รองรับใน report v0.1.3 — ควรตั้งเป็น action ใน report v0.1.4 |

---

## Related

- ที่มาของตัวเลขก่อนแก้: [reports/v0.1.3/report.md](../reports/v0.1.3/report.md) · [RELEASE_0_1_3.md](./RELEASE_0_1_3.md)
- Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md)
- ศัพท์: [CONTEXT.md](../CONTEXT.md)
- ประวัติเวอร์ชัน: [CHANGELOG.md](./CHANGELOG.md)
