# AutoBots Android Camera

Edge-AI sports camera on a tripod-mounted Android phone.  
**Current build (v0.1.6):** record or import video → offline detect + score → save JPEGs to gallery → upload.

## Overall Architecture

```
CameraX
│
├── Preview
│     └── แสดงภาพให้ผู้ใช้งาน (ติดทันทีที่เข้าหน้า Live)
│
└── VideoCapture
      └── บันทึก MP4 chunk (หมุนไฟล์ใหม่ทุก 50 MB)

↓  (หรือเข้าทาง Import ไฟล์ / Network URL — remux split ที่ 50 MB เท่ากัน)

Video Queue (ช่องทางละ 8 chunk · เต็มเมื่อไหร่ตัวอัดหยุดรอ)

↓

Offline Extract  (อ่านทีละ chunk ขณะที่กล้องยังอัดอยู่)
      ├── Frame Sampling         (decode ทุก 120 ms)
      ├── Detection              (Face / Pose / Person — เลือกได้ก่อน Start)
      ├── Capture Zone           (ลากกรอบเองบนภาพจริง)
      ├── Sharpness Evaluation   (Laplacian variance)
      ├── Frame Quality Score    (คม · ขนาด · กลางเฟรม · confidence · ระยะห่างขอบ)
      ├── Subject Tracking       (IOU กับตำแหน่งที่ทำนายไว้ + ระยะห่างจุดกึ่งกลาง)
      └── Dedup ต่อคน            (เก็บเฟรมดีที่สุด 1 วินาที ของ "คนคนนั้น" ไม่ใช่ของนาฬิกา)

↓

Write Queue

↓

Local Storage (MediaStore)
      ├── JPEG q95 → DCIM/AutoBots/{session}/
      └── session_log.txt · photos.csv · tracks.csv · perf_report.json → Download/AutoBots/{session}/

↓

Upload Queue (Room · 6 สถานะ)
      Pending → Uploading → Uploaded → Success
                          ↘ Failed (retry ได้) ↘ Abandoned (retry ไปก็ไม่ช่วย)

↓

WorkManager (unique work หนึ่งตัวระบายทั้งคิว · foreground service · exponential backoff)

↓

Backend GraphQL
      │
      └── Generate Presigned URL

↓

Object Storage (GCS)

↓

Backend Save Metadata

※ ไม่ลบไฟล์ในเครื่องไม่ว่ากรณีใด — upload คือการ *คัดลอก* ขึ้นคลาวด์ ไม่ใช่การ *ย้าย*
```

**ทำไมไม่ใช้ ImageAnalysis + ImageCapture แบบ real-time** — เส้นทางนั้นมีจุดตายที่ *จังหวะกดชัตเตอร์*
คาดการณ์พลาด 150 ms หรือ shutter lag ไม่นิ่ง = นักวิ่งคนนั้นหายไปเลย กู้ไม่ได้
เส้นทางนี้บันทึกทุกเฟรมไว้ก่อน แล้วค่อยเลือกตอนที่เห็นครบทั้ง chunk แล้ว — ไม่มีจังหวะให้พลาด
ราคาที่จ่ายคือความละเอียดปลายทางถูกล็อกที่เฟรมวิดีโอ (4K = 8.3 MP) ไม่ใช่ความละเอียดสูงสุดของเซนเซอร์

**เทียบกับ Flow Design v1 ทีละข้อ** — video pipeline 13 ขั้น · upload · backend · ตัวเลข perf:
[docs/DESIGN_FLOW.md](docs/DESIGN_FLOW.md)

## Quick start

```bash
./gradlew :androidApp:installDebug
```

## Helper scripts

| Script | Purpose |
|--------|---------|
| `./install_with_log.sh` | Install debug APK + capture logcat to `crash.log` |
| `./sync_gallery.sh` | Pull JPEGs + `session_log.txt` from device → Mac |
| `./scripts/check_docs_drift.sh` | Guard against stale doc/UI strings (see [CONVENTIONS.md](docs/CONVENTIONS.md)) |

## Documentation

Start at **[docs/DOCS.md](docs/DOCS.md)** — operator guide, pipeline reference, build instructions.

| Doc | Purpose |
|-----|---------|
| [DESIGN_FLOW.md](docs/DESIGN_FLOW.md) | **Flow Design v1 เทียบทีละข้อ** — video pipeline · upload · backend · perf |
| [OPERATOR_FLOW.md](docs/OPERATOR_FLOW.md) | How to use the app in the field |
| [PIPELINE_FLOW.md](docs/PIPELINE_FLOW.md) | Technical pipeline (workers, thresholds, storage) |
| [BUILD.md](docs/BUILD.md) | Build, install, logs, wireless adb |

## Stack

Kotlin Multiplatform (`shared/`) + Android app (`androidApp/`) · CameraX · ML Kit · LiteRT/TFLite (NPU) · Room · WorkManager · Jetpack Compose
