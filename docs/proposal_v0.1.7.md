# 📝 เอกสารตอบกลับข้อเสนอแนะทางเทคนิค: แผนพัฒนา v0.1.7 (Person ID เป็นแกน)

> **ถึง:** ทีมพัฒนา AutoBots Camera  
> **เรื่อง:** ผลการตรวจสอบความสอดคล้องกับสเปก (Spec Review), การวิเคราะห์จุดเสี่ยงระดับ Production, และข้อเสนอแนะในการแตก Task  
> **อ้างอิง:** แผนงาน `v0.1.7 — Person ID เป็นแกน` / [RUNNER_CAPTURE_PIPELINE.md](./RUNNER_CAPTURE_PIPELINE.md) / [PIPELINE_GAP_ANALYSIS.md](./PIPELINE_GAP_ANALYSIS.md)

---

## 🎯 1. บทสรุปการประเมิน (Executive Summary)

**สถานะ:** ✅ **เห็นชอบในหลักการและโครงสร้าง (Approve & Ready to Proceed)**

การปรับเปลี่ยนแกนของระบบจาก **Frame-centric** เป็น **Person-centric** (ย้าย ByteTrack มาอยู่ต้นทาง และประเมินคุณภาพแยกตามรายคน) เป็นการตัดสินใจทางสถาปัตยกรรมที่ถูกต้องและตรงตามข้อกำหนดของ [RUNNER_CAPTURE_PIPELINE.md](./RUNNER_CAPTURE_PIPELINE.md) อย่างสมบูรณ์ ซึ่งช่วยแก้ปัญหาคนที่วิ่งคู่กับคนตัวใหญ่กว่าแล้วไม่ได้รูปได้อย่างถาวร

อย่างไรก็ตาม เพื่อให้ระบบมีความเสถียรสูงสุดสำหรับการใช้งานจริงในสนาม (Production-Grade: ตั้งถ่ายกลางแดดต่อเนื่อง 3–4 ชั่วโมง, รองรับนักวิ่งกลุ่มใหญ่, ทนทานต่อการแครช) ขอส่งมอบ **ข้อวิเคราะห์จุดเสี่ยง 5 ด้าน** และ **ข้อเสนอแนะการปรับแตก Task (Refined Slices)** ดังนี้

---

## ⚠️ 2. การวิเคราะห์จุดเสี่ยงระดับ Production & มาตรการป้องกัน (Risks & Mitigations)

### 🚨 จุดเสี่ยงที่ 1: พื้นที่ดิสก์เต็มจาก Candidate Staging (ความเสี่ยงสูงสุด)
* **ปัญหา:** W1 บันทึกภาพ Candidate ของทุกคนลงดิสก์ชั่วคราว แล้วให้ W2 ตามลบภายหลัง หากเกิดช่วงนักวิ่งหนาแน่นหรือ W2 ประมวลผลช้ากว่า W1 ขยะชั่วคราวจะสะสมเร็วมาก (รูป 4K ~1.5–2 MB × 5 คน/วิ = **~18 GB ใน 30 นาที**)
* **มาตรการป้องกันที่ต้องเพิ่ม:**
  1. **Staging Circuit Breaker:** กำหนดเพดาน In-flight Candidates สูงสุด (เช่น ไม่เกิน 150 ไฟล์) หากคิวเต็ม ให้ W1 คัดทิ้งในหน่วยความจำทันทีโดยไม่ต้องเขียนลงดิสก์
  2. **Dynamic Disk Watermark:** เช็กพื้นที่ดิสก์ว่างระหว่างรัน หากเหลือน้อยกว่า 2 GB หรือ < 5% ให้หยุดรับรูปเพิ่มและบังคับ Flush W2 ทันที
  3. **Startup Orphan Cleanup:** มีฟังก์ชันล้างโฟลเดอร์ `/staging/` และ `/sightings/` ที่ตกค้างจากการแครชทุกครั้งที่เปิดแอปใหม่

---

### 🏃 จุดเสี่ยงที่ 2: การจับคู่สลับคน (ID Switch) ของ ByteTrack ที่ 8.3 FPS
* **ปัญหา:** ที่ความถี่ 8.3 FPS (120ms) นักวิ่งที่วิ่งไหล่ชนกัน หรือวิ่งตัดหน้าแซงกัน จะมีจุดกึ่งกลางกล่องใกล้กันมาก ทำให้ DIoU อาจจับคู่สลับคนได้ง่าย
* **มาตรการป้องกันที่ต้องเพิ่ม:**
  1. **Height Consistency Penalty:** ใช้ความสูงของกล่อง (Height) มาเป็นตัวถ่วงน้ำหนักในการ Match เพราะความสูงของคนเดิมจะไม่เปลี่ยนกะทันหันใน 120ms
  2. **Direction Consistency Check:** ตรวจสอบทิศทางความเร็ว หากทิศทางกลับลำเกิน 90°–180° ใน 120ms ให้ถือเป็น False Match และตัดเป็น Unconfirmed

---

### 🧵 จุดเสี่ยงที่ 3: Concurrency, Partial Reads และ Session Drain Safety
* **ปัญหา:** W1 ส่งไฟล์ CSV ย่อย (`sightings/c0007.csv`) ให้ W2 อ่าน หาก Flush ข้อมูลลงดิสก์ไม่ทัน W2 อาจเจอปัญหา Partial Read รวมถึงหากกด Stop กลางคัน Stage 3 อาจถูก Cancel จนข้อมูล Metadata เสียหาย
* **มาตรการป้องกันที่ต้องเพิ่ม:**
  1. **Explicit File Sync:** สั่ง `fileOutputStream.fd.sync()` หรือปิด Stream ให้เรียบร้อยก่อน Enqueue ส่งต่อให้ W2
  2. **NonCancellable Session Drain:** ใน Stage 3 (รวมไฟล์และเย็บ Track) ต้องครอบด้วย `withContext(NonCancellable)` พร้อม Timeout ที่แน่นอน เพื่อการันตีว่าไฟล์ CSV รวมจะถูกเขียนจนจบสมบูรณ์เสมอ
  3. **Boundary Candidate Cleanup:** ต้องการันตีว่า Candidate ของคนที่แตะขอบ Chunk จะต้องถูกสั่งลบออกจากเครื่องเสมอแม้ Stage 3 จะข้ามการเย็บ (เช่น กรณี Gap > 600ms)

---

### 🎯 จุดเสี่ยงที่ 4: ขอบเขต ROI Clamping และ CPU Overhead ของ Sharpness
* **ปัญหา:** การคำนวณ Laplacian บน 4K Bitmap หลายๆ คนพร้อมกัน อาจทำให้เกิด `IllegalArgumentException` ถ้ากล่องหลุดขอบจอ หรือทำให้ CPU ร้อนจัด
* **มาตรการป้องกันที่ต้องเพิ่ม:**
  1. **Strict ROI Clamping:** ครอบพิกัด ROI ด้วย `Rect.intersect(0, 0, width, height)` ก่อนส่งเข้า Crop เสมอ
  2. **Focus on Face ROI:** คำนวณ Laplacian เฉพาะบน ROI ของใบหน้า (หรือลำตัวท่อนบน) ไม่ควรคำนวณทั้งตัว เพราะแขนขาที่แกว่งจะมี Motion Blur ตามธรรมชาติ

---

### 🏷️ จุดเสี่ยงที่ 5: ข้อมูล Metadata ขาดการเชื่อมโยงกับไฟล์ภาพ (Decoupled Metadata)
* **ปัญหา:** ใน `photos.csv` กำหนดให้ `1 แถว = (file × personId)` หากไฟล์ CSV สูญหาย ไฟล์ภาพจะไม่ทราบว่าเป็นของใคร
* **มาตรการป้องกันที่ต้องเพิ่ม:**
  1. ใช้ Android `ExifInterface` ฝัง `personId` และ `sessionId` ลงใน Exif Tag (`UserComment`) ของไฟล์ JPEG ขณะที่ W1 บันทึกภาพ

---

## 🏗️ 3. ข้อเสนอแนะการปรับแตก Task (Refined Slices Breakdown)

ขอเสนอให้คงโครงสร้าง 3 ก้อนหลักของ Dev ไว้ แต่เพิ่ม **Production Guardrails** เข้าไปในแต่ละ Slice ดังนี้:

```
[S0: Pre-flight Measurement] 
           │
           ▼
[ก้อนที่ 1: บันทึก, Staging Safety & แยก Worker (S1-S4)] 
           │
           ▼
[ก้อนที่ 2: Person-Centric Engine & ByteTrack DIoU (S5-S8)] 
           │
           ▼
[ก้อนที่ 3: Metadata Stitching, Drain Resilience & Exif (S9-S10)] 
           │
           ▼
[ก้อนที่ 4: Stress Testing & Production Sign-off (S11)]
```

### 📏 Phase S0 — การวัด Baseline ก่อนเริ่มงาน (คงเดิม)
* **S0.1:** วัด Gap ที่รอยต่อ Chunk (ms) จาก `session_log.txt`
* **S0.2:** วัดจำนวนคนที่ถูกหั่นที่รอยต่อ Chunk จาก `tracks.csv`
* **S0.3:** วัดอัตรา Track แตก (`framesSeen == 1`) เพื่อใช้เป็น Baseline เปรียบเทียบ

---

### 📦 ก้อนที่ 1 — วางโครงสร้างบันทึก, ความปลอดภัยของดิสก์ & แยก Worker (S1–S4)
* **S1: Data Flow ครบสาย:** ปรับ `TrackedBox` และ `Sighting.boxes` ให้ส่ง `score` และ `DetectedPerson` ได้สมบูรณ์
* **S2: Streaming CSV & Staging Guardrails (🆕 เพิ่มการป้องกันดิสก์):**
  * พัฒนาระบบ Streaming Write สำหรับ `sightings/c000X.csv` และ `chunks.csv`
  * ➕ *เพิ่มฟังก์ชัน Startup Orphan Cleanup ล้างไฟล์ตกค้างตอนเปิดแอป*
  * ➕ *เพิ่ม Staging In-flight Cap (จำกัดไม่เกิน 150 candidates)*
* **S3: แยก Worker 1 / Worker 2:** 
  * W1 แตะ Pixel เขียน Candidate และ CSV ย่อย $\rightarrow$ W2 อ่าน CSV ย่อยและคัดเลือกรูป
  * ➕ *เพิ่ม `fileOutputStream.fd.sync()` ก่อน Handoff ระหว่าง Worker*
* **S4: ปรับปรุง `tracks.csv`:** เพิ่มคอลัมน์ `framesSeen`, `framesMissed`, `speedMps`

---

### 📦 ก้อนที่ 2 — เปลี่ยนแกนเป็น Person-Centric & ByteTrack (S5–S8)
* **S5: Two-tier Detections:** ปรับ Detector คืนค่า High tier (> 0.5) และ Low tier (0.1–0.5)
* **S6: ByteTrack with DIoU & Robust Tuning:**
  * พอร์ต ByteTrack 3 สเตจ (High match, Low match, Unconfirmed) โดยใช้ **DIoU**
  * ปรับค่าคงที่ตามความถี่ 8.3 FPS จริง (8 เฟรม $\approx$ 1 วินาที)
  * ➕ *เพิ่ม Height Consistency & Direction Check ป้องกัน ID Switch จังหวะวิ่งชนกัน*
* **S7: Face-to-Body Multi Matching:** จับคู่ใบหน้าทุกคนกับตัวทุกคนในเฟรม (ไม่ยุบเหลือคนเดียว)
* **S8: Person-Centric Quality & ROI Scoring:**
  * ประเมิน `FrameQuality` และคำนวณ Sharpness แยกตาม ROI แต่ละคน
  * ➕ *เพิ่ม Strict Boundary Clamping และเน้นวัดความคมชัดที่ Face ROI*
  * บันทึก `sightings.csv` เต็มรูปแบบ และ `photos.csv` เป็น `(file × personId)`
  * ➕ *ฝัง `personId` ลงใน Exif `UserComment` ของไฟล์ภาพ*

---

### 📦 ก้อนที่ 3 — การเย็บรอยต่อ Chunk & ความเสถียรตอนจบ Session (S9–S10)
* **S9: Metadata Stitching & NonCancellable Drain:**
  * เย็บ Track ข้าม Chunk ที่ Stage 3 ด้วย Velocity Extrapolation โดยออกเป็นคอลัมน์ `personId`
  * W2 เก็บ Candidate แตะขอบไว้รอการเย็บ
  * ➕ *ครอบ Stage 3 ด้วย `withContext(NonCancellable)` และการันตีการลบ Boundary Candidate ที่ไม่ชนะ*
* **S10: เอกสารและ Architecture Sync:** อัปเดต `DOCS.md`, `ROADMAP.md`, `DESIGN_FLOW.md`

---

### 🧪 ก้อนที่ 4 (แนะนำเพิ่ม) — การทดสอบความเสถียรระดับ Production (S11)
* **S11: Production Stress Test & Field Simulation:**
  * รันคลิปทดสอบยาว 60–90 นาที (เช่น คลิปกลางคืน 547 Chunks)
  * ตรวจสอบว่า Peak Heap ไม่โตตามเวลา, ไม่มีไฟล์ Staging ตกค้าง, และ `realtimeRatio` ไม่เกิน 0.85

---

## 🛠️ 4. เครื่องมือที่แนะนำให้ทีม Dev ใช้ร่วมในการพัฒนา

1. **[`supervision`](https://github.com/roboflow/supervision) (Python):** ใช้เขียนสคริปต์สั้นๆ อ่าน `chunks.csv` + `sightings.csv` ไปวาด Trajectory และ Bounding Box บนวิดีโอต้นฉบับ เพื่อใช้ตรวจสอบความถูกต้องของ ByteTrack และการเย็บรอยต่อ
2. **[`motmetrics`](https://github.com/cheind/py-motmetrics) (Python):** ใช้วัดค่า **ID Switches** และ **IDF1** เพื่อยืนยันประสิทธิภาพของ S6 เชิงตัวเลข
3. **[Android Perfetto](https://ui.perfetto.dev/):** ใช้ดู Timeline ของ Worker 1 และ Worker 2 เพื่อตรวจเช็กว่าไม่มีการบล็อกกันระหว่างเทรด

---

## 🏁 5. เกณฑ์การตรวจรับงาน (Definition of Done)

- [ ] คนที่วิ่งคู่กับคนตัวใหญ่กว่าได้รับภาพถ่ายของตัวเองอย่างถูกต้อง
- [ ] Track แตก (`framesSeen == 1`) ใน `tracks.csv` ลดลงอย่างมีนัยสำคัญ
- [ ] ไฟล์ Staging ถูกล้างหมด 100% หลังจบ Session ไม่มีไฟล์ขยะตกค้าง
- [ ] `realtimeRatio` ของระบบไม่เพิ่มขึ้นเกิน 5% (ควบคุมอยู่ที่ระดับ ~0.50–0.60)
- [ ] เมื่อกด Stop กลางคัน แอปสามารถบันทึกและรวบรวมไฟล์ CSV ทั้งหมดได้ครบถ้วนโดยไม่แครช