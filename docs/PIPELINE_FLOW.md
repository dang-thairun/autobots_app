# AutoBots Pipeline Flow — จาก Video ไปสู่ Photo ที่เก็บ

> เอกสารนี้อธิบายขั้นตอนการทำงานของระบบ AutoBots Sports Camera ตั้งแต่รับวิดีโอ (Live Capture หรือ Import) จนได้ภาพที่เก็บลงใน Gallery ของเครื่อง

---

## 1. ภาพรวม Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         AutoBots Pipeline                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────┐       ┌──────────────┐       ┌──────────────────┐    │
│  │  Camera  │──────▶│  Video Chunk │──────▶│  Worker 2        │    │
│  │  (CameraX)│      │  Recorder    │      │  (Frame Processor)│    │
│  │          │      │  (MP4 chunks) │      │                  │    │
│  └──────────┘      └──────────────┘      └────────┬─────────┘    │
│                                                     │              │
│  ┌──────────┐       ┌──────────────┐               │              │
│  │  Gallery │◀──────│  Write Queue │◀──────────────┘              │
│  │  (JPEG)  │       │  (MediaStore)│                              │
│  └──────────┘       └──────────────┘                              │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│  Worker 1: VideoChunkRecorder  │  Worker 2: VideoFrameProcessor     │
│  (บันทึกวิดีโอเป็น chunk)       │  (สกัด frame → detect → บันทึก)  │
└─────────────────────────────────────────────────────────────────────┘
```

**มี 2 Workers ทำงานคู่กัน:**
- **Worker 1** — บันทึกวิดีโอเป็น chunk (ไฟล์ MP4 ขนาดจำกัด)
- **Worker 2** — สกัด frame จาก chunk → ตรวจจับใบหน้า/ท่าทาง → บันทึกภาพที่ผ่านเกณฑ์

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

### 2.4 การชดเชย — Sampling Rate

```kotlin
// 4K ต้อง sample บ่อยขึ้น (120ms) เพื่อ compensating sharpness ที่ต่ำลง
Fhd  → frameSampleIntervalMs = 300L   // sample ทุก 300ms
Uhd  → frameSampleIntervalMs = 120L   // sample ทุก 120ms (บ่อย 2.5 เท่า)
```

---

## 3. Flow: Live Capture (ถ่ายภาพจากกล้อง)

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
  ├── บันทึกวิดีโอทีละ chunk (ขนาดจำกัด)
  ├── เมื่อ chunk ครบ target bytes → หยุด chunk เก่า → เริ่ม chunk ใหม่
  │
  ├── FHD: chunkTargetBytes = 20 MB  → chunk ทุก ~12 วินาที
  └── UHD: chunkTargetBytes = 50 MB  → chunk ทุก ~30 วินาที
  │
  └─ เมื่อ chunk เสร็จ (Finalize event):
       └─ ส่ง ChunkCaptureMeta → เข้า videoQueue → Worker 2 เริ่มประมวลผล
```

### Step 3: Worker 2 ประมวลผล Chunk

```
VideoFrameProcessor.process(chunkFile, chunkIndex, resolution, extractionTarget)
  │
  ├── [Stage 1: Frame Sampling]
  │  └─ MediaCodec decoder อ่าน frame ทีละ sample ตาม interval:
  │       │
  │       ├── FHD: sample ทุก 300ms (~2 frames/sec)
  │       ├── UHD: sample ทุก 120ms (~5 frames/sec)
  │       ├── YUV 420 → NV21 → JPEG 92% → Bitmap ARGB_8888
  │       └── ถ้าไฟล์มี rotation ≠ 0 → rotate bitmap ให้ตั้งตรง
  │
  ├── [Stage 2: Face/Pose Detection]
  │  └─ สำหรับแต่ละ frame ที่ sample ได้:
  │       │
  │       ├── ถ้า target = Face:
  │       │   ├── scale bitmap ลง 640px (detectBitmapWidth)
  │       │   ├── ML Kit Face Detection → list of bounding box (Rect)
  │       │   ├── map bounding box กลับไป full-size bitmap
  │       │   ├── เลือก face ที่ใหญ่สุด (largest = ใกล้สุด)
  │       │   ├── ตรวจสอบ subjectRatio ≥ 5% ของความสูง frame
  │       │   ├── FaceSharpnessScorer.scoreNormalized(bitmap, face)
  │       │   └── ถ้า sharpness ≥ threshold → FrameCandidate
  │       │
  │       └── ถ้า target = Pose:
  │           ├── ML Kit Pose Detection
  │           ├── ตรวจสอบ torso bounds ≥ 25% ของความสูง frame
  │           └── sharpness score → FrameCandidate
  │
  ├── [Stage 3: Dedup + Best-of-Window]
  │  └─ DEDUP_WINDOW_US = 1,000,000 µs (1 วินาที):
  │       ├── ถ้า frame ใหม่ห่างจาก window ก่อนหน้า ≥ 1s:
  │       │   ├── บันทึก frame ที่ดีที่สุดของ window ก่อนหน้า → JPEG 95%
  │       │   └── เริ่ม window ใหม่
  │       └── ถ้า frame ใหม่ใกล้กว่า:
  │           └── ถ้า sharpness ดีกว่า → replace best frame
  │
  └── [Stage 4: Save Final Frames]
       └─ สำหรับทุก FrameCandidate ที่ถูก kept:
            └─ saveFrame() → "${prefix}_c${chunkIndex}_${ptsUs}.jpg"
                 (prefix = "face" หรือ "pose")
```

### Step 4: ส่งภาพลง Gallery

```
WriteQueue.enqueue(jpegFile)
  │
  ├── Channel.trySend(file) → ถ้า queue full → drop (log warning)
  │
  └─ [Dispatchers.IO — background thread]
       └─ LocalDeliveryWriter.publish(file)
            ├── MediaStore.insert("DCIM/AutoBots")
            │   ├── Android Q+: RELATIVE_PATH + IS_PENDING=1
            │   ├── copy file bytes → output stream
            │   └── IS_PENDING=0 → visible in gallery
            │
            ├── ลบ temp file → file.delete()
            └─ onPhotoDelivered(uri) → UI: keptPhotoCount++
```

### Step 5: สรุป Live Capture Flow

```
Live Capture (5 นาที):
  │
  ├── Worker 1: บันทึก ~25 chunks (FHD 20MB/chunk)
  │
  ├── Worker 2: ประมวลผล ~25 chunks
  │    ├── แต่ละ chunk: sample ~40 frames → face detection → sharpness → dedup
  │    └── เก็บ ~30-50 photos (หลัง sharpness + dedup)
  │
  └── Gallery: 30-50 JPEG ใน DCIM/AutoBots/
```

---

## 4. Flow: Import Video (นำเข้าวิดีโอจากไฟล์)

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
  ├── ตั้งค่า resolution (FHD/UHD) + extraction target (Face/Pose)
  ├── ตรวจสอบพื้นที่เก็บข้อมูล (hasStorageForRecording)
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
  ├── สร้าง ChunkRecord (status = Pending)
  ├── ส่งเข้า videoQueue: trySend(ChunkWorkItem(index, file))
  │
  └─ [Worker 2 — Dispatchers.Default]
       └─ สำหรับแต่ละ chunk:
            ├── frameProcessor.process(chunkFile, ...)
            │  └─ [เหมือน Live Capture — Step 3]
            └─ สำหรับทุก JPEG ที่ถูก save:
                 └─ imageDelivery.enqueue(jpegFile)
                      └─ [เหมือน Live Capture — Step 4]
```

### Step 5: สรุป Import Video Flow

```
Import Video 5 นาที (FHD 1080p):
  │
  ├── ImportedVideoSplitter.split(): remux → ~25 chunks (20MB/chunk)
  │
  ├── Worker 2: ประมวลผล ~25 chunks
  │    ├── แต่ละ chunk: sample ~40 frames → face detection → sharpness → dedup
  │    └── เก็บ ~30-50 photos (หลัง sharpness + dedup)
  │
  └── Gallery: 30-50 JPEG ใน DCIM/AutoBots/
```

---

## 5. สรุปตัวเลข (FHD 1080p)

| Parameter | ค่า |
|-----------|-----|
| **Chunk target** | 20 MB/chunk |
| **จำนวน chunk (5 นาที)** | ~25 chunks |
| **Frame sample interval** | 300 ms |
| **Frames ต่อ chunk** | ~40 frames (12s ÷ 0.3s) |
| **Total frames sampled** | ~1,000 frames |
| **Photo kept** | ~30-50 photos (หลัง sharpness + dedup) |
| **Output path** | `DCIM/AutoBots/face_c000_123456.jpg` |

| Parameter | 1080p | 4K |
|-----------|-------|----|
| **Chunk target** | 20 MB | 50 MB |
| **Frame sample interval** | 300 ms | 120 ms |
| **Min sharpness** | 80.0 | 65.0 (แก้ไข) |
| **Detect bitmap width** | 640 | 640 (เท่ากัน) |

---

## 6. สรุป Flow แบบ Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                       User Action                                 │
│  ┌──────────────┐       ┌──────────────────┐                     │
│  │ Live Capture │       │ Import Video     │                     │
│  │ (เริ่ม capture)│       │ (เลือกไฟล์)       │                     │
│  └──────┬───────┘       └────────┬─────────┘                     │
│         │                         │                               │
├─────────┼─────────────────────────┼───────────────────────────────┤
│         │                         │                               │
│         ▼                         ▼                               │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │              CapturePipelineCoordinator                   │    │
│  │  sessionDir = cache/autobots/{id}                         │    │
│  │  facesDir = sessionDir/faces                              │    │
│  │  videoQueue = Channel<ChunkWorkItem>(capacity=8)          │    │
│  └──────────────────────────────────────────────────────────┘    │
│         │                         │                               │
│         ▼                         ▼                               │
│  ┌──────────────────┐    ┌──────────────────────┐                │
│  │ VideoChunkRecorder │    │ ImportedVideoSplitter│               │
│  │ (Worker 1)       │    │ (Remux splitting)    │               │
│  │                  │    │                      │               │
│  │ บันทึก MP4 chunks │    │ Split → MP4 chunks  │               │
│  │ (20MB/50MB)      │    │ (Keyframe-aligned)   │               │
│  └────────┬─────────┘    └──────────┬───────────┘               │
│           │                         │                            │
│           ▼                         ▼                            │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    videoQueue (Channel)                   │   │
│  │          ┌───────────────────────────────────────┐       │   │
│  └─────────▶│  Worker 2: VideoFrameProcessor        │◀────────┘   │
│              │                                       │           │
│              │  [Stage 1: Frame Sampling]            │           │
│              │    MediaCodec decode: MP4 → YUV →    │           │
│              │    Bitmap (YUV 420 → NV21 → JPEG 92%)│           │
│              │    Sample ตาม interval (300ms/120ms)  │           │
│              │                                       │           │
│              │  [Stage 2: Face/Pose Detection]       │           │
│              │    ML Kit Face/Pose Detection         │           │
│              │    (detectBitmapWidth = 640)          │           │
│              │    Map bounding box กลับไป full-size  │           │
│              │                                       │           │
│              │  [Stage 3: Quality Check]             │           │
│              │    FaceSharpnessScorer.scoreNormalized│           │
│              │      → Laplacian variance on 128px    │           │
│              │    subjectRatio ≥ 5% (face) / 25% (pose)│         │
│              │    sharpness ≥ 80 (FHD) / 65 (UHD)    │           │
│              │                                       │           │
│              │  [Stage 4: Dedup + Best-of-Window]    │           │
│              │    DEDUP_WINDOW = 1 วินาที            │           │
│              │    เก็บ frame ที่ sharp ที่สุด           │           │
│              │                                       │           │
│              │  [Stage 5: Save JPEG]                 │           │
│              │    "${prefix}_c${chunkIndex}_${ptsUs}.jpg"│        │
│              └───────────────────────────────────────┘           │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    WriteQueue                             │   │
│  │  Channel<File>(capacity=8)                                │   │
│  │  Dispatchers.IO — background drain loop                   │   │
│  └──────────────────────────────────────────────────────────┘    │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              LocalDeliveryWriter                          │   │
│  │    MediaStore.insert("DCIM/AutoBots")                     │   │
│  │    copy file → output stream                              │   │
│  │    IS_PENDING=0 → visible in gallery                      │   │
│  └──────────────────────────────────────────────────────────┘    │
│                         │                                       │
│                         ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    Gallery                                │   │
│  │  DCIM/AutoBots/face_c000_123456.jpg                       │   │
│  │  DCIM/AutoBots/face_c000_234567.jpg                       │   │
│  │  DCIM/AutoBots/face_c001_345678.jpg                       │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 7. สรุปการแก้ไขล่าสุด (MIN_SHARPNESS_UHD)

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
