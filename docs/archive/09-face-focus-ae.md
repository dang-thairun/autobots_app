# 09 — Face-Tracking AF + AE Lock

> **MVP status: IN SCOPE** as **Face Lock** ([ADR 0010](./adr/0010-face-lock-af-ae-mvp.md)).

## Problem

กล้องบน tripod โฟกัสที่กึ่งกลางภาพโดย default  
แต่นักวิ่งที่กำลังวิ่งเข้ามา — ใบหน้ามักอยู่ **ด้านบนของภาพ** (เพราะร่างกายอยู่ล่าง)  
และแสงกลางแจ้งมักทำให้ **background สว่างกว่าใบหน้า** → face underexposed

ต้องให้กล้อง:
1. **AF** — โฟกัสที่ตำแหน่งใบหน้า (ไม่ใช่กึ่งกลาง)
2. **AE** — expose สำหรับผิวหน้า (ไม่ใช่ background)

---

## CameraX Metering API

```
SurfaceOrientedMeteringPointFactory
        │  สร้าง MeteringPoint จาก (x, y) ใน surface coordinate
        ▼
FocusMeteringAction
        │  ระบุว่า point นี้ใช้สำหรับ AF / AE / AWB
        ▼
CameraControl.startFocusAndMetering(action)
        │  ส่ง metering region ไปที่ hardware camera ISP
        ▼
ISP: re-focus + re-expose ที่ region ที่กำหนด ✅
```

---

## Pre-lock Strategy (สำคัญมาก)

```
Timeline การเข้ามาของนักวิ่ง:

ไกล          กลาง         ใกล้       trigger
 [5%] ──── [10%] ──────── [25%] ──── [40%] ──── [60%]
             │                          │
             │ lockOnFace() เริ่ม       │ takePicture()
             │ (face > 10%)             │ AF settled แล้ว ✅
             ▼                          │
         AF settling...                 │
         ≈ 200–400ms                    │
             │                          │
             └────── AF ready ──────────┘
                     ก่อน trigger มาถึง
```

**หลักการ:** lock AF/AE ก่อนที่นักวิ่งจะถึง threshold → พอถึงเวลา trigger กล้องพร้อมทันที

---

## `FaceFocusController.kt` (androidMain)

```kotlin
package com.autobots.camera.detection

import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringAction.FLAG_AE
import androidx.camera.core.FocusMeteringAction.FLAG_AF
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import android.util.Log
import java.util.concurrent.TimeUnit

class FaceFocusController(
    private val cameraControl: CameraControl,
    private val cameraInfo: CameraInfo,
    // ขนาดของ ImageAnalysis output ที่ face detector ทำงาน
    private val surfaceWidth: Float  = 640f,
    private val surfaceHeight: Float = 360f
) : FocusStrategy {

    companion object {
        private const val TAG = "FaceFocusController"
        private const val AUTO_CANCEL_SEC = 3L   // reset AF หลัง 3 วิ ถ้าไม่มีการ update
    }

    private val meteringFactory = SurfaceOrientedMeteringPointFactory(
        surfaceWidth, surfaceHeight
    )

    /**
     * Lock AF + AE ไปที่ใบหน้าที่ detect ได้
     *
     * ควรเรียกทุก ~100ms เมื่อมีใบหน้าในภาพ
     * (ไม่ต้องรอ trigger — เรียกก่อนได้เลย)
     */
    override fun lockOnFace(bbox: BoundingBox) {
        // ── คำนวณ center ของใบหน้าใน surface coordinate ──────────────────
        val faceCenterX = ((bbox.left + bbox.right)  / 2f) * surfaceWidth
        val faceCenterY = ((bbox.top  + bbox.bottom) / 2f) * surfaceHeight

        // ── AF point: จุดเดียวที่กึ่งกลางใบหน้า ──────────────────────────
        // size = สัดส่วนของ bbox เทียบกับ surface (ยิ่งใหญ่ = AF region ใหญ่)
        val afSize = (bbox.width * 0.5f).coerceIn(0.1f, 0.5f)

        val afPoint = meteringFactory.createPoint(
            faceCenterX,
            faceCenterY,
            afSize
        )

        // ── AE point: ขยายกว่า AF เล็กน้อย เพื่อ expose ทั้งใบหน้า ────────
        // รวม forehead + chin ด้วย ไม่ใช่แค่กลาง
        val aeSize = (bbox.width * 0.8f).coerceIn(0.15f, 0.7f)

        val aePoint = meteringFactory.createPoint(
            faceCenterX,
            faceCenterY,
            aeSize
        )

        val action = FocusMeteringAction
            .Builder(afPoint, FLAG_AF or FLAG_AE)   // AF + AE ที่ face center
            .addPoint(aePoint, FLAG_AE)              // เพิ่ม AE region ใหญ่ขึ้น
            .setAutoCancelDuration(AUTO_CANCEL_SEC, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action)
            .addListener(
                {
                    // callback — log only, non-blocking
                    Log.d(TAG, "Focus metering applied: center=(${faceCenterX.toInt()}, ${faceCenterY.toInt()})")
                },
                { runnable -> runnable.run() }
            )
    }

    /**
     * ยกเลิก AF/AE lock กลับไปใช้ center/continuous mode
     * เรียกเมื่อไม่มีใบหน้าในภาพ
     */
    override fun reset() {
        cameraControl.cancelFocusAndMetering()
        Log.d(TAG, "Focus reset to default")
    }

    /**
     * ตรวจสอบว่าอุปกรณ์รองรับ AF metering หรือไม่
     * บางอุปกรณ์ (fixed-focus) จะ ignore AF แต่ยังทำ AE ได้
     */
    fun isAfSupported(): Boolean {
        return cameraInfo.isFocusMeteringSupported(
            FocusMeteringAction.Builder(
                meteringFactory.createPoint(0.5f, 0.5f),
                FLAG_AF
            ).build()
        )
    }
}
```

---

## Coordinate Mapping Explained

```
Face Detector output (BoundingBox):
  normalized 0.0–1.0
  left=0.3, top=0.1, right=0.7, bottom=0.5
  → center = (0.5, 0.3)

Surface coordinate (640×360):
  faceCenterX = 0.5 × 640 = 320 px
  faceCenterY = 0.3 × 360 = 108 px

SurfaceOrientedMeteringPointFactory(640, 360):
  createPoint(320, 108)
  → CameraX maps internally to camera sensor coordinate
  → ไม่ต้องคิดเรื่อง rotation หรือ crop ด้วยตัวเอง ✅
```

---

## Outdoor AE Scenarios

| สถานการณ์ | AE default (center) | AE on face |
|----------|---------------------|------------|
| ท้องฟ้าสว่าง + ใบหน้ามืด | expose สำหรับท้องฟ้า → **หน้ามืด** ❌ | expose สำหรับหน้า → **ถูกต้อง** ✅ |
| เงาไม้ + หน้าสว่าง | expose เฉลี่ย → **ไม่แน่นอน** | expose สำหรับหน้า → **ถูกต้อง** ✅ |
| กลางแจ้งสว่างจ้า (noon) | overexpose ทั้งภาพ | **ปรับลง** ให้หน้าพอดี ✅ |

---

## Integration ใน FaceAnalyzer

```kotlin
// ใน FaceAnalyzer.analyze()

if (faces.isNotEmpty()) {
    val bestFace = faces.maxByOrNull { it.confidence }!!
    val faceArea = bestFace.boundingBox.area

    // ── Phase 1: เริ่ม lock AF/AE ตั้งแต่ face เริ่มปรากฏ ─────────────
    if (faceArea > AF_START_THRESHOLD &&          // 10%
        now - lastFocusMs > FOCUS_UPDATE_MS) {    // ทุก 100ms
        focusController.lockOnFace(bestFace.boundingBox)
        lastFocusMs = now
    }

    // ── Phase 2: trigger capture เมื่อ face ใหญ่พอ ───────────────────
    if (faceArea > CAPTURE_THRESHOLD &&           // 40%
        now - lastCaptureMs > policy.minCooldownMs) {
        lastCaptureMs = now
        // AF พร้อมแล้ว (settled ระหว่างรอ 10%→40%)
        captureStrategy.capture(...)
    }
} else {
    focusController.reset()  // ไม่มีหน้า → กลับ default
}
```

---

## Limitations & Mitigations

| ข้อจำกัด | การแก้ไข |
|----------|---------|
| Fixed-focus device (AF ไม่มี) | `isAfSupported()` check → skip AF, ยังทำ AE ได้ |
| AF hunting (กล้องโฟกัสไม่หยุด) | throttle `lockOnFace()` ทุก 100ms, ไม่ใช่ทุก frame |
| `setAutoCancelDuration(3s)` reset เร็วไป | ปรับได้ตาม use case (3–5 วินาที) |
| นักวิ่งหลายคน — เลือกหน้าไหน? | `maxByOrNull { it.confidence }` = หน้าที่ชัดที่สุด |
| AF settle ไม่ทัน (นักวิ่งเร็วมาก) | ลด `AF_START_THRESHOLD` ลงเป็น 5–8% |
