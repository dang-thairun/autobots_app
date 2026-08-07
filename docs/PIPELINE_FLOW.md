# AutoBots Pipeline Flow — จาก Video ไปสู่ Photo ที่เก็บ

> เอกสารนี้อธิบายขั้นตอนการทำงานของระบบ AutoBots Sports Camera ตั้งแต่รับวิดีโอ (Live Capture หรือ Import) จนได้ภาพที่เก็บลงใน Gallery ของเครื่อง

---

## 1. ภาพรวม Architecture

### 1.1 GPU Pipeline (ปัจจุบัน)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    AutoBots GPU Pipeline                             │
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
│  (บันทึกวิดีโอเป็น chunk)       │  (GPU decode → GPU detect → GPU  │
│                                 │   sharpness → บันทึก)            │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Pipeline Components (ปัจจุบัน)

| Stage | Technology | หมายเหตุ |
|-------|-----------|----------|
| **Decode** | MediaCodec (Hardware) | VPU — `VideoFrameSampler` |
| **Detect** | ML Kit Face / Pose (bundled TFLite) | `OfflineFaceDetector` / `OfflinePoseDetector` |
| **Sharpness** | Laplacian variance (CPU) | `FaceSharpnessScorer.scoreNormalized()` |
| **Save** | MediaStore → `DCIM/AutoBots/{subfolder}/` | `LocalDeliveryWriter` + `session_log.txt` |

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

### Step 3: Worker 2 ประมวลผล Chunk (GPU Pipeline)

```
VideoFrameProcessor.process(chunkFile, chunkIndex, resolution, extractionTarget)
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
            ├── โฟลเดอร์ย่อยใต้ DCIM/AutoBots:
            │   ├── Import: `ext_DDMMYYYY_HHMM` (เช่น `ext_07082026_1415`)
            │   └── Live:   `{yyyyMMdd_HHmmss}` จากเวลา Start
            ├── รูป: `face_c000_123456.jpg` / `pose_c000_123456.jpg`
            ├── `session_log.txt` — สรุป session (chunks, photos, timing)
            ├── IS_PENDING=0 → visible in gallery
            ├── ลบ temp file → file.delete()
            └── onPhotoDelivered(uri) → UI: keptPhotoCount++
```

### Step 5: สรุป Live Capture Flow

```
Live Capture (5 นาที):
  │
  ├── Worker 1: บันทึก ~8–10 chunks (FHD) หรือ ~15–25 chunks (UHD) — ทุก chunk 50 MB
  │
  ├── Worker 2: ประมวลผล chunks (ตาม queue)
  │    ├── Hardware decode → NNAPI detect → GPU sharpness → dedup
  │    └── เก็บ ~30-50 photos (หลัง sharpness + dedup)
  │
  └── Gallery: 30-50 JPEG ใน DCIM/AutoBots/
```

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

### Step 4: Chunk เข้า Queue → Worker 2 ประมวลผล (GPU Pipeline)

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
            │  └─ [GPU Pipeline: Hardware decode → NNAPI detect → GPU sharpness]
            └─ สำหรับทุก JPEG ที่ถูก save:
                 └─ imageDelivery.enqueue(jpegFile)
                      └─ [เหมือน Live Capture — Step 4]
```

### Step 5: สรุป Import Video Flow

```
Import Video 5 นาที (FHD 1080p):
  │
  ├── ImportedVideoSplitter.split(): remux → ~10 chunks (50MB/chunk)
  │
  ├── Worker 2: ประมวลผล ~10 chunks (GPU Pipeline)
  │    ├── Hardware decode → NNAPI detect → GPU sharpness → dedup
  │    └── เก็บ ~30-50 photos (หลัง sharpness + dedup)
  │
  └── Gallery: JPEG + session_log.txt ใน `DCIM/AutoBots/{subfolder}/`
```

### Step 6: Session History (UI หน้า swipe ที่ 3)

```
Session history — สรุประดับ session (ไม่ใช่แค่ chunk รายตัว)
  ├── Import: ชื่อไฟล์, 4K·3840×2160, chunks, photos, total time
  ├── Live:   Live · HH:mm:ss, timestamp folder
  ├── Import: แสดงเฉพาะ chunk ที่ detect ได้ (facesKept > 0)
  └── Expand → รายละเอียด JPEG ต่อ chunk
```

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

### Gallery path

```
DCIM/AutoBots/
├── ext_07082026_1415/        ← import video
│   ├── face_c000_….jpg
│   └── session_log.txt
└── 20260806_160512/        ← live capture
    ├── face_c001_….jpg
    └── session_log.txt
```

---

## 7. สรุป Flow แบบ Diagram (GPU Pipeline)

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
│  │ (50MB unified)   │    │ (Keyframe-aligned)   │               │
│  └────────┬─────────┘    └──────────┬───────────┘               │
│           │                         │                            │
│           ▼                         ▼                            │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    videoQueue (Channel)                   │   │
│  │          ┌───────────────────────────────────────┐       │   │
│  └─────────▶│  Worker 2: VideoFrameProcessor (GPU)  │◀────────┘   │
│              │                                       │           │
│              │  [Stage 1: Hardware Frame Sampling]   │           │
│              │    MediaCodec (Hardware decoder)      │           │
│              │    YUV 420 → NV21 → JPEG 92% →       │           │
│              │    Bitmap ARGB_8888                   │           │
│              │    Sample: 120ms (ทุก resolution)       │           │
│              │                                       │           │
│              │  [Stage 2: ML Kit Face/Pose]          │           │
│              │    scale 640px, FAST + tracking       │           │
│              │                                       │           │
│              │  [Stage 3: CPU Sharpness]             │           │
│              │    FaceSharpnessScorer                │           │
│              │    threshold 80 (FHD) / 65 (UHD)      │           │
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
│  │    DCIM/AutoBots/{subfolder}/face_c….jpg              │   │
│  │    DCIM/AutoBots/{subfolder}/session_log.txt          │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 8. Changelog ล่าสุด

| หัวข้อ | รายละเอียด |
|--------|------------|
| **Sample interval** | **120 ms** ทุก resolution (`StreamResolution.kt`) |
| **Import resolution** | Auto-detect จากไฟล์ — ไม่ใช้ค่า UI |
| **Gallery folders** | `DCIM/AutoBots/ext_DDMMYYYY_HHMM` (import) หรือ `{yyyyMMdd_HHmmss}` (live) |
| **Session log** | `session_log.txt` ในโฟลเดอร์เดียวกับรูป |
| **Session history UI** | สรุป session + แสดงเฉพาะ chunk ที่ detect ได้ (import) |
| **Sharpness** | CPU `FaceSharpnessScorer` (ไม่ใช่ GPU shader) |
| **Detect** | ML Kit bundled TFLite (ไม่ใช่ NNAPI โดยตรง) |

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