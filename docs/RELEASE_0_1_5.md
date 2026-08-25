# v0.1.5 — Menu Navigation + Network URL Ingest

> รายงานการเปลี่ยนแปลงจาก **v0.1.4** → **v0.1.5**
> diff: `36ead5e` → `f62692e` · 18 ไฟล์ · +1,921 / −128
>
> **นี่คือ feature release ไม่ใช่ perf release** — ไม่มี TC ใหม่ ไม่มีตัวเลขวัดผล ทุกอย่างใน 0.1.4 ที่ค้างอยู่ยังค้างเหมือนเดิม (ดู [ยังไม่ได้แก้](#ยังไม่ได้แก้ใน-015))
>
> **⚠️ default detector เปลี่ยนจาก ML Kit FAST → LiteRT NPU** — รายงานใดที่เทียบกับ v0.1.4 ต้องระบุ backend ให้ชัด ไม่งั้นตัวแปรปนทันที
>
> **✅ TC-11 รันแล้ว (18/08/2026) — live capture ผ่าน** 3 session ติดกันบน Xiaomi peridot · 1080p และ 4K · `Status: Done` ทั้งหมด · `session_log.txt` + `perf_report.json` ถูกเขียนครบทุก session (NA-05 ไม่ปรากฏซ้ำ) · `decodeFailures` 0 · `truncation` 0 · ปิด NA-06 ที่ค้างมาตั้งแต่ v0.1.3

---

## สรุปผู้บริหาร

0.1.4 ทำให้ pipeline เร็วพอจะประมวลผลเร็วกว่าความยาวคลิป (`realtimeRatio` 1.010) แต่ทางเข้าของงานยังมีอยู่สองทางเท่านั้น: กดอัดสด หรือเลือกไฟล์ในเครื่อง และการเลือกไฟล์คือ **fire-and-forget** — แตะแล้ว extract ทันที ไม่มีจังหวะให้ดูก่อนว่าคลิปนี้กี่นาที ความละเอียดเท่าไร จะใช้ backend ไหน หรือจะเอาแค่ช่วงไหน

0.1.5 แก้สองเรื่องนี้พร้อมกัน:

| | v0.1.4 | **v0.1.5** |
|--|--|--|
| **Navigation** | `HorizontalPager` 3 หน้า · preview ผูกอยู่ตลอด | Home menu → 5 destination + ปุ่ม Back ของระบบ |
| **เลือกไฟล์แล้ว** | extract ทันที | หน้า **Import Preview** — เห็น metadata, เลือก target/backend, ตัดช่วง, ดูเวลาโดยประมาณ |
| **ทางเข้าของงาน** | live · ไฟล์ในเครื่อง | + **Network URL** (พิมพ์ / สแกน QR) |
| **default backend** | ML Kit FAST | **LiteRT NPU** → GPU → ML Kit |
| **session log** | `Target: Face` | `Detector: Face · NPU · face_det_lite.tflite` |

Network URL **สตรีมเป็นค่าเริ่มต้น** ไม่ได้ดาวน์โหลดทั้งไฟล์ก่อน — `MediaExtractor` ยิง byte-range ตามที่ remux เดินไป chunk แรกจึงเข้า Worker 2 ได้ตั้งแต่ปลายไฟล์ยังมาไม่ถึง ส่วนการดาวน์โหลดถูกลดชั้นเป็น **fallback** สำหรับ CDN ที่ปฏิเสธ `MediaHTTPConnection` (R2 และพวกเดียวกัน)

---

## การเปลี่ยนแปลง

### 1. ⭐ รื้อ navigation — จาก pager เป็น menu

**ไฟล์:** [OperatorShellScreen.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt) · [MainActivity.kt](../androidApp/src/main/kotlin/com/autobots/MainActivity.kt)

```
                    ┌──────────────┐
                    │  Home        │  AppIdentityCard + ProcessingStatusCard
                    │  (menu)      │  Live capture · Browse Video · Network URL
                    └──┬───┬───┬───┘  Session History · Gallery
        ┌──────────────┘   │   └──────────────┬─────────────┐
   LiveCapture      SessionHistory     ImportPreview    NetworkUrl
```

`OperatorDestination` เป็น enum 5 ค่าใน `remember` ธรรมดา ไม่ได้ใช้ Navigation component — จำนวนหน้าน้อยและไม่มี deep link จึงยังไม่คุ้มที่จะเพิ่ม dependency

สิ่งที่เปลี่ยนพฤติกรรมจริง ไม่ใช่แค่ layout:

- **preview ไม่ได้ผูกอยู่ตลอดเวลาอีกแล้ว** — `CameraPreviewPane` อยู่ใน `LiveCapture` destination เท่านั้น หน้าอื่นไม่ bind กล้อง
- **`goHome()` มีผลข้างเคียงตั้งใจ** — ออกจาก `LiveCapture` ระหว่างอัดอยู่ = สั่ง `onToggleCapture()` ให้หยุดเอง และออกจาก `ImportPreview` = `onCancelImport()` ไม่มีสถานะค้างเมื่อกดย้อนกลับ
- **`BackHandler`** — ปุ่ม Back ของระบบกลับ Home แทนที่จะปิดแอป
- `OverlayPages.Count` 3 → 2 · `ChunkHistoryPage` รับ `onBack` (null ได้ เพื่อคงการใช้แบบเดิม)

`onPhotoDelivered` ถูก `@Suppress("UNUSED_PARAMETER")` ไว้ — ยังอยู่ใน signature แต่ไม่มีใครเรียกในเส้นทางใหม่

### 2. ⭐ Import Preview — ดูก่อน แล้วค่อย extract

**ไฟล์:** [ImportPreviewPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/ImportPreviewPage.kt) *(ใหม่ 480 บรรทัด)* · [OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt)

`videoPicker` เรียก **`prepareImport(uri)`** แทน `importVideo(uri)` — probe ก่อน แล้วหยุดรอที่ `PendingVideoImport`:

| ส่วน | รายละเอียด |
|--|--|
| **Metadata** | ชื่อ · ขนาด · `resolutionLine` · `durationLabel` · **`fpsLabel`** |
| **Range** | สลับ *Full length* ↔ *Range* · ช่อง Start/End รับ `mm:ss` และ `h:mm:ss` (`parseTrimTime`) |
| **Target** | Face / Pose |
| **Process with** | backend chip — ตัวที่รันไม่ได้ถูก disable **พร้อมเหตุผล** จาก `detectorUnavailable` |
| **Estimate** | `~ N min` คำนวณสดตามที่เลือก |

การ validate ช่วงเวลาอยู่ที่หน้า UI (`Start is past the clip` · `End is past the clip` · `Start must be before end`) และซ้ำอีกชั้นในตัว splitter

**สูตร estimate** ([OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt) · `estimateImportWallMs`):

```
frames = durationMs / FRAME_SAMPLE_INTERVAL_MS        // 120 ms คงที่
wall   = frames × detectMs × poseMul × resMul
         detectMs: NPU 50 · GPU 75 · อื่นๆ 110
         poseMul : Pose 1.3 · Face 1.0
         resMul  : longEdge ≥ 2160 → 1.4
```

> 🔴 **ค่าพวกนี้เป็นการเดา ไม่ได้ calibrate จากผลวัดจริง** doc ในโค้ดเขียนกำกับไว้แล้วว่า *"a field guess … not a benchmark"* แต่ต้องย้ำ: TC-12 วัด wall ได้ 270.8 s สำหรับคลิป UHD 273.7 s ขณะที่สูตรนี้ให้ 273700/120 × 50 × 1.4 ≈ **160 s** — ต่ำกว่าของจริงเกือบ 40% ถือเป็น**หนี้ที่ต้องใช้คืนด้วยการ calibrate จาก `perf_report.json`** ไม่ใช่ตัวเลขที่เชื่อได้ตอนนี้

### 3. ⭐ Network URL — สตรีมก่อน ดาวน์โหลดเมื่อจำเป็น

**ไฟล์:** [RemoteVideoFetcher.kt](../androidApp/src/main/kotlin/com/autobots/camera/network/RemoteVideoFetcher.kt) *(ใหม่)* · [VideoHttp.kt](../androidApp/src/main/kotlin/com/autobots/camera/network/VideoHttp.kt) *(ใหม่)* · [NetworkUrlPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/NetworkUrlPage.kt) *(ใหม่)* · [QrScanPreview.kt](../androidApp/src/main/kotlin/com/autobots/ui/QrScanPreview.kt) *(ใหม่)*

รับเฉพาะ **URL ของไฟล์วิดีโอตรงๆ** — หน้าเว็บ (YouTube, ลิงก์แชร์ Drive) อยู่นอกขอบเขตโดยตั้งใจ

**เส้นทางของ Check:**

```
validate(scheme http/https + host)
  → head()            405/501 → fallback 1-byte GET เอาเฉพาะ header
  → rejectIfNotVideo(contentType)
  → probe()           withTimeout(12s)
       สำเร็จ → PendingVideoImport(remoteUrl = url)      ← สตรีมทีหลัง
       ล้มเหลว/timeout → download() → probeFile()         ← R2 fallback
                          PendingVideoImport(uri = file://…, remoteUrl = null)
```

**`VideoHttp` คือหัวใจของการสตรีม** — `setDataSource(Context, Uri)` ของ Android ส่ง header ที่ CDN หลายเจ้าปฏิเสธ จึง bind ด้วย header แบบเบราว์เซอร์แทน:

```kotlin
"User-Agent"      to CHROME_ANDROID_UA
"Accept"          to "*/*"
"Accept-Encoding" to "identity"      // ห้าม gzip — byte-range ต้องตรงกับไฟล์จริง
```

ทั้ง `MediaExtractor` และ `MediaMetadataRetriever` bind ผ่านฟังก์ชันเดียวกัน (`VideoHttp.bind`) และ local URI ยังเดินทางเดิมทุกประการ

**ดาวน์โหลดเป็น fallback สองชั้น** — ชั้นแรกตอน Check (ข้างบน) ชั้นที่สองตอน split จริง ใน [CapturePipelineCoordinator.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt):

```kotlin
val streamed = runSplit(source)
if (!shouldDownloadFallback(source, streamed)) streamed
else { download(...); runSplit(Uri.fromFile(dest)) }
```

`shouldDownloadFallback` จงใจ**ไม่**ดาวน์โหลดซ้ำเมื่อ error คือ `"Start must be before…"` — นั่นเป็นความผิดของค่าที่ผู้ใช้กรอก ไม่ใช่ของ transport ดาวน์โหลดไฟล์เป็นกิกะไบต์มาแล้วก็ fail เหมือนเดิม

**QR scan** — `QrScanPreview` ใช้ CameraX `ImageAnalysis` + ML Kit barcode (`17.3.0` ใหม่ใน [libs.versions.toml](../gradle/libs.versions.toml)) กรองเฉพาะ `FORMAT_QR_CODE` ขอ permission ผ่าน `rememberCameraPermissionState` เดิม

**`usesCleartextTraffic="true"`** ใน [AndroidManifest.xml](../androidApp/src/main/AndroidManifest.xml) — เปิดทั้งแอป เพื่อรับ URL `http://` ของเซิร์ฟเวอร์ในสนามที่ยังไม่มี TLS

### 4. Splitter — trim ช่วงเวลา + อ่าน fps

**ไฟล์:** [ImportedVideoSplitter.kt](../androidApp/src/main/kotlin/com/autobots/camera/capture/ImportedVideoSplitter.kt)

- **`startTimeUs` / `endTimeUs`** — `seekTo(clipStartUs, SEEK_TO_PREVIOUS_SYNC)` แล้ว `break` เมื่อ `ptsUs > clipEndUs` · เริ่มที่ sync frame ก่อนหน้าเสมอ ดังนั้น**จุดเริ่มจริงอาจมาก่อนที่กรอกไว้ถึงหนึ่ง GOP** ซึ่งถูกต้องแล้วสำหรับ remux ที่ไม่ re-encode
- **progress คิดจาก span ของ clip** ไม่ใช่ทั้งไฟล์ — `firstPtsUs` ที่เคยเดาจาก sample แรกถูกแทนด้วย `clipStartUs` ที่รู้ค่าแน่นอน · `ImportSplitResult.durationMs` ก็รายงานความยาวของ clip ไม่ใช่ของ source
- **`probe` ย้ายไป companion** (`probe(context, source)`) — เรียกได้โดยไม่ต้องสร้าง splitter ซึ่งเป็นสิ่งที่ `prepareImport` และ `RemoteVideoFetcher` ต้องการ · เมธอด instance เดิมยังอยู่ ชี้ไปที่ตัวเดียวกัน
- **`VideoProbeResult.frameRate`** — `METADATA_KEY_CAPTURE_FRAMERATE` ก่อน แล้ว fallback ไป `MediaFormat.KEY_FRAME_RATE` ซึ่งอ่านทั้งแบบ `Int` และ `Float` เพราะ container/เครื่องเก็บไม่เหมือนกัน

> fps ยัง**ไม่ได้**ถูกใช้คำนวณ sample interval — `FRAME_SAMPLE_INTERVAL_MS` ยังคงที่ 120 ms ตอนนี้มันมีไว้แสดงใน Import Preview และ (สำคัญกว่า) ทำให้ค่าที่จำเป็นต่อการปรับ interval ตาม fps มีอยู่ในมือแล้ว

### 5. ⚠️ Detector default: ML Kit FAST → LiteRT NPU

**ไฟล์:** [DetectorBackend.kt](../shared/src/commonMain/kotlin/com/autobots/camera/DetectorBackend.kt) · [PipelineSessionRecord.kt](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt)

```kotlin
val DEFAULT = LiteRtNpu                              // เดิม MlKitFast

fun firstAvailable(unavailable: Map<DetectorBackend, *>) =
    listOf(LiteRtNpu, LiteRtGpu, MlKitFast).firstOrNull { it !in unavailable } ?: MlKitFast
```

เครื่องที่ไม่มี NPU ไล่ลงไป GPU แล้ว ML Kit — ไม่มีทางที่ผู้ใช้จะเจอ backend ที่สตาร์ตไม่ขึ้นเป็นค่าเริ่มต้น

พร้อมกันนั้น **session รู้แล้วว่าตัวเองรันด้วยอะไร** — `PipelineSessionRecord.detectorBackend` ถูกบันทึกจากตัว coordinator และ `session_log.txt` เปลี่ยนบรรทัด `Target: Face` เป็น:

```
Detector: Face · NPU · face_det_lite.tflite
```

Pose บังคับเป็น `Pose · ML Kit · pose-detection` เสมอ เพราะ LiteRT path มีแต่ face · `hardwareLabel` / `modelName` เป็นตัวจ่ายข้อความนี้ และหน้า Session History ก็แสดงบรรทัดเดียวกัน

> 🔴 **นี่คือการเปลี่ยนตัวแปรของการทดลอง ไม่ใช่แค่ default ของ UI** — ทุก TC ที่รันหลังจากนี้โดยไม่ระบุ backend จะได้ NPU ซึ่งเทียบกับตัวเลข ML Kit ใน [RELEASE_0_1_4.md](./RELEASE_0_1_4.md) ตรงๆ ไม่ได้ · ข้อดีคือ `detectorLine` ทำให้ session log บอกเองแล้วว่าใช้อะไร จึงตรวจย้อนหลังได้

### 6. QNN — ถอด asset แบบ atomic

**ไฟล์:** [QnnDelegate.kt](../androidApp/src/main/kotlin/com/autobots/camera/detection/QnnDelegate.kt)

`ensureDspLibraries` ใช้ mtime เป็นบันทึกความสดของไฟล์ ซึ่งแปลว่า**การ copy ที่ขาดกลางคันจะกลายเป็นความเสียหายถาวร**: ไฟล์ไม่ครบถูกประทับเวลาปัจจุบัน รอบต่อไป guard อ่านว่า "ใหม่กว่า APK" แล้วข้าม อาการที่ได้คือ `loadRemoteSymbols failed with err 4000` เหมือนเดิมทุกประการ และแก้ได้ทางเดียวคือให้ผู้ใช้ไป clear app data

แก้ด้วยการเขียนลง `.tmp` แล้ว `renameTo` เข้าที่ (ไดเรกทอรีเดียวกัน = atomic) ไฟล์ปลายทางจึงมีแค่สองสถานะ: ไม่มี หรือครบพร้อม mtime ที่ถูกต้อง

`apkTime` fallback `0L` ถูกถอดออกด้วย — มันทำให้ `lastModified() >= 0` จริงตลอด แปลว่า**อัปเกรด APK แล้วไม่มีวัน re-extract** ซึ่งเป็นบั๊กเดียวกันคนละหน้า ตอนนี้ปล่อยให้ throw และ `unavailableReason()` รายงานว่า backend ใช้ไม่ได้ ดังกว่าและตรวจเจอง่ายกว่า

### 7. ย้าย detector probe ไป `Dispatchers.IO`

**ไฟล์:** [OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt)

`DetectorAvailability.checkAll()` แตก DSP library ~96 MB ออกจาก APK ในรอบแรก ซึ่งเป็น blocking disk I/O ไม่ใช่งาน CPU — ความถูกต้องเชิงความหมาย ไม่ใช่ผลด้าน performance ที่วัดได้ (IO กับ Default ใช้ thread pool เดียวกัน)

---

## ยังไม่ได้แก้ใน 0.1.5

| ข้อ | สถานะ |
|--|--|
| ~~**NA-06 / TC-11 · live capture smoke test**~~ | **✅ ปิดแล้ว 18/08/2026** — ดูหัวเอกสาร · ยังเหลือกรณีเดียวที่ยังไม่ยืนยัน: กด Back ระหว่างอัดเพื่อทดสอบว่า `goHome()` สั่งหยุดอัดให้เองจริง |
| **TC-05 / TC-13** | ยังค้างจาก 0.1.4 · ยังแยกผลของข้อ 1 กับข้อ 4 ไม่ได้ |
| ~~**`yuv420ToNv21` = 61.6% ของ wall**~~ | **✅ แก้แล้ว 21/08/2026 — เกินเป้าที่ 0.1.4 ตั้งไว้** · ดู [perf](#perf--yuv420tonv21-เลกเปนคอขวดแลว) ข้างล่าง |
| **สูตร estimate ไม่ได้ calibrate** | ต่ำกว่า TC-12 เกือบ 40% (ดูข้อ 2) · **แก้ตัวเลขที่เคยเขียนผิดไว้:** เคยจดว่าของจริง ~85 วินาที ซึ่งมาจากการกะเวลาคร่าวๆ ไม่ใช่การวัด — `perf_report.json` ของ session จริงบอก **53.2 วินาที** (แสดง `~35s`) · **และหลังแก้ `yuv420ToNv21` เหลือ 30.5 วินาที สูตรจึงเพี้ยนคนละทางแล้ว — ตอนนี้ประเมิน*สูง*ไป 15%** ต้อง calibrate ใหม่บนตัวเลขหลังแก้ |
| ~~**`cacheDir/network_import/` ไม่มีใครลบ**~~ | **✅ แก้แล้ว 20/08/2026** — รั่วสองทางไม่ใช่ทางเดียว: (1) ไฟล์ที่โหลดมาแล้วแยกเสร็จไม่มีใครลบ แก้ด้วย `try/finally` (2) ไฟล์ที่โหลดตอนกด *Check* ถูก**ตั้งใจเก็บไว้**ให้ Extract ใช้ต่อ จึงรอดจากข้อแรก — แก้ด้วยการลบตอนกด Cancel และ**กวาดโฟลเดอร์ทิ้งตอนเปิดแอป** (pending import ไม่รอดข้ามโปรเซส อะไรที่ค้างอยู่ตอนเปิดคือของไม่มีเจ้าของ) |
| **`usesCleartextTraffic` เปิดทั้งแอป** | 🚫 **แคบลงตามที่เขียนไว้ไม่ได้** (ตรวจ 20/08/2026) — `network_security_config` รับเฉพาะ**ชื่อโฮสต์** ใน `<domain>` ไม่รับ CIDR ระบุ `192.168.0.0/16` ไปมันกลายเป็นชื่อโฮสต์ที่ไม่มีวันตรงกับอะไร · เหลือทางเลือกแค่ "เปิดทั้งแอป" หรือ "ปิดหมดเหลือ localhost" ซึ่ง**จะพัง Network URL ที่ดึงคลิปจากโน้ตบุ๊กผ่าน http** — เป็นการตัดฟีเจอร์ ไม่ใช่ hardening เฉยๆ ต้องให้เจ้าของตัดสิน |
| **fps ยังไม่ขับ sample interval** | ค่าอ่านได้แล้ว แต่ `FRAME_SAMPLE_INTERVAL_MS` ยังคงที่ 120 ms · interval จริงถูกปัดขึ้นเข้ากริดเฟรม (30 fps → 133 ms) |
| **B3 · HTTP upload** | เอกสารฉบับนี้ครอบคลุมถึงตอนที่ตัดเวอร์ชัน 0.1.5 ซึ่งยังไม่มี upload · งาน upload ถูกทำเพิ่มบน 0.1.5 หลังจากนั้น ดู [การใช้งานจริง](#การใชงานจรง--upload-ทำงานตอนวางเครองทงไว) และ [สถานะงาน upload](#สถานะงาน-upload--20082026) ข้างล่าง |

---

## การใช้งานจริง — upload ทำงานตอนวางเครื่องทิ้งไว้

> เพิ่มเมื่อ **20/08/2026** หลังงาน upload ถูกทำเพิ่มบน 0.1.5 · รายละเอียดเต็มอยู่ที่ [PHASES.md B3f-0 / B3f-1](./PHASES.md)

วัดจริงบน Xiaomi peridot (HyperOS 3.0) สองรอบ **ก่อน** และ **หลัง** มี foreground service:

| สถานการณ์ | ไม่มี FGS | **มี FGS** |
|--|--|--|
| เปิดแอปค้าง · จอดับ · เสียบสายชาร์จ | ✅ 15 ใบครบ | ✅ |
| เครื่องนิ่งจนเข้า Doze (ถอดสายชาร์จ) | 🔴 **6 จาก 15 ใบ แล้วหยุดสนิท** | ✅ **16 จาก 16 ใบ ใน 4 นาที 30 วินาที** |
| งานถูกระบบยกเลิกระหว่างนั้น | 3 ครั้ง | **0** |
| โปรเซสถูกฆ่า | ใช่ (token หายไปด้วย) | ไม่ |

**สรุป: ไม่ต้องเฝ้าจอแล้ว** ปิดจอ วางทิ้ง ถอดสายได้ คิวเดินต่อ

### สองข้อที่ยังต้องทำก่อนออกงานจริง

**1 · ติ๊ก "จำ username/password"**

foreground service กันโปรเซสตายเฉพาะ**ตอนที่คิวกำลังเดิน** ถ้าคิวว่างอยู่แล้วโปรเซสถูกฆ่า token ที่อยู่ใน memory จะหายไปด้วย พอมีรูปใหม่เข้าคิว worker จะตื่นมาในโปรเซสใหม่ที่ไม่รู้จักใครแล้วขึ้น `Not signed in — standing by`

ติ๊กไว้ = worker ขอ token ใหม่เองได้ ไม่ต้องมีคนมากด · ไม่ติ๊ก = ต้องเปิดแอป login ใหม่ทุกครั้งที่โปรเซสตาย

**2 · อนุญาตการแจ้งเตือน** ตอนที่แอปขอ (กดเปิด auto-upload ครั้งแรก) — ปฏิเสธได้ คิวยังเดิน แต่จะไม่เห็นความคืบหน้าบนแถบสถานะ

**3 · (MIUI/HyperOS)** Settings → Apps → AutoBots → Battery saver → **No restrictions** และเปิด **Autostart**

### วิธีทดสอบซ้ำบนเครื่องรุ่นอื่น

ใช้ได้กับทุกยี่ห้อ ไม่ต้องรอ 30 นาทีให้ Doze มาเอง:

```bash
adb shell svc power stayon false
adb shell input keyevent 26            # ปิดจอ
adb shell dumpsys battery unplug       # หลอกว่าถอดสายแล้ว (USB ยังต่ออยู่)
adb shell dumpsys deviceidle force-idle

adb logcat -d | grep -E "RunxUploadTransport|UploadWorker|was cancelled"

adb shell dumpsys deviceidle unforce   # คืนค่าให้เรียบร้อยทุกครั้ง
adb shell dumpsys battery reset
```

**เกณฑ์ผ่าน:** คิวระบายจนหมดขณะอยู่ในโหมด idle · ไม่มี `was cancelled` · ไม่มี `Not signed in — standing by`

> **Doze เป็นของ Android เองตั้งแต่ 6.0 ไม่ใช่ของ Xiaomi** — เปลี่ยนยี่ห้อไม่ได้ทำให้หายไป สิ่งที่ OEM แต่ละเจ้าต่างกันคือความดุในการฆ่าโปรเซส ซึ่งเป็นชั้นที่สอง · foreground service คือทางเดียวที่ Android รับรองว่างานจะได้รันและได้ใช้เน็ตตอนเครื่องนิ่ง

---

## สถานะงาน upload — 20/08/2026

> งาน upload ทั้งหมดถูกทำเพิ่มบน 0.1.5 หลังตัดเวอร์ชันไปแล้ว · แผนเต็มและกติกาที่ล็อกไว้อยู่ที่ [PHASES.md](./PHASES.md)

**ทำงานได้แล้ว:** คิวบนดิสก์ · WorkManager + foreground service · login ด้วย username/password · เลือก event จาก dropdown · presign → PUT → complete ขึ้น Runx จริง · สวิตช์ auto-upload · การ์ดสถานะบนหน้าแรก · การแจ้งเตือนพร้อม %

### 🔴 รอออกสนาม — ทดสอบในออฟฟิศไม่ได้

| | |
|--|--|
| **live capture → production** | ที่ยิงขึ้นจริงคือเส้นทาง **import** เท่านั้น · live capture เข้าคิวคนละจุด ([CapturePipelineCoordinator](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt)) พิสูจน์แล้วแต่กับ fake transport |
| **เน็ตมือถือจริง (4G)** | ทุกอย่างที่วัดมาอยู่บน Wi-Fi หรือ loopback · อัตราการล้มของ PUT บน 4G ยังไม่มีตัวเลข |
| **FGS บนงานยาว 4 ชั่วโมง** | ที่ผ่านคือ Doze ที่บังคับเอง 4 นาทีครึ่ง · ของจริงคือความร้อน แบต และ Doze ที่มาเองซ้ำๆ ทั้งงาน |
| **เพดาน `dataSync` 6 ชม./วัน (Android 14+)** | ยังไม่รู้ว่าชนเมื่อไหร่ · ถ่ายสองงานยาวในวันเดียวคือกรณีที่ต้องเฝ้า |

### 🟠 รอเจ้าของ backend

| | ผลถ้ายังไม่ได้ |
|--|--|
| **คุมชื่อไฟล์สุดท้ายได้ไหม** | ยิงถามแล้ว `photoUpload` มีแค่ `provider` · `path` · `mimeType` และ `path` เป็นแค่โฟลเดอร์ — server ต่อ UUID เสมอ · **requeue จึงสร้าง object ซ้ำตลอดไป** (รูปไม่หาย แต่มีของกำพร้าบน bucket) |
| **`/success` รับ `capturedAt` ไหม** | แพลตฟอร์มเห็นแต่เวลาที่อัป ไม่ใช่เวลาที่ถ่าย · สำคัญขึ้นเพราะพิสูจน์แล้วว่าคิวค้างข้ามช่วงเวลาได้จริง |

### perf — `yuv420ToNv21` เลิกเป็นคอขวดแล้ว

> วัดบนคลิปเดียวกัน (`run1mins.mp4` · 4K · Face · NPU · 507 เฟรม) เทียบ session ก่อน/หลังตรงๆ

| | ก่อน | **หลัง** | |
|--|--|--|--|
| `yuv_nv21` | 63.9 ms/เฟรม | **9.5** | **−85%** |
| `worker_idle` | 109.6 ms/เฟรม | **27.5** | **−75%** |
| wall (process) | 52,486 ms | **29,917 ms** | **−43%** |
| **`realtimeRatio`** | **0.881** | **0.502** | **−43%** |
| เวลา import ทั้งงาน | 53.2 s | **30.5 s** | |
| `kept` | 15 | 15 | เท่ากันเป๊ะ |

**เกินเป้า** — v0.1.4 ทำนายว่า "ลดครึ่งเดียว → ratio ~0.60" ผลจริงลดได้ 85% และได้ **0.502**

**แก้อะไร:** ลูปเดิมวน `ByteBuffer.get(int)` ทีละไบต์ **2,073,600 รอบต่อเฟรม** ที่ 4K ของใหม่คัดลอกทีละแถวด้วย bulk `get()` เหลือ ~1,080 ครั้ง · ไม่แตะ NDK ไม่แตะ MediaCodec อยู่ในไฟล์เดียว

**🔑 บทเรียน: อย่าเดา layout ของ chroma** — เขียนรอบแรกโดยสมมติว่าอ่านไล่จาก plane V จะได้ `V U V U` ซึ่งจริงกับ NV21 แต่**เครื่องนี้ให้ NV12 (U มาก่อน)** ผลคือ chroma เลื่อนไปหนึ่งตัวอย่าง = สีเพี้ยนแบบที่**ไม่ crash ไม่มี test ไหนจับได้** ตอนนี้โค้ด**วัด layout จากของจริงทุกเฟรม** (เทียบ 64 คู่แรกกับลูปเดิม) แล้วเลือกทางที่ตรง ถ้าไม่ตรงสักทางก็ถอยไปใช้ลูปเดิม

**พิสูจน์ความถูกต้องสามชั้น:**
1. debug build เทียบผลลัพธ์กับลูปเดิม **byte ต่อ byte** 3 เฟรมแรกของทุก session → `verified byte-exact via FromUSwapped`
2. เฟรมที่ถูกเก็บ **ชื่อไฟล์ตรงกันทั้ง 15 ใบ** — เลือกเฟรมเดียวกันเป๊ะ
3. รูปที่ได้ **byte-identical** กับของเดิม (1,683,743 ไบต์ ตรงกันทุกไบต์)

**คอขวดย้ายที่แล้ว** — `queue_wait` ขยับจาก 0.3 เป็น 4.1 ms/เฟรม แปลว่า producer เร็วจนต้องรอคิวบ้าง งานหนักย้ายไปฝั่ง worker (`detect` · `jpeg_argb_detect`) ตามที่ควรเป็น

---

### แก้เพิ่มหลังตัดเวอร์ชัน 0.1.5

| | |
|--|--|
| **ชิปกรองสถานะในหน้า Upload โกหก** | ตัวเลขบนชิปนับจาก**ทั้งตาราง** แต่รายการกรองเฉพาะ**หน้าที่โหลดมา** (200 แถวล่าสุด) · คิวเกิน 200 ใบเมื่อไหร่ ชิปจะขึ้น `Abandoned 2` แล้วกดเข้าไปเจอรายการว่าง ซึ่งอ่านแล้วเหมือนแอปพัง ทั้งที่รูปมีอยู่จริงแค่เก่ากว่าหนึ่งหน้า · ย้ายไปกรองที่ฐานข้อมูล (`observePageByStatus`) และเพิ่มบรรทัด *"Showing the newest N of M"* เมื่อรายการถูกตัด |
| **ไฟล์ค้างใน cache** | ดูตาราง [ยังไม่ได้แก้](#ยงไมไดแกใน-015) ข้างบน |
| **`UploadSettings` แต่ละอินสแตนซ์ไม่รู้เรื่องกัน** | pause ผ่าน scheduler ไม่เคยขึ้นบนจอ และ worker ที่พักคิวเองเพราะ token ถูกปฏิเสธก็เงียบไปด้วย · แก้ด้วย `OnSharedPreferenceChangeListener` |
| **การแจ้งเตือนไม่มีตัวเลข** | HyperOS ตัด `contentText` ทิ้งทั้งบรรทัดเมื่อมี progress bar · ย้ายจำนวนกับ % ไปไว้ที่ `title` และชื่อ event ไว้ที่ `subText` |

### telemetry — perf schema 5 · 24/08/2026

**ปัญหา:** `perf_report.json` ถูกสร้างในหน่วยความจำทั้งก้อนแล้วเขียนครั้งเดียวตอน drain · session ที่ตายนาทีที่ 105 ของ 120 จึงเหลือ **ศูนย์** ไม่ใช่ "ไม่ครบ" (NA-05 ที่ comment ในโค้ดระบุว่าเกิดมาแล้วสองครั้ง) และตัวเลขที่เก็บก็ตอบคำถามหลังเกิดเหตุไม่ได้ — `usedRamMb` เป็น RAM **ทั้งเครื่อง** ไม่ใช่ของ process เรา

**ไฟล์ใหม่:** [PerfStream.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfStream.kt) · [PerfRecovery.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfRecovery.kt) · [ProcessVitals.kt](../androidApp/src/main/kotlin/com/autobots/camera/load/ProcessVitals.kt) · [PowerReader.kt](../androidApp/src/main/kotlin/com/autobots/camera/load/PowerReader.kt) · [SysfsProbes.kt](../androidApp/src/main/kotlin/com/autobots/camera/load/SysfsProbes.kt) · [CrashDiagnostics.kt](../androidApp/src/main/kotlin/com/autobots/camera/diag/CrashDiagnostics.kt) · [SessionRecovery.kt](../androidApp/src/main/kotlin/com/autobots/camera/diag/SessionRecovery.kt) · [AutobotsApplication.kt](../androidApp/src/main/kotlin/com/autobots/AutobotsApplication.kt)

**1. write-ahead log** — `perf_stream.jsonl` ใน session dir · หนึ่งบรรทัดหนึ่ง record · **flush ทุกบรรทัด** (buffer จะกลืนวินาทีสุดท้ายก่อน crash ซึ่งคือส่วนเดียวที่มีคนอ่าน) · เขียนต่อ **chunk** ไม่ใช่ต่อเฟรม — TC-16 คือ ~300 บรรทัด ไม่ใช่ 22,643 · เพิ่ม **heartbeat ทุก 30 วินาที** เพื่อผูกเวลาตายให้แคบกว่าความยาว chunk

**2. กู้รายงาน** — `SessionRecovery` กวาดตอนเปิดแอป: session dir ที่มี `.jsonl` แต่ไม่มี `perf_report.json` = ตายก่อน drain → parse กลับเป็น data class เดิม แล้ว replay ผ่าน `PerfReport.render()` **ตัวเดียวกัน** (ไม่เขียน renderer ที่สอง — มันจะ drift แล้วไปโผล่ตอนอ่านรายงานของ crash ที่กำลังสืบอยู่พอดี) · ผลลง `Download/AutoBots/recovered_<sessionId>/`

> **stream เก็บครบกว่ารายงาน** — เขียนก่อนที่ `MAX_FRAME_DIAGS`/`MAX_EVENTS`/`loadStride` จะตัด เพราะ cap พวกนั้นมีไว้กัน RAM และ stream ไม่ได้อยู่ใน RAM · รายงานที่กู้มาจึงอาจ**สมบูรณ์กว่า**ที่ session จะเขียนเองถ้ารอด

**3. ตายเพราะอะไร** — `ApplicationExitInfo` อ่านตอนเปิดแอป → `last_exit.json` · เหตุผลที่ต้องมี: native crash (MediaCodec/QNN) กับ LMKD kill คือสองแบบที่**น่าจะเกิดที่สุด**กับ pipeline นี้ และเป็นสองแบบที่ `UncaughtExceptionHandler` จับไม่ได้เลย — ไม่มีโค้ดเราทำงานตอนตาย · มี crash handler ด้วยแต่เป็นของแถม และ **chain ต่อ handler เดิมเสมอ** ไม่งั้นจะไปปิด crash reporting ของ platform ซึ่งเป็นตัวที่เติม `ApplicationExitInfo` ให้เราตั้งแต่แรก

**4. ตัวเลขใหม่ (schema 4 → 5, เพิ่มอย่างเดียว เทียบกับรายงานเก่าได้ตรงๆ)**

| block | ตอบคำถาม |
|--|--|
| `deviceLoad[].proc` · `totals.memory` | **แอปเรา**กินเท่าไร · Java heap / **native heap (bitmap 4K อยู่ตรงนี้)** / graphics / PSS |
| `totals.cpu` | CPU ที่ใช้จริง + **แยกตาม role ของเธรด** — แยก "detect worker ทำงานเต็มที่" ออกจาก "detect worker รอ" ซึ่ง `queue_wait` แยกไม่ได้ |
| `totals.thermal` | อุณหภูมิแบต °C · SoC °C (ถ้าเครื่องยอมบอก) · **thermal headroom** ซึ่งนำหน้าระดับ 0–6 ที่รายงาน OK ตลอด TC-06 ทั้งที่ throttle ไป 23% |
| `totals.power` | mAh / mWh / **mAh ต่อรูป** |
| `env.probes` | เครื่องนี้ยอมบอกอะไรบ้าง — `socTempC` หายไปทุก sample จะได้อ่านว่า *"เครื่องไม่บอก"* ไม่ใช่ *"ไม่ร้อน"* |

**⚠️ พลังงานวัดได้เฉพาะรอบที่ถอดสาย** — charge counter ตอนชาร์จมัน**เพิ่มขึ้น** รอบที่เสียบสายจะได้ค่าติดลบที่หน้าตาเหมือนผลวัด · ทุก sample เก็บ `charging` ไว้ และถ้ามีสักตัวที่เสียบอยู่ `totals.power` จะเขียน `energyMeasured: false` พร้อมเหตุผล **แทนที่จะเดา** · แปลว่าถ้าจะได้ตัวเลขพลังงานจริง ต้องเพิ่มรอบเทสแบบถอดสาย

**สถานะ:** build ผ่าน · **ยังไม่ได้รันบนเครื่อง** (ไม่มี device ต่ออยู่ตอนเขียน) — วิธีตรวจอยู่ใน [BUILD.md](./BUILD.md)

### 🟡 ทำได้เลย ไม่ต้องรอใคร

- **แถว `Abandoned` ยังลบทีละใบไม่ได้** — เหตุผลของแต่ละใบแสดงอยู่ในแถวแล้ว และกรองด้วยชิปได้ถูกต้องแล้ว แต่ยังมีแต่ Retry แบบเหมารวม
- **calibrate สูตร estimate** — มีตัวเลขจากการรันจริง 4 ครั้งของ 20/08/2026 พอจะปรับค่าคงที่ได้
- **v0.1.6 ยังไม่ตัด** — [ร่าง release doc](./RELEASE_0_1_6.md) เขียนรอไว้แล้ว
- `yuv420ToNv21` 61.6% ของ wall — ก้อนใหญ่ ควรแยกเป็นรอบของตัวเอง

---

## งาน upload (B3) แบบเต็ม

> ย้ายมาจากร่าง `RELEASE_0_1_6.md` เมื่อ 25/08/2026 · ตอนที่เขียนร่างนั้น ตั้งใจจะตัด upload เป็น 0.1.6
> แต่ `appVersionName` ไม่เคยถูก bump — งาน B3 ทั้งหมดจึงรันอยู่บนบิลด์ที่ขึ้นแบนเนอร์ **v0.1.5**
> และรายงานภาคสนาม TC-16..19 ก็ทดสอบบนบิลด์นั้น จุดตัด 0.1.5 คือ `0636e21`

**✅ ยิงขึ้น production จริงแล้ว** (`api.photo.thai.run` · event `test-upload`) — 15 ใบจาก import คลิป 1 นาที 4K · PUT 15 · complete 15 · fail 0

## เพิ่ม

- **คิวอัปโหลดบนดิสก์** (Room v2) — รูปที่ส่งมอบทุกใบเข้าคิวทันทีที่ publish ลง MediaStore เก็บเป็น `content://` เพราะไฟล์ cache ถูกลบไปแล้วตอนนั้น
- **หกสถานะ** `Pending → Uploading → Uploaded → Success` + `Failed` (ลองใหม่ได้) + `Abandoned` (เลิกถาวร) · `Uploaded` คือสถานะที่บอกว่า "ไฟล์ขึ้นไปแล้ว เหลือแค่แจ้ง" ซึ่งทำให้ retry ไม่ส่งไฟล์ซ้ำ
- **`RunxUploadTransport`** — presign (GraphQL `photoUpload`) → PUT (signed URL) → complete (form POST คนละ host)
- **Sign in** ด้วย username/password (`authAdminUser`) — **token ไม่ลงดิสก์เลย** ต้อง login ใหม่ทุกครั้งที่เปิดแอป · ติ๊ก "จำ username/password" ได้ (ปิดเป็นค่าเริ่มต้น)
- **เลือก event จาก dropdown** — `eventItems` ตามหน้าจนครบ (เพดาน 10 หน้า) และประกาศบนจอเมื่อโดนตัด
- **หน้า Upload** — สรุปคิว · ตัวกรองตามสถานะ · Pause/Resume · Retry · ทางเข้าหน้าตั้งค่า
- **การ์ด Upload บนหน้าแรก** — ใครส่งเข้างานไหน · แถบ progress · **สวิตช์ auto-upload ที่ล็อกระหว่างถ่าย**
- **`.env`** → `BuildConfig` → ค่าเริ่มต้นในแอป · URL สองตัวมาจาก `.env` ทุกครั้งที่เปิดแอป ที่เหลือ seed ครั้งแรกครั้งเดียว
- **จอไม่ดับระหว่างถ่าย** — `keepScreenOn` ผูกกับ `isCapturing`

## เปลี่ยน

- **worker ไม่ทำงานเมื่อยังไม่ได้ตั้งค่าหรือยังไม่ login** — ไม่ตกไปใช้ fake sink · fake transport ต้องเปิดเองด้วย debug deep link
- prefs ของ upload ถูก **กันออกจาก backup** ทั้ง cloud และ device transfer เพราะอาจมีรหัสผ่านอยู่

## แก้

- **`UploadSettings` แต่ละตัวไม่รู้เรื่องกัน** — มี prefs ไฟล์เดียวแต่มีอ็อบเจกต์หลายตัว (ViewModel · worker · scheduler) แต่ละตัวถือ `StateFlow` ของตัวเอง การ pause ผ่าน scheduler จึงไม่เคยขึ้นบนจอ และการที่ worker พักคิวเองเพราะ token ถูกปฏิเสธก็เงียบไปด้วย · แก้ด้วย `OnSharedPreferenceChangeListener`
- **build default ถูกเอามาทับซ้ำๆ** — `.env` ถูก apply ใหม่ทุกครั้งที่มีการสร้าง `UploadSettings` ทำให้ค่าที่เพิ่งแก้หายไปได้จาก background thread · ตอนนี้ทำครั้งเดียวต่อโปรเซส
- **QNN asset extraction ไม่ atomic** — ไฟล์ครึ่งใบที่ดูเหมือนสมบูรณ์ทำให้ NPU พังถาวรจนกว่าจะล้างข้อมูลแอป · เขียนลง `.tmp` แล้ว rename

---

## ยังไม่ได้ทำใน 0.1.6

| | |
|--|--|
| 🔴 **คิวรอดไหมตอนจอดับ / แอปอยู่หลัง** | ยังไม่ทดสอบ · ถ้าไม่รอดต้องมี foreground service ([PHASES.md §2.6](./PHASES.md)) |
| 🟠 **live capture → production** | ทดสอบขึ้นจริงเฉพาะเส้นทาง import · live capture พิสูจน์กับ fake transport เท่านั้น |
| 🟠 **requeue = object ซ้ำ** | server มินต์ key เอง · แก้ได้ถ้า `$path` ใช้ได้ (คำถามข้อ 2) |
| 🟡 **`Unauthorized` → พักทั้งคิว** | ยังไม่เคยเกิดกับ backend จริง |
| 🟡 **แถว `Abandoned`** | ไม่มี UI ให้เคลียร์ |

หนี้เก่าที่ยกมาจาก 0.1.5: `yuv420ToNv21` 61.6% ของเวลา · `estimateImportWallMs` ต่ำไป 30–40% · `cacheDir/network_import/` ไม่เคยถูกล้าง · `usesCleartextTraffic` เปิดทั้งแอป

---

## ถัดไป

> ย่อหน้านี้เขียนไว้ตอนตัดเวอร์ชัน 0.1.5 ซึ่ง B3 ยังไม่เริ่ม · ตอนนี้ B3a–B3f ทำเสร็จแล้ว ดู [สถานะงาน upload](#สถานะงาน-upload--20082026) ข้างบน

**B3 — upload** คือสิ่งที่ 0.1.5 ปูทางไว้ให้ครึ่งหนึ่งแล้ว: `VideoHttp` กับ `RemoteVideoFetcher` ได้พิสูจน์รูปแบบของ HTTP layer ในโปรเจกต์นี้ไปแล้ว (header แบบเบราว์เซอร์ · แยก validate/head/probe/transfer ออกจากกัน · fallback ที่ตัดสินจากชนิดของ error ไม่ใช่จากการลองซ้ำ) ขาออกน่าจะเดินทางเดียวกันแบบกลับด้าน

ก่อนถึงตรงนั้น ลิสต์ "ยังไม่ได้แก้" ข้างบนมีสองข้อที่ควรปิดก่อน — **TC-11** เพราะเป็นความเสี่ยง ship ของพัง และ **calibrate สูตร estimate** เพราะตอนนี้ UI กำลังบอกตัวเลขที่รู้อยู่แล้วว่าผิดให้ผู้ใช้ตัดสินใจ
