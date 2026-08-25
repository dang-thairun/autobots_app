# v0.1.6 — Framing, Light and Survivability

> รายงานการเปลี่ยนแปลงจาก **v0.1.5** → **v0.1.6**
> diff: `0636e21` → `c6ac423` · 34 ไฟล์ · +4,154 / −466 · ตัดเวอร์ชัน 25/08/2026
>
> **นี่คือ feature release** — ไม่มี TC ใหม่ ไม่มีตัวเลข perf ใหม่ ทุกอย่างที่ค้างใน 0.1.5 ยังค้างเหมือนเดิม
>
> **⚠️ งาน upload (B3) ไม่ได้อยู่ในเวอร์ชันนี้** — มันรันอยู่บนบิลด์ v0.1.5 มาตั้งแต่แรก และย้ายไปอยู่ใน
> [RELEASE_0_1_5.md](./RELEASE_0_1_5.md#งาน-upload-b3-แบบเต็ม) แล้ว · ร่าง 0.1.6 เดิมที่เขียนถึง upload ถูกแทนที่ด้วยเอกสารฉบับนี้
>
> **⚠️ `appVersionName` ถูก bump เป็น 0.1.6 พร้อมกับเอกสารนี้** — ชื่อโฟลเดอร์อัลบั้มจะเปลี่ยนจาก `v0_1_5_*` / `ext_v0_1_5_*` เป็น `v0_1_6_*` ตั้งแต่รอบถัดไป

---

## สรุปผู้บริหาร

0.1.5 ทำให้ pipeline **รับงานเข้าได้สามทางและส่งงานออกได้จริง** ทั้ง live capture, ไฟล์ในเครื่อง, Network URL แล้วอัปขึ้นแพลตฟอร์ม แต่การตัดสินว่า "เฟรมไหนควรเก็บ" ยังอยู่บนสมมติฐานเดียวที่ PRD เขียนไว้ตั้งแต่ต้น: *หน้าที่ใหญ่ที่สุดคือตัวเอกของ passage นี้*

หน้าตอบได้ว่า **มีคนอยู่ไหม** แต่ไม่เคยตอบว่า **ภาพนี้ใช้ได้ไหม** — เกตทุกด่านวัดอยู่ในกรอบใบหน้าล้วน ๆ เฟรมที่หน้าคมเป๊ะแต่ตัวถูกตัดที่ขอบ หรือหน้าที่ใหญ่ที่สุดเป็นคนดูข้างเลนไม่ใช่นักวิ่ง ผ่านฉลุยทั้งคู่ และตอนตี 4 ที่แสงไม่พอ AE จะยืดชัตเตอร์จนนักวิ่งกลายเป็นรอยเปื้อน ซึ่งตายที่ด่าน sharpness แล้ว session ก็ **ไม่ได้รูปเลยโดยไม่มีอะไรบอก**

0.1.6 คือการเติมสิ่งที่หายไปสามชั้น — เลือกให้ตรงตัว, เห็นให้ทัน, และรอดให้ถึงรายงาน

| | v0.1.5 | **v0.1.6** |
|--|--|--|
| **เกณฑ์คัดเฟรม** | หน้าอย่างเดียว หรือท่าทางอย่างเดียว | + **Face + Pose พร้อมกัน** — ต้องผ่านทั้งคู่ |
| **ขอบเขตการตรวจ** | ทั้งเฟรมเสมอ | + **Capture Zone** ที่ลากเองบนภาพจริง |
| **กล้อง** | ไม่มีการตั้งค่าใด ๆ เลย | + **เพดานชัตเตอร์ · exposure compensation** |
| **preview** | ติดเมื่อกด Start (= เริ่มอัด) | ติดทันทีที่เข้าหน้า Live |
| **หลังโปรเซสตาย** | ไม่เหลืออะไรเลย | **`perf_stream.jsonl`** + กู้รายงานย้อนหลัง |
| **realtime ratio** | ไม่ขึ้นบนจอ | ขึ้นทั้งตอนรันและใน Session history |
| **ปุ่ม Gallery** | เด้งกลับทันที | เปิดแกลเลอรีที่หน้าแรกจริง ๆ |

---

## การเปลี่ยนแปลง

### 1. ⭐ Face + Pose — ต้องผ่านทั้งคู่

**ไฟล์:** [ExtractionTarget.kt](../shared/src/commonMain/kotlin/com/autobots/camera/ExtractionTarget.kt) · [VideoFrameProcessor.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/VideoFrameProcessor.kt)

`ExtractionTarget.FaceAndPose` เป็นค่าที่สาม ไม่ใช่ `Set` — DB เก็บเป็นชื่อ enum อยู่แล้ว และ `when (target)` ที่ exhaustive ทั่วโค้ดกลายเป็นเครื่องมือให้คอมไพเลอร์ไล่ชี้ทุกจุดที่ต้องแก้เอง (เจอ 5 จุด)

```
uprightDetectBitmap()          ← decode ครั้งเดียว ใช้ร่วมกัน
   ├─ face.detect()            ← NPU · ตกด่าน no_subject / too_small → จบ
   ├─ pose.detect()            ← CPU · เฉพาะเฟรมที่ผ่านหน้าแล้ว
   recycle
   uprightFullFrame()          ← full-res decode ยังจ่ายทีหลังสุดเหมือนเดิม
```

**หน้าเป็นด่านแรกเสมอ** เพราะคัดทิ้งเยอะที่สุด และบน LiteRT มันคัดทิ้งอยู่บน NPU ต้นทุน pose ที่เพิ่มเข้ามาจึงเป็นสัดส่วนกับจำนวนเฟรมที่ "มีหน้าใหญ่พอ" ไม่ใช่จำนวนเฟรมทั้งหมด ถ้าปล่อยให้เป็นสองพาสจะจ่ายค่า decode+scale ซ้ำทุกเฟรมฟรี ๆ

ด่าน "เห็นตัวครบ" ไม่ได้บังคับให้เห็นขา — [FIELD_SETUP.md](./FIELD_SETUP.md) ตั้งกล้องที่ระดับอก–หัว ข้อเท้าจึงอยู่นอกเฟรมในการตั้งกล้องที่ถูกต้อง ใช้สองสัญญาณนี้แทน:

- `OfflinePoseDetector` บังคับอยู่แล้วว่าต้องเห็นไหล่สองข้างและสะโพกสองข้าง (`inFrameLikelihood ≥ 0.5`) — ตรวจเจอ torso = ลำตัวครบ
- `isFullyFramed` — ลำตัวต้องห่างขอบเฟรม ≥ 2% กันเคสวิ่งชิดขอบเลนจนตัวโดนตัดข้าง

**sharpness ยังวัดที่ ROI ของหน้า** ในโหมดรวม ถ้าไปวัด union กับลำตัว ค่าจะถูกเจือจางด้วยเนื้อผ้าเรียบ ๆ แล้ว `MIN_SHARPNESS` ทุกค่าที่จูนมาจะใช้ไม่ได้ทันที

### 2. ⭐ Capture Zone — ลากพื้นที่ตรวจจับเอง

**ไฟล์:** [DetectZone.kt](../shared/src/commonMain/kotlin/com/autobots/camera/DetectZone.kt) *(ใหม่)* · [ZoneEditorPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/ZoneEditorPage.kt) *(ใหม่)*

PRD เรียกสิ่งนี้ว่า **Capture Zone** มาตั้งแต่ต้นและทำเครื่องหมายไว้ว่า *"v0.1 path not wired in shell"* — 0.1.6 ต่อสายให้จริง

```
← Detect zone                 Reset
┌───────────────────────────────┐
│      [เฟรมจริงจากคลิป/กล้อง]  │
│    ◤━━━━━━━━━━━┓              │   ลากมุม ◤ ◢
│    ┃   ZONE    ┃              │   ลากกลางเพื่อย้าย
│    ┗━━━━━━━━━━━◢              │   นอกโซนหรี่ลง
└───────────────────────────────┘
  X 432   Y 576   W 1019   H 1979
```

- **เก็บเป็น normalized 0..1** ไม่ใช่ px — การเทียบเกิดใน detect space (เฟรมกว้าง 640), operator ลากเทียบกับความละเอียดต้นทาง, live capture ใช้อีกขนาดหนึ่ง มีแต่พิกัดสัดส่วนที่รอดทั้งสามแบบ
- **ตัดสินด้วยจุดกึ่งกลางกล่อง** ไม่ใช่ต้องอยู่ครบทั้งกล่อง ถ้าบังคับให้อยู่ครบ คนที่วิ่งเข้ามาใกล้จนหน้าใหญ่จะหลุดโซนง่ายกว่าคนอยู่ไกล ซึ่งกลับหัวกับสิ่งที่ต้องการ
- **กรองใบหน้าด้วยโซนก่อนแล้วค่อยหาใบใหญ่สุด** — หน้าที่ใหญ่ที่สุดในเฟรมอาจเป็นคนดูที่ยืนใกล้กล้องกว่านักวิ่งในเลน ถ้าเรียงก่อนกรอง เฟรมที่มีนักวิ่งดี ๆ อยู่ในโซนจะถูกทิ้งทั้งที่ไม่ควร
- **พื้นหลัง editor เป็นพิกเซลจริง** — เฟรมกลางคลิปสำหรับ import (ต้นคลิปงานวิ่งมักเป็นเลนว่าง) หรือกล้องสดสำหรับ live · ลากกรอบบนสี่เหลี่ยมเปล่าคือการเดาว่าเลนอยู่ตรงไหน
- **`MIN_SIDE = 0.1`** — ลากพลาดนิดเดียวได้โซน 5 px แล้วทั้ง session ไม่ได้รูปโดยไม่มีอะไรเตือน คือความพังที่เงียบที่สุดของฟีเจอร์นี้ · reject reason `out_of_zone` มีไว้ให้เห็นว่าโซนตัดไปเท่าไหร่
- **กรอบสีน้ำเงินทับ preview** ตอน live — `LiveZoneOverlay` คำนวณ scale/centring ซ้ำแบบ `FILL_CENTER` เพราะเฟรม 1080×1920 ถูกขยายจนล้นจอ ถ้าวาดเทียบขอบ view ตรง ๆ เส้นจะอยู่คนละที่กับที่ detector ตัดจริง — อันตรายกว่าไม่มีเส้น เพราะมันโกหก

ใช้ได้ทั้ง live capture และ import ทุกทาง

### 3. ⭐ แสง — เพดานชัตเตอร์ และ exposure compensation

**ไฟล์:** [VideoPreviewController.kt](../androidApp/src/main/kotlin/com/autobots/camera/VideoPreviewController.kt) · [OperatorShellScreen.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt)

ก่อนหน้านี้ **ไม่มีการตั้งค่ากล้องเลยแม้แต่ตัวเดียว** — ไม่มี `CONTROL_AF_MODE`, `CONTROL_AE_MODE`, exposure compensation หรือ FPS range · `Camera2Interop` ที่ผูกไว้เป็น callback อ่านค่าอย่างเดียว

งานวิ่งที่ออกตัวตี 4 และจบหลังพระอาทิตย์ขึ้นข้ามความสว่างเป็นร้อยเท่า และ AE ตอบความมืดด้วยการ **ยืดชัตเตอร์**:

| ช่วงเวลา | อาการ | ผลต่อ pipeline |
|--|--|--|
| ตี 4–5 | AE ยืดถึง 1/30 s | นักวิ่งเบลอ → ตายที่ `MIN_SHARPNESS` → **ได้ 0 รูปแบบเงียบ** |
| 6 โมง | ฟ้าสว่างหลังนักวิ่ง | หน้าเป็นเงา → คมพอผ่านด่าน แต่ขายไม่ได้ |

```
Light
Shutter                    [ Auto ] [ 1/30 ] [ 1/60 ]
Exposure                        −   +1.0 EV   +
4.9mm · 1/33 · ISO 2240              ← ค่าที่เซนเซอร์เลือกจริง
```

- **เพดานชัตเตอร์** = ปักหมุด `CONTROL_AE_TARGET_FPS_RANGE` พอ AE ชนเพดานมันจะไปดัน ISO แทน — **noise ยังขายได้ ความเบลอไม่ได้**
- **EV** แสดงเป็นสตอป ไม่ใช่ index ดิบที่ไม่มีความหมายกับคน
- ทั้งคู่ไปทาง `Camera2CameraControl` บน repeating request จึงไม่ต้อง rebind และ **re-apply ทุกครั้งหลัง bind** เพราะเปลี่ยน resolution = rebind แล้วค่าจะกลับเป็น auto เงียบ ๆ ซึ่งแย่กว่าไม่มีฟีเจอร์
- `CameraCapabilities` ที่เดิมอ่านแล้ว log ทิ้ง ตอนนี้ถึง UI — ปุ่มที่เครื่องไม่ประกาศรองรับถูก disable แทนที่จะส่งค่าที่กล้องจะปฏิเสธ (**บน Xiaomi peridot ปุ่ม 1/60 ปิดตัวเอง**)
- **readout สดโผล่บนจอมือถือครั้งแรก** — เดิมมีแต่บนหน้าเว็บของ `AutobotsServer` · บนขาตั้งตอนตี 5 ค่าชัตเตอร์คือทั้งหมดของเรื่อง และมันคือค่าที่เซนเซอร์เลือกจริง ไม่ใช่ค่าที่เราขอ

**เฉพาะ live capture** — คลิปที่ import มาถูกเปิดรับแสงไปแล้วก่อนถึงเครื่องนี้ session ของ import จึงไม่บันทึกค่าพวกนี้เลย เพราะจะเป็นคำโกหกในรายงาน

ตรวจแล้วว่าค่าถึงฮาร์ดแวร์จริง โดยอ่าน `CONTROL_AE_EXPOSURE_COMPENSATION` และ `CONTROL_AE_TARGET_FPS_RANGE` กลับจาก `TotalCaptureResult` → `ev=-9` (step 1/6 EV) · `fpsRange=[30, 30]`

### 4. preview ติดทันทีที่เข้าหน้า Live

**ไฟล์:** [CameraPreviewPane.kt](../androidApp/src/main/kotlin/com/autobots/ui/CameraPreviewPane.kt)

แยก `active` (ผูกกล้อง เห็นภาพ) ออกจาก `recording` (เขียน chunk) เดิมทั้งสองอย่างเป็นตัวแปรเดียวกัน แปลว่าจะเล็งขาตั้งให้ตรงเลนต้องกด Start ซึ่งเริ่มบันทึกไปด้วย

**บั๊กที่เจอตอนทำ:** `VideoPreviewController.unbindCamera()` เรียก `provider.unbindAll()` ซึ่งปลด use case ของ **ทั้งแอป** ไม่ใช่เฉพาะของตัวเอง พอย้ายหน้าจาก Live ไป zone editor ตัวเก่าจะ stop แบบ async แล้ว callback ไปตกหลัง pane ใหม่ bind เสร็จ → ปลดของใหม่ทิ้ง ไฟกล้องติดแต่จอดำ · แก้โดยจำ use case ที่ตัวเองผูกไว้แล้ว `provider.unbind(*mine)`

### 5. realtime ratio กลับมาแสดง

**ไฟล์:** [OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt) · [PipelineSessionRecord.kt](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt)

ตัวเลขไม่เคยหาย — coordinator ส่งมาตลอด แต่ `throughputLine` มีบรรทัดนี้:

```kotlin
if (sessionHistory.any { it.source == SessionSource.VideoImport }) return ""
```

ตั้งใจให้เป็น "live capture only" แต่ผลจริงคือซ่อนทุกกรณีที่เป็น import และเพราะเช็คทั้งลิสต์ไม่ใช่เฉพาะรันปัจจุบัน มันจึงซ่อนของ **live capture ด้วย** เมื่อไหร่ก็ตามที่เคย import อะไรไปแล้ว

ตอนนี้แสดงทุก source · คำเตือน `TOO SLOW` ยังเป็นของ live เท่านั้น เพราะมีแต่ live ที่ตกขบวนแล้วคิวพอกขึ้นเรื่อย ๆ ส่วน import ที่เกิน 1× แค่ช้า ไม่มีอะไรพอกอยู่ข้างหลัง

`PipelineSessionRecord.realtimeRatio` คำนวณจากข้อมูลที่มีอยู่แล้ว (`processDurationMs / footageDurationMs`) นิยามเดียวกับใน `perf_report.json` — ตัวเลขบนจอกับในรายงานเทียบกันได้ตรง ๆ ไม่ต้องแปลง

### 6. รอดให้ถึงรายงาน — perf stream · crash diagnostics · session recovery

**ไฟล์:** [PerfStream.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfStream.kt) · [PerfRecovery.kt](../androidApp/src/main/kotlin/com/autobots/camera/perf/PerfRecovery.kt) · [CrashDiagnostics.kt](../androidApp/src/main/kotlin/com/autobots/camera/diag/CrashDiagnostics.kt) · [SessionRecovery.kt](../androidApp/src/main/kotlin/com/autobots/camera/diag/SessionRecovery.kt) · [AutobotsApplication.kt](../androidApp/src/main/kotlin/com/autobots/AutobotsApplication.kt) *(ใหม่ทั้งหมด)*

`perf_report.json` ถูกสร้างในหน่วยความจำและเขียนครั้งเดียวตอน drain — session ที่ตายที่นาทีที่ 105 จาก 120 จึงไม่เหลืออะไรเลย ไม่ใช่ไฟล์ครึ่งใบ แต่คือ **ไม่มีไฟล์**

- **`perf_stream.jsonl`** — write-ahead log บรรทัดละ JSON object
- **`PerfRecovery`** — สร้าง `perf_report.json` ย้อนหลังจาก stream โดย parse กลับเป็น data class เดิมแล้ว replay ผ่าน `PerfReport` ตัวจริง ไม่มีโค้ดสร้างรายงานซ้ำสองที่
- **`CrashDiagnostics`** — สาเหตุการตายของโปรเซสก่อนหน้า ทั้ง uncaught exception พร้อม stack และ `ApplicationExitInfo` ซึ่งเป็นแหล่งเดียวที่เห็น native crash และ low-memory kill
- **`SessionRecovery`** — ที่ startup: โฟลเดอร์ที่มี stream แต่ไม่มี report คือ session ที่ตายก่อน drain ตามนิยาม
- **`ProcessVitals` · `PowerReader` · `SysfsProbes`** — วัดที่ scope ของ **โปรเซสเรา** ไม่ใช่ทั้งเครื่อง (`/proc/self`) · charge counter บันทึก `isCharging` ทุก sample เพราะเครื่องที่เสียบสายอยู่จะให้ตัวเลขพลังงาน**ผิดเครื่องหมาย** · probe ทุกตัว resolve ครั้งเดียวตอน construct แล้วเป็นได้แค่ "ใช้ได้" หรือ "ปิดถาวร" — diagnostic ที่อาจทำงานแย่กว่า diagnostic ที่บอกได้ว่าตัวเองทำงานไหม

### 7. UI — home grid · toggle · stat chip · gallery

**ไฟล์:** [OperatorShellScreen.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt) · [AutobotsSwitch.kt](../androidApp/src/main/kotlin/com/autobots/ui/AutobotsSwitch.kt) *(ใหม่)* · [StatChip.kt](../androidApp/src/main/kotlin/com/autobots/ui/StatChip.kt) *(ใหม่)* · [GalleryLauncher.kt](../androidApp/src/main/kotlin/com/autobots/camera/delivery/GalleryLauncher.kt)

```
CAPTURE ─────────────────────      REVIEW ──────────────────────
┌────────┬────────┬────────┐      ┌────────┬────────┬────────┐
│   ▶    │   📁   │   🔗   │      │   🕘   │   🖼   │   ☁   │
│  Live  │ Browse │Network │      │History │Gallery │ Upload │
└────────┴────────┴────────┘      │   1    │   15   │   15   │
                                   └────────┴────────┴────────┘
```

- **home เป็น grid สองกลุ่ม** — ตัวเลขที่ตัดสินว่าปลายทางไหนน่าเปิดย้ายมาอยู่บน tile เดิมกระจายอยู่ในวงเล็บท้ายป้ายบ้าง ในการ์ดด้านบนบ้าง
- **`AutobotsSwitch`** แทน Material switch ทุกตัว ของเดิมกำหนดแต่สีตอนเปิดแล้วปล่อยให้ธีม tint ตอนปิด ซึ่งบนจอมืดอ่านว่า "เปิดอยู่มั้ง" ทั้งสองสถานะ และสามหน้าใช้คนละสีเปิดกัน · disabled ใช้สีเดิมลด alpha — "ปิดอยู่" กับ "แก้ไม่ได้ตอนนี้" ต้องไม่หน้าตาเหมือนกัน
- **`StatChip`** ใช้ร่วมกันระหว่างแถบ live capture กับ import preview · การ์ดไฟล์ใน import preview กลายเป็น `Type · Size · Res · FPS · Dur.` โดย `Res` แสดง `WxH` หลังหมุนภาพจริง ไม่ใช่แท็ก `4K` ที่ซ่อนว่าคลิปเป็นแนวตั้ง
- **ปุ่ม Gallery** — เดิมยิง `ACTION_VIEW` ใส่รูปใบล่าสุด ซึ่ง resolve ไปที่ตัวดูรูปใบเดียว (Photos Go `ExternalOneUpActivity`) มันเปิด อ่านไฟล์ แล้ว `finish()` ตัวเองทันที เด้งกลับมาในพริบตา ดูเหมือนปุ่มไม่ทำงาน · ตอนนี้ใช้ `CATEGORY_APP_GALLERY` เปิดแกลเลอรีที่หน้าแรกของมัน

---

## ยังไม่ได้ทำใน 0.1.6

| | |
|--|--|
| 🔴 **แสงตอนแสงน้อยจริง** | เพดานชัตเตอร์ยืนยันแค่ว่าค่าถึงฮาร์ดแวร์ · **ยังไม่เคยวัดผลกับฉากที่มืดพอดีและมีคนวิ่ง** ต้องรันหน้างานตี 4–6 |
| 🔴 **AND gate ยังไม่เจอสนามจริง** | ทดสอบกับ `run1mins.mp4` เท่านั้น · ยังไม่รู้ว่ามันตัด yield ลงเท่าไหร่ในกลุ่มนักวิ่งที่วิ่งเบียดกัน |
| 🟠 **pose ยังไม่ขึ้น NPU** | `OfflinePoseDetector` ฮาร์ดโค้ด ML Kit บน CPU · ปุ่ม backend ในแถว Pose จึงไม่มีผล และ `modelTag()` ขึ้น `pose_detection` ทั้งสามช่อง ตามความจริง · ตัวเลือกที่สำรวจไว้: CenterNet-Pose (MIT · พาสเดียว · อยู่ AI Hub) · RTMPose-t (Apache-2.0 · top-down) · RTMO |
| 🟠 **โซนยังไม่ครอปก่อน detect** | กรองหลัง detect เท่านั้น · การครอป detect bitmap ให้เหลือแค่โซนจะเร็วขึ้นจริง แต่ไปยุ่งกับแผน tile 640×480 ของ `face_det_lite` |
| 🟠 **ค่าตั้งไม่ persist** | zone · เพดานชัตเตอร์ · EV อยู่ใน memory ล้วน ปิดแอปแล้วหาย |
| 🟡 **`estimateImportWallMs`** | ตัวคูณของโหมด Face+Pose (1.5) เดาไว้ ยังไม่ได้ calibrate · ของเดิมก็ต่ำไป 30–40% อยู่แล้ว |
| 🟡 **ความหนากรอบโซนเป็น px** | `ZoneOutlineWidthPx = 10f` เป็นหน่วย canvas ไม่ใช่ dp — เครื่องที่ความหนาแน่นพิกเซลต่างกันจะได้เส้นหนาไม่เท่ากัน |
| 🟡 **ล็อกโฟกัส** | ยังเป็น continuous AF ตามค่าเริ่มต้นของ CameraX · บนขาตั้งที่ระยะเลนคงที่ การล็อกน่าจะนิ่งกว่าในที่มืด แต่ต้องดู `afState` จริงก่อน |

หนี้เก่าที่ยกมาจาก 0.1.5: `yuv420ToNv21` 61.6% ของเวลา · `cacheDir/network_import/` ไม่เคยถูกล้างตามรอบ · `usesCleartextTraffic` เปิดทั้งแอป · แถว `Abandoned` ไม่มี UI ให้เคลียร์

---

## ถัดไป

สองอย่างที่ควรวัดก่อนจะเพิ่มอะไรอีก เพราะทั้งคู่เปลี่ยน yield โดยตรงและตอนนี้ยังเดาอยู่:

1. **รอบตี 4–6 หน้างานจริง** — เปิด `CamPerf` แล้วอ่าน `ExposureStats` ดูว่าชัตเตอร์ไปหยุดที่เท่าไหร่ ISO เท่าไหร่ · สลับ Auto ↔ 1/30 แล้วเทียบจำนวนรูปที่เก็บได้ · นี่คือข้อมูลชิ้นเดียวที่จะบอกว่าควรแตะ `MIN_SHARPNESS` ต่อไหม
2. **AND gate กับกลุ่มนักวิ่งจริง** — `cropped` และ `out_of_zone` ใน reject stats มีไว้ตอบข้อนี้โดยเฉพาะ ถ้ามันตัดทิ้งเยอะเกิน ทางแก้ที่เตรียมไว้คือย้าย pose จาก **ด่านเข้า** ไปเป็น **ตัวจัดอันดับ** ใน `closeWindow` — เลือกใบที่เห็นทั้งตัวขึ้นก่อนโดยไม่ทิ้ง passage ที่มีแต่ใบไม่สวย

ส่วน pose บน NPU ควรรอตัวเลขจากข้อ 2 ก่อน — เส้น export→quantize→QNN ไม่ใช่งานบ่ายเดียว ตามที่ [CHANGELOG.md](./CHANGELOG.md) บันทึกไว้เองตอนทำ `face_det_lite`

---

## Related

- [RELEASE_0_1_5.md](./RELEASE_0_1_5.md) — ingest ขาเข้า และงาน upload (B3) ทั้งหมด
- [FIELD_SETUP.md](./FIELD_SETUP.md) — การตั้งกล้องที่ทำให้ด่าน "เห็นตัวครบ" ไม่บังคับให้เห็นขา
- [PRD.md](./PRD.md) — Capture Zone · Passage Gate · Subject Face
- [BUILD.md](./BUILD.md) — วิธีเปิด `CamPerf` และอ่าน `perf_stream.jsonl`
