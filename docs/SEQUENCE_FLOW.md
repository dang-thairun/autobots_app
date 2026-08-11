# AutoBots Sequence Flow — Mermaid

> Sequence diagram ของ **AutoBots Sports Camera** ตั้งแต่ Operator กด Start/Import จนภาพ JPEG ขึ้น Gallery และเขียน `session_log.txt`
> อ้างอิงโค้ดจริง: `OperatorViewModel` · `CapturePipelineCoordinator` · `VideoChunkRecorder` · `ImportedVideoSplitter` · `VideoFrameSampler` · `VideoFrameProcessor` · `WriteQueue` · `LocalDeliveryWriter`
>
> ภาพรวมแบบ text/ตาราง อยู่ที่ [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)

---

## 1. Full Pipeline — Live Capture + Import Video

```mermaid
sequenceDiagram
    autonumber

    actor Operator
    participant UI as OperatorShellScreen / ViewModel
    participant Coord as CapturePipelineCoordinator
    participant Recorder as Worker 1A · VideoChunkRecorder
    participant Splitter as Worker 1B · ImportedVideoSplitter
    participant VQ as videoQueue Channel cap=8
    participant Worker2 as Worker 2 · VideoFrameProcessor
    participant Sampler as VideoFrameSampler / MediaCodec
    participant MLKit as ML Kit Face / Pose
    participant Sharp as FaceSharpnessScorer CPU
    participant WQ as WriteQueue cap=16
    participant Writer as LocalDeliveryWriter
    participant Store as MediaStore / Gallery

    alt Live Capture
        Operator->>UI: กด Start
        UI->>UI: ตรวจ camera permission + storage
        UI->>Coord: CapturePipelineCoordinator.create()
        Coord->>Coord: sessionDir = cache/autobots/{sessionId}, facesDir
        Coord->>VQ: เปิด videoQueue + start Worker 2 loop
        UI->>Coord: setResolution(FHD/UHD), setExtractionTarget(Face/Pose)
        UI->>Recorder: start() ผูก CameraX VideoCapture
        Coord->>Coord: onRecordingStarted() → beginSession(LiveCapture)
    else Import Video
        Operator->>UI: กด Import แล้วเลือกไฟล์ (OpenDocument)
        UI->>Coord: importVideo(uri, displayName)
        Coord->>Coord: beginSession(VideoImport) · albumFolder = ext_DDMMYYYY_HHMM
        Coord->>Splitter: probe(uri)
        Splitter-->>Coord: width × height × rotation × duration
        Coord->>Coord: StreamResolution.fromVideoDimensions() → FHD หรือ UHD
        Coord->>Splitter: split(targetSegmentBytes = 50 MB)
    end

    loop Worker 1 ผลิต chunk (Live = record · Import = remux)
        alt Live Capture
            Recorder->>Recorder: บันทึก MP4 จนถึง 50 MB
            Recorder->>Coord: canAcceptVideoChunk()?
            alt videoQueue เต็ม (pending = 8)
                Coord-->>Recorder: false
                Recorder->>Coord: onRecorderPaused() — หยุดหมุน chunk
                Recorder->>Recorder: poll จนคิวว่างแล้ว resumeIfPaused()
            else คิวว่าง
                Coord-->>Recorder: true
                Recorder->>Coord: onChunkRecorded(ChunkCaptureMeta)
            end
        else Import Video
            Splitter->>Splitter: MediaExtractor อ่าน sample → MediaMuxer เขียน segment
            Note over Splitter: ตัด chunk ที่ Keyframe เท่านั้น + rebase PTS (remux ไม่ re-encode)
            Splitter->>Coord: onChunkReady(ChunkCaptureMeta)
            Splitter->>UI: onProgress(importPercent 0–99)
        end

        Coord->>Coord: chunksRecorded++ · ChunkRecord(status = Pending)
        Coord->>VQ: trySend(ChunkWorkItem(index, file))
        alt trySend ไม่สำเร็จ
            Coord->>Coord: videoPending-- และ log "Video queue full, dropped"
        end
        Coord->>UI: publishStats(PipelineStats)
    end

    loop Worker 2 — แต่ละ chunk ใน videoQueue (Dispatchers.Default, serial)
        VQ-->>Worker2: ChunkWorkItem
        Coord->>Coord: ChunkRecord.status = Processing
        Worker2->>Sampler: sampleFrames(file, sampleIntervalMs = 120 ms)

        loop ทุก sample frame (~8.3 fps)
            Sampler->>Sampler: hardware MediaCodec decode (fallback software)
            Sampler->>Sampler: YUV420 → NV21 → JPEG 92% → Bitmap + rotate
            Sampler-->>Worker2: (timestampUs, bitmap)

            Worker2->>Worker2: scaleForDetect → กว้าง 640 px

            alt ExtractionTarget = Face
                Worker2->>MLKit: FaceDetector FAST + enableTracking
                MLKit-->>Worker2: face bounds
                Worker2->>Worker2: เลือก face ใหญ่สุด · subjectRatio ≥ 5%
            else ExtractionTarget = Pose
                Worker2->>MLKit: PoseDetector SINGLE_IMAGE
                MLKit-->>Worker2: torso bounds (ไหล่ + สะโพก)
                Worker2->>Worker2: torsoRatio ≥ 25%
            end

            alt ไม่พบ subject หรือ subject เล็กเกินไป
                Worker2->>Worker2: rejects.noSubject / tooSmall · skipped++ · recycle bitmap
            else ผ่านขนาด
                Worker2->>Sharp: scoreNormalized(bitmap, roi) — Laplacian variance
                Sharp-->>Worker2: sharpness score
                alt score < minSharpness (FHD 80 · UHD 65)
                    Worker2->>Worker2: rejects.tooSoft · skipped++
                else score ผ่าน
                    Worker2->>Worker2: FrameCandidate(timestampUs, bitmap, sharpness)
                end
            end

            opt มี FrameCandidate — Dedup best-of-window
                alt ห่างจาก window เดิม ≥ DEDUP_WINDOW_US (1 s)
                    Worker2->>Worker2: saveFrame(best เดิม) → {face|pose}_c###_{ptsUs}.jpg (JPEG 95%)
                    Worker2->>Worker2: kept++ · เปิด window ใหม่
                else อยู่ใน window เดิม
                    Worker2->>Worker2: ถ้าคมกว่า → แทน best · ถ้าไม่ → recycle + skipped++
                end
            end

            Worker2->>UI: onProgress(currentChunkPercent)
        end

        Worker2->>Worker2: flush best ของ window สุดท้าย → saveFrame()
        Worker2-->>Coord: VideoProcessResult(kept, skipped, framesSampled, durationMs, savedFiles)
        Coord->>Coord: ChunkRecord.status = Done + metrics
        Coord->>Coord: recordChunkEndToEnd — queue wait · REALTIME RATIO

        loop ทุกไฟล์ใน savedFiles
            Coord->>WQ: enqueue(jpegFile)
            alt WriteQueue เต็ม
                WQ->>WQ: drop + log "Queue full"
            else
                WQ->>Writer: publish(file) [Dispatchers.IO]
                Writer->>Store: MediaStore.Images insert → DCIM/AutoBots/{subfolder}/
                Store-->>Writer: content Uri (IS_PENDING 0)
                Writer-->>WQ: Uri
                WQ->>WQ: ลบ temp file ใน cache
                WQ->>Coord: onDelivered(uri) + onDeliveredFile(file)
                Coord->>Coord: recordMomentToGallery — MOMENT→GALLERY latency
                Coord->>UI: publishStats + onPhotoDelivered(uri)
            end
        end

        Coord->>Coord: videoPending-- · maybeNotifyDrainComplete()
    end

    Note over Coord,Store: Drain — ไม่ recording / ไม่ importing / worker ว่าง / videoQueue = 0 / WriteQueue = 0

    Operator->>UI: กด Stop (เฉพาะ Live)
    UI->>Recorder: stop()
    Recorder->>Coord: onChunkRecorded(chunk สุดท้าย) แล้ว onRecorderStopSettled()

    Coord->>Coord: finalizeCurrentSession() → status Done หรือ Failed
    Coord->>Coord: buildSessionRecord(chunkHistory) → PipelineSessionRecord
    Coord->>Coord: toLogText() → session_log.txt
    Coord->>Coord: mirror cache/autobots/{sessionId}/ และ cache/autobots/logs/{subfolder}/
    Coord->>Writer: publishText("session_log.txt")
    Writer->>Store: legacy File → DCIM/AutoBots/{subfolder}/
    alt legacy ไม่สำเร็จ (API 29+)
        Writer->>Store: fallback MediaStore.Downloads → Download/AutoBots/{subfolder}/
    end
    Store-->>Writer: log Uri
    Coord->>UI: onDrainComplete()
    UI-->>Operator: Session card ใน ChunkHistoryPage — N chunks · X faces · total time
```

---

## 2. Backpressure — ทำไม Live ถึงหยุดหมุน chunk

```mermaid
sequenceDiagram
    autonumber

    participant Recorder as Worker 1A · VideoChunkRecorder
    participant Coord as CapturePipelineCoordinator
    participant VQ as videoQueue cap=8
    participant Worker2 as Worker 2 · VideoFrameProcessor
    participant UI as Operator UI

    Note over Recorder,Worker2: videoQueue = จุดเดียวที่ควบคุมความเร็ว ทั้ง live และ import

    loop chunk ครบ 50 MB
        Recorder->>Coord: canAcceptVideoChunk()
        Coord-->>Recorder: videoPending < 8 ?

        alt คิวว่าง
            Recorder->>Coord: onChunkRecorded(meta)
            Coord->>VQ: trySend → videoPending++
            Recorder->>Recorder: startNextChunk()
        else คิวเต็ม (Worker 2 ช้ากว่า realtime)
            Recorder->>Coord: onRecorderPaused()
            Coord->>UI: PipelineStats(pipelinePaused = true)
            loop poll ทุก RESUME_POLL_MS
                Recorder->>Coord: canAcceptVideoChunk()
            end
            Worker2->>Coord: chunk เสร็จ → videoPending--
            Recorder->>Coord: onRecorderResumed()
            Coord->>UI: PipelineStats(pipelinePaused = false)
            Recorder->>Recorder: startNextChunk()
        end
    end

    Note over Coord: REALTIME RATIO = processDurationMs ÷ recordedMs · ≥ 1.0 = คิวจะบวมแน่นอน
```

> Import ใช้ backpressure ตัวเดียวกัน — `ImportedVideoSplitter.split(canAcceptChunk = ::canAcceptVideoChunk)` ทำให้ไฟล์ยาวแค่ไหนก็ไม่ระเบิด memory

---

## 3. หมายเหตุตัวเลข (source of truth ในโค้ด)

| ค่า | ที่มา | ค่าปัจจุบัน |
|-----|-------|-------------|
| Sample interval | `StreamResolution.FRAME_SAMPLE_INTERVAL_MS` | 120 ms (ทั้ง FHD/UHD) |
| Chunk target | `StreamResolution.chunkTargetBytes` | 50 MB |
| Video queue | `CapturePipelineCoordinator.VIDEO_QUEUE_CAPACITY` | 8 |
| Image queue | `CapturePipelineCoordinator.IMAGE_QUEUE_CAPACITY` | 16 |
| Dedup window | `VideoFrameProcessor.DEDUP_WINDOW_US` | 1,000,000 µs |
| Min sharpness | `MIN_SHARPNESS` / `MIN_SHARPNESS_UHD` | 80.0 / 65.0 |
| Min subject ratio | `MIN_FACE_HEIGHT_RATIO` / `MIN_TORSO_HEIGHT_RATIO` | 5% / 25% |
| Detect width | `ProcessProfile.detectBitmapWidth` | 640 px |
| JPEG quality | sampler 92% (decode) · `saveFrame` 95% (deliverable) | — |

---

## Related

- Pipeline รายละเอียด: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
- Operator guide: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md)
- Architecture: [ARCHITECTURE.md](./ARCHITECTURE.md)
- Code layout: [STRUCTURE.md](./STRUCTURE.md)
- Doc index: [DOCS.md](./DOCS.md)
