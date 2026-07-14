# 12 — Implementation Phases

แนวทาง: **สร้าง KMP เปล่าให้รัน/เทสได้ก่อน** แล้วค่อยเติมความสามารถทีละชิ้นตาม [`11-mvp-spec.md`](./11-mvp-spec.md)  
แต่ละ phase จบเมื่อมี **เกณฑ์เทสที่ชัด** — อย่ากระโดดข้ามถ้า phase ก่อนยังไม่เขียว

อ้างอิงภาษาโดเมน: [`../CONTEXT.md`](../CONTEXT.md)

---

## ภาพรวม

```
P0  Empty KMP + Android shell (รันได้)
P1  Operator shell UI (ปุ่ม + สถานะปลอม)
P2  CameraX Preview only
P3  ImageAnalysis + Face detect + Subject Face overlay
P4  Arm → Face Lock (AF/AE)
P5  Fire → Lean Burst Keep-All + Passage Gate
P6  Write Queue + Local Delivery
P7  Capture Mode Option (Standard / Max-Sensor)
P8  Device Load Readout
─── MVP slice complete ───
P9+ Post-MVP (thermal throttle, scoring, upload, iOS) — นอกแผนนี้
```

---

## Phase 0 — Empty KMP ที่รันได้

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- สร้างโปรเจกต์ KMP: `shared` + `androidApp` (ยังไม่ต้องมี `iosApp` ส่งมอบ)
- จอว่าง / Hello + ข้อความจาก `AutobotsApp.banner` (shared)
- `./gradlew :androidApp:assembleDebug` ผ่าน

**ยังไม่ทำ:** CameraX, AI, permissions กล้อง

**เทส:** ติดตั้ง APK บนเครื่อง/เอมูเลเตอร์ → เปิดแอปเห็นจอ ไม่แครช

```bash
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug   # ถ้ามี device/emulator
```

---

## Phase 1 — Operator shell (UI โครง)

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- หน้าจอโครงตาม Operator Controls: **Start/Stop**, **Capture Mode** toggle
- สถานะปลอม: Armed / Fired / kept count / load (ค่า mock)
- ViewModel + state ใน `androidApp` (+ `CaptureMode` ใน `shared`)

**ยังไม่ทำ:** ผูกกล้องจริง

**เทส:** กด Start/Stop สลับโหมดแล้ว UI อัปเดต; kept count ตามโหมด (3 หรือ 1)

```bash
./gradlew :androidApp:installDebug
```

---

## Phase 2 — CameraX Preview

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- permission กล้อง
- CameraX **Preview** เต็มจอใต้ overlay UI จาก P1
- Start = bind preview, Stop = unbind

**ยังไม่ทำ:** ImageAnalysis, ImageCapture, face AI

**เทส:** เห็นภาพสดจากกล้อง; Start/Stop เปิด-ปิดพรีวิวได้

```bash
./gradlew :androidApp:installDebug
```

---

## Phase 3 — Face detect + Subject Face

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- `ImageAnalysis` 640×360 + face detector
- **P3 interim:** ML Kit Face Detection (สลับเป็น YOLOv8/TFLite ตาม doc 06 ได้ทีหลัง — interface แยกแล้วที่ analyzer)
- เลือก **Subject Face** = ใบหน้าใหญ่สุด (`SubjectFaceSelector` ใน shared)
- **Face Proximity** + วาด bbox (เขียว = subject, เหลือง = อื่น)

**ยังไม่ทำ:** AF/AE lock, burst, เขียนไฟล์

**เทส:** หันหน้าเข้ากล้อง → มี bbox + proximity อัปเดต; หลายหน้า → กรอบเขียวที่ใบใหญ่สุด

---

## Phase 4 — Arm → Face Lock

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- เมื่อ proximity ≥ Arm (~10%) → **Face Lock** AF + AE ที่ Subject Face (`FaceFocusController`)
- UI แสดง **Armed** อัตโนมัติ (hysteresis ปลดเมื่อ < 7%)
- throttle lock ~100ms

**ยังไม่ทำ:** Fire / burst

**เทส:** เดินเข้าใกล้ → Armed + โฟกัส/แสงเกาะใบหน้า

---

## Phase 5 — Fire → Lean Burst + Passage Gate

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- proximity ≥ Fire (slider, default 10%) → **Lean Burst** Keep-All
  - Standard: 3 shots / 200ms
  - Max-Sensor mode: 1 shot (ยังไม่ max-res จริง — นับช็อตอย่างเดียว)
- **Passage Gate**: ยิงครั้งเดียว แล้วปิดจนกว่า proximity ต่ำ / ไม่มีหน้า
- เขียน JPEG ชั่วคราวที่ `cacheDir/autobots_burst/` (P6 ค่อยย้าย DCIM)
- UI: Fired · Gate OPEN/closed · Kept count

**ยังไม่ทำ:** Write Queue ไป DCIM / Max-Sensor resolution จริง

**เทส:** เข้าใกล้เกิน Fire → ได้ ~3 ไฟล์; ค้างหน้าไว้ไม่ยิงซ้ำ; ถอยแล้วเข้าใหม่ยิงได้อีกรอบ

---

## Phase 6 — Write Queue + Local Delivery

**สถานะ:** ✅ scaffolded (2026-07-13)

**ทำอะไร**
- **Write Queue** แบบ bounded (capacity 8)
- หลัง Burst → enqueue cache JPEG → **MediaStore `DCIM/AutoBots/`** (เห็นในแกลเลอรี)
- Kept count เพิ่มเมื่อ delivery สำเร็จ
- ปุ่ม **Open Gallery** เปิดรูปล่าสุดใน AutoBots

**เทส:** Fire อัตโนมัติ → เปิด Gallery เห็นรูป · โฟลเดอร์ `DCIM/AutoBots`

---

## Phase 7 — Capture Mode Option

**สถานะ:** ✅ scaffolded (2026-07-14)

**ทำอะไร**
- สลับ **Standard** (burst ×3 Keep-All @ ~1920×1080) ↔ **Max-Sensor** (×1, max resolution)
- factory default = Standard
- ห้าม Max-Sensor + burst ×3
- เปลี่ยนโหมดขณะ Start → rebind ImageCapture

**เทส:** สลับโหมดแล้วพฤติกรรมช็อต/ไฟล์ตรงตารางใน spec; default เปิดมาเป็น Standard

---

## Phase 8 — Device Load Readout (จบ MVP slice)

**สถานะ:** ✅ scaffolded (2026-07-14)

**ทำอะไร**
- อ่าน **thermal** (`PowerManager`, API 29+) + **approx RAM** (`ActivityManager.MemoryInfo`)
- โชว์บนการ์ดสถานะ — **display only** ไม่ throttle
- poll ~2s + listener เมื่อ thermal เปลี่ยน
- สี: ขาว OK · เขียวคราม Warm · ส้ม Hot · แดงสด Very hot · แดงเข้ม Critical

**เทส:** ค่าบนจอขยับเมื่อโหลดเครื่อง (เช่น เปิด Max-Sensor / ยิงหลายรอบ); ไม่มีการลด fps อัตโนมัติจากโค้ดเรา

---

## Post-MVP (ยังไม่ใส่ในรอบนี้)

ดูรายการรวมที่ [`post-mvp.md`](./post-mvp.md) · ตัดสินใจที่ [`decisions.md`](./decisions.md)

---

## กฎการทำงาน

1. **หนึ่ง phase ต่อ PR / ต่อรอบงาน** ถ้าทำได้  
2. Phase ถัดไปเริ่มได้เมื่อเกณฑ์เทสของ phase ก่อนผ่าน  
3. ของที่เลื่อน (scoring, thermal throttle) **ห้ามลักลอบใส่** ระหว่าง P0–P8  
4. `shared/commonMain` ใส่สัญญา/โดเมนล้วน; CameraX / TFLite อยู่ `androidMain` / `androidApp`

---

## ลำดับเริ่มทันที

**ถัดไป = Post-MVP** (ถ้าจะต่อ): thermal throttle / scoring / upload / iOS — นอก P0–P8
MVP slice = **P0–P8 ปิดแล้ว** (P8 Device Load Readout)
