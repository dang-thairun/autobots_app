# Changelog

Release notes for AutoBots Sports Camera.  
Version source of truth: **`gradle.properties`** → `appVersionName` / `appVersionCode`  
Also sync: `shared/.../AutobotsApp.kt` → `version` (KMP, docs, non-Android).

---

## v0.1.6 (current)

**Theme:** เลือกเฟรมให้ตรงตัวคน · เห็นแสงทัน · รู้ว่าใครผ่านหน้ากล้องไปบ้าง
**Phase:** B1 + B3 (upload ทำเสร็จบน 0.1.5 แล้วยิง production ผ่าน — ดู [RELEASE_0_1_5.md](./RELEASE_0_1_5.md#งาน-upload-b3-แบบเต็ม))

รายละเอียดเต็ม: **[RELEASE_0_1_6.md](./RELEASE_0_1_6.md)**

### เพิ่ม

- **ตัวตรวจจับที่สาม — `foot_track_net` (Person) บน NPU** · ตัวเดียวที่รายงาน **ทุกคน** ในเฟรม ซึ่งเป็นเงื่อนไขที่ทำให้ per-runner tracking เป็นไปได้
- **`SubjectTracker`** — จับคู่ detection ข้ามเฟรมด้วย IOU กับ *กล่องที่ทำนายไว้* แล้วตามด้วยระยะห่างจุดกึ่งกลาง · **หน่วยของ dedup เปลี่ยนจาก 1 วินาทีของนาฬิกา เป็น 1 วินาทีของคนคนนั้น**
- **`FrameQuality`** — จัดอันดับเฟรมด้วยคะแนนรวม 5 ด้าน (sharpness `.40` · ขนาด `.20` · กลางเฟรม `.15` · confidence `.15` · ระยะห่างขอบ `.10`) แทนการตัดสินด้วยความคมอย่างเดียว
- **`tracks.csv`** — 1 แถวต่อคน **รวมคนที่ไม่ได้รูป** · เป็นครั้งแรกที่ตอบได้ว่า *พลาดใครไปกี่คน*
- **`photos.csv`** — คะแนนทั้ง 5 ด้านต่อรูปที่เก็บ ไว้ re-fit น้ำหนักกับหลักฐานจริง
- **Capture Zone ลากเองบนภาพจริง** (`ZoneEditorPage`) · **เพดานชัตเตอร์ + exposure compensation** — งานวิ่งตี 4 เดิม AE ยืดชัตเตอร์จนตกด่าน sharpness ทั้ง session
- **`perf_stream.jsonl`** — เขียนสดระหว่างรัน กู้รายงานได้แม้โปรเซสตาย
- Preview ติดทันทีที่เข้าหน้า Live (เดิมต้องกด Start ก่อน) · realtime ratio ขึ้นบนจอและใน Session history · จำนวนคนบนการ์ด history

### เปลี่ยน

- ⚠️ **`ExtractionTarget` จาก enum เป็น 3 flags** (`usesFace` · `usesPose` · `usesPerson`) — สาม flag คือแปดคอมบิเนชัน การไล่เขียนค่าคงที่แปดตัวคือการลอกตารางความจริงมาแทนที่จะใช้ type · **เกตทุกตัวเป็น AND ไม่ใช่ OR**
- ชื่อโฟลเดอร์อัลบั้มเป็น `v0_1_6_*` / `ext_v0_1_6_*`

---

## v0.1.5

**Theme:** รื้อ navigation เป็นเมนู + เปิดทางเข้าใหม่ให้ pipeline ผ่าน **Network URL**
**Phase:** B1 ขาเข้า — **งาน upload (B3) ถูกทำเพิ่มบนบิลด์ 0.1.5 หลังตัดเวอร์ชัน** และยิง production ผ่านแล้ว ดู [RELEASE_0_1_5.md](./RELEASE_0_1_5.md#งาน-upload-b3-แบบเต็ม)

รายละเอียดเต็ม: **[RELEASE_0_1_5.md](./RELEASE_0_1_5.md)**

### เพิ่ม

- **Home menu แทน `HorizontalPager`** — 5 destination (Home · Live capture · Import preview · Network URL · Session history) + ปุ่ม Back ของระบบ · กล้องถูก bind เฉพาะหน้า Live capture
- **Import Preview** — เลือกไฟล์แล้วไม่ extract ทันทีอีกแล้ว เห็น metadata (ขนาด · resolution · ความยาว · **fps**) เลือก Face/Pose เลือก backend (ตัวที่รันไม่ได้บอกเหตุผล) **ตัดช่วงเวลา** และเห็นเวลาโดยประมาณก่อนกด Extract
- **Network URL ingest** — พิมพ์ URL หรือ **สแกน QR** · รับเฉพาะ URL ของไฟล์วิดีโอตรงๆ · **สตรีมเป็นค่าเริ่มต้น** (byte-range ไม่ต้องโหลดครบก่อน) ดาวน์โหลดเฉพาะเมื่อ CDN ปฏิเสธ `MediaHTTPConnection`
- **ตัดช่วงเวลาตอน import** — `startTimeUs` / `endTimeUs` ใน `ImportedVideoSplitter` · progress คิดจากช่วงที่เลือก ไม่ใช่ทั้งไฟล์
- `VideoProbeResult.frameRate` — อ่าน fps จาก `CAPTURE_FRAMERATE` แล้ว fallback `MediaFormat.KEY_FRAME_RATE`

### เปลี่ยน

- ⚠️ **default detector: ML Kit FAST → LiteRT NPU** (fallback NPU → GPU → ML Kit) — **การเทียบผลกับ v0.1.4 ต้องระบุ backend ให้ชัด**
- `session_log.txt`: `Target: Face` → `Detector: Face · NPU · face_det_lite.tflite` · session จำ backend ที่ใช้จริงแล้ว
- `usesCleartextTraffic="true"` — รับ URL `http://` ของเซิร์ฟเวอร์ในสนาม

### แก้

- **QNN asset extraction เป็น atomic** (`.tmp` + rename) — เดิม copy ที่ขาดกลางคันทำให้ `err 4000` ถาวร แก้ได้ทางเดียวคือ clear app data · `apkTime` fallback `0L` ที่ทำให้อัปเกรด APK แล้วไม่ re-extract ก็ถูกถอดออก
- detector probe ย้ายไป `Dispatchers.IO` (แตก DSP library ~96 MB เป็น disk I/O ไม่ใช่งาน CPU)

---

## v0.1.4

**Theme:** Worker 2 เป็นสองเธรด — ทำให้ decode กับ detect ทำงานทับซ้อนกันแทนที่จะรอกัน
**Phase:** B1 (ไม่เปลี่ยน UI หรือ operator flow)

เหตุผลเต็มพร้อมคำทำนายที่รอผลวัด: **[RELEASE_0_1_4.md](./RELEASE_0_1_4.md)**

### วัดบนเครื่องแล้ว — `run4mins.mp4` UHD, Xiaomi peridot (SM8635)

| | v0.1.3 | **v0.1.4** | |
|--|--|--|--|
| **realtimeRatio** | 2.196× | **1.499×** | **−32%** |
| wall time | 588.5 s | **401.7 s** | −31.7% |

invariant ทั้งสามยังตรงเป๊ะที่ 302 / 579 / 550 · `decodeFailures` 0 · thermal OK ตลอด · `splitActiveMs` = **19.9 s** สำหรับ remux 1.93 GB (จากที่เคยรายงาน 455.8 s)

**pipeline เป็น producer-bound แบบสุดขั้วตามที่ออกแบบ** — `worker_idle` 373.5 s เทียบ `queue_wait` 0.89 s (420 เท่า) และ `yuv_jpeg_argb` ตัวเดียวกินไป **89.5% ของ wall** งานที่เหลือจึงเหลือเป้าหมายเดียว และตัวเลขบอกว่า**ลดมันครึ่งเดียวก็ข้ามเส้น 1.0 แล้ว** (consumer จะกลายเป็นเพดานที่ 0.77×)

**สองสิ่งที่ไม่เป็นไปตามคาด:**
- 📉 **สมมติฐาน aliasing ผิด** — `noSubject` 1,441 → 1,458 ไม่ลด การย่อแบบ halving ไม่ช่วย recall เลย ทั้งที่ `scale_for_detect` แพงขึ้น 4.1 เท่า (ฟรีในแง่ wall เพราะอยู่ฝั่งที่ว่าง 46.5%) OQ-01 กลับไปเป็นคำถามเปิด
- 🐛 **`enableTracking()` ถูกผ่าครึ่งโดยไม่ตั้งใจ** — detector 2 ตัวเห็นเฟรมคนละครึ่ง ช่องว่างเวลาโตเป็น 2 เท่า `roiInvalid` จึงเพิ่มจาก 16 → 29 เกือบเท่าตัว เป็นตัวแปรแฝงที่ทำให้ผลของ halving แยกไม่ออก

**ราคาของการทับซ้อน:** ทุก stage ช้าลงต่อเฟรม 17–78% จากการแย่ง CPU/memory bandwidth (`rotate` +78% · `mlkit_face` +29% · `yuv_jpeg_argb` +17%) แต่ wall ยังลง 32% — overlap ชนะ contention ขาดลอย เพดานทฤษฎี 1.09× จึงไปไม่ถึง

### ⭐ TC-12 · หลังเลื่อน full-res decode — ข้ามเส้น realtime

| | TC-10 (NPU ก่อนแก้) | **TC-12** | |
|--|--|--|--|
| wall clock | 383.3 s | **270.8 s** | **−29.3%** |
| `realtimeRatio` | 1.430 | **1.010** | **−29.4%** |
| `kept` | 134 | **134** | เท่ากันเป๊ะ |
| `worker_idle` | 211–220 ms/เฟรม | **107.7** | ครึ่งเดียว |

งานเสร็จใน **271.9 s สำหรับคลิป 273.7 s** — เร็วกว่าความยาวคลิปเป็นครั้งแรก · invariant ตรงเป๊ะ (`jpeg_argb_full` n = 541 = 293 candidates + 248 tooSoft + 0 roiInvalid) · `decodeFailures` 0 · `roiInvalid` 0 · reject ขยับ 4 เฟรมจาก DCT-vs-bilinear ซึ่งอยู่ในกรอบที่ทำนายไว้

- 🔴 **เหตุผลที่เขียนไว้ตอนแก้ผิด** — ARGB decode ไม่ได้ถูกตัดทิ้ง 75% มันลดแค่ 4% (49.0 → 46.9 ms/เฟรม) แล้ว**ย้ายฝั่ง**ไปเธรดที่ `worker_idle` วัดว่าว่าง 92% · `inSampleSize = 2` ให้ pixel น้อยลง 4 เท่าแต่เร็วขึ้นแค่ 36% เพราะ entropy decoding ของ JPEG ไม่ย่อตาม **บทเรียน: ย้ายงานไปเธรดที่ว่างได้ผล ย่อ input ให้ JPEG decoder ไม่ได้ผลตามสัดส่วน**
- 🎯 **คอขวดตัวถัดไปชี้ชัดแล้ว: `yuv420ToNv21` = 61.6% ของ wall เดี่ยวๆ** (73.2 ms/เฟรม · ใหญ่กว่า `nv21_jpeg` 2.25 เท่า) เป็น nested loop อ่าน `ByteBuffer.get()` ทีละ byte ~2M ครั้ง/เฟรม · แก้ด้วย bulk `get()` ต่อแถว ไม่ต้องแตะ NDK/MediaCodec · ลดครึ่งเดียว → ratio ~0.60
- ✅ **DVFS drift ยืนยันแล้วที่ +25%** ด้วยสามตัววัดอิสระ (`cpuProbeMs` +25.3% · `cpuMaxFreqKhz` 2,918,400 → 1,651,200 · ms/เฟรมของ chunk ที่ `kept=0` +21%) ขณะที่ `thermal` รายงาน `OK` ตลอด 36 chunk — ตรงกับ 23% ที่ TC-06 เจอ · **ratio 1.01 จึงวัดบนเครื่องที่ช้าลงระหว่างทาง คุมได้น่าจะ ~0.90**
- 🐛 **`DvfsProbe` ไม่มี warm-up** — probe ของ chunk 1 โดน JIT compile ปน (6.217 vs steady 3.0 ms) ทำให้ `totals.cpuProbe.driftPercent` ที่รายงาน (−39.6%) อ่านไม่ได้ · ต้องข้าม chunk 1 เองไปก่อน
- ⚠️ **ก้อนใหม่ที่โผล่หลังงานย้ายฝั่ง** — `rotate` 4K กลายเป็น 14.4% ของ wall (ใหญ่กว่า `jpeg_argb_full` เอง) · เฟรมที่ตก `tooSoft` จ่าย full decode + rotate ฟรี 31.2 s · `save_jpeg` เข้ารหัสทิ้ง 159 จาก 293 ไฟล์ (14.8 s)
- **`FRAME_QUEUE_CAPACITY` 2 → 6 เป็นตัวแปรเฉื่อย** — `queue_wait` รวมทั้ง session 0.62 s เพราะ producer ยังช้ากว่า consumer เกือบสองเท่า คิวว่างเกือบตลอด

### เทียบ backend แบบ end-to-end — TC-08/09/10 · คลิปเดียวกัน เปลี่ยนแค่ chip เดียว

| | ML Kit FAST | LiteRT GPU | LiteRT NPU |
|--|--|--|--|
| wall clock | **376.6 s** | 398.9 s | 383.3 s |
| `realtimeRatio` | ~1.40 | 1.489 | **1.430** |
| `kept` | 124 | **135** | 134 |
| `detect` avg | — | 60.3 ms | **39.8 ms** |

**NPU เร็วกว่า GPU 34% ที่ inference แต่ wall ลดแค่ 4%** — ประหยัดเวลา consumer ไป 47 วินาที ได้ wall คืนมา 16 วินาที เพราะ `yuv_jpeg_argb` ยืนที่ **92% ของ wall ทั้งสองรอบ** และ `worker_idle` 211–220 ms/frame บอกว่า detect worker ว่างเกินครึ่งเวลาอยู่แล้ว → **การเพิ่ม `DETECT_WORKERS` หรือทำ chunk ขนานไม่ช่วยอะไร** (ปิดคำถามนั้น)

- 🐛 **GPU delegate warm-up 2.5 วินาที** — `detect` max 2,464.7 ms ที่เฟรมแรก เทียบ NPU 313.3 ms ทำให้ chunk 1 ช้ากว่า 3 วินาทีจากการ warm-up ล้วนๆ
- **GPU กับ NPU คือโมเดลเดียวกันจริง** — reject ทุกช่องต่างกันไม่ถึง 0.2% (`noSubject` 1558 vs 1561) ยืนยันว่า JNI shim ป้อนพิกเซลชุดเดียวกัน · ส่วน ML Kit ได้หน้าน้อยกว่า LiteRT 8% จากเฟรมชุดเดียวกัน
- ⚠️ **สามรอบรันติดกันไม่ได้พักเครื่อง** — DVFS drift 23%/session ที่ TC-06 เจอไว้ยังไม่ถูกควบคุม ระยะห่าง 4% ระหว่าง ML Kit กับ NPU จึงยังสรุปไม่ได้ ต้องวัดซ้ำแบบสลับลำดับ

### Why

v0.1.3 จบที่ `realtimeRatio` 2.196× และสรุปว่าทางเดียวที่เหลือคือแก้ `yuv_jpeg_argb` (49.5%) ซึ่งลองแล้วล้มเหลว ข้อสรุปนั้นมองข้ามไปว่า **`yuv_jpeg_argb` ไม่ได้ทำงานพร้อมกับอะไรเลย** — `runBlocking { onFrame(...) }` ในลูป decode ทำให้ Worker 2 ทั้งตัวรันเรียงกับ decoder เวลาของ chunk จึงเป็น *ผลบวก* ของทั้งสองฝั่ง ทั้งที่เครื่องมี 8 คอร์และ thermal OK ตลอด

### Shipped

| Area | What | Why |
|------|------|-----|
| **Pipeline** | Worker 2 แยกเป็น producer (decode + YUV→Bitmap) กับ consumer (`DETECT_WORKERS = 2`) คั่นด้วย `Channel(2)` · worker แต่ละตัวถือ ML Kit detector ของตัวเอง (lazy ตาม target) | wall เปลี่ยนจาก `producer + consumer` (93%) เป็น `max(producer, consumer/2)` → คาด ratio 2.196 → **~1.3–1.5×** และทำให้ pipeline เป็น **producer-bound** ซึ่งแปลว่าต้นทุน detection ที่เพิ่มหลังจากนี้แทบไม่กระทบ wall |
| **Pipeline** | dedup ย้ายไปทำครั้งเดียวท้าย chunk หลังเรียง candidate ตาม PTS (`selectKeepers`) | worker เสร็จไม่เรียงลำดับ — การคัดแบบ streaming จึงใช้ไม่ได้อีก กติกาเดิมทุกประการ แต่ไม่ขึ้นกับลำดับที่ worker ทำเสร็จ |
| **Yield** | `downscale()` ย่อแบบ halving (4K → 1080 → 640) แทนขั้นเดียว · สวิตช์ `MULTISTEP_DOWNSCALE` + ฟิลด์ `downscaleMode` | `createScaledBitmap(filter=true)` เป็น bilinear อ่านแค่ 2×2 — ย่อ 3.4× ในขั้นเดียวทำให้ aliasing กินรายละเอียดที่ detector ใช้ หน้าที่เกณฑ์ขนาดเหลือ ~40 px พอดี **สมมติฐานที่ทดสอบได้สำหรับ OQ-01 (`no_subject` 63–79%)** |
| **Instrumentation** | stage ใหม่ `worker_idle` · `decoder_blocked` → `queue_wait` | คู่นี้บอกตรงๆ ว่าฝั่งไหนคือคอขวด: `queue_wait` สูง → เพิ่ม worker · `worker_idle` สูง → มีแต่งาน `yuv_jpeg_argb` ที่ช่วยได้ |
| **Instrumentation** | 🐛 `sharePercent` หารด้วย wall clock จริงแทนผลบวกของ stage · stage ติดป้าย `thread` · `SCHEMA_VERSION` 1 → **2** | `NESTED_STAGES` ของ v0.1.3 **ตก `rotate`** ทำให้ตัวหารเฟ้อ ~5% และ `sharePercent` ทุกตัวใน v0.1.3 ต่ำกว่าจริง วิธีเดิมต้องรู้ว่า stage ไหนซ้อนใน stage ไหน ซึ่งพลาดมาสองครั้งแล้วและใช้ไม่ได้อีกหลังแยกเธรด |
| **Instrumentation** | `splitDurationMs` แยกเป็น `splitActiveMs` / `splitBlockedMs` (NA-03) | v0.1.3 รายงาน 2,912 ms กับ 455,841 ms สำหรับ remux ที่ควรใช้ ~20 s ทั้งคู่ — ค่าเดิมวัดทั้ง pipeline ไม่ใช่ความเร็ว remux |
| **Detector** | `DetectorBackend` เลือกได้ตอน runtime — ML Kit FAST / ACCURATE · `face_det_lite` (Qualcomm AI Hub, w8a8) บน CPU / GPU / **NPU** ผ่าน JNI shim เหนือ QNN · โมเดลรับ 640×480 grayscale จึงตัดเป็น 3 tile ที่พอดี **ไม่ต้องย่อ** | เทียบ "โมเดลไหนเห็นดีกว่า" กับ "ฮาร์ดแวร์ไหนเร็วกว่า" แยกกันได้ทีละตัวแปร บนพิกเซลชุดเดียวกับที่ ML Kit เห็น |
| **Detector** | โหมด **`Compare all`** รันทุก backend บนเฟรมเดียวกัน โดย ML Kit FAST ตัดสิน keep/reject คนเดียว → `detector_compare.json` | import ทีละ backend แยกไม่ออกระหว่าง "คนละ detector" กับ "คนละเฟรม" และตอบไม่ได้ว่า bbox decode ที่เขียนเองถูกไหม |
| **Detector** | UI เหลือ 4 chip (FAST / GPU / NPU / Compare all) · `DetectorAvailability` disable ตัวที่เครื่องใช้ไม่ได้พร้อมบอกเหตุผล | ACCURATE จ่าย 30% ของเวลาเพื่อหน้าใหม่จริง 5 เฟรม · LiteRT CPU **ช้ากว่า ML Kit บนงานจริง** (106.8 vs 94.9 ms) ทั้งที่บนภาพสังเคราะห์เร็วกว่า · ทั้งคู่ยังอยู่ในโค้ดเป็น fallback และใน Compare all |
| **Yield** | `minFaceHeightRatio` ย้ายเข้า `ProcessProfile` แยกตามความละเอียด · **UHD 0.035 → 0.030** · FHD คงที่ 0.035 | วัดการกระจายขนาดหน้าทั้งชุดแล้วพบ 0.035 อยู่บน**จุดชันที่สุด** (49 เฟรม/0.001 เทียบกับ 28–36 ที่อื่น) — threshold ตรงนั้นไวต่อ "โมเดลวาดกรอบยังไง" ที่สุด และเป็นคำอธิบายของ "ACCURATE recall ดีกว่า" ที่แท้จริง · 0.030 = หน้า 115 px ในภาพ 4K ยังจำได้ · FHD ยังไม่ได้วัด ratio เดียวกันคือ ~58 px เท่านั้น |
| **UX** | 🐛 progress bar ระหว่าง import เลิกขับด้วย `importPercent` · ใช้ `overallProcessingPercent` กับตัวหาร `expectedChunks` ที่ประมาณจำนวน chunk ทั้งหมดแล้ว pin ค่าจริงเมื่อ split จบ · ข้อความเป็น `split 9/~36 chunks` + `waiting for extractor` | บาร์ค้างที่ **25-26%** ทุกครั้งที่ import คลิปยาว — ไม่ใช่บั๊กของ splitter แต่โชว์ผิดตัวเลข `splitActiveMs` เป็นแค่ 5% ของ `splitDurationMs` ที่เหลือคือจอดรอ `videoQueue` (cap 8) และ `importPercent` ไม่ขยับระหว่างจอด · ตัวเลขใหม่มาจาก extractor จึงขยับ **ทุก ~165 ms** แทนทุก ~10.7 s |
| **Pipeline** | ⭐ เฟรมข้ามคิวมาแบบ **JPEG** ไม่ใช่ ARGB (`SampledFrame`) · worker เป็นคน decode — `inSampleSize` สำหรับ detect แล้วค่อย decode เต็มขนาดเฉพาะเฟรมที่ผ่านด่านขนาด · `FRAME_QUEUE_CAPACITY` 2 → 6 | TC-08/09/10 พิสูจน์แล้วว่าเร่ง detector ไม่ช่วย (34% ที่ inference = 4% ที่ wall) เพราะ worker ว่างอยู่แล้ว **ทางแก้คือย้ายงานออกจาก producer ไม่ใช่ทำ producer ให้เร็วขึ้น** · full-res ARGB decode ลดจาก 100% ของเฟรมเหลือ ~25% (579/2281) และย้ายไปฝั่งที่ `worker_idle` วัดได้ว่าว่าง 92% · คิวถือ ~2 MB/ช่องแทน ~33 MB จึงลึกขึ้นได้ฟรี **⚠️ ยังไม่ได้วัดบนเครื่องจริง** |
| **Instrumentation** | `DvfsProbe` — งาน integer ขนาดคงที่ จับเวลาก่อนทุก chunk → `chunks[].cpuProbeMs` + `totals.cpuProbe.driftPercent` · `deviceLoad[].cpuMaxFreqKhz` จาก sysfs | TC-06 เจอ per-frame cost ลอย 23%/session ขณะที่ `thermal` รายงาน OK ตลอด — drift ใหญ่พอจะกลืนสิ่งที่กำลังวัด (ระยะห่าง 4% ของ TC-08 vs TC-10 อยู่ข้างในนั้น) · เลือกงาน integer แทนอ่านไฟล์ freq เพราะไม่ต้องพึ่ง permission และจับทุกอย่างที่ทำให้ core ช้าลง ไม่ใช่แค่ frequency |
| **Instrumentation** | `SCHEMA_VERSION` 3 → **4** — `yuv_jpeg_argb` หายไป แทนด้วย `yuv_nv21` + `nv21_jpeg` (producer) และ `jpeg_argb_detect` + `jpeg_argb_full` (consumer) | ARGB decode ย้ายเธรดแล้ว stage เดิมจึงไม่มีความหมายเดิมอีก · รายงาน schema 3 เทียบ share ต่อ stage กับ schema 4 ตรงๆ ไม่ได้ ตัวที่ใกล้ที่สุดคือผลรวมของ 4 stage ใหม่ |
| **Detector** | ถอด `enableTracking()` ออกจาก `OfflineFaceDetector` | ไม่มีอะไรในโปรเจกต์เคยอ่าน `Face.trackingId` เลย แต่มันจ่าย `roiInvalid` ทั้งหมด (tracker ทำนายกล่องล้ำขอบเฟรม) + ทำให้ผลไม่ deterministic (v0.1.3 ต้องเขียนเตือนเองว่าอย่าเชื่อ delta เล็กๆ) และพอ `DETECT_WORKERS = 2` detector แต่ละตัวเห็นเฟรมเว้นเฟรม `roiInvalid` เลยขึ้น 16 → 29 · ปิดแล้วผลขึ้นกับเฟรมของ chunk นั้นอย่างเดียว ซึ่งเป็นเงื่อนไขที่การทดลองหลังจากนี้ต้องการ |
| **Correctness** | 🐛 **ลบ chunk `.mp4` หลัง Worker 2 อ่านเสร็จ** (`releaseChunkFile()`) | ไม่มีที่ไหนในโค้ดลบมันเลย และ `videoQueue` cap 8 คุมแค่จำนวนที่*รอคิว* ไม่ใช่จำนวนที่*มีอยู่* — cache จึงสะสมสำเนา remux ของทั้งคลิป: 1.93 GB ที่ 4.5 นาที (ไม่มีใครสังเกต) → **~25 GB ที่ 1 ชม. · ~51 GB ที่ 2 ชม.** บวกไฟล์ต้นฉบับ · `hasStorageForRecording()` เช็คครั้งเดียวตอนเริ่ม จึงไม่มีอะไรหยุดมันกลางคัน · ปลอดภัยเพราะสิ่งเดียวที่อยู่ต่อคือ**ชื่อไฟล์**ใน `session_log.txt` |
| **Instrumentation** | `perf_report.json` รับ session ยาวได้ — `MAX_FRAME_DIAGS = 30,000` · `deviceLoad` หารสองแทนตัดท้าย · บล็อก `truncation` ใหม่ | `frames[]` 60,000 เฟรม = ~11 MB string ผ่าน `JSONObject` tree ตอน drain บนเครื่องที่เพิ่งถูกอัดสองชั่วโมง · `MAX_LOAD_SAMPLES = 600` ทำให้ **DVFS logging ที่เพิ่งใส่ไปตาบอดหลังผ่าน 1/3 ของ session** พอดีตอนเครื่องร้อนจริง — เปลี่ยนเป็นทิ้ง index คี่แล้วเพิ่ม stride อนุกรมจึงกินทั้ง session เสมอ (จำลอง 2 ชม.: 476 จุด ครอบคลุม 0→3,800 ระยะห่าง 8) · sharpness percentile ย้ายไปคำนวณก่อนตัดจึงรอด · **ทั้งสาม cap เดิมล้มเหลวแบบเงียบ** ซึ่งทำให้รายงานที่ขาดข้อมูล 2/3 ดูเหมือนสมบูรณ์ |
| **Correctness** | 🐛 `ImportedVideoSplitter` ปล่อย `MediaMuxer` ใน `finally` เสมอ + ลบ segment ที่ค้างครึ่งทาง · `CancellationException` ถูก rethrow ทั้งใน `split()` และ `importVideo()` แทนที่จะถูก `catch (Throwable)` กลืน | `var muxer` เคยประกาศอยู่ใน `try` จึงอยู่นอก scope ของ `finally` — ออกจากฟังก์ชันด้วย exception เมื่อไหร่ก็รั่วทั้ง native memory และ fd · และมันไม่ใช่เคสหายาก: `awaitQueueSpace` จอดอยู่ **95% ของเวลา split** (293.8 s จาก 313.8 s) ดังนั้นการกดยกเลิก import แทบทุกครั้งจะตกลงไปใน `delay()` แล้วโยน `CancellationException` เข้า `catch (Throwable)` พอดี — รั่วทุกครั้ง แถม cancellation หายไปเงียบๆ |
| **Correctness** | `@Volatile` บนสถานะที่ข้ามเธรดใน `CapturePipelineCoordinator` (`recording` · `importing` · `awaitingRecorderFinalize` · `closed` · ตัวนับ · `sessionMeta`) · `VideoChunkRecorder` · `VideoPreviewController.exposureStats` | สี่ตัวแรกคือสิ่งที่ `isBusy()` อ่าน และ `drainWatchdog` อ่านมันจากคนละ dispatcher กับที่เขียน — ไม่มี happens-before edge แปลว่า watchdog ไม่การันตีว่าจะเห็น `importing` เป็น `false` และ watchdog ที่ไม่เห็น pipeline ว่าง = session ที่ไม่เขียนทั้ง `session_log.txt` และ `perf_report.json` ซึ่งตรงกับอาการ **NA-05** · ที่เหลือเป็น visibility ของ UI · เลือก `@Volatile` ไม่ใช่ `Atomic` เพราะตัวนับมีผู้เขียนรายเดียว (worker coroutine) จึงไม่มี update ให้หาย มีแต่ค่าให้ publish |
| **Correctness** | `WriteQueue.enqueue()` นับ pending **ก่อน** `trySend` แล้ว rollback เมื่อคิวเต็ม · `currentSessionStatus()` รับ snapshot ของ `chunkHistory` แทนอ่าน list สด | ตัวแรกยังไม่เคยพังจริง เพราะ `workerBusy` (AtomicBoolean) ครอบ enqueue ทั้งชุดอยู่ — แต่นั่นคือ invariant ที่ไม่ได้เขียนไว้ที่ `WriteQueue` เลย และ `PhotoDeliveryService` ก็เรียกจากอีกเส้นทาง · ตัวหลังทำให้ status กับตัวเลขที่แสดงคู่กันมาจาก**ภาพเดียวกัน** (เดิม `buildSessionRecord` รับ snapshot แต่ `currentSessionStatus` แอบอ่าน list สดนอก `historyLock`) |
| **Build** | `<uses-native-library>` สำหรับ `libcdsprpc.so` / `libadsprpc.so` · `tools/restore-qairt.sh` | ตัวแรกคือ**ต้นเหตุตัวจริง**ที่ทำให้ NPU ใช้ไม่ได้ ทุกอย่างดู "สำเร็จ" แล้วไปพังลึกสองชั้นเป็น `Failed to apply delegate` · ตัวหลังกู้ไลบรารี QNN 97 MB + SDK headers ที่ gitignore ไว้และหายไปแล้วหนึ่งครั้งตอน `git reset` |

### ยังไม่ได้แก้ (ตั้งใจ)

`HardwareBuffer` / `ImageFormat.PRIVATE` (NA-02 · OQ-02) — **รอผลของการเลื่อน decode ก่อน** ตอนนี้ยังไม่รู้ว่าเหลืออะไรบน producer จริง (`yuv_nv21` เทียบ `nv21_jpeg` เป็นตัวเลขที่ยังไม่เคยเห็น) ทางนี้เคยล้มเหลวมาแล้วครั้งหนึ่ง จึงไม่ควรแตะจนกว่าจะมีเป้าที่ชัด · `detectBitmapWidth` 640→960 เก็บไว้เป็นการทดลองตัวแปรเดียวรอบถัดไป **แยกจากการเปลี่ยน gate** และตอนนี้ต้องรอ baseline ใหม่หลังเลื่อน decode ด้วย · **ยังไม่ตั้ง NPU เป็น default** — pipeline เป็น producer-bound จึงยังไม่ได้อะไรจาก 14 ms ที่เร็วกว่า GPU และ NPU กิน APK 97 MB เฉพาะ Snapdragon

**⚠️ live capture ยังไม่ได้ทดสอบตั้งแต่ v0.1.3 (NA-06)** ทั้งที่ 0.1.4 รื้อ Worker 2 ทั้งก้อน และแก้เส้นทาง progress/stats ที่ live เดินผ่านด้วย — เป็นข้อเดียวในลิสต์นี้ที่ "ยังไม่ทำ" แปลว่า *อาจ ship ของพัง* ไม่ใช่แค่ *ยังช้าอยู่* · ควรรันก่อนงานวัดผลทุกตัว

**🔴 สมมติฐานที่ผลักดันงาน detection ทั้งเฟส ถูกหักล้างด้วยการวัด** — `no_subject` 64% ไม่ใช่ความผิดของ ML Kit: detector 5 ตัวจาก 2 ตระกูลโมเดลเห็นตรงกันว่า **1295 จาก 2281 เฟรม (57%) ไม่มีหน้าให้เจอ** หน้าเล็กเกินไปที่ 640 px ต่างหาก เป็นปัญหาความละเอียดและเรขาคณิต ไม่ใช่โมเดล (ปิด OQ-01) · และ bbox decode ที่อนุมานเองถูกต้อง — `face_det_lite` ตรงกับ ML Kit FAST (median IoU 0.740) มากกว่าที่ ML Kit สองโหมดของตัวเองตรงกัน (0.727)

---

## v0.1.3

**Theme:** Worker 2 performance & yield — make **UHD faster than realtime** and stop discarding runners that were merely one step short of the size gate.
**Phase:** B1 (no change to pipeline shape, operator flow, or UI).

Full rationale with measured before/after: **[RELEASE_0_1_3.md](./RELEASE_0_1_3.md)**

### Why

`perf_report.json` from a real UHD import (60.8 s, Xiaomi peridot) showed v0.1.2 running at **realtimeRatio 2.35×** and keeping **3 photos from 507 sampled frames**. The cause was not detection, decode, or thermal throttling — **65.6% of all processing time was image format conversion**, and 96 of the discarded frames were rejected purely on face size (`tooSoft` was 0).

### Shipped

| Area | What | Why |
|------|------|-----|
| **Decode** | Surface/`ImageReader` decode written, tested on device, **found unsupported, and disabled** (`SURFACE_DECODE_ENABLED = false`). Probe now aborts after 3 frames and is remembered per process. | `c2.qti.avc.decoder` does not render into an `RGBA_8888` ImageReader — 0 frames, 54 s wasted per chunk, ratio **8.62×**. The probe+memo makes a wrong guess cost ~360 ms once instead. |
| **Rotation** | Sampler emits frames **unrotated**; detect input is scaled *then* rotated at ~640 px; full-res rotate happens only for frames that pass the size gate | `rotate` was **19.5%** of runtime (52 ms/frame on 4K). Detect bitmap dimensions are unchanged, so detection sees an identical image. |
| **Yield** | `MIN_FACE_HEIGHT_RATIO` 0.05 → **0.035** (and `setMinFaceSize` 0.05 → 0.025) | 42 of 96 rejected frames measured ≥ 0.035 — runners approaching the lens, cut one step short. Measured: `tooSmall` 96 → 50, candidates 9 → 42. The `setMinFaceSize` change turned out to make **no** difference (`noSubject` 402 → 404); the pipeline gate was the only binding one. |
| **Yield** | Dedup keeps the **top 3** frames per 1 s window instead of 1. Candidates are written to JPEG on arrival and ranked from disk, so memory stays at one bitmap. `IMAGE_QUEUE_CAPACITY` 16 → 48. | With the size gate opened, dedup became the new limiter: **42 candidates → 7 photos**. One runner's 16-frame pass yielded 3 photos. Matches Keep-All Policy (~3 per passage). Costs ~3.5 s (3% of runtime) in extra JPEG writes. |
| **Correctness** | `mapRect()` clamps the ROI to frame bounds; an unusable ROI is now counted as `roiInvalid`, not `tooSoft` | 4 frames scored exactly `0` with a 169 px-tall subject — ML Kit with tracking returns boxes past the frame edge, and the scorer returns 0.0 for an empty region. Those frames were being rejected as "blurry" when they were actually "not measurable". |
| **Correctness** | `WriteQueue` fires `onDelivered` **after** decrementing `pending`, not before | A session whose final chunk produced photos never wrote `session_log.txt` or `perf_report.json`: the drain check reads `pendingCount`, and the last file was still counted as pending when the callback ran. Present since v0.1.2 but masked — every 1-minute test happened to end on a chunk with `kept = 0`. Found only on a 4-minute file (158 photos, no logs). |
| **Decode** | Hardware decoder picked via `MediaCodecInfo.isHardwareAccelerated()` on API 29+ | The old `"omx"`/`"hw"` name heuristic never matches Codec2 names (`c2.qti.*`), so it always returned null and logged "using software" incorrectly |
| **Instrumentation** | `perf_report.json`: fixed double-counted `sharePercent`, removed duplicate `session_end`, added `chunks[].decodePath` | A measurement tool that reports wrong numbers is worse than none |

### Measured on device — two UHD imports, Xiaomi peridot / Android 16

Same file both versions (`run1mins.mp4`, 60.8 s, 507 frames):

| | v0.1.2 | v0.1.3 |
|--|--|--|
| **Photos kept** | **3** | **16 — 5.3×** |
| realtimeRatio | 2.35× | **2.017×** |
| Process time | 140.0 s | **120.1 s** |
| `rotate` | 26,458 ms (n=507) | **2,969 ms (n=55)** |
| candidates | 9 | **40** |

Then a longer file (`run4mins.mp4`, 273.7 s, 1.93 GB, 36 chunks): **149 photos · ratio 2.196× · 2,281 frames**. That is **0.54 photos per second of video vs 0.05 in v0.1.2 — roughly 10×**.

Three structural invariants held exactly on both runs — `save_jpeg n` = candidates, `rotate n` = candidates + tooSoft + roiInvalid, `sharpness n` = rotate n − roiInvalid (40/55/54 and 315/574/558). Holding across a 4.5× scale change is not coincidence.

`MIN_SHARPNESS = 65` is now **validated, not just untuned**: with n=558 the cutoff sits mid-distribution and cleanly separates sharp passages (chunk 23: 13% below) from soft ones (chunk 31: 100% below, a whole runner lost to blur in the source footage).

Still above 1.0×, so **live UHD is not yet viable**. That needs `yuv_jpeg_argb` (49.5% of the remaining runtime) solved, which this release attempted and failed. Thermal stayed OK and RAM flat across a 9.8-minute run — every remaining limit is in the code, not the hardware.

**Two caveats for reading future reports:**
- `enableTracking()` makes detection depend on frame history, so per-category reject counts vary ±2–3 between runs of the same file. Trust the structural counts and totals, not small deltas.
- `splitDurationMs` measures wall time including backpressure waits, not remux speed (2,912 ms vs 455,841 ms for the two runs). The two numbers are not comparable; splitting the field is queued for the next release.

### Not in this release (deliberate)

`yuv_jpeg_argb` remains unfixed — the largest outstanding item, needs an `ImageFormat.PRIVATE` + `HardwareBuffer` redesign verified on hardware. `MIN_SHARPNESS` re-tune waits for data from a top-3 run. Exposure control is live-capture-only. `noSubject` (404 of 507 frames, unchanged across both versions) is still undiagnosed. See RELEASE_0_1_3.md.

### Verify after install

`env.appVersion` = `0.1.3` · no `surface_rgba` stage · `save_jpeg n` = candidate count · `rotate n` = candidates + tooSoft + roiInvalid · no `sharpness: 0` anywhere · `realtimeRatio` ≈ 2.0–2.2

A session must always produce `session_log.txt` **and** `perf_report.json` — including one whose final chunk yielded photos, and including a 4-minute import. If either is missing, `adb logcat -s CamPerf | grep drain` names the stuck flag.

---

## v0.1.2

**Theme:** Plan B — continuous **video chunk** recording (live + import) → offline Face/Pose extraction → local gallery + session log.  
**Phase:** **B1** (replaces active stills/burst path in operator UI; legacy P5 code remains in repo but is not wired).

Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · Pipeline: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)

### Shipped

| Area | What |
|------|------|
| **Pipeline** | Workers: **Record** (`VideoChunkRecorder`) or **Import split** (`ImportedVideoSplitter`) → **Extract** (`VideoFrameProcessor`) → **Deliver** (`WriteQueue` → `DCIM/AutoBots`) |
| **Camera bind** | CameraX **Preview + VideoCapture** only — no live `ImageAnalysis` / face overlay while recording |
| **Chunk rotate** | Auto-finalize MP4 at **50 MB** (`StreamResolution.CHUNK_TARGET_BYTES`); immediate next chunk while camera keeps rolling |
| **Sample interval** | **120 ms** for both 1080p and 4K (`FRAME_SAMPLE_INTERVAL_MS`) |
| **Sharpness** | FHD ≥ 80 · UHD ≥ 65 (`FaceSharpnessScorer`) |
| **Extraction** | `MediaCodec` frame sampler → scale 640px → ML Kit offline Face (FAST) or Pose (experimental) → dedup **1 JPEG/sec** → full-frame JPEG |
| **Import video** | `OpenDocument` → remux split 50 MB chunks → same `videoQueue` as live |
| **Gallery folders** | Live `yyyyMMdd_HHmmss` · Import `ext_DDMMYYYY_HHMM` (`SessionAlbumNaming`) |
| **Session log** | `session_log.txt` → `Download/AutoBots/{subfolder}/` (API 29+) + cache mirror |
| **Stop behavior** | Stop = end record only; partial chunk finalized and queued; processing drains before IDLE |
| **Queue backpressure** | Video queue cap **8** — recorder pauses when full, resumes when worker catches up |
| **UI — status** | Compact chips: Ch / VQ / Face·Pose / K / Th / Disk; REC progress bar (size / 50 MB / elapsed / KB/s) |
| **UI — processing** | Face/Pose extraction card; throughput line on live only |
| **UI — history** | Swipe page **Session history** — `PipelineSessionRecord` cards, expand chunks, detection summary |
| **UI — settings** | Collapsible **Video pipeline** — Face/Pose + 1080p/4K (locked while recording) |
| **UI — actions** | Start/Stop, **Import**, Gallery |
| **Shared models** | `StreamResolution`, `ExtractionTarget`, `PipelineSessionRecord`, `ChunkRecord`, `PipelineStats`, `ChunkRecordingProgress`, `ExtractedFaceImage` |
| Remote | Ktor server: Start/Stop + resolution via WebSocket; state broadcast includes pipeline stats |
| **Screen mirror** | ใช้ **scrcpy** บน Mac — ดู [SCRCPY.md](./SCRCPY.md) (in-app preview stream ยังไม่ส่งเฟรม) |
| **Device load** | Thermal + RAM readout (unchanged from v0.1) |
| **Sync script** | `sync_gallery.sh` — ดึง JPEG + session log จากเครื่องกลับ Mac |

### Bug fixes (B1)

| Fix | Detail |
|-----|--------|
| **Partial chunk on Stop** | `awaitingRecorderFinalize` — pipeline no longer closes before camera finalizes last partial file |
| **Stop / unbind race** | `CameraPreviewPane` waits for recorder finalize before `unbindCamera()` |
| **Single-chunk Stop** | Start → Stop before first rotate (e.g. 8/50 MB) now produces Chunk #1 and runs extract |

### Not in this build

| Item | Notes |
|------|--------|
| Live face overlay on preview | Preview only during record |
| Burst stills / Passage Gate / Capture Zone Fire | v0.1 path not wired in operator shell |
| HTTP upload / cloud delivery | Local gallery only |
| Session JSON persistence | Session log as text file only; UI history in-memory per app run |
| Thumbnail preview in Session history | Path list + metadata only |

### Known gaps

| Topic | Detail |
|-------|--------|
| **4K extract recall** | 1080p field tests OK; 4K may report **No face** on chunks that visibly contain faces — likely sharpness threshold + decode path (tuning in B2) · **แก้แล้วใน v0.1.3–v0.1.5 · B2 ปิด 26/08/2026** |
| **Legacy code** | `MlKitFaceAnalyzer`, `LeanBurstCapturer`, `PreviewCameraController`, `FaceOverlay` etc. remain for reference / future re-wire |
| **Docs drift (remaining)** | None tracked — Phase 1–3 doc sync complete (v0.1 sections marked legacy where applicable) |

### Storage layout (session)

```
cache/autobots/{sessionId}/video/chunk_NNN.mp4
cache/autobots/{sessionId}/faces/face_{timestampUs}.jpg  →  DCIM/AutoBots/{subfolder}/
Download/AutoBots/{subfolder}/session_log.txt            ←  API 29+
```

### Key files

| Component | Path |
|-----------|------|
| Coordinator | `androidApp/.../pipeline/CapturePipelineCoordinator.kt` |
| Record | `androidApp/.../capture/VideoChunkRecorder.kt` |
| Import split | `androidApp/.../capture/ImportedVideoSplitter.kt` |
| Extract | `androidApp/.../pipeline/VideoFrameProcessor.kt`, `VideoFrameSampler.kt` |
| Preview + record | `androidApp/.../VideoPreviewController.kt` |
| UI | `OperatorShellScreen.kt`, `ChunkHistoryPage.kt`, `OperatorViewModel.kt` |
| Models | `shared/.../StreamResolution.kt`, `PipelineSessionRecord.kt`, `ExtractionTarget.kt` |

### Documentation (Phase 1 sync)

| File | Change |
|------|--------|
| [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) | Rewritten — live + import, 50 MB / 120 ms, session history, session log |
| [DOCS.md](./DOCS.md) | Added `PIPELINE_FLOW.md`; updated B1 + quick pipeline |
| [SCREEN.md](./SCREEN.md) | Already aligned with current shell (prior update) |

### Documentation (Phase 2 sync)

| File | Change |
|------|--------|
| [STRUCTURE.md](./STRUCTURE.md) | Plan B tree — pipeline/, import, session models; legacy marked |
| [PLATFORM_APIS.md](./PLATFORM_APIS.md) | Active vs legacy quick map; VideoCapture, MediaCodec, offline ML Kit |
| [BUILD.md](./BUILD.md) | Plan B first launch, logcat tags, `install_with_log.sh`, `sync_gallery.sh` |
| [README.md](../README.md) | Project summary + link to `docs/DOCS.md` |

### Documentation (Phase 3 sync)

| File | Change |
|------|--------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Plan B pipeline §2 active; v0.1 legacy §3; Flow 4 updated; defaults split |
| [PRD.md](./PRD.md) | Plan B scope + acceptance criteria; v0.1 sections labeled legacy |
| [CONTEXT.md](../CONTEXT.md) | Plan B glossary first; video-out-of-scope qualified |
| [FIELD_SETUP.md](./FIELD_SETUP.md) | Plan B field checklist + v0.1 legacy section |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | B1 shipped slices B1a–B1i · สถานะ phase ปัจจุบันดูที่ไฟล์นั้น |
| [CONVENTIONS.md](./CONVENTIONS.md) | PIPELINE_FLOW, OPERATOR_FLOW, Phase B1–B4 in tables |
| [ROADMAP.md](./ROADMAP.md) | Pose experimental shipped; body pre-filter still future |

### Documentation (Phase 4 sync)

| File | Change |
|------|--------|
| [BUILD.md](./BUILD.md) | Wireless install example (`adb -s <ip>:5555`); drift check script |
| [SCRCPY.md](./SCRCPY.md) | `VideoFrameProcessor` log tag; Related links |
| `OperatorShellScreen.kt` | Ch chip tooltip → 50 MB unified |
| [STRUCTURE.md](./STRUCTURE.md) | PRD tree label; `scripts/check_docs_drift.sh` |
| [PRD.md](./PRD.md), [IMPLEMENTATION.md](./IMPLEMENTATION.md), [FIELD_SETUP.md](./FIELD_SETUP.md), [PIPELINE_FLOW.md](./PIPELINE_FLOW.md), [ROADMAP.md](./ROADMAP.md), [CONTEXT.md](../CONTEXT.md) | § Related cross-links |
| [CONVENTIONS.md](./CONVENTIONS.md) | §7 Plan B doc sync checklist |
| [README.md](../README.md) | Helper scripts table |
| `scripts/check_docs_drift.sh` | New — drift guard for docs + UI tooltips |
| `docs/install.md` | **Removed** — content merged into BUILD.md |
| `architecture.md` → `ARCHITECTURE.md` | Rename for consistent doc naming |

---

## v0.1

**Theme:** MVP complete (P0–P8) + first tripod hardening (partial P9/P10) + operator UI polish.

### Shipped

| Area | What |
|------|------|
| **MVP P0–P8** | KMP shell, Operator UI, CameraX preview, ML Kit faces, Arm/AE, Lean Burst, Passage Gate, Write Queue → `DCIM/AutoBots`, Standard/Max-Sensor, thermal + RAM readout |
| **P9b** | Sustained AE lock (no 3 s auto-cancel); tripod path uses **AE-only** on face (`FocusStrategy.Fixed`) |
| **P10a–b** | **Capture Zone Fire** (`PassageFireEvaluator`); Early Arm ~2.5%; min size ~6%; settle ~100 ms; zone dwell 2 frames |
| **Speed** | Burst gap **150 ms** (1080p Standard); faster Arm/AE metering interval |
| **UI** | Compact status chips; **Start \| Gallery** row; face box **% score** on overlay |
| **Debug** | ML Kit `analyze` timing log (1 s summary) — `adb logcat -s MlKitFaceAnalyzer` |
| **Docs** | `BUILD.md`, `SCREEN.md`, `IMPLEMENTATION.md`, `FIELD_SETUP.md`, `PLATFORM_APIS.md`, Flows 13–18 |

### In progress / not shipped in v0.1

| Slice | Item | Status |
|-------|------|--------|
| **P9a** | Docs + shared contracts fully aligned | 🔄 mostly done |
| **P9c** | **Fixed Focus** — Camera2 lock distance at setup | ⏳ |
| **P9d** | **EV compensation** slider | ⏳ |
| **P10c** | Capture Zone drawn on observation grid | ⏳ |
| **P10d** | Field-tune defaults on site | ⏳ |

---

## Version bump checklist

1. Edit `gradle.properties` → `appVersionName` / `appVersionCode`
2. Edit `AutobotsApp.version` in `shared/.../AutobotsApp.kt` (same string)
3. Add section to this file
4. Update `docs/DOCS.md` phase table if a phase completed
5. Update the version claim in doc headers that carry one (`DOCS.md`, `ARCHITECTURE.md`,
   `STRUCTURE.md`, `PLATFORM_APIS.md`, `CONTEXT.md`, `README.md`) — these drifted 3 releases
   behind once already
6. Rebuild: `./gradlew :androidApp:assembleDebug`

**Do not use `.env`** — Android/KMP standard is `gradle.properties` + optional `AutobotsApp` for shared code.

---

## Earlier

Pre-changelog releases were tracked as **Phase P0–P8** only. See [DOCS.md](./DOCS.md) phase table for milestone history.
