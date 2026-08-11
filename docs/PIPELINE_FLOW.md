# AutoBots Pipeline Flow — จาก Video ไปสู่ Photo ที่เก็บ

> เอกสารนี้อธิบายขั้นตอนการทำงานของระบบ AutoBots Sports Camera ตั้งแต่รับวิดีโอ (Live Capture หรือ Import) จนได้ภาพที่เก็บลงใน Gallery ของเครื่อง

---

## 1. ภาพรวม Architecture

### 1.1 Video Pipeline (ปัจจุบัน)

มี **สองทางเข้า** ที่รวมก่อน Worker 2 เหมือนกัน:

| ทางเข้า | Worker 1 | ไฟล์ชั่วคราว |
|---------|----------|----------------|
| **Live Capture** — กล้อง CameraX | `VideoChunkRecorder` บันทึก MP4 | `cache/autobots/{sessionId}/video/` |
| **Import Video** — เลือกไฟล์จากเครื่อง (`OpenDocument`) | `ImportedVideoSplitter` remux แบ่ง chunk | โฟลเดอร์เดียวกัน |

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         AutoBots Video Pipeline                           │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌─────────────────┐              ┌──────────────────────┐              │
│   │ Live Capture    │              │ Import Video           │              │
│   │ Camera (CameraX)│              │ Browse ไฟล์บนเครื่อง    │              │
│   └────────┬────────┘              │ (OpenDocument picker)  │              │
│            │                       └──────────┬─────────────┘              │
│            ▼                                  ▼                            │
│   ┌─────────────────┐              ┌──────────────────────┐              │
│   │ VideoChunkRecorder│            │ ImportedVideoSplitter │              │
│   │ บันทึก MP4 chunks │            │ remux → MP4 chunks    │              │
│   └────────┬────────┘              └──────────┬─────────────┘              │
│            │                                  │                            │
│            └──────────────┬───────────────────┘                            │
│                           ▼                                                │
│                  ┌─────────────────┐                                       │
│                  │   videoQueue    │  Channel capacity = 8                 │
│                  └────────┬────────┘                                       │
│                           ▼                                                │
│                  ┌─────────────────────────┐                               │
│                  │ Worker 2                │                               │
│                  │ VideoFrameProcessor     │                               │
│                  │ sample 120ms → ML Kit   │                               │
│                  │ → CPU sharpness → dedup │                               │
│                  └────────┬────────────────┘                               │
│                           ▼                                                │
│                  ┌─────────────────┐       ┌──────────────────┐            │
│                  │  Write Queue    │──────▶│ Gallery (JPEG)   │            │
│                  │  MediaStore     │       │ DCIM/AutoBots/…  │            │
│                  └─────────────────┘       └──────────────────┘            │
│                           │                                                │
│                           ▼ (เมื่อ drain เสร็จ)                              │
│                  session_log.txt → Download/AutoBots/{subfolder}/          │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│  Worker 1A: VideoChunkRecorder  │  Worker 1B: ImportedVideoSplitter      │
│  Worker 2: VideoFrameProcessor — HW decode → ML Kit → CPU sharpness       │
└──────────────────────────────────────────────────────────────────────────┘
```

> หลังแยก chunk แล้ว **import กับ live ใช้ pipeline เดียวกัน** (`CapturePipelineCoordinator` → `videoQueue` → Worker 2 → Gallery)

### 1.2 Pipeline Components (ปัจจุบัน)

| Stage | Technology | หมายเหตุ |
|-------|-----------|----------|
| **Decode** | MediaCodec (Hardware) | VPU — `VideoFrameSampler` |
| **Detect** | ML Kit Face / Pose (bundled TFLite) | `OfflineFaceDetector` / `OfflinePoseDetector` |
| **Sharpness** | Laplacian variance (CPU) | `FaceSharpnessScorer.scoreNormalized()` |
| **Save** | MediaStore Images → `DCIM/AutoBots/{subfolder}/` | `LocalDeliveryWriter.publish()` |
| **Session log** | `session_log.txt` → `Download/AutoBots/{subfolder}/` (API 29+) | `LocalDeliveryWriter.publishText()` + cache mirror |

> ML Kit จัดการ hardware delegate ภายใน SDK เอง — **ไม่ได้** เปิด NNAPI โดยตรงจากแอป

---

## 2. Stream Resolution — ทำไม 1080p กับ 4K ถึงต่างกัน?

### 2.1 Pixel Binning vs Full Readout

| Mode | วิธีอ่าน Sensor | Noise | ISP Noise Reduction | ผลต่อ Edge |
|------|----------------|-------|--------------------|------------|
| **1080p** | Pixel Binning (รวม 4 pixel เป็น 1) | น้อย | Mild (น้อย) | Edge คม |
| **4K** | Full Readout (อ่านทุก pixel) | มาก | Aggressive (มาก) | Edge เหม่อ |

**เหตุผล:**
- **1080p** — Sensor รวม 4 pixel เข้าด้วยกัน → signal-to-noise ratio สูง → noise น้อย → ISP ใช้ Noise Reduction (NR) น้อย → edge ของใบหน้าคม
- **4K** — Sensor อ่านทุก pixel → noise มาก → ISP ต้อง apply Noise Reduction แบบหนัก → edge ของใบหน้าถูก smooth → เหม่อ

### 2.2 ผลต่อ Sharpness Scorer

```
[ 1080p: edge ของ face ]          [ 4K: edge ของ face (หลัง NR) ]
   |  /|\  |                         |  /~~~\  |
   | / | \ |                         | /     \ |
   |/  |  \|                          |/       \|
   ↑ Gradient ชัด                   ↑ Gradient blur (smoothed)
   → Laplacian value สูง              → Laplacian value ต่ำ
   → Sharpness score ~85              → Sharpness score ~72
   → ผ่าน (threshold 80)              → ถูกตัด (threshold 80)
```

### 2.3 การแก้ไข — Resolution-Aware Threshold

```kotlin
// ก่อนแก้ไข: 1080p และ 4K ใช้ threshold เดียวกัน (80.0)
// หลังแก้ไข: 4K ใช้ threshold ต่ำลง (65.0)

StreamResolution.Fhd -> ProcessProfile(minSharpness = 80.0)
StreamResolution.Uhd -> ProcessProfile(minSharpness = 65.0)  // compensating ISP NR
```

### 2.4 Frame Sample Interval (ค่าปัจจุบันในโค้ด)

ค่าอยู่ที่ `StreamResolution.kt` — **ทุก resolution ใช้ interval เดียวกัน**:

| Profile | Interval | ~samples/sec |
|---------|----------|--------------|
| **FHD (1080p)** | **120 ms** | ~8.3 fps |
| **UHD (4K)** | **120 ms** | ~8.3 fps |

```kotlin
// shared/.../StreamResolution.kt
const val FRAME_SAMPLE_INTERVAL_MS = 120L

val frameSampleIntervalMs: Long
    get() = FRAME_SAMPLE_INTERVAL_MS
```

**ความต่าง FHD vs UHD** อยู่ที่ **min sharpness** (80 vs 65) ไม่ใช่ interval — UHD ยัง detect auto จากไฟล์ import (`long edge ≥ 2160`)

---

## 3. Worker 2 — ขั้นตอนประมวลผลต่อ Chunk

### 3.1 Hardware Decoder (MediaCodec)

```kotlin
// VideoFrameSampler — hardware decoder ก่อน, fallback software
val hwDecoder = findHardwareDecoder(mime)
val decoder = if (hwDecoder != null) {
    MediaCodec.createByCodecName(hwDecoder)
} else {
    MediaCodec.createDecoderByType(mime)
}
```

Decoder อ่าน frame แล้ว **ข้ามไป sample ทุก 120 ms** — ไม่ decode ทุก frame ในวิดีโอ

### 3.2 ML Kit Detection

```kotlin
// Face — OfflineFaceDetector.kt
FaceDetectorOptions.Builder()
    .setPerformanceMode(PERFORMANCE_MODE_FAST)  // offline pipeline
    .setMinFaceSize(0.05f)
    .enableTracking()
    .build()

// Pose — OfflinePoseDetector.kt
PoseDetectorOptions.Builder()
    .setDetectorMode(SINGLE_IMAGE_MODE)
    .build()
```

| Target | เงื่อนไขผ่าน | Detect bitmap |
|--------|-------------|---------------|
| **Face** | หน้าสูง ≥ 5% ความสูงเฟรม | scale กว้าง 640px |
| **Pose** | ลำตัวสูง ≥ 25% (ไหล่+สะโพกครบ 4 จุด) | scale กว้าง 640px |

### 3.3 Sharpness (CPU Laplacian)

```kotlin
FaceSharpnessScorer.scoreNormalized(bitmap, roi)
// threshold: FHD ≥ 80.0, UHD ≥ 65.0
```

### 3.4 Dedup

`DEDUP_WINDOW_US = 1_000_000` (1 วินาที) — เก็บเฟรมคมที่สุดต่อ window

---

## 4. Flow: Live Capture (ถ่ายภาพจากกล้อง)

### Step 1: เริ่ม Capture

```
MainActivity.kt
  └─ operatorViewModel.startCapture()
       │
       ├── ตรวจสอบ camera permission
       ├── สร้าง CapturePipelineCoordinator
       │    ├── sessionDir = cacheDir/autobots/{sessionId}
       │    ├── facesDir   = sessionDir/faces (เก็บ JPEG)
       │    ├── videoQueue = Channel<ChunkWorkItem>(capacity=8)
       │    └── Worker 2 เริ่มรอรับ chunk
       │
       ├── สร้าง PreviewCameraController (CameraX)
       │    └──绑定 preview + video capture
       │
       └── สร้าง VideoChunkRecorder (Worker 1)
            └── เริ่มบันทึกวิดีโอ
```

### Step 2: บันทึกวิดีโอเป็น Chunk

```
VideoChunkRecorder.start()
  │
  ├── บันทึกวิดีโอทีละ chunk (ขนาดจำกัดตาม bytes)
  ├── เมื่อ chunk ครบ target bytes → หยุด chunk เก่า → เริ่ม chunk ใหม่
  │
  ├── FHD และ UHD ใช้ chunkTargetBytes เดียวกัน = 50 MB (StreamResolution.CHUNK_TARGET_BYTES)
  │
  ├── FHD (~10–15 Mbps): 50 MB เต็มช้ากว่า  → chunk ทุก ~**30–40 วินาที**
  └── UHD (~25–50 Mbps): 50 MB เต็มเร็วกว่า → chunk ทุก ~**12–20 วินาที**
  │
  └─ เมื่อ chunk เสร็จ (Finalize event):
       └─ ส่ง ChunkCaptureMeta → เข้า videoQueue → Worker 2 เริ่มประมวลผล
```

> **ทำไม UHD ไม่ได้ยาวกว่า?** — เป้าคือ **ขนาดไฟล์** (50 MB) ไม่ใช่เวลา 4K encode ข้อมูลหนักกว่าต่อวินาที → ครบ 50 MB **เร็วกว่า** → สลับ chunk บ่อยกว่า FHD (เอกสารเก่าที่เขียน ~60s สำหรับ UHD **ผิด**)

### Step 3: Worker 2 ประมวลผล Chunk

```
VideoFrameProcessor.process(chunkFile, chunkIndex, resolution, extractionTarget, sampleIntervalMs)
  │
  ├── [Stage 1: Frame Sampling — Hardware Decoder]
  │  └─ MediaCodec อ่าน frame ตาม interval จาก StreamResolution:
  │       │
  │       ├── ทุก resolution: sample ทุก **120 ms** (~8.3 samples/sec)
  │       ├── YUV 420 → NV21 → JPEG 92% → Bitmap ARGB_8888
  │       └── ถ้า rotation ≠ 0 → rotate bitmap
  │
  ├── [Stage 2: Face / Pose Detection — ML Kit]
  │  └─ สำหรับแต่ละ frame ที่ sample ได้:
  │       │
  │       ├── Face: scale 640px → ML Kit FAST + enableTracking()
  │       │   → เลือก face ใหญ่สุด → subjectRatio ≥ 5%
  │       │   → FaceSharpnessScorer (CPU) ≥ 80 (FHD) / 65 (UHD)
  │       │
  │       └── Pose: scale 640px → ML Kit Pose (ไหล่+สะโพก)
  │           → torso ≥ 25% → sharpness เหมือน Face
  │
  ├── [Stage 3: Dedup + Best-of-Window]
  │  └─ DEDUP_WINDOW_US = 1,000,000 µs (1 วินาที):
  │       ├── ถ้า frame ใหม่ห่างจาก window ก่อนหน้า ≥ 1s:
  │       │   ├── บันทึก frame ที่ดีที่สุดของ window ก่อนหน้า → JPEG 95%
  │       │   └── เริ่ม window ใหม่
  │       └── ถ้า frame ใหม่ใกล้กว่า:
  │           └── ถ้า sharpness ดีกว่า → replace best frame
  │
  └── [Stage 4: Save Final Frames + Chunk stats]
       ├─ saveFrame() → "${prefix}_c${chunkIndex}_${ptsUs}.jpg" (prefix = face / pose)
       └─ อัปเดต ChunkRecord:
            ├── framesSampled, facesKept, processDurationMs
            ├── detectionSummary: "Found X … from Y frames (Z%)"
            └── avgFrameProcessMs = processDurationMs ÷ framesSampled (pipeline ทั้ง frame ไม่ใช่แค่ detect)
```

### Step 4: ส่งภาพลง Gallery + Session log

```
WriteQueue.enqueue(jpegFile)
  │
  ├── Channel.trySend(file) → ถ้า queue full → drop (log warning)
  │
  └─ [Dispatchers.IO]
       └─ LocalDeliveryWriter.publish(file)   ← JPEG
            ├── MediaStore.Images → DCIM/AutoBots/{subfolder}/
            ├── รูป: face_c000_123456.jpg / pose_c000_123456.jpg
            ├── IS_PENDING=0 → visible in gallery
            └── ลบ temp file หลัง publish

เมื่อ pipeline drain เสร็จ (maybeNotifyDrainComplete):
  └─ writeSessionLog(session)
       ├── mirror: cache/autobots/logs/{subfolder}/session_log.txt
       ├── mirror: cache/autobots/{sessionId}/session_log.txt
       └─ LocalDeliveryWriter.publishText("session_log.txt")
            ├── ลอง legacy File → DCIM/AutoBots/{subfolder}/ (มักไม่สำเร็จบน API 29+)
            └── fallback: MediaStore.Downloads → Download/AutoBots/{subfolder}/session_log.txt
```

**โฟลเดอร์ session** (`SessionAlbumNaming`):

| แหล่ง | ชื่อโฟลเดอร์ |
|--------|----------------|
| Import | `ext_DDMMYYYY_HHMM` (เวลาเริ่ม session) |
| Live | `{yyyyMMdd_HHmmss}` |

> รูปกับ log ใช้ **ชื่อโฟลเดอร์เดียวกัน** แต่บน Android 10+ มักอยู่คนละ root: JPEG ใน **DCIM** · log ใน **Download**  
> ดึงกลับ Mac: `./sync_gallery.sh` รวมทั้งสอง path เข้า `~/Downloads/AutoBots-export/{subfolder}/`

### Step 5: สรุป Live Capture Flow

```
Live Capture (5 นาที):
  │
  ├── Worker 1: บันทึก ~8–10 chunks (FHD) หรือ ~15–25 chunks (UHD) — ทุก chunk 50 MB
  │
  ├── Worker 2: ประมวลผล chunks (ตาม queue)
  │    ├── HW decode → ML Kit detect → CPU sharpness → dedup
  │    └── เก็บ ~30–50 photos (หลัง filter)
  │
  └── Gallery: JPEG ใน DCIM/AutoBots/{subfolder}/ · log ใน Download/AutoBots/{subfolder}/
```

> แอปล็อค **portrait only** (`AndroidManifest` `screenOrientation=portrait`) — ไม่หมุนจอระหว่าง capture

---

## 5. Flow: Import Video (นำเข้าวิดีโอจากไฟล์)

### Step 1: ผู้ใช้เลือกไฟล์วิดีโอ

```
MainActivity.kt
  └─ rememberLauncherForActivityResult(OpenDocument())
       └─ เมื่อเลือกไฟล์ → operatorViewModel.importVideo(uri)
```

### Step 2: สร้าง Pipeline (ไม่ใช้กล้อง)

```
OperatorViewModel.importVideo(uri)
  │
  ├── ตรวจสอบว่า pipeline ว่าง (canImportVideo = true)
  ├── ปิด pipeline เก่า (ถ้ามี)
  ├── สร้าง CapturePipelineCoordinator ใหม่
  │    ├── sessionDir = cacheDir/autobots/{sessionId}
  │    ├── facesDir   = sessionDir/faces
  │    ├── videoQueue = Channel<ChunkWorkItem>(capacity=8)
  │    └── Worker 2 เริ่มรอรับ chunk
  │
  ├── ตั้งค่า extraction target (Face/Pose) ใน UI
  ├── **ไม่ใช้** resolution จาก UI — probe จากไฟล์แทน:
  │    ImportedVideoSplitter.probe() → width × height × rotation
  │    → StreamResolution.fromVideoDimensions() → sharpness profile FHD/UHD
  ├── ตรวจสอบพื้นที่เก็บข้อมูล
  ├── UI state: isImporting = true, importPercent = 0
  │
  └─ เริ่ม coroutine: coordinator.importVideo(uri, displayName)
```

### Step 3: Split Video Into Chunks (ImportedVideoSplitter)

```
ImportedVideoSplitter.split(source, targetSegmentBytes)
  [Dispatchers.IO — blocking I/O ไม่บล็อก main thread]
  │
  ├── MediaExtractor เปิดไฟล์ → หา video track
  ├── MediaMetadataRetriever อ่าน rotation metadata (portrait = 90°)
  │
  └─ Loop อ่าน sample data ทีละ packet:
       │
       ├── ถ้า muxer ยังไม่มี → openSegment() สร้างไฟล์ใหม่
       │   └─ MediaMuxer("import_000.mp4", MPEG_4)
       │
       ├── ถ้าเป็น Keyframe + chunk ครบ target bytes → closeSegment()
       │   ├── MediaMuxer.stop() + release()
       │   ├── สร้าง ChunkCaptureMeta(index, file, timestamps)
       │   └─ เรียก onChunkReady(meta) → ส่งเข้า videoQueue
       │
       ├── เขียน sample data เข้า muxer (rebase PTS to segment-relative)
       ├── อัพเดท importPercent (0–99%) → UI แสดง progress
       └─ เมื่อจบไฟล์ → closeSegment() chunk สุดท้าย
```

> **Key Point:** ใช้ **remux** (copy codec stream) ไม่ใช่ decode+reencode → เร็ว + lossless
> แต่ละ chunk ต้อง start ที่ **Keyframe** เท่านั้น → decoder downstream เปิดแล้วได้ภาพถูกต้อง

### Step 4: Chunk เข้า Queue → Worker 2 ประมวลผล

```
onChunkRecorded(meta)
  │
  ├── chunksRecorded++
  ├── สร้าง ChunkRecord (status = Pending, sampleIntervalMs จาก resolution)
  ├── ส่งเข้า videoQueue: trySend(ChunkWorkItem(index, file))
  │
  └─ [Worker 2 — Dispatchers.Default]
       └─ สำหรับแต่ละ chunk:
            ├── frameProcessor.process(...) → VideoProcessResult(framesSampled, kept, durationMs)
            └─ imageDelivery.enqueue(jpegFile) → WriteQueue → DCIM
```

### Step 5: สรุป Import Video Flow

```
Import Video 5 นาที (FHD 1080p):
  │
  ├── ImportedVideoSplitter.split(): remux → ~8–10 chunks (50 MB/chunk)
  │
  ├── Worker 2: ประมวลผล chunks
  │    └── HW decode → ML Kit → CPU sharpness → dedup
  │
  └── Gallery: JPEG (DCIM) + session_log.txt (Download) · โฟลเดอร์ ext_DDMMYYYY_HHMM
```

### Step 6: Session History + session_log.txt

**UI** (หน้า swipe ที่ 3 — `ChunkHistoryPage`):

```
Session card
  ├── ชื่อ session · resolution · target · status
  ├── headline: "N chunks · X faces · total time"
  ├── detectionSummary: Found X from Y frames (Z%) · avg Nms/frame · sample 120ms
  └── Expand chunks:
       ├── Import: แสดงเฉพาะ chunk ที่ facesKept > 0
       ├── Live: แสดงทุก chunk
       └── ต่อ chunk:
            ├── Duration: 45.123 s (วินาทีทศนิยม 3 ตำแหน่ง)
            ├── Sample 120ms · N frames
            └── Found X from N frames (%) · avg Nms/frame · extract 12.456 s
```

**`session_log.txt`** (`PipelineSessionRecord.toLogText()`):

```
Chunk #4
  Video: chunk_004.mp4
  Duration: 45.123 s · 48 MB
  Sample interval: 120 ms
  Frames sampled: 375
  Found 3 faces from 375 frames (0%)
  avg 33ms/frame · extract 12.456 s
    - face_c004_2880000.jpg (1.8 MB)
```

| ตัวเลข | ความหมาย |
|--------|----------|
| **Duration** | ความยาววิดีโอ chunk (`recordDurationMs`) — รูปแบบ `SS.mmm s` |
| **Frames sampled** | จำนวน frame ที่ decode จริง (ทุก 120 ms) |
| **Found X from Y** | รูปที่เก็บ (หลัง dedup) จาก frame ที่ sample |
| **(Z%)** | `facesKept ÷ framesSampled × 100` (ปัดเป็น int — ค่าน้อยอาจแสดง 0%) |
| **avg Nms/frame** | `processDurationMs ÷ framesSampled` — เวลา pipeline ต่อ frame (decode+detect+sharpness+dedup) **ไม่ใช่แค่ face detect** |
| **extract** | wall-clock ทั้ง chunk ใน Worker 2 |

---

## 6. สรุปตัวเลขสำคัญ

### Frame Sample Interval (source of truth)

| | **1080p (FHD)** | **4K (UHD)** |
|--|-----------------|--------------|
| **Interval** | **120 ms** | **120 ms** |
| **~samples/sec** | ~8.3 | ~8.3 |
| **Min sharpness** | 80.0 | 65.0 |
| **Detect width** | 640 px | 640 px |
| **Chunk target** | 50 MB | 50 MB |

### โดยประมาณ (วิดีโอ 5 นาที)

| Parameter | FHD | UHD |
|-----------|-----|-----|
| Chunk เป้า | 50 MB | 50 MB |
| ระยะเวลาต่อ chunk (โดยประมาณ) | ~30–40 s | ~12–20 s |
| จำนวน chunk ใน 5 นาที | ~8–10 | ~**15–25** |
| Frames sampled (5 min) | 300s ÷ 0.12s ≈ **2500** | 300s ÷ 0.12s ≈ **2500** |
| Photos kept (หลัง filter) | ~30–50 | ~30–50 |

> Bitrate จริงขึ้นกับเครื่อง/codec — ตัวเลข chunk เป็นค่าประมาณ  
> Frames sampled เท่ากันเพราะอิง **ความยาววิดีโอรวม** (5 นาที) ไม่ใช่จำนวน chunk

### Gallery path (บนเครื่อง)

**เวอร์ชันแอปฝังอยู่ในชื่อโฟลเดอร์** ไม่ใช่ชั้นไดเรกทอรีเพิ่ม — บอกได้ว่ารูปชุดไหนมาจาก build ไหน
โดยที่ไฟล์เบราว์เซอร์บนมือถือไม่ต้องกดลึกขึ้นอีกชั้น (`versionTag` มาจาก `appVersionName` แปลง `.` เป็น `_`)

```
DCIM/AutoBots/
├── ext_v0_1_3_07082026_1415/   ← import (JPEG)
│   ├── face_c000_….jpg
│   └── …
└── v0_1_3_20260806_160512/     ← live (JPEG)
    └── face_c001_….jpg

Download/AutoBots/              ← session_log + perf_report (Android 10+)
├── ext_v0_1_3_07082026_1415/
│   ├── session_log.txt
│   └── perf_report.json
└── v0_1_3_20260806_160512/
    └── …
```

> โฟลเดอร์จาก build เก่าที่ยังไม่มี versionTag (`ext_07082026_1415`) จะอยู่ปนกันในระดับเดียวกัน — `sync_gallery.sh` ดึงได้หมด

**ดึงกลับ Mac:** `./sync_gallery.sh` รวม DCIM + Download (+ debug cache) → `~/Downloads/AutoBots-export/{subfolder}/`

---

## 7. สรุป Flow แบบ Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                       User Action                                 │
│  ┌──────────────┐       ┌──────────────────┐                     │
│  │ Live Capture │       │ Import Video     │                     │
│  └──────┬───────┘       └────────┬─────────┘                     │
├─────────┼─────────────────────────┼───────────────────────────────┤
│         ▼                         ▼                               │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              CapturePipelineCoordinator                   │    │
│  │  cache/autobots/{sessionId}/faces  ·  videoQueue (8)    │    │
│  └──────────────────────────────────────────────────────────┘    │
│         ▼                         ▼                               │
│  ┌──────────────────┐    ┌──────────────────────┐                │
│  │ VideoChunkRecorder│    │ ImportedVideoSplitter │               │
│  │ 50 MB/chunk      │    │ remux, keyframe-aligned│              │
│  └────────┬─────────┘    └──────────┬───────────┘               │
│           └────────────┬────────────┘                            │
│                        ▼                                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │         Worker 2: VideoFrameProcessor                     │   │
│  │  sample 120ms → ML Kit → CPU sharpness → dedup 1s        │   │
│  │  → JPEG temp → WriteQueue → DCIM/AutoBots/{subfolder}/   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                        │ drain complete                          │
│                        ▼                                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  session_log.txt → Download/AutoBots/{subfolder}/        │   │
│  │  (+ cache mirror สำหรับ debug sync)                        │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 8. Changelog ล่าสุด (สอดคล้องโค้ดปัจจุบัน)

| หัวข้อ | รายละเอียด |
|--------|------------|
| **Sample interval** | **120 ms** ทุก resolution (`StreamResolution.FRAME_SAMPLE_INTERVAL_MS`) |
| **Chunk size** | **50 MB** ทั้ง FHD และ UHD |
| **Import resolution** | Auto-detect (`ImportedVideoSplitter.probe` + `fromVideoDimensions`) |
| **Gallery JPEG** | `DCIM/AutoBots/{subfolder}/` ผ่าน `MediaStore.Images` |
| **Session log** | `Download/AutoBots/{subfolder}/session_log.txt` (API 29+); mirror ใน app cache |
| **โฟลเดอร์** | Import: `ext_DDMMYYYY_HHMM` · Live: `yyyyMMdd_HHmmss` |
| **Session history** | `ChunkHistoryPage` — chunk stats, import แสดงเฉพาะ chunk ที่มี faces |
| **Chunk metrics** | `framesSampled`, `Found X from Y frames`, `avg ms/frame`, duration `SS.mmm s` |
| **Sharpness** | CPU `FaceSharpnessScorer` — FHD ≥ 80, UHD ≥ 65 |
| **Detect** | ML Kit FAST + `enableTracking()` (ไม่ใช่ NNAPI โดยตรง) |
| **Orientation** | Portrait only (`MainActivity` `screenOrientation=portrait`) |
| **Mac sync** | `./sync_gallery.sh` — รวม DCIM + Download |

---

## 9. สรุปการแก้ไข MIN_SHARPNESS_UHD

### ปัญหา
- 4K ถูกตัดเพราะ `sharpness score ~72 < threshold 80`
- สาเหตุ: Phone camera ISP ใช้ aggressive Noise Reduction → edge เหม่อ
- ผล: 4K ได้รูปน้อยกว่า 1080p ทั้งที่ความละเอียดสูงกว่า

### การแก้ไข
```kotlin
// VideoFrameProcessor.kt
const val MIN_SHARPNESS = 80.0        // 1080p (เดิม)
const val MIN_SHARPNESS_UHD = 65.0    // 4K (ใหม่)

StreamResolution.Fhd -> ProcessProfile(minSharpness = 80.0)
StreamResolution.Uhd -> ProcessProfile(minSharpness = 65.0)
```

### ผลลัพธ์
- 4K ที่เคยถูกตัด (score 72) ตอนนี้ผ่าน (threshold 65)
- 1080p ไม่ได้รับผลกระทบ (threshold 80 คงเดิม)
- จำนวน photo ที่ kept จาก 4K เพิ่มขึ้น → compensating coverage เท่ากับ 1080p

---

## Related

- Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- UI layout: [SCREEN.md](./SCREEN.md)
- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Platform APIs: [PLATFORM_APIS.md](./PLATFORM_APIS.md)
- Doc index: [DOCS.md](./DOCS.md)