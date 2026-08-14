# v0.1.4 — Two-Stage Worker 2 *(กำลังดำเนินการ)*

> รายงานการเปลี่ยนแปลงจาก **v0.1.3** → **v0.1.4**
> ตัวเลข "ก่อนแก้" ทั้งหมดมาจาก `perf_report.json` ของ TC-04 ใน [reports/v0.1.3](../reports/v0.1.3/report.md) — import UHD 273.7 s บน Xiaomi peridot (SM8635 · Snapdragon 8s Gen 3 · Android 16)
>
> **สถานะ: วัดแล้ว (TC-06 · TC-08/09/10)** — `realtimeRatio` **2.196 → 1.499 (−32%)** · invariant ทั้งสามตรงเป๊ะ · **สมมติฐาน aliasing ในข้อ 4 ถูกหักล้าง** · เทียบ backend แบบ end-to-end แล้ว: **NPU เร็วกว่า GPU 34% ที่ inference แต่ wall clock ลดแค่ 4%** ยืนยัน producer-bound ซ้ำอีกรอบ
>
> **⭐ TC-12 วัดแล้ว — `realtimeRatio` 1.430 → 1.010 (−29.4%) โดย `kept` เท่าเดิมเป๊ะที่ 134** งานเสร็จเร็วกว่าความยาวคลิปเป็นครั้งแรก · คำทำนายทั้ง 5 ข้อของข้อ 7 ตรงหมด · DVFS drift ยืนยันแล้วที่ +25% ด้วยสามตัววัดอิสระ
>
> **แต่เหตุผลที่เขียนไว้ในข้อ 7 ผิด** — ARGB decode ไม่ได้ถูกตัด 75% มันลดแค่ 4% แล้ว**ย้ายฝั่ง**ไปเธรดที่ว่าง · คอขวดตัวถัดไปคือ `yuv420ToNv21` (**61.6% ของ wall เดี่ยวๆ**) · `DvfsProbe` ที่เพิ่งเขียนมีบั๊ก JIT ทำให้ `driftPercent` ที่รายงานอ่านไม่ได้
>
> ยังค้าง: **TC-11 (live capture smoke test)** ซึ่งเป็นความเสี่ยง ship ตัวเดียวที่เหลือ · **TC-05** จึงยังแยกผลของข้อ 1 กับข้อ 4 ไม่ได้ · **TC-13** (`FRAME_QUEUE_CAPACITY = 2`) ซึ่ง TC-12 บอกแล้วว่าน่าจะไม่ต่างเพราะคิวเป็นตัวแปรเฉื่อย

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

### 6. Progress bar ระหว่าง import — เลิกขับด้วย split%

**ไฟล์:** [PipelineStats.kt](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineStats.kt) · [CapturePipelineCoordinator.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt) · [OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt) · [OperatorShellScreen.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt)

**อาการที่รายงานเข้ามา:** บาร์ค้างที่ **25-26%** ทุกครั้งที่ import คลิปยาว

**ไม่ใช่บั๊กของ splitter — เป็นบั๊กของการเลือกตัวเลขมาโชว์** ข้อ 5 ข้างบนวัดไว้เองแล้วว่า `splitActiveMs` เป็นแค่ 5% ของ `splitDurationMs` ที่เหลือ 95% คือ `awaitQueueSpace()` จอดรอ `videoQueue` (cap 8) และ `importPercent` คำนวณจาก PTS ที่เพิ่ง mux ไป จึงไม่ขยับเลยระหว่างจอด

ไล่จาก `events` ของ TC-09: chunk 1-8 เข้าคิวรวดใน 3.9 s → บาร์พุ่ง 0 → 23% แล้วหยุด · chunk 9 ปิดที่ 13.8 s → **25.95% ปัดลงเป็น 25** แล้วนิ่งอีก 10 s รอ chunk 2 เสร็จ จากนั้นขยับทีละ ~2.8% ทุก ~10.7 s ตามจังหวะ extractor ไม่ใช่ตามจังหวะ splitter

```
คุมโดย splitter  ▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░  0→23% ใน 3.9 s
คุมโดย extractor ░░░░░▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  23→100% ใน 395 s
```

**สิ่งที่แก้** — บาร์เดียวตลอดงาน ขับด้วย `overallProcessingPercent` ซึ่งมาจาก extractor:

| ส่วน | เดิม | ใหม่ |
|--|--|--|
| ตัวเลขที่ขับบาร์ | `importPercent` (timeline ของ splitter) | `overallProcessingPercent` (chunk ที่ extract เสร็จ + สัดส่วนใน chunk ปัจจุบัน) |
| ความถี่ที่ขยับ | ทุก ~10.7 s | **ทุก ~165 ms** (`VideoFrameProcessor.onProgress` ยิงทุกเฟรมที่ sample) |
| ตัวหาร | `videoChunksRecorded` ซึ่งโตไปพร้อมตัวตั้ง | `expectedChunks` — ประมาณจาก `chunksRecorded × 100 / importPercent` ตอนปิดแต่ละ segment แล้ว pin เป็นค่าจริงเมื่อ split จบ |
| ข้อความ | `splitting 25%` | `split 9/~36 chunks` + บรรทัด `waiting for extractor` เมื่อคิวเต็ม |

จำลองกับ chunk จริงทั้ง 36 ตัวแล้ว: ตัวหารนิ่งที่ **37** (จริง 36 · คลาด 2.7%) ตั้งแต่ chunk ที่ 4 บาร์ไม่ถอยหลังสักจุด และ pin เป็น 36 ตอน split จบ ทำให้กระโดดไปข้างหน้าครั้งเดียว ~2 จุด แล้วจบที่ 100%

> ตัวหารที่เป็นการประมาณโตได้อีก 1-2 chunk ตอนท้าย ซึ่งจะดันบาร์ถอย — กันด้วย floor ใน `applyPipelineStats()` ที่ไม่ให้ % ลดลงระหว่างงานเดียวกัน · การ์ด `ProcessingStatusCard` ตั้ง `heightIn(min = 118.dp)` ไม่ให้เปลี่ยนขนาดตอนข้อความสลับ 1↔2 บรรทัด

**ยังไม่ได้ทำ:** ปล่อย splitter วิ่งฟรีไม่ต้องรอคิว (split จะจบใน 17 s จริงๆ) — ต้องมีดิสก์ว่าง ~1.9 GB พร้อมกัน ควรทำเป็นเงื่อนไขจาก `StatFs` ไม่ใช่เปิดตลอด

> **แก้ข้อความเดิม:** บรรทัดนี้เคยเขียนว่า "เทียบกับ ~420 MB ที่ cap 8 ใช้อยู่" ซึ่ง**ผิด** — 420 MB คือของที่อยู่*ในคิว* ไม่ใช่ของที่อยู่*บนดิสก์* ตอนนั้นยังไม่มีใครลบ chunk ทิ้งเลย ดิสก์จึงโตเท่าไฟล์ต้นฉบับเสมอ · แก้แล้วในข้อ 9

### 7. ⭐ เลื่อน full-res decode — เฟรมข้ามคิวมาแบบ JPEG

**ไฟล์:** [SampledFrame.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/SampledFrame.kt) *(ใหม่)* · [VideoFrameSampler.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameSampler.kt) · [VideoFrameProcessor.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt)

**ที่มา** — TC-08/09/10 ปิดคำถามฝั่ง detector ไปแล้ว: `worker_idle` 211-220 ms/เฟรม แปลว่า worker ว่างเกือบทั้ง session ส่วน producer กิน 89-92% ของ wall การเร่ง detector ให้เร็วขึ้น 34% จึงคืน wall มาแค่ 4% เพราะไปเร่งฝั่งที่ว่างอยู่แล้ว

**ทางแก้จึงไม่ใช่ทำ producer ให้เร็วขึ้น แต่คือ *ย้ายงานออกจาก producer*** — และที่ที่จะย้ายไปก็ว่างรออยู่แล้ว

```
เดิม   producer: YUV → NV21 → JPEG → ARGB 4K  ─Channel(2)─▶  worker: ย่อ → detect → …
                                    ▲ ทุกเฟรม 100%                    ▲ ว่าง 92%

ใหม่   producer: YUV → NV21 → JPEG            ─Channel(6)─▶  worker: decode 1/2 → detect
                                                                      ↳ ผ่านด่านค่อย decode เต็ม (25%)
```

| | เดิม | ใหม่ |
|--|--|--|
| ของที่อยู่ในคิว | ARGB_8888 เต็มขนาด ~33 MB/ช่อง | JPEG ~2 MB/ช่อง |
| ARGB decode 4K เต็ม | **100%** ของเฟรมที่ sample · บน producer | **~25%** (579/2281) · บน worker |
| decode สำหรับ detect | — (ย่อจาก bitmap 4K ที่ decode มาแล้ว) | `inSampleSize=2` → 1/4 ของ pixel · บน worker |
| เพดานหน่วยความจำของคิว | 2 × 33 MB | 6 × ~2 MB |
| stage ใน `perf_report.json` | `yuv_jpeg_argb` (producer) | `yuv_nv21` + `nv21_jpeg` (producer) · `jpeg_argb_detect` + `jpeg_argb_full` (consumer) |

**ภาพที่เซฟออกมาไม่เปลี่ยน** — bytes ชุดเดียวกัน decode ด้วย options เดียวกัน แล้วผ่าน rotate/JPEG(95) เส้นทางเดิม

**แต่ภาพที่ใช้ detect เปลี่ยน** — `sampleSizeFor()` เลือกกำลังของ 2 ตัวเดียวกับที่ halving loop เคยใช้ (UHD portrait: 3840×2160 → 1920×1080 เท่ากันเป๊ะ แล้วต่อด้วย `createScaledBitmap` ขั้นเดียวเหมือนเดิม) แต่ libjpeg ย่อใน DCT domain ซึ่งเป็นคนละ filter กับ bilinear halving **คุณภาพเทียบเท่า ไม่ใช่ pixel เดียวกัน** — `kept` ขยับได้ 1-2 เฟรมจากการเปลี่ยนนี้เพียงลำพัง ต้องคุมไว้เวลาจะอ้าง delta

> `MULTISTEP_DOWNSCALE = false` คืน `sampleSize = 1` ให้ **TC-05 ยังวัดสิ่งเดิม** คือ bilinear ขั้นเดียวจากเฟรมเต็ม

**ตัวแปรที่สองในการแก้ครั้งเดียวกัน:** `FRAME_QUEUE_CAPACITY` 2 → 6 · เหตุผลที่เคยตรึงไว้ที่ 2 คือหน่วยความจำ ซึ่งหายไปแล้ว และมันต้องโตด้วย เพราะ full-res decode ที่ย้ายมาฝั่ง worker เป็นงานที่มาเป็นชุด (คนเดินผ่านกล้อง = keeper ติดกันหลายเฟรม) คิวลึก 2 จะทำให้ producer จอดรอชุดนั้นแทนที่จะวิ่งผ่านไป · `frameQueueCapacity` ถูกบันทึกรายชั้นก็เพื่อกรณีแบบนี้ — **ตั้งกลับเป็น 2 เพื่อแยกผลของ deferred decode ล้วนๆ**

> ### ⚠️ วัดแล้ว — เหตุผลข้างบนนี้ผิด อ่าน [TC-12](#ผลการทดสอบ--tc-12--หลังเลื่อน-full-res-decode-ข้อ-78) ก่อนใช้อ้างอิง
>
> ผลลัพธ์ถูก (**wall −29.3%** · `kept` เท่าเดิม) แต่กลไกไม่ใช่อย่างที่เขียน: **ARGB decode ไม่ได้ถูกตัดทิ้ง 75% — มันลดแค่ 4% (49.0 → 46.9 ms/เฟรม) แล้วย้ายฝั่งไปทั้งก้อน** สิ่งที่จ่ายผลตอบแทนคือการย้ายไปเธรดที่ว่าง 92% ไม่ใช่การย่อ input
>
> `inSampleSize = 2` ให้ pixel ออกน้อยลง 4 เท่าแต่เร็วขึ้นแค่ 36% เพราะ entropy decoding ของ JPEG ไม่ย่อตาม · **ตารางเทียบ "เดิม/ใหม่" ข้างบนจึงอ่านผิดได้ตรงแถว "ARGB decode 4K เต็ม"** — 100% → ~25% เป็นจำนวน*ครั้ง* ไม่ใช่จำนวน*งาน*
>
> `yuv_nv21` เทียบ `nv21_jpeg` วัดได้แล้วเช่นกัน: **61.6% vs 27.4% ของ wall** — คอขวดตัวถัดไปคือ `yuv420ToNv21` ตามที่เดาไว้ และแก้ได้โดยไม่ต้องแตะ HardwareBuffer

### 8. วัด DVFS drift ตรงๆ แทนที่จะอนุมาน *(ปิด NA ของ TC-06)*

**ไฟล์:** [DvfsProbe.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/DvfsProbe.kt) *(ใหม่)* · [PerfReport.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfReport.kt) · [DeviceLoadReader.kt](../androidApp/src/main/kotlin/com/autobots/camera/load/DeviceLoadReader.kt)

TC-06 เจอ per-frame cost ลอย ~23% จาก chunk แรกถึง chunk สุดท้ายโดยที่ `thermal` API รายงาน OK ตลอด — drift มีจริง แต่มองไม่เห็นจากฟิลด์ที่ pipeline sample อยู่ และมันใหญ่พอจะกลืนสิ่งที่กำลังวัด (ระยะห่าง 4% ของ TC-08 vs TC-10 อยู่ข้างในนั้น)

| ตัววัด | คืออะไร | อ่านยังไง |
|--|--|--|
| `chunks[].cpuProbeMs` | งาน integer ขนาดคงที่ 2M รอบ จับเวลาก่อนแต่ละ chunk เริ่ม เอาค่า**ต่ำสุด**จาก 3 รอบ | เทียบข้าม chunk เท่านั้น · แบน = run นี้เทียบกันได้ตลอด · ไต่ขึ้น = chunk ท้ายๆ ถูกวัดบนเครื่องที่ช้ากว่า |
| `totals.cpuProbe.driftPercent` | probe ตัวสุดท้ายเทียบตัวแรก | ตัวเลขที่ควรอ่าน**ก่อน**อย่างอื่นทั้งหมด · ใกล้ 23% แบบ TC-06 = per-chunk trend ในรายงานนั้นสรุปไม่ได้ |
| `deviceLoad[].cpuMaxFreqKhz` | `scaling_cur_freq` สูงสุดจาก sysfs | cross-check หยาบๆ · เป็น 0 เมื่อ kernel ไม่ยอมให้อ่าน — จึงเป็นตัวรอง ไม่ใช่ตัวหลัก |

เลือกงาน integer แทนการอ่านไฟล์ freq เป็นหลัก เพราะไม่ต้องพึ่ง permission หรือ path เฉพาะเจ้า และมันจับ**ทุกอย่างที่ทำให้ core ช้าลง** (frequency, contention, การถูกโยนไป little core) ไม่ใช่แค่ตัวเดียวที่ไฟล์ freq บอก · ราคา ~5 ms ต่อ chunk ~11 s และปิดตาม `CamPerf.enabled`

**ผลข้างเคียงที่ต้องรู้:** `perf_report.json` ขึ้นเป็น **schema 4** — `yuv_jpeg_argb` ไม่มีแล้ว รายงาน schema 3 จึงเทียบ share ต่อ stage กับ schema 4 ตรงๆ ไม่ได้ ตัวที่ใกล้เคียงที่สุดคือผลรวมของ 4 stage ใหม่

### 9. 🐛 เตรียมรับคลิปยาว — chunk ที่ไม่เคยถูกลบ และ instrumentation ที่เงียบไปเอง

**ไฟล์:** [CapturePipelineCoordinator.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt) · [PerfReport.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfReport.kt)

**ที่มา:** คำถามว่า "รันคลิป 1-2 ชั่วโมงจะเจออะไร" · สเกลจาก run4mins (273.7 s · 1.93 GB · 36 chunk · 2,281 sample) ไป 2 ชั่วโมงคือ **~950 chunk · ~60,000 sample · ~3,500 รูป** แล้วไล่ดูว่าอะไรพังก่อน

#### 🔴 chunk `.mp4` ไม่เคยถูกลบเลย — ตัวที่จะพังก่อนเพื่อน

`grep delete` ทั้ง `androidApp/` เจอแค่ 3 ที่: JPEG ที่ dedup ตัด · temp JPEG หลังส่งเข้าแกลเลอรี · segment ค้างตอน error **ไม่มีที่ไหนลบ `import_NNN.mp4` หลัง Worker 2 อ่านเสร็จ**

`videoQueue` cap 8 คุมแค่จำนวนที่*รอคิว* ไม่ได้คุมจำนวนที่*มีอยู่* cache จึงสะสมสำเนา remux ของทั้งคลิป:

| ความยาว | ค้างใน cache | + ไฟล์ต้นฉบับ |
|--|--|--|
| 4.5 นาที | 1.93 GB | ไม่มีใครสังเกต |
| 1 ชั่วโมง | **~25 GB** | ~25 GB |
| 2 ชั่วโมง | **~51 GB** | ~51 GB |

แถม `hasStorageForRecording()` เช็คครั้งเดียวตอนเริ่ม import ไม่ได้เช็คระหว่างทาง — **ไม่มีอะไรหยุดมันกลางคัน**

แก้ด้วย `releaseChunkFile()` ใน `finally` ของ worker loop · ปลอดภัยเพราะสิ่งเดียวที่อยู่ต่อจาก chunk คือ**ชื่อ**ใน `ChunkRecord.videoFileName` สำหรับ `session_log.txt` ไม่มีเส้นทางไหนเปิดไฟล์ซ้ำ · มีสวิตช์ `KEEP_PROCESSED_CHUNKS` ไว้ debug

#### 🟠 `frames[]` จะทำให้ render `perf_report.json` ไม่ไหว

1 sample = 1 JSON object (~180 bytes) · 2 ชั่วโมง = 60,000 → **~11 MB ของ string** ที่ต้องสร้างผ่าน `JSONObject` tree ในหน่วยความจำ (overhead หลายเท่า) **ตอน drain บนเครื่องที่เพิ่งถูกอัดมาสองชั่วโมง**

แก้ด้วย `MAX_FRAME_DIAGS = 30,000` — chunk แรกๆ เก็บรายละเอียดครบ chunk หลังๆ เหลือแต่ค่ารวม · **ค่าที่คนอ่าน run ยาวจริงๆ ต้องใช้อยู่นอก `frames[]` ทั้งหมด** (reject tally · stage · `cpuProbeMs`) ส่วน sharpness percentile ย้ายไปคำนวณตอน `addChunk()` **ก่อน**ตัด จึงรอด

#### 🟠 `deviceLoad` ตาบอดหลังผ่านไป 1/3 ของ session

`MAX_LOAD_SAMPLES = 600` กับ 2 sample/chunk → เต็มที่ chunk ที่ 300 จาก 950 · **DVFS logging ที่เพิ่งใส่ในข้อ 8 จะหายไปตรงช่วงที่เครื่องร้อนจริง** ซึ่งเป็นเหตุผลเดียวที่มันมีอยู่

แก้ด้วยการ**หารสองแทนการตัดท้าย** — เมื่อเต็มให้ทิ้งทุกตัวที่ index คี่ แล้วเพิ่ม stride เป็นสองเท่า อนุกรมจึงกินทั้ง session เสมอที่ความละเอียดที่ลดลงตามความยาว

จำลองกับ 2 ชั่วโมง (3,801 sample): เก็บ **476 จุด ครอบคลุม index 0 → 3,800 ระยะห่างสม่ำเสมอ 8** เทียบของเดิมที่เก็บ 0–599 แล้วเงียบ 84% ที่เหลือ

#### บล็อก `truncation` ใหม่ — ทุก cap ต้องบอกตัวเองได้

ทั้งสาม cap เดิม**ล้มเหลวแบบเงียบ** ซึ่งเป็นวิธีล้มเหลวที่แย่ที่สุดของเครื่องมือวัด: รายงานที่ดูครบถ้วนแต่ขาดข้อมูลไปสองในสาม `perf_report.json` จึงมี:

```json
"truncation": {
  "frameDiagsKept": 30000, "frameDiagsDropped": 29800, "frameDiagBudget": 30000,
  "eventsDropped": 0, "eventBudget": 4000,
  "loadStride": 8, "loadSamplesSeen": 3801
}
```

ศูนย์ทั้งหมด = ไม่มีอะไรหาย · `chunks[].framesOmitted` บอกรายชั้นว่า `frames[]` ที่ว่างเปล่านั้นคือ "ไม่มีอะไรเกิดขึ้น" หรือ "งบหมด"

**ยังไม่ได้ทำ:** `publishStats()` ถูกเรียกทุก sample (~ทุก 105 ms) และทำ `chunkHistory.toList()` + `sumOf` ทั้ง list ทุกครั้ง — ที่ 36 chunk ไม่มีใครสังเกต ที่ 950 chunk × 60,000 ครั้ง = **~57 ล้าน element copy** ที่ขโมย CPU จาก pipeline ตรงๆ · ยังไม่แก้ในรอบนี้

**ยังไม่ได้ทดสอบ:** ทั้งหมดนี้มาจากการอ่านโค้ดกับสเกลเลขจาก run4mins — **ยังไม่เคยรันคลิปยาวจริงสักครั้ง** ตัวเลข 25/51 GB เป็นการคูณตรงๆ จาก 1.93 GB ต่อ 4.5 นาที

---

## แผนการทดสอบ

ไฟล์เดิมทั้งสองไฟล์ เครื่องเดิม เพื่อให้ delta ผูกกับโค้ดไม่ใช่ฟุตเทจ

| ID | Input | Build | เพื่ออะไร |
|--|--|--|--|
| **TC-05** | `run4mins.mp4` | v0.1.4 · `MULTISTEP_DOWNSCALE = false` | **แยกผลของข้อ 1 ล้วนๆ** — detect input เหมือน v0.1.3 เป๊ะ delta ทั้งหมดคือการแยกเธรด |
| **TC-06** | `run4mins.mp4` | v0.1.4 · `MULTISTEP_DOWNSCALE = true` (ค่าที่ตั้งไว้) | **แยกผลของข้อ 4 ล้วนๆ** — เทียบกับ TC-05 ตัวแปรเดียว |
| **TC-07** | `run1mins.mp4` | v0.1.4 (ค่าที่ตั้งไว้) | ต่อ baseline TC-03 |
| **TC-08** | `run4mins.mp4` | v0.1.4 · backend = ML Kit FAST | **เทียบ backend ตัวแปรเดียว** — วัดที่ wall clock ไม่ใช่ที่ `detect` เพื่อดูว่า detector ที่เร็วขึ้นแปลงเป็นงานเสร็จเร็วขึ้นจริงไหม |
| **TC-09** | `run4mins.mp4` | v0.1.4 · backend = LiteRT GPU | ↑ |
| **TC-10** | `run4mins.mp4` | v0.1.4 · backend = LiteRT NPU | ↑ |
| **TC-11** | live capture | v0.1.4 (ค่าที่ตั้งไว้) | **smoke test ไม่ใช่ benchmark** — ปิด NA-06 · live path ไม่ถูกทดสอบมาตั้งแต่ v0.1.3 ทั้งที่ 0.1.4 รื้อ Worker 2 ทั้งก้อนและเพิ่งเปลี่ยนเส้นทาง progress/stats ที่ live เดินผ่าน (`expectedChunks = 0` fallback) · ต้องรันก่อนอย่างอื่นทั้งหมด เพราะเป็นข้อเดียวในลิสต์ที่ "ไม่ทำ" แปลว่าอาจ ship ของพัง ไม่ใช่แค่ช้า |
| **TC-12** | `run4mins.mp4` | v0.1.4 · หลังข้อ 7/8 · backend = ML Kit FAST | **baseline ใหม่หลังเลื่อน decode** — เทียบกับ TC-08 ตรงๆ (backend เดียวกัน ไฟล์เดียวกัน) · อ่าน `cpuProbe.driftPercent` ก่อนตีความ delta |
| **TC-13** | `run4mins.mp4` | v0.1.4 · หลังข้อ 7/8 · `FRAME_QUEUE_CAPACITY = 2` | **แยกตัวแปรที่สองของข้อ 7 ออก** — เทียบกับ TC-12 บอกว่าคิวที่ลึกขึ้นให้อะไรจริงบ้าง · TC-12 บอกไปแล้วว่าน่าจะไม่ต่าง (`queue_wait` 0.62 s ทั้ง session) |
| **TC-14** | คลิป **20–30 นาที** | v0.1.4 · หลังข้อ 9 | **ขั้นกลางก่อนแตะคลิปยาว** — ยืนยันว่า chunk ถูกลบจริง (ดู cache ระหว่างรัน ไม่ใช่หลังจบ) · `truncation` ยังเป็นศูนย์ทั้งบล็อก · `deviceLoad` ครอบคลุมถึงจุดจบ · เทียบ ratio กับ TC-12 เพื่อดูว่า DVFS กินเพิ่มแค่ไหนเมื่อโหลดยาวขึ้น 5 เท่า |
| **TC-15** | คลิป **1 ชั่วโมง** | v0.1.4 · หลัง TC-14 ผ่าน | ของจริง · ดู `loadStride` ขึ้นเป็น 2 · `frameDiagsDropped` เริ่มไม่เป็นศูนย์ · เวลา render `perf_report.json` ตอน drain · **อย่ารันก่อน TC-14** |

> **TC-11 มาก่อน** ที่เหลือเป็นงานวัดผล อันนี้เป็นงานกันของพัง

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

**เกณฑ์ตกที่ต้องระวังเป็นพิเศษ:** ถ้า TC-05 ได้ `kept` ≠ 149 อย่าเพิ่งดีใจว่า "ได้รูปเพิ่ม" — มันแปลว่าการคัดเลือกท้าย chunk ไม่เทียบเท่าของเดิม ต้องหาสาเหตุก่อน (ข้อกวนใจเดิมคือ `enableTracking()` ที่ทำให้ผลแกว่ง ±2–3 ระหว่างรัน — ถอดออกแล้ว TC-05 รอบต่อไปจึงเทียบได้สะอาดกว่าที่เขียนบรรทัดนี้ไว้ตอนแรก)

### สิ่งที่ต้องวัดใน run ถัดไป — ข้อ 7/8 ยังไม่มีข้อมูลจากเครื่องจริงเลย

| ตัวเลข | ทำนาย | ถ้าไม่เป็นตามนี้แปลว่าอะไร |
|--|--|--|
| `jpeg_argb_full` **n** | ≈ `save_jpeg n` + `tooSoft` + `roiInvalid` (~579 จาก 2281) | ถ้า n ≈ framesSampled แปลว่าการเลื่อน decode ไม่ทำงาน — full-res ยัง decode ทุกเฟรม |
| `jpeg_argb_detect` **n** | = `framesSampled` เป๊ะ | ต่างเมื่อไหร่แปลว่ามีเฟรมหลุดไปโดยไม่ผ่าน detect |
| `yuv_nv21` + `nv21_jpeg` share | **ต่ำกว่า** `yuv_jpeg_argb` เดิม (89.5%) อย่างมีนัย | ถ้าเกือบเท่าเดิม แปลว่า ARGB decode ไม่เคยเป็นก้อนใหญ่ใน stage นั้น — สมมติฐานของข้อ 7 ผิด และเป้าหมายต่อไปคือ `yuv420ToNv21` |
| `worker_idle` | **ลดลง** จาก 211-220 ms/เฟรม | ถ้าไม่ลด แปลว่างานไม่ได้ย้ายไปฝั่ง worker จริง |
| `queue_wait` | ยังต่ำ (เคย 0.89 s) | ถ้าพุ่งขึ้นมาก แปลว่า consumer กลายเป็นคอขวดแทน — ถึงจุดที่ NPU เริ่มมีความหมาย |
| `kept` | ขยับได้ 1-2 เฟรมจาก DCT-vs-bilinear | ถ้าขยับเกิน ~5 ต้องสงสัย `sampleSizeFor()` เลือกกำลังผิดตัว |
| `totals.cpuProbe.driftPercent` | — (ยังไม่รู้ค่าปกติ) | รอบนี้คือรอบที่ตั้ง baseline · ต้องอ่านค่านี้**ก่อน**สรุปอะไรกับ delta ข้างบนทั้งหมด |
| RAM peak | **ลดลง** — คิวถือ JPEG ไม่ใช่ ARGB 4K | ถ้าโต ต้องดูว่า worker ถือ full-res bitmap ค้างไว้หลายใบพร้อมกันไหม |
| `decodeFailed` ใน `frames[].outcome` | **0** | ค่าที่ไม่ใช่ 0 คือ outcome ใหม่ที่เพิ่งมีในข้อ 7 — JPEG decode ล้มบน worker |

**ยังไม่มีการรันใดๆ ทั้งสิ้นสำหรับข้อ 7 และ 8** — ตารางนี้คือคำทำนาย ไม่ใช่ผล

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

> **แก้แล้วใน 0.1.4** — `enableTracking()` ถูกถอดออกจาก [OfflineFaceDetector.kt](../androidApp/src/main/kotlin/com/autobots/camera/detection/OfflineFaceDetector.kt) แล้ว (live path ใน `MlKitFaceAnalyzer` ไม่เคยเปิดอยู่แล้ว) · ไม่มีอะไรในโปรเจกต์เคยอ่าน `Face.trackingId` เลย สิ่งที่มันจ่ายไปคือ `roiInvalid` ทั้งหมด + ผลที่ทำซ้ำไม่ได้ (v0.1.3 ต้องเขียนเตือนเองว่าอย่าเชื่อ delta เล็กๆ) · ผลข้างเคียงคือ **`noSubject` ของ TC-06 ยังแยกไม่ออกอยู่ดี** แต่ทุกการทดลองหลังจากนี้จะไม่มีตัวแปรนี้ปนแล้ว

### เครื่องช้าลงเรื่อยๆ ตลอด session

เทียบเฉพาะ chunk ที่ `kept = 0` (ไม่มีงาน rotate/save เลย จึงเทียบกันได้ตรงๆ):

| chunk | ms/frame |
|--|--|
| 2 | 142.4 |
| 11 | 168.6 |
| 21 | 174.5 |

**+23% ตลอด 6.7 นาที** ทั้งที่ `thermal` รายงาน `OK` (level 0) ตลอด · RAM ไม่โต · `decodeFailures` 0 — น่าจะเป็น DVFS ที่ต่ำกว่าเกณฑ์รายงานของ thermal API

> วิธีเทียบข้างบนนี้ต้องรอให้มี chunk ที่ `kept = 0` โผล่มาเอง จึงใช้กับทุก run ไม่ได้ · ข้อ 8 เปลี่ยนเป็นวัดตรงๆ ด้วย `cpuProbeMs` ทุก chunk แทน — **ยังไม่มีข้อมูลจากของจริง** run ถัดไปคือรอบแรกที่จะได้เห็น

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

> **ข้อ 7 ทำให้โมเดลนี้ต้องคำนวณใหม่** และไม่ใช่แค่ตัวเลข — มันไม่ได้ "ลด `yuv_jpeg_argb`" แต่**ย้าย**ARGB decode ข้ามไปฝั่ง consumer ทั้งก้อน · producer ลดลงเท่าที่ ARGB decode เคยกิน ส่วน consumer โตขึ้นด้วยงานเดียวกัน ลบส่วนที่ประหยัดได้จากการ decode ที่ detect resolution (1/4 ของ pixel) และจากการทำ full-res แค่ 25% ของเฟรม · `max(producer, consumer)` ตัวใหม่จึงต้องวัดเอา คาดคะเนจากเลขชุดเดิมไม่ได้ — **ห้ามอ้างตัวเลขในบล็อกนี้กับ build หลังข้อ 7**

---

## NPU / LiteRT — ทำแล้ว และผลหักล้างเหตุผลที่เขียนไว้ตอนแรก

ส่วนนี้เคยเขียนว่า *"ยังไม่ใช่รอบนี้"* พร้อมเหตุผล 4 ข้อ สุดท้ายทำในรอบนี้ และ**ข้อ 4 ผิด**

### ทำอะไรไป

`DetectorBackend` เลือกได้ตอน runtime 5 ตัว — ML Kit FAST / ACCURATE, `face_det_lite` บน CPU / GPU / NPU — ทั้งหมดกินภาพเดียวกัน จึงเปลี่ยนได้ทีละตัวแปร ผ่านชั้น `SubjectFaceDetector` เดียวกัน

`face_det_lite` รับ tensor **640×480 grayscale** ส่วนเฟรมตั้งคือ 9:16 ถ้า letterbox ทั้งเฟรมจะย่อ 0.125 เท่า เหลือหน้าสูง ~17 px ต่ำกว่า ~40 px ที่ ML Kit ได้ — โมเดลจะดูแย่ลงเพราะเรขาคณิตล้วนๆ จึงตัดเป็น **3 tile ทับกัน** ซึ่งที่ detect bitmap 640 px กว้าง ออกมาพอดี 640×480 **ไม่ต้องย่อเลย** โมเดลเห็นพิกเซลชุดเดียวกับ ML Kit แค่แบ่งส่วน — นั่นคือสิ่งที่ทำให้เทียบกันได้จริง

QNN ไม่มี Java binding มีแต่ C API จึงต้องเขียน JNI shim (`src/main/cpp/qnn_delegate_jni.cpp`) สามอุปสรรคที่เสียเวลามากที่สุดบันทึกไว้ที่ [jniLibs/README.md](../androidApp/src/main/jniLibs/README.md) — สรุปสั้น: `<uses-native-library>` ที่ขาดไปคือต้นเหตุตัวจริง, HTP เป็น **v73 ไม่ใช่ v75** (ห้ามเดาจากไฟล์ใน `/vendor`), และไฟล์ฝั่ง DSP ต้องแยกโฟลเดอร์จาก arm64 เพราะ `libQnnSystem.so` ชื่อชนกัน

### วัดอย่างไร

`Compare all` รัน **ทุก backend บนเฟรมเดียวกัน** โดยให้ ML Kit FAST เป็นคนตัดสิน keep/reject คนเดียว — ตัวเลขของ pipeline จึงเทียบกับ release ก่อนได้เหมือนเดิม ที่เหลือเป็นการสังเกตการณ์ ผลลง `detector_compare.json`

ที่ต้องทำแบบนี้เพราะการ import ทีละ backend **แยกไม่ออกระหว่าง "คนละ detector" กับ "คนละเฟรม"** และตอบไม่ได้เลยว่า bbox decode ที่เขียนเองถูกหรือเปล่า

### ผล — run4mins.mp4 · UHD · 2281 เฟรม · 986 เฟรมที่มีใครเห็นอะไร

| backend | avgMs | เฟรมที่เจอ | **ผ่าน size gate** | median IoU vs FAST |
|--|--|--|--|--|
| ML Kit FAST | 94.9 | 812 | 404 | — |
| ML Kit ACCURATE | 123.5 | 985 | 485 | 0.727 |
| `face_det_lite` CPU | 106.8 | 722 | 417 | 0.740 |
| `face_det_lite` GPU | 65.2 | 723 | 416 | 0.739 |
| **`face_det_lite` NPU** | **50.2** | 720 | 414 | 0.740 |

**bbox decode ถูกต้อง** — เรื่องที่เสี่ยงที่สุดของงานนี้ ค่าที่ decode ได้มาจากการอนุมานช่วง quantization ไม่ได้อ่านจากเอกสาร ผลคือ `face_det_lite` ตรงกับ ML Kit FAST (0.740) **มากกว่าที่ ML Kit สองโหมดของตัวเองตรงกัน** (0.727) และมีเฟรมเดียวจาก 703 ที่ IoU < 0.3 ถ้า decode ผิดค่านี้จะใกล้ 0

**CPU/GPU/NPU เห็นตรงกัน** 722/723/720 เฟรม IoU ต่างกันในหลักพัน — delegate เปลี่ยนแค่ความเร็ว ไม่เปลี่ยนสิ่งที่เห็น ซึ่งเป็น control ที่ต้องการพอดี NPU เร็วกว่า GPU ใน **877/986 เฟรม (89%)** median ต่างกัน 14 ms และ p90 ของ NPU (64.7 ms) ยังต่ำกว่า median ของ GPU

**LiteRT CPU ช้ากว่า ML Kit บนงานจริง** (106.8 vs 94.9) ทั้งที่บนภาพสังเคราะห์เร็วกว่า (62 vs 87) — ตัวเลข smoke test ไม่ทนต่อโหลดจริง เป็นเหตุผลที่มันถูกตัดออกจาก UI

### สองข้อที่หักล้างสิ่งที่เคยเขียนไว้

**1. "คุณค่าจริงของมันคือ recall ไม่ใช่ speed" — กลับกันทั้งข้อ**

recall เท่ากันภายในความคลาดเคลื่อนของ threshold (414 vs 404 ที่ gate; ได้เพิ่ม 42 หายไป 32 ทั้งสองกองอยู่คาเส้น) สิ่งที่ได้จริงคือ **speed −47%** ซึ่งเป็นสิ่งเดียวที่ข้อความเดิมบอกว่าจะไม่ได้

**2. `no_subject` 64% ไม่ใช่ความผิดของ ML Kit**

```
framesAllEmpty = 1295 / 2281  (57%)
```

**detector 5 ตัว จาก 2 ตระกูลโมเดล เห็นตรงกันว่า 1295 เฟรมไม่มีหน้าให้เจอ** สมมติฐานที่ผลักดันงานทั้งเฟสนี้ — "ML Kit พลาดคนที่มองเห็นด้วยตา" — ไม่ผ่านการวัด หน้าในคลิปเล็กเกินไปที่ 640 px ต่างหาก เป็นปัญหา**ความละเอียดและเรขาคณิต ไม่ใช่โมเดล** (ปิด OQ-01)

### ML Kit ACCURATE — ข้อได้เปรียบที่ไม่มีจริง

ดูเผินๆ ACCURATE เจอ 985 vs 812 (+21%) แต่ 174 เฟรมที่มันเจอคนเดียวมี **median height ratio 0.014** ขณะที่ gate คือ 0.035 — ผ่านจริง 5 เฟรม

และ +81 ที่ผ่าน gate แยกได้เป็น **5 เฟรมที่เจอหน้าใหม่จริง** กับ **83 เฟรมที่เป็นหน้าเดิมแต่วาดกรอบสูงกว่า 7.1% จนข้ามเส้น** พิสูจน์ได้โดยลด gate ของ FAST ลง 7.1% → ได้ 552 เฟรม **มากกว่า** ACCURATE ที่ 485

ไม่ใช่เรื่องความสามารถ เป็น artifact ของ threshold

### ผลพลอยได้ที่สำคัญกว่า: gate อยู่ผิดที่

พอมีการกระจายตัวของขนาดหน้าทั้งชุด (812 เฟรม) ก็เห็นว่า **0.035 อยู่บนจุดชันที่สุดของมันพอดี**

| ช่วง | เฟรมที่ได้เพิ่มต่อ 0.001 |
|--|--|
| 0.040 → 0.038 | 28 |
| 0.038 → 0.035 | 33 |
| **0.035 → 0.032** | **49** ← gate เดิมอยู่ตรงนี้ |
| 0.032 → 0.030 | 36 |
| 0.030 → 0.028 | 29 |

threshold ที่วางบนจุดชันสุดคือ threshold ที่ไวต่อ "โมเดลนี้วาดกรอบยังไง" มากที่สุด และนั่นไม่ใช่เรื่องสมมติ — มันคือคำอธิบายของ ACCURATE ข้างบนพอดี (7.1% × ความชัน 49 ≈ 120 เฟรม, วัดได้ 83)

**UHD จึงเปลี่ยนเป็น 0.030** ออกจากหน้าผา (36/0.001) และการสลับ detector จะแกว่งจาก ~30% ของเฟรมที่ผ่าน เหลือ ~12% ราคาที่จ่ายคือขนาดหน้าขั้นต่ำ 134 px → **115 px ในภาพ 4K ที่ส่งมอบ** ซึ่งยังจำหน้าได้ ส่วนเฟรมที่ผ่าน gate ขึ้นจาก 404 → 623 (+54%)

**FHD คงไว้ที่ 0.035 เพราะยังไม่ได้วัด** — ratio เดียวกันที่ FHD คือ ~58 px ในภาพ 1080p ซึ่งเล็กกว่ามาก จะลอกตัวเลข UHD มาใช้ไม่ได้ จึงย้าย `minFaceHeightRatio` เข้า `ProcessProfile` แยกตามความละเอียดเหมือน `minSharpness`

> ⚠️ ตัวเลขข้างบนคือ **เฟรมที่ผ่านด่านขนาด** ไม่ใช่ `kept` — ยังต้องผ่าน sharpness แล้วถูกคัดเหลือ 3 ต่อหน้าต่าง 1 วินาที ผลจริงต้องวัดด้วยการ import อีกรอบ

### ตัดสินใจเรื่อง backend

| | |
|--|--|
| **UI เหลือ 4 chip** | ML Kit FAST · GPU · NPU · Compare all — ตัวที่เครื่องใช้ไม่ได้จะ disable พร้อมบอกเหตุผล |
| **ACCURATE / CPU อยู่ในโค้ดต่อ** | ยังรันใน Compare all และ ML Kit FAST ยังเป็น fallback เมื่อ LiteRT สตาร์ตไม่ขึ้น |
| **ยังไม่ตั้ง NPU เป็น default** | pipeline เป็น producer-bound (`worker_idle` 373 s vs `queue_wait` 0.89 s) — detector ที่เร็วขึ้นทำให้ worker ว่างนานขึ้น ไม่ได้ทำให้ chunk เสร็จเร็วขึ้น **GPU ได้ผลมา 76% ของ NPU โดยไม่กิน APK สักไบต์** ส่วน NPU กิน 97 MB และรันได้เฉพาะ Snapdragon |

NPU จะคุ้มเมื่อ **แก้คอขวด producer แล้ว** หรือ **เพิ่มจำนวน tile เพื่อไล่หาหน้าเล็ก** — ซึ่งเป็นคันโยกจริงข้อถัดไปที่ข้อมูลชี้ ตอนนั้น 14 ms จะมีค่า

**HRNetPose ยังพักไว้** ด้วยเหตุผลเดิม — เป็น top-down single-person ต้องมี person detector ป้อน crop ให้ก่อน และ **pose ยังไม่เคยถูกทดสอบเลย** จะยกเครื่องก่อนมี baseline ไม่ได้

---

## ผลการทดสอบ — TC-08/09/10 · เทียบ backend แบบ end-to-end

`Compare all` ตอบว่า *detector ตัวไหนเห็นดีกว่าบนเฟรมเดียวกัน* แต่ตอบไม่ได้ว่า *เลือกตัวไหนแล้วงานเสร็จเร็วขึ้นไหม* เพราะมันรันทุก backend ในรอบเดียว จึงรัน `run4mins.mp4` สามรอบติดกัน เปลี่ยนแค่ chip เดียว

| ID | เวลา | backend | album |
|--|--|--|--|
| **TC-08** | 14:32 | ML Kit FAST | `ext_v0_1_4_13082026_1432` |
| **TC-09** | 14:38 | LiteRT GPU | `ext_v0_1_4_13082026_1438` |
| **TC-10** | 14:49 | LiteRT NPU | `ext_v0_1_4_13082026_1449` |

2281 เฟรมที่ sample · 36 chunks · `decodeFailures` 0 · `thermal` OK ทุกรอบ

| | TC-08 ML Kit FAST | TC-09 GPU | TC-10 NPU |
|--|--|--|--|
| wall clock (extract) | **376.6 s** | 398.9 s | 383.3 s |
| ms/frame | **165** | 175 | 168 |
| `realtimeRatio` | ~1.40 † | 1.489 | **1.430** |
| `kept` | 124 | **135** | 134 |
| `detect` avg | — ‡ | 60.3 ms | **39.8 ms** |
| `detect` max | — ‡ | **2,464.7 ms** | 313.3 ms |
| `detect` sharePercent | — ‡ | 34.5% | 23.7% |
| `yuv_jpeg_argb` sharePercent | — ‡ | 91.8% | 92.1% |
| `worker_idle` avg | — ‡ | 211.5 ms | 220.3 ms |

† คำนวณจาก session log (376.6 / 267.96) — TC-08 ไม่มี `perf_report.json`
‡ TC-08 บันทึกเป็น `session_log.txt` อย่างเดียว เทียบได้แค่ระดับ wall clock

### NPU เร็วกว่า GPU 34% ที่ inference — และแทบไม่มีผลกับเวลารวม

`detect` ลดจาก 60.3 → 39.8 ms คือประหยัดเวลา consumer ไป **~47 วินาที** แต่ wall clock ลดแค่ **16 วินาที (4%)**

นี่คือคำทำนายของหัวข้อ "producer-bound" ที่ถูกวัดซ้ำอีกครั้ง — `yuv_jpeg_argb` ยืนที่ **92% ของ wall ทั้งสองรอบ** และ `worker_idle` (211-220 ms/frame บน 2 worker) แปลว่า detect worker นั่งว่างเกินครึ่งเวลาอยู่แล้ว **detector ที่เร็วขึ้นแค่ทำให้มันว่างนานขึ้น**

ตัวเลขนี้ปิดคำถามว่าควรเพิ่ม `DETECT_WORKERS` หรือทำ chunk แบบขนานไหม — **ไม่ควร** ทั้งคู่ขยายฝั่งที่ว่างอยู่แล้ว และ chunk ขนานจะแย่ง HW decoder กับกิน bitmap 4K เพิ่มเป็นเท่าตัว

### 🐛 GPU delegate มี warm-up cost ที่แพงจนเห็นได้ใน wall clock

| | TC-09 GPU | TC-10 NPU |
|--|--|--|
| chunk 1 · `detect` avg | 118.9 ms | 40.0 ms |
| chunk 1 · `detect` max | **2,464.7 ms** | 313.3 ms |
| chunk 1 · `processDurationMs` | 12,623 | 9,527 |
| chunk 2+ · `detect` avg | 55-59 ms | 39-41 ms |

เฟรมแรกของ GPU กินไป **2.5 วินาที** เฟรมเดียว (shader compile) ทำให้ chunk 1 ช้ากว่า NPU 3 วินาทีจากการ warm-up ล้วนๆ ก่อนเข้า steady state · ยังไม่ได้แก้ — ทางที่ควรลองคือ warm-up ตอนสร้าง `DetectorSet` ด้วยเฟรมเปล่า ไม่ใช่ปล่อยให้เฟรมจริงเฟรมแรกจ่าย

### GPU กับ NPU คือโมเดลเดียวกันจริง — ต่างกันแค่ floating point

| reject | TC-09 GPU | TC-10 NPU | delta |
|--|--|--|--|
| `noSubject` | 1,558 | 1,561 | +3 |
| `tooSmall` | 184 | 179 | −5 |
| `tooSoft` | 246 | 249 | +3 |
| `kept` | 135 | 134 | −1 |

ต่างกันไม่ถึง 0.2% ทุกช่อง — ยืนยันว่า JNI shim ส่งพิกเซลชุดเดียวกันเข้าไปทั้งสอง delegate และความต่างที่เหลือคือ numerical precision ไม่ใช่พฤติกรรมโมเดล **ผลนี้ทำให้ TC-09/TC-10 ใช้แทนกันได้ในทุกการทดลองที่ไม่ได้วัดความเร็ว**

ส่วน ML Kit ได้ 124 หน้าจากเฟรมชุดเดียวกัน — **น้อยกว่า LiteRT 8%** ซึ่งสอดคล้องกับ median IoU 0.740 ที่ `Compare all` วัดไว้: โมเดลคนละตระกูลวาดกรอบต่างกันพอที่จะข้ามหรือไม่ข้าม gate ขนาด 0.030

### ⚠️ ข้อจำกัดของการวัดรอบนี้

สามรอบรันติดกันในเครื่องเดียวโดยไม่พักให้เย็น และ **DVFS drift ที่ TC-06 เจอไว้ (23% ต่อ session) ยังไม่ได้ถูกควบคุม** — TC-08 รันเป็นรอบแรกจึงได้เครื่องที่เย็นที่สุด ระยะห่าง 4% ระหว่าง TC-08 กับ TC-10 **อยู่ในระดับเดียวกับ drift** จึงยังสรุปไม่ได้ว่า ML Kit เร็วกว่า NPU จริง

ที่สรุปได้แน่คือ TC-10 เร็วกว่า TC-09 **ทั้งที่รันทีหลัง** (เสียเปรียบ drift) ซึ่งทำให้ delta 16 วินาทีเป็น lower bound ไม่ใช่ upper bound

**รอบหน้าต้องพักเครื่องระหว่างรัน หรือสลับลำดับแล้วรันซ้ำ** ก่อนจะใช้ตัวเลขนี้ตัดสินใจอะไรที่ย้อนกลับยาก

### สิ่งที่ผลนี้ทำกับการตัดสินใจเรื่อง default

ยังคง **ไม่ตั้ง NPU เป็น default** ตามเดิม แต่เหตุผลเปลี่ยนไป: เดิมคือ "NPU เร็วกว่าแต่ไม่ได้ช่วย wall" ตอนนี้เป็น **"NPU เร็วเท่า ML Kit แต่เห็นหน้ามากกว่า 8%"** ซึ่งเป็นข้อได้เปรียบด้าน *yield* ไม่ใช่ *ความเร็ว* — และ yield คือสิ่งที่ต้นทุน APK 97 MB ต้องคุ้ม ไม่ใช่ ms

ตัดสินได้เมื่อ (ก) ควบคุม DVFS แล้ววัดซ้ำ และ (ข) รู้ว่า 8% นั้นคือหน้าคนละคนหรือเฟรมซ้ำของคนเดิม — ข้อหลังยังไม่มีข้อมูล

---

## ผลการทดสอบ — TC-12 · หลังเลื่อน full-res decode (ข้อ 7/8)

`run4mins.mp4` · UHD 3840×2160 rot 90° · 36 chunks · Xiaomi peridot (SM8635) · backend **LiteRT NPU** · schema 4 · `ext_v0_1_4_14082026_1214`

เทียบกับ **TC-10** (NPU เหมือนกัน ไฟล์เดียวกัน เครื่องเดียวกัน ต่างกันแค่โค้ด):

| | TC-10 | **TC-12** | |
|--|--|--|--|
| wall clock | 383.3 s | **270.8 s** | **−29.3%** |
| `realtimeRatio` | 1.430 | **1.010** | **−29.4%** |
| `kept` | 134 | **134** | เท่ากันเป๊ะ |
| `noSubject` | 1561 | 1557 | −4 (0.26%) |
| `roiInvalid` | 0 | 0 | — |
| `decodeFailures` | 0 | 0 | — |
| `worker_idle` | 211–220 ms/เฟรม | **107.7** | ครึ่งเดียว |

**งานเสร็จเร็วกว่าความยาวคลิป** — `totalDurationMs` 271,857 ms สำหรับต้นฉบับ 273,680 ms · ข้ามเส้น 1.0× ที่ v0.1.2–0.1.3 ตั้งไว้เป็นเป้าแล้วในทางปฏิบัติ (ยังไม่ใช่ live capture — ดู NA-06)

`kept` เท่าเดิมเป๊ะและ reject ขยับ 4 เฟรม คือคำตอบของความเสี่ยง **DCT-vs-bilinear** ที่ข้อ 7 เตือนไว้: กรอบที่ตั้งไว้คือ "ขยับ 1–2 เฟรมได้ เกิน 5 ต้องสงสัย `sampleSizeFor()`" ได้ 4 — ผ่าน

### คำทำนายทั้ง 5 ข้อตรวจแล้วตรงหมด

| ตัวเลข | ทำนายไว้ | วัดได้ |
|--|--|--|
| `jpeg_argb_full` n | = candidates + `tooSoft` + `roiInvalid` | **541 = 293 + 248 + 0** เป๊ะ |
| สัดส่วน full decode | ~25% ของเฟรมที่ sample | **23.7%** (541/2281) |
| `jpeg_argb_detect` n | = `framesSampled` | **2281** เป๊ะ |
| `decode_failed` | 0 | ไม่ปรากฏสักเฟรม |
| `worker_idle` | ลดลง | 211–220 → **107.7 ms/เฟรม** |

### 🔴 แต่เหตุผลที่เขียนไว้ในข้อ 7 **ผิด** — งาน ARGB ไม่ได้ถูกตัด มันแค่ย้ายฝั่ง

ข้อ 7 อ้างว่า "ตัด full-res decode ทิ้ง 75%" ตัวเลขจริงบอกคนละเรื่อง:

```
ARGB decode ที่เคยอยู่บน producer  ≈ 154.6 − 105.6 = 49.0 ms/เฟรม
ARGB decode ที่ย้ายมาอยู่บน consumer:
  jpeg_argb_detect  34.164 ms × 100% ของเฟรม      = 34.16
  jpeg_argb_full    53.575 ms × 23.7% ของเฟรม     = 12.70
                                          รวม      = 46.87 ms/เฟรม
```

**49.0 → 46.9 ms/เฟรม — งานหายไป 4%** ทั้งหมดของ 29% ที่ได้มาคือการ**ย้ายงานไปฝั่งที่ `worker_idle` วัดไว้ว่าว่าง 92%** ไม่ใช่การตัดงานทิ้ง

ทำไม `inSampleSize = 2` ไม่ลดงานลง 4 เท่า? เพราะ `jpeg_argb_full` 53.6 ms เทียบ `jpeg_argb_detect` 34.2 ms คือ **−36% ทั้งที่ pixel ออกน้อยกว่า 4 เท่า** — Huffman/entropy decoding ยังต้องไล่ทั้ง stream เหมือนเดิมไม่ว่าจะย่อเท่าไหร่ `inSampleSize` ประหยัดได้แค่ IDCT + chroma upsample + writeback

> **สิ่งที่ต้องจำ:** ทางที่ได้ผลคือ *ย้ายงานไปเธรดที่ว่าง* ไม่ใช่ *ย่อ input ให้ decoder* — ข้อเสนอไหนที่อ้างว่า "ลด pixel แล้วจะเร็วขึ้นตามสัดส่วน" กับ JPEG ต้องถูกตั้งคำถามด้วยตัวเลขคู่นี้ก่อน

### คอขวดตัวถัดไป — `yuv420ToNv21` เดี่ยวๆ คือ 61.6% ของ wall

```
producer  242.9 s (89.7% ของ wall)  ◄── ยังเป็นเพดาน
├─ yuv_nv21    166.9 s  61.6%  ·  73.2 ms/เฟรม
├─ nv21_jpeg    74.1 s  27.4%  ·  32.5 ms/เฟรม
├─ decode        1.4 s   0.5%
└─ queue_wait    0.6 s   0.2%

consumer  270.0 s ÷ 2 worker = 135.0 s (49.9% ของ wall)
├─ detect             83.5 s  ·  36.6 ms/เฟรม
├─ jpeg_argb_detect   77.9 s  ·  34.2 ms/เฟรม
├─ rotate             39.1 s  ·  72.2 ms × 541
├─ jpeg_argb_full     29.0 s  ·  53.6 ms × 541
├─ save_jpeg          27.3 s  ·  93.2 ms × 293
├─ scale_for_detect   12.8 s
└─ sharpness           0.4 s
```

`yuv_nv21` ใหญ่กว่า `nv21_jpeg` **2.25 เท่า** และเป็น nested loop ใน Kotlin ที่เรียก `ByteBuffer.get(index)` ทีละ byte — ที่ UHD คือ ~2M ครั้งต่อเฟรม · เร่งได้ด้วย bulk `get()` ต่อแถวเมื่อ `pixelStride == 1` **ไม่ต้องแตะ NDK ไม่ต้องแตะ HardwareBuffer**

ถ้าลดลงครึ่งเดียว: producer 242.9 → 159.5 s · `max(159.5, 135.0)` → **ratio ~0.60**

### ⚠️ ก้อนใหม่ที่โผล่มาเพราะงานย้ายฝั่ง

| เรื่อง | ตัวเลข | หมายเหตุ |
|--|--|--|
| **`rotate` โตเป็นก้อนใหญ่ฝั่ง consumer** | 39.1 s · 14.4% ของ wall | 72.2 ms × 541 เฟรม หมุน bitmap 4K · **ใหญ่กว่า `jpeg_argb_full` เอง** · เลี่ยงได้ด้วยการเขียน EXIF Orientation แทนการหมุน pixel แต่เปลี่ยนความหมายของไฟล์ที่ส่งออก |
| **เฟรมที่ตก `tooSoft` จ่าย decode เต็ม + rotate ฟรี** | **31.2 s** เสียเปล่า | 248 จาก 541 เฟรม (46%) decode 4K + หมุน แล้วโดนตัดที่ sharpness · ทางแก้: `BitmapRegionDecoder` decode เฉพาะ ROI ของหน้า (~100×130 px จาก 3840×2160) มาวัด sharpness แล้ว decode เต็มเฉพาะ 293 เฟรมที่ผ่าน |
| **`save_jpeg` เข้ารหัสทิ้ง 159 จาก 293 ไฟล์** | **14.8 s** | dedup ตัดท้าย chunk ตามดีไซน์ v0.1.4 · เคยเป็นเศษเงิน ตอนนี้เป็น 10% ของ wall แล้ว |

### `FRAME_QUEUE_CAPACITY` 2 → 6 เป็นตัวแปรเฉื่อย — ไม่ได้อะไรเลย

`queue_wait` เฉลี่ย **0.272 ms** รวมทั้ง session **0.62 s** · producer (242.9 s) ยังช้ากว่า consumer (135.0 s) เกือบสองเท่า คิวจึงว่างเกือบตลอด ความลึกไม่มีผลกับอะไร

ข้อ 7 บันทึกไว้เองว่าให้ตั้งกลับเป็น 2 เพื่อแยกผล — **ผลออกมาแล้วว่าไม่ต้องแยกก็ได้ เพราะมันไม่ได้ทำอะไร** TC-13 ยังควรรันเพื่อยืนยัน แต่คาดว่าจะได้เลขเท่ากันในระดับ noise · จะได้ผลก็ต่อเมื่อ producer เร็วกว่า consumer ซึ่งจะเกิดหลังแก้ `yuv_nv21`

### 🐛 บั๊กใน `DvfsProbe` เอง — `driftPercent` ที่รายงานออกมาอ่านไม่ได้

`totals.cpuProbe.driftPercent = −39.6` ซึ่ง**ตีความไม่ได้** เพราะ probe ของ chunk 1 = **6.217 ms** เทียบ steady state **~3.0 ms** — ครั้งแรกโดน **JIT compile ของตัว loop เองปนเข้าไป** probe ต้องมี warm-up round ก่อนเริ่มจับเวลาจริง

ตัด chunk 1 ทิ้งแล้วอ่านใหม่ ตัวเลขกลับมามีความหมายทันที:

| ตัววัด | ค่า |
|--|--|
| `cpuProbeMs` ต่ำสุด (chunk 6–7) | 2.998 ms |
| `cpuProbeMs` ล่าสุด (chunk 36) | 3.756 ms |
| **drift จริง** | **+25.3%** |
| `cpuMaxFreqKhz` เริ่ม → จบ | **2,918,400 → 1,651,200** (−43%) |
| `thermal` ตลอด 36 chunk | `OK` (level 0) |
| ms/เฟรมของ chunk ที่ `kept = 0` | 97.1 → 117.5 (**+21%**) |

**สามตัววัดอิสระเห็นตรงกัน และตรงกับ 23% ที่ TC-06 เจอ** — DVFS drift ยืนยันแล้วว่ามีจริงและวัดซ้ำได้ · `thermal` API มองไม่เห็นมันเลยตามที่สงสัยไว้ · ปิดคำถามของ NA เรื่อง drift ได้ เหลือแค่แก้ warm-up ของ probe

**นัยต่อ ratio 1.01:** วัดบนเครื่องที่ช้าลง 21–25% ระหว่างทาง ถ้าคุมความถี่ได้จริงน่าจะอยู่แถว **0.90**

---

## ยังไม่ได้แก้ใน 0.1.4

| เรื่อง | ทำไมเว้นไว้ |
|--|--|
| ⭐ **`yuv420ToNv21` — loop per-pixel ใน Kotlin** | **คอขวดตัวใหญ่ที่สุดที่เหลือ วัดยืนยันแล้วใน TC-12: 61.6% ของ wall เดี่ยวๆ** (73.2 ms/เฟรม · ใหญ่กว่า `nv21_jpeg` 2.25 เท่า) · เป็น nested loop อ่าน `ByteBuffer.get(index)` ทีละ byte ตลอด chroma plane = ~2M ครั้ง/เฟรมที่ UHD · เร่งด้วย bulk `get()` ต่อแถวเมื่อ `pixelStride == 1` **ไม่ต้องแตะ NDK ไม่ต้องแตะ MediaCodec** · ลดครึ่งเดียว → ratio ~0.60 |
| **`rotate` 4K — 14.4% ของ wall** | โผล่มาเป็นก้อนใหญ่หลังข้อ 7 ย้ายงานข้ามฝั่ง (39.1 s · 72.2 ms × 541 เฟรม) **ใหญ่กว่า `jpeg_argb_full` เอง** · เลี่ยงได้ด้วยการเขียน EXIF Orientation แทนการหมุน pixel — แต่เปลี่ยนความหมายของไฟล์ที่ส่งออก ต้องตัดสินใจเรื่อง contract ก่อน ไม่ใช่งาน perf ล้วน |
| **เฟรม `tooSoft` จ่าย full decode + rotate ฟรี — 31.2 s** | 248 จาก 541 เฟรม (46%) decode 4K + หมุน แล้วโดนตัดที่ sharpness · `BitmapRegionDecoder` decode เฉพาะ ROI ของหน้า (~100×130 px จาก 3840×2160) มาวัด sharpness แล้วค่อย decode เต็มเฉพาะ 293 เฟรมที่ผ่าน · คุ้มรองจาก `yuv_nv21` |
| **🐛 `DvfsProbe` ไม่มี warm-up** | probe ของ chunk 1 = 6.217 ms เทียบ steady 3.0 ms — โดน JIT compile ของ loop ตัวเองปน ทำให้ `totals.cpuProbe.driftPercent` ที่รายงานออกมา (−39.6%) **อ่านไม่ได้** · แก้ด้วยการรัน throwaway round ก่อนเริ่มจับเวลา · ระหว่างที่ยังไม่แก้ ให้อ่าน `chunks[].cpuProbeMs` เองโดยข้าม chunk 1 |
| **`HardwareBuffer` / `ImageFormat.PRIVATE`** *(NA-02 · OQ-02)* | **ยังเว้นไว้** — ตอนนี้มีเป้าที่ถูกกว่าและเสี่ยงน้อยกว่ารออยู่สองอัน (`yuv420ToNv21` · `BitmapRegionDecoder`) ซึ่งรวมกันน่าจะพา ratio ลงต่ำกว่า 0.6 ได้โดยไม่ต้องแตะ MediaCodec · ทางนี้เคยล้มเหลวมาแล้วครั้งหนึ่ง เก็บไว้เป็นทางสุดท้าย |
| **`FRAME_QUEUE_CAPACITY` = 6** | TC-12 บอกว่ามัน**ไม่ได้ทำอะไรเลย** (`queue_wait` 0.62 s ทั้ง session) เพราะ producer ยังช้ากว่า consumer เกือบสองเท่า · ไม่ต้องรีบตั้งกลับเป็น 2 แต่ก็อย่าอ้างว่ามันช่วย · จะเริ่มมีผลหลังแก้ `yuv_nv21` เสร็จ |
| **`detectBitmapWidth` 640 → 960** | **ตอนนี้มีข้อมูลหนุนแล้ว** — detector 5 ตัวเห็นตรงกันว่า 57% ของเฟรมไม่มีหน้าให้เจอที่ 640 px ปัญหาคือขนาดหน้า ไม่ใช่โมเดล เพิ่มความกว้าง = หน้าใหญ่ขึ้นในแต่ละ tile และ NPU มีเวลาเหลือให้จ่าย ต้องทำเป็นการทดลองตัวแปรเดียว **แยกจากการเปลี่ยน gate** · **เว้นไว้จงใจ** — ข้อ 7 เพิ่งเปลี่ยน pixel ของภาพ detect ต้องรู้ baseline ใหม่ก่อน |
| **TC-05 (`MULTISTEP_DOWNSCALE = false`)** | **ยังไม่ได้รัน** — เป็นตัวควบคุมที่จะแยกผลของข้อ 1 ออกจากข้อ 4 และเป็นที่เดียวที่ตรวจได้ว่า `kept` = 149 เป๊ะ คือ "การคัดท้าย chunk เทียบเท่าของเดิม" ยังพิสูจน์ไม่ได้ · ข้อ 7 รักษาความหมายของ flag นี้ไว้แล้ว (`sampleSize = 1`) จึงยังรันได้ |
| **GPU delegate warm-up 2.5 s** | พบจาก TC-09 · เฟรมแรกของ session จ่ายค่า shader compile เต็มๆ (`detect` max 2,464.7 ms เทียบ NPU 313.3 ms) ทำให้ chunk 1 ช้ากว่า 3 วินาที · แก้ได้ด้วยการ warm-up ใน `DetectorSet` ด้วยเฟรมเปล่า แต่เป็นค่าคงที่ครั้งเดียวต่อ session ไม่ใช่คอขวด จึงยังไม่คุ้มจะแตะระหว่างที่ producer ยังกิน 92% |
| **NA-04 · ดูคลิปยืนยัน `no_subject`** | **ตอบด้วยการวัดแทนแล้ว** — detector 5 ตัวเห็นตรงกันว่า 1295/2281 เฟรมไม่มีหน้า การดูคลิปยังมีค่าอยู่ แต่ไม่ใช่ตัวบล็อกงาน detection อีกต่อไป |
| **NA-05 · ต้นเหตุบั๊ก drain** *(OQ-03)* | watchdog ยังคุมความเสี่ยงอยู่ · **ระวัง: การแยกเธรดเปลี่ยนจังหวะการจบ chunk** ซึ่งเป็นเงื่อนไขที่บั๊กนี้รออยู่พอดี ต้องดู `drain complete · trigger=` ในทุก run · **ตัดผู้ต้องสงสัยออกไปหนึ่งราย** — `recording` / `importing` / `awaitingRecorderFinalize` / `closed` ที่ `isBusy()` อ่านเป็น `Boolean` ธรรมดา เขียนจาก main แต่อ่านจาก watchdog coroutine คนละ dispatcher ไม่มี happens-before edge · เติม `@Volatile` แล้ว **แต่ยังพิสูจน์ไม่ได้ว่าเป็นตัวนี้จริง** ยังไม่มี repro |
| **NA-06 · ทดสอบ live capture** | ยังไม่ได้ทดสอบตั้งแต่ v0.1.3 — เป็นช่องว่าง scope ที่ใหญ่ที่สุด และตอนนี้ Worker 2 เพิ่งถูกรื้อ |
| **DVFS drift ~25% — คุมไม่ได้** | **มองเห็นแล้วแต่ยังคุมไม่ได้** · TC-12 ยืนยันด้วยสามตัววัดอิสระ (`cpuProbeMs` +25.3% · `cpuMaxFreqKhz` −43% · ms/เฟรมของ chunk ที่ `kept=0` +21%) ขณะที่ `thermal` รายงาน `OK` ตลอด · **ทุกตัวเลข ratio ในเอกสารนี้วัดบนเครื่องที่ช้าลงระหว่างทาง รวมถึง 1.01 ของ TC-12** (คุมได้น่าจะ ~0.90) · การเทียบข้าม run ยังปลอดภัยเฉพาะเมื่อ drift ใกล้กัน — เช็ค `totals.cpuProbe` ก่อนทุกครั้ง |
| **NA-07 · บล็อก live UHD ใน UI** *(OQ-05)* | **ตอบได้แล้วบางส่วน** — TC-12 พา import ลงถึง 1.01 (คุม DVFS แล้วน่าจะ ~0.90) แปลว่า live UHD ไม่จำเป็นต้องบล็อกด้วยเหตุผลด้านความเร็วอีกต่อไป · **แต่ยังตัดสินไม่ได้จนกว่าจะรัน NA-06** เพราะ live path มี camera encode แย่ง CPU เพิ่มซึ่ง import ไม่มี |
| **OQ-04 · `MAX_KEEP_PER_WINDOW`** | ยังไม่มี NA รองรับใน report v0.1.3 — ควรตั้งเป็น action ใน report v0.1.4 |

---

## Related

- ที่มาของตัวเลขก่อนแก้: [reports/v0.1.3/report.md](../reports/v0.1.3/report.md) · [RELEASE_0_1_3.md](./RELEASE_0_1_3.md)
- Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md)
- **ศัพท์เทคนิคในเอกสารนี้ (ไทย)**: [GLOSSARY_TH.md](./GLOSSARY_TH.md) — DVFS · TC-XX · `realtimeRatio` · YUV/NV21 · backpressure · `@Volatile`
- ศัพท์เชิงธุรกิจ: [CONTEXT.md](../CONTEXT.md)
- ประวัติเวอร์ชัน: [CHANGELOG.md](./CHANGELOG.md)
