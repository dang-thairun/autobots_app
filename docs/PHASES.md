# Upload Pipeline — บันทึกการตัดสินใจ (B3)

> **เอกสารนี้ไม่ใช่แผนงานอีกแล้ว** — B3 ทำเสร็จและยิง production ผ่านแล้ว (B3a–B3e ✅ · B3f-1 ✅ 20/08/2026)
> สิ่งที่เหลืออยู่ในนี้คือ **เหตุผลว่าทำไมโค้ดถึงหน้าตาแบบนี้** และ **สัญญาที่ตกลงกับแพลตฟอร์ม**
> ซึ่งเป็นของที่หาไม่ได้จากที่อื่น: [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) บอกว่าระบบ*ทำงานยังไง*
> เอกสารนี้บอกว่า*ทำไมถึงเลือกทางนั้น* และทางไหนที่ลองแล้วไม่เวิร์ค
>
> **โค้ด 19 ไฟล์อ้างถึงหัวข้อในเอกสารนี้โดยตรง** (`docs/PHASES.md §2.1`, `§3`, `§9`, `§10`, `B3c` …)
> — ถ้าจะย้ายหรือเปลี่ยนเลขหัวข้อ ต้องไล่แก้คอมเมนต์พวกนั้นด้วย
>
> **งานที่ยังเหลือจริง ๆ อยู่ที่ [IMPLEMENTATION.md](./IMPLEMENTATION.md)** — ที่นี่เก็บแค่ B3f ไว้เป็นลิสต์สิ่งที่ต้องเฝ้าตอนออกงาน
>
> ⚠️ **สมมติฐาน "Cloudflare R2 + backend ที่เราเขียนเอง" ถูกยกเลิกไปแล้ว** — ปลายทางจริงคือแพลตฟอร์ม
> Runx (GraphQL presign → **Google Cloud Storage** → completion call) ที่มีอยู่แล้ว ไม่ได้เขียน backend เอง
> เอกสารนี้ยังพูดถึง R2 อยู่หลายที่เพราะเป็นบันทึกการตัดสินใจ **ตามลำดับเวลา** — ที่ไหนขัดกัน ให้ §10 ชนะเสมอ
>
> **ขอบเขต: อัปโหลด JPEG ที่ extract แล้วเท่านั้น** — วิดีโอ chunk 50 MB ไม่อัปโหลด ([§2.4](#24-ขอบเขต--รูปเทานน))
> **ไม่ลบไฟล์ในเครื่องไม่ว่ากรณีใด** — upload เป็นการ *คัดลอกขึ้นคลาวด์* ไม่ใช่การ *ย้าย* ([§2.5](#25--ไมลบไฟลในเครอง))

---

## สรุปสำหรับผู้ตัดสินใจ (เขียนไว้ก่อนเริ่มงาน — เก็บไว้เป็นบันทึก)

**ทำได้ และเสี่ยงต่ำกว่าที่คิด** เพราะ pipeline ปัจจุบันมี**จุดต่อเดียว**ที่ต้องแตะ — callback `onDelivered(uri)` ใน [WriteQueue.kt](../androidApp/src/main/kotlin/com/autobots/camera/delivery/WriteQueue.kt) ที่ยิงหลังรูปถูก publish ลง MediaStore สำเร็จ ทุกอย่างที่เหลือเป็นโมดูลใหม่ที่ไม่มีใครในเส้นทาง capture → extract → deliver รู้จัก

### การตัดสินใจที่ล็อกแล้ว

| # | เรื่อง | ผล |
|--|--|--|
| **1** | **ไฟล์ที่จะอัป** | **`content://` URI ของ MediaStore** ไม่ใช่ path ในระบบไฟล์ — cache JPEG ถูกลบทันทีหลัง publish ([§2.1](#21--ไฟลอยทไหนตอนถงคว)) |
| **2** | **สถานะในคิว** | **6 สถานะ** — เพิ่ม `UPLOADED` และ `ABANDONED` เข้ากับ 4 ตัวเดิม ([§3](#3-state-machine)) |
| **3** | **presign / complete** | รับเป็น **array** ตั้งแต่ v1 ([§2.2](#22--ขนาดของ-round-trip)) |
| **4** | ~~**object key deterministic**~~ | ❌ **กลับคำแล้ว** — ยิงทดสอบกับ API จริงพบว่า server มินต์ UUID ต่อท้ายเสมอ · แอปจึงต้อง**เก็บ key ที่ตอบกลับมา** ลง `remoteKey`/`remoteUri` ([§2.3](#23--object-key-deterministic)) |
| **5** | **WorkManager** | **หนึ่ง unique work ระบายทั้งคิว** ไม่ใช่หนึ่ง work ต่อไฟล์ ([§5](#5-สถาปตยกรรมทเสนอ)) |
| **6** | **ขอบเขต** | **รูปที่ extract แล้วเท่านั้น** — วิดีโอ chunk ไม่อัปโหลด ([§2.4](#24-ขอบเขต--รปเทานน)) |
| **7** | **ลบไฟล์หลังอัป** | **ไม่ลบ** — ตัดออกจากขอบเขตทั้งหมด ไม่มี toggle ([§2.5](#25--ไมลบไฟลในเครอง)) |
| **8** | **เครือข่าย** | Wi-Fi **และ** 4G ใช้ได้ทั้งคู่ → `NetworkType.CONNECTED` ไม่มี Wi-Fi-only toggle ([§2.6](#26--เครอขาย)) |
| **9** | **identity / token** | ตั้งค่าด้วย **QR** ใช้ `QrScanPreview` ที่มีอยู่ซ้ำ ([B3d](#b3d--ตงคา-endpoint--identity)) |

ข้อ 1 กับ 2 เป็นสองข้อที่ถ้าตัดสินผิดจะแก้ทีหลังไม่ได้ — ข้อแรกเพราะไฟล์หายไปแล้วจริงๆ ข้อที่สองเพราะมันคือความต่างระหว่างระบบที่ retry ได้กับระบบที่ทิ้งขยะใน R2 โดยไม่มีใครรู้

**ขนาดงานโดยประมาณ:** B3a–B3c (ครบวงจร ทดสอบได้ ไม่มี backend) เป็นก้อนที่ใหญ่ที่สุด · B3d–B3e (ต่อของจริง) เล็กกว่ามากถ้า contract ใน §4 ถูกล็อกไว้ก่อน

---

## 1. สภาพปัจจุบัน — สิ่งที่มีอยู่แล้วและสิ่งที่ยังไม่มี

### มีแล้ว ✅

| | รายละเอียด |
|--|--|
| `INTERNET` + `ACCESS_NETWORK_STATE` | ประกาศไว้แล้วใน [AndroidManifest.xml](../androidApp/src/main/AndroidManifest.xml) — WorkManager network constraint ใช้ได้ทันที |
| จุดต่อเข้า pipeline | `WriteQueue.onDelivered(uri)` — ยิงหลัง publish สำเร็จเท่านั้น และยิง **หลัง** ตัวนับ `pending` ลดแล้ว (มี comment อธิบายเหตุผลไว้ในโค้ด) |
| HTTP client | `ktor-client-*` อยู่ใน [libs.versions.toml](../gradle/libs.versions.toml) แล้ว (2.3.12) **แต่ยังไม่ถูก wire เข้า `androidApp`** — มีแต่ `ktor-server-*` ที่ใช้จริง |
| kotlinx-serialization | plugin + runtime พร้อม ใช้กับ request/response body ได้เลย |
| แบบแผน UI สำหรับตั้งค่า endpoint | [NetworkUrlPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/NetworkUrlPage.kt) + [QrScanPreview.kt](../androidApp/src/main/kotlin/com/autobots/ui/QrScanPreview.kt) — **สแกน QR เพื่อตั้งค่า backend URL + token ใช้รูปแบบเดียวกันได้ตรงๆ** |
| แบบแผน navigation | `OperatorDestination` enum จาก 0.1.5 — เพิ่ม destination ใหม่คือเพิ่ม enum + `when` branch |
| identity ของ session | [PipelineSessionRecord.kt](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt) มี session id, ชื่ออัลบั้ม, backend, เวลา — พอสำหรับเป็น metadata ของ upload |

### ยังไม่มี ❌ *(ณ ตอนเขียนแผน — ตอนนี้มีครบทุกข้อแล้ว ยกเว้นข้อสุดท้ายที่ถูกยกเลิก)*

> เก็บตารางนี้ไว้เป็นบันทึกจุดตั้งต้น · **สถานะปัจจุบัน: Room + KSP ✅ · WorkManager ✅ ·
> foreground service ✅ (B3f-1) · device identity ✅ (QR + login) · R2 ❌ ยกเลิก ใช้ Runx/GCS แทน**

| | ผลกระทบ |
|--|--|
| **Room + KSP** | KSP เป็น plugin ใหม่ของ build ต้องเพิ่มใน `libs.versions.toml`, root `build.gradle.kts` และ `androidApp/build.gradle.kts` · **ไม่เกี่ยวกับ CMake/NDK** — คนละ task graph เจอกันแค่ตอน package · ความเสี่ยงจริงคือ **KSP ผูกเวอร์ชันกับ Kotlin แบบ lockstep** (`<kotlin>-<ksp>`) ดังนั้นการอัป Kotlin ทุกครั้งหลังจากนี้ต้องอัป KSP ตาม ([§9](#9-เรอง-ksp--ทางเลอกของ-room)) |
| **WorkManager** | dependency ใหม่ · ดึง `androidx.work:work-runtime-ktx` เข้ามา |
| **Foreground service** | แอปยังไม่มี `<service>` เลยสักตัว · targetSdk = 35 ดังนั้นถ้า worker ต้องรันยาวและใช้ `setForeground()` ต้องเพิ่ม `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` และ merge `foregroundServiceType="dataSync"` เข้า `SystemForegroundService` ของ WorkManager |
| **device identity / token** | ยังไม่มีที่เก็บค่าคงทน (ไม่มี DataStore/SharedPreferences ในโปรเจกต์) |
| **Cloudflare R2 + backend** | ทั้งสองอย่าง — จึงต้องออกแบบให้ทดสอบได้โดยไม่มีมันทั้งคู่ (ดู B3c) |

---

## 2. สิ่งที่ต้องตัดสินใจก่อนเขียนโค้ด

### 2.1 ✅ ไฟล์อยู่ที่ไหนตอนถึงคิว

```kotlin
// WriteQueue.kt — โค้ดปัจจุบัน
val uri = writer.publish(file)
if (uri != null) {
    onDeliveredFile(file)
    file.delete()          // ← cache JPEG หายตรงนี้
    delivered = uri
}
```

ตัวเลือกที่มี:

| ทางเลือก | ผล |
|--|--|
| **A · เก็บ `content://` URI ของ MediaStore** *(แนะนำ)* | DCIM/AutoBots **เป็น** durable store อยู่แล้ว · uploader เปิดด้วย `contentResolver.openInputStream(uri)` · "Delete Local File (optional)" กลายเป็นการลบจาก MediaStore ซึ่งตรงกับสิ่งที่ผู้ใช้เห็นในแกลเลอรี · **ความเสี่ยง:** ผู้ใช้ลบรูปเองก่อนอัปเสร็จ → ต้องมีสถานะ `ABANDONED` |
| B · คัดลอกไว้ใน app-private ก่อน | ใช้ที่**สองเท่า** · แอปต้องการ 2 GB ว่างเพื่ออัดอยู่แล้ว และ session 4 นาทีให้รูป 134 ใบ · ไม่คุ้ม |
| C · ไม่ลบ cache แล้วให้ uploader ลบเอง | ผูก lifecycle ของ cache เข้ากับเครือข่าย — ถ้าออฟไลน์ยาว cache โตไม่จำกัด และไปชนกับ storage guard ของ pipeline |

**ตัดสินแล้ว: A** — แก้ `WriteQueue` ให้เรียก hook ใหม่พร้อม `uri` + ชื่อไฟล์ + sessionId ก่อน `file.delete()` ไม่ต้องแตะลำดับการลบเดิม

> **สิ่งที่ตามมาและต้องรับไว้** — MediaStore เป็นพื้นที่ที่ผู้ใช้ควบคุม ลบรูปจากแอปแกลเลอรีได้ทุกเมื่อ · uploader ต้องถือว่า `openInputStream` ล้มเหลว = ไฟล์หายถาวร ไม่ใช่ความผิดของเครือข่าย → เข้าสถานะ `ABANDONED` ทันทีโดยไม่ retry

### 2.2 ✅ ขนาดของ round trip

TC-12 (คลิป UHD 4.5 นาที) ให้รูปที่เก็บไว้ **134 ใบ** ถ้าออกแบบเป็น presign 1 ครั้งต่อ 1 ไฟล์ จะได้ `134 × 3 = 402` HTTP request ต่อ session — บนเครือข่ายสนามที่อาจเป็น 4G ที่มี latency 150–300 ms นั่นคือเวลาที่หมดไปกับ handshake มากกว่าการส่ง byte

**ตัดสินแล้ว: รับเป็น array ตั้งแต่ v1**

```
POST /uploads/presign   { items: [ {objectKey, contentType, sizeBytes}, ... ] }   ← รับเป็น array
POST /uploads/complete  { items: [ {objectKey, capturedAt, sessionId, ...}, ... ] } ← รับเป็น array
```

รูปแบบ array รองรับการส่งทีละใบได้อยู่แล้ว (array ยาว 1) แต่ scalar ขยายเป็น array ทีหลังไม่ได้โดยไม่ทำ API เวอร์ชันใหม่ — จึงรับ array ตั้งแต่แรกแม้ implementation รอบแรกจะส่งทีละใบก็ตาม

> ⚠️ **ผู้สมัคร transport ตัวจริง (Runx) ไม่รองรับ batch** — `photoUpload` คืน URL ทีละใบ ดังนั้นถ้าใช้ API นั้นจะกลับไปเป็น 3N request ต่อ session ตามที่ย่อหน้าบนเตือนไว้ ดู [§10](#10-transport-ทมของจรงแลว--runx-graphql)

**ขนาด batch ที่เสนอ: 20–50 รายการต่อ presign** · ใหญ่กว่านั้นเสี่ยงว่า URL ชุดท้ายๆ หมดอายุก่อนจะได้ PUT ถ้าเครือข่ายช้า (ดูเรื่อง `expiresAtMs` ใน [§4](#4-contract-ทลอกไวกอนได-ยงไมตองมี-backend))

### 2.3 ❌ Object key deterministic — **กลับคำแล้ว**

> **ข้อนี้ตัดสินไว้ผิด และของจริงหักล้างไปแล้ว** เก็บไว้ทั้งย่อหน้าเพราะเหตุผลข้างล่างยังถูกต้อง
> — สิ่งที่ผิดคือสมมติฐานว่าเราเป็นคนเลือก key ได้ · ข้อสรุปที่ใช้จริงอยู่ท้ายหัวข้อ

ถ้า backend สุ่ม UUID ใหม่ทุกครั้งที่ presign การ retry หลัง "ไม่รู้ว่าสำเร็จไหม" จะสร้าง object ซ้ำทุกครั้ง

```
{deviceId}/{sessionId}/{fileName}
```

> ⚠️ **ขึ้นกับ backend** — Runx `photoUpload` มีพารามิเตอร์ `$path: String` ในสกีมาแต่ client ปัจจุบันไม่ได้ส่ง · ถ้า `path` คือคีย์ของ object จริง ข้อนี้ใช้ได้ตามที่เขียน ถ้าไม่ใช่ ต้องเก็บคีย์ที่ server มินต์ลงแถวแทน ดู [§10](#10-transport-ทมของจรงแลว--runx-graphql)

**ผลจริงหลังยิงทดสอบ (ยกเลิกข้อสรุปเดิม):** `path` เป็น**โฟลเดอร์เท่านั้น** server ต่อ UUID เป็นชื่อไฟล์เสมอ
→ **deterministic key ทำไม่ได้บนแพลตฟอร์มนี้**

สิ่งที่ทำแทน และเป็นสิ่งที่รันอยู่จริง:

| | |
|--|--|
| แอปเก็บอะไร | `relativeKey` = `{sessionId}/{fileName}` — ใช้**กันแถวซ้ำในคิวของเราเอง** ไม่ใช่ชื่อ object |
| server ตั้งชื่อ object | UUID · แอปเก็บค่าที่ตอบกลับมาไว้ที่ `remoteKey` / `remoteUri` |
| ทำไมต้องเก็บ | ถ้าไม่เก็บ แถวที่ค้างสถานะ `Uploaded` จะปิดไม่ได้เลย — ไบต์อยู่ใน bucket โดยไม่มีใครเรียกชื่อมันถูก |
| ราคาที่จ่าย | **requeue รูปเดิมสร้าง object ซ้ำเสมอ** รูปไม่หาย แต่มีของกำพร้าค้างบน bucket · ยังไม่มีทางแก้ฝั่งแอป |

⚠️ **หมายเหตุที่ต้องตรวจกับโค้ด:** `RunxUploadTransport.presign` ส่งตัวแปร `path` ไปใน `variables` แต่ตัว
mutation ประกาศพารามิเตอร์แค่ `$provider` กับ `$mimeType` — ตามสเปค GraphQL ตัวแปรที่ไม่ได้ประกาศจะถูกทิ้ง
แปลว่าไฟล์อาจกองรวมที่รากของ bucket ไม่ได้แยกโฟลเดอร์ตามที่ตั้งใจ **ยังไม่ได้ตรวจกับ object จริง**

### 2.4 ✅ ขอบเขต — รูปเท่านั้น

**ตัดสินแล้ว: อัปโหลดเฉพาะ JPEG ที่ extract แล้ว วิดีโอ chunk ไม่อัป**

นี่ทำให้สถาปัตยกรรมทั้งหมดในเอกสารนี้ใช้ได้ตามที่เขียน:

| | ผลจากการตัดวิดีโอออก |
|--|--|
| **PUT ครั้งเดียวจบ** | JPEG ระดับ 100 KB–2 MB · ไม่ต้องใช้ multipart upload ของ R2 · ไม่ต้องเก็บ `uploadId` หรือรายการ part ที่สำเร็จในตาราง |
| **state machine เล็กลง** | 6 สถานะใน [§3](#3-state-machine) พอ · ไม่ต้องมีสถานะระดับ part |
| **contract เล็กลง** | ไม่ต้องมี `/uploads/multipart/*` |
| **ปริมาณคาดการณ์ได้** | session UHD 4.5 นาที ≈ 134 ใบ · เทียบกับ chunk 50 MB × N ที่โตตามความยาวคลิปแบบไม่มีเพดาน |

chunk วิดีโอยังถูกลบทิ้งหลัง extract ตามพฤติกรรมเดิมของ pipeline ตั้งแต่ 0.1.4 — ไม่มีอะไรต้องเปลี่ยน

### 2.5 ✅ ไม่ลบไฟล์ในเครื่อง

**ตัดสินแล้ว: upload ไม่ลบอะไรทั้งสิ้น** — ไม่มีทั้ง default และ toggle

สิ่งที่ตัดออกไปจากแผนเดิมเพราะข้อนี้:

| ตัดออก | เหตุผล |
|--|--|
| ขั้น *Delete Local File (optional)* ท้าย flow | ไม่มีอยู่แล้ว · `SUCCESS` เป็นสถานะปลายทาง |
| toggle *ลบไฟล์หลังอัปสำเร็จ* ในหน้าตั้งค่า | ไม่มี setting ที่ไม่มีคนใช้ |
| นโยบาย retention (ลบทันที / เก็บ N วัน) | ไม่ต้องออกแบบ |

**ผลข้างเคียงที่ต้องรู้:** DCIM/AutoBots จะโตขึ้นเรื่อยๆ ตามจำนวน session และ upload ไม่ได้ช่วยทวงคืนพื้นที่เลย · การจัดการพื้นที่ยังเป็นเรื่องของผู้ใช้กับแอปแกลเลอรีเหมือนเดิม ซึ่งตรงกับพฤติกรรมของแอปวันนี้ ไม่ได้แย่ลง

ข้อดีที่ได้แลกมาคือ **upload กลายเป็นการคัดลอกที่ย้อนกลับได้** — ถ้า backend รับของผิด อัปหาย หรือ R2 ถูกลบ ต้นฉบับยังอยู่ครบในเครื่อง ซึ่งเป็นสิ่งที่ควรมีจนกว่าจะผ่าน field test จริง

### 2.6 ✅ เครือข่าย

**ตัดสินแล้ว: ใช้ได้ทั้ง Wi-Fi และ 4G** → `Constraints.NetworkType.CONNECTED` · **ไม่มี Wi-Fi-only toggle**

> **ยังไม่ได้ตอบ: เครื่องเสียบไฟระหว่างงานไหม** — จนกว่าจะรู้ ตั้ง `requiresBatteryNotLow = true` ไว้ก่อน (WorkManager จะหยุดระบายคิวเมื่อแบตต่ำ แล้วกลับมาเองเมื่อชาร์จ) และ**ยังไม่ทำ foreground service** · ถ้า field test พบว่าคิวค้างเพราะระบบตัดงานเบื้องหลัง ค่อยเพิ่มทีหลัง — ดู [§1](#ยงไมม-) เรื่อง permission ที่ต้องเพิ่มถ้าถึงจุดนั้น

รูปแบบนี้แปลว่า **การอัปโหลดกินเน็ตมือถือได้** ซึ่งเป็นสิ่งที่ตั้งใจ · ถ้าภายหลังพบว่าเปลืองเกินไป การเพิ่ม toggle คือการเปลี่ยน `NetworkType` บรรทัดเดียวบวก UI ไม่กระทบคิวหรือ state machine

---

## 3. State machine

**ตัดสินแล้ว: 6 สถานะ** — 4 ตัวเดิมบวก `UPLOADED` และ `ABANDONED`

`UPLOADED` ปิดช่องที่อันตรายที่สุด คือ **R2 ได้ไฟล์แล้วแต่ backend ยังไม่รู้** · `ABANDONED` ปิดช่องที่สอง คือความล้มเหลวที่ retry เท่าไรก็ไม่หาย ซึ่งถ้าไม่แยกออกมาจะวนอยู่ใน `FAILED` ตลอดไปและกินแบตไปเรื่อยๆ

```
   PENDING ──────▶ UPLOADING ──────▶ UPLOADED ──────▶ SUCCESS
      ▲                │                 │            (ปลายทาง · ไฟล์ในเครื่องอยู่ครบ)
      │                │ PUT ล้ม          │ complete ล้ม
      │                ▼                 │
      └───────────── FAILED ◀────────────┘
                       │
                       │ retry หมด / 4xx ที่ retry ไม่ช่วย / ไฟล์หาย
                       ▼
                   ABANDONED
```

| สถานะ | ความหมาย | การ retry ทำอะไร |
|--|--|--|
| `PENDING` | อยู่ในคิว ยังไม่เคยลอง | presign → PUT → complete |
| `UPLOADING` | worker กำลังถืออยู่ | — (ถ้าเจอค้างตอนบูตเพราะ process ตาย → รีเซ็ตกลับ `PENDING`) |
| **`UPLOADED`** | **byte อยู่บน R2 แล้ว `objectKey` ยืนยันแล้ว แต่ยังไม่ได้ commit metadata** | **ข้าม PUT ทั้งหมด ยิงแค่ `/uploads/complete` ซ้ำ** |
| `SUCCESS` | backend บันทึก metadata แล้ว · **ปลายทาง ไม่มีขั้นลบไฟล์ต่อ** | — |
| `FAILED` | ล้มแบบที่ลองใหม่ได้ (5xx, timeout, ไม่มีเน็ต) | ตาม backoff |
| `ABANDONED` | ล้มแบบถาวร (403/404 ถาวร, ไฟล์ถูกลบจากแกลเลอรี, ครบจำนวนครั้ง) | ต้องให้คนกด Retry เอง |

`UPLOADED` คือสถานะที่ทำให้ระบบนี้ไม่ทิ้งขยะใน R2 และไม่อัปซ้ำ ตัดออกไม่ได้

**คอลัมน์ที่ตารางต้องมีนอกจากสถานะ:** `attemptCount`, `nextAttemptAtMs` (สำหรับ backoff ที่รอดข้ามการรีบูต), `lastError`, `objectKey`, `contentUri`, `sessionId`, `capturedAtMs`, `sizeBytes`

---

## 4. Contract ที่ล็อกไว้ก่อนได้ (ยังไม่ต้องมี backend)

```
POST /uploads/presign
  Authorization: Bearer <token>
  { deviceId, items: [ { objectKey, contentType, sizeBytes } ] }
→ { items: [ { objectKey, uploadUrl, expiresAtMs } ] }

PUT <uploadUrl>                       ← ตรงไป R2 ไม่ผ่าน backend
  Content-Type: image/jpeg            ← ต้องตรงกับตอน sign เป๊ะ
  <body>

POST /uploads/complete
  Authorization: Bearer <token>
  { deviceId, items: [ { objectKey, sessionId, capturedAtMs, sizeBytes, width, height } ] }
→ { accepted: [objectKey], rejected: [ { objectKey, reason } ] }
```

**ข้อควรระวังเฉพาะของ R2 presigned PUT** — เขียนไว้ตรงนี้เพราะเป็นสาเหตุอันดับหนึ่งของ `SignatureDoesNotMatch`:

- `Content-Type` ที่ส่งตอน PUT ต้อง**ตรงกับที่ backend ใช้ตอน sign ทุกตัวอักษร** · client HTTP หลายตัวเติม charset ให้เอง ต้องปิด
- ห้ามให้ client เติม `Accept-Encoding: gzip` หรือ transform body — ใช้ `identity` เหมือนที่ [VideoHttp.kt](../androidApp/src/main/kotlin/com/autobots/camera/network/VideoHttp.kt) ทำฝั่งขาเข้าแล้ว
- `expiresAtMs` ควร **≥ 15 นาที** เพราะงานอาจนอนอยู่ในคิวหลัง presign แล้วเน็ตหลุด · ถ้า URL หมดอายุตอนจะ PUT ต้องกลับไป presign ใหม่ ไม่ใช่นับเป็น `FAILED`
- R2 ใช้ SigV4 · endpoint เป็น `https://` เสมอ (`usesCleartextTraffic` ที่เปิดไว้ใน 0.1.5 ไม่เกี่ยวและไม่ควรถูกใช้เป็นข้ออ้างให้ backend เป็น http)

---

## 5. สถาปัตยกรรมที่เสนอ

```
VideoFrameProcessor ──▶ WriteQueue ──▶ MediaStore (DCIM/AutoBots)
                            │
                            └─ onDelivered(uri, name, sessionId)
                                     │
                                     ▼
                        ┌────────────────────────┐
                        │  UploadRepository      │  ← จุดเดียวที่ pipeline รู้จัก
                        └────────┬───────────────┘
                                 ▼
                        ┌────────────────────────┐
                        │  Room: upload_queue    │  ← Flow<List<UploadItem>> ป้อน UI ตรงๆ
                        └────────┬───────────────┘
                                 ▼
                        ┌────────────────────────┐
                        │  UploadWorker          │  unique work · Constraints(CONNECTED)
                        │  (WorkManager)         │  ระบาย batch ในลูปเดียว ไม่ใช่ 1 work/ไฟล์
                        └────────┬───────────────┘
                                 ▼
                        ┌────────────────────────┐
                        │  UploadTransport       │  ← interface
                        ├────────────────────────┤
                        │ RealUploadTransport    │  ktor-client → backend + R2
                        │ FakeUploadTransport    │  → filesDir/fake_r2/  (B3c)
                        └────────────────────────┘
```

**ตัดสินแล้ว: หนึ่ง unique work ที่ระบายทั้งคิว ไม่ใช่หนึ่ง work ต่อไฟล์** — WorkManager มี overhead ต่อ work request สูง ไม่รับประกันลำดับ และ 134 request ต่อ session จะทำให้ตาราง `WorkSpec` ของระบบบวม · ใช้ `enqueueUniqueWork(KEEP)` แล้วให้ worker วนอ่านคิวเองพร้อม concurrency จำกัด (2–3) จะคุมได้ทั้งลำดับและอัตรา

**Exponential backoff อยู่สองชั้น** — ชั้นนอกคือ `setBackoffCriteria(EXPONENTIAL)` ของ WorkManager สำหรับกรณีทั้ง batch ล้ม · ชั้นในคือ `nextAttemptAtMs` ต่อแถว สำหรับไฟล์ที่ล้มเป็นรายตัวโดยที่ไฟล์อื่นยังไปได้ · ชั้นในสำคัญกว่า เพราะไฟล์เสียใบเดียวต้องไม่บล็อกทั้งคิว

---

## 6. Slice plan — **ทำเสร็จแล้วทั้งหมด**

> รายละเอียดของแต่ละ slice ถูกตัดออกเมื่อ 26/08/2026 หลังงานทั้งก้อนขึ้น production แล้ว
> ประวัติเต็ม (สิ่งที่ทำ · สิ่งที่ทดสอบ · สิ่งที่ยังไม่ได้ทดสอบในแต่ละรอบ) อยู่ใน git history ของไฟล์นี้

| Slice | งาน | ผล |
|--|--|--|
| **B3a** | โครงคิว Room + KSP · `UploadStatus` · `UploadItem` · `UploadDao` · hook `onPublished` | ✅ |
| **B3b** | หน้าคิวแบบอ่านอย่างเดียว | ✅ |
| **B3c** | ⭐ `UploadWorker` + `FakeUploadTransport` · WorkManager · Pause/Resume | ✅ |
| **B3d** | ตั้งค่า endpoint + identity ด้วย QR | ✅ |
| **B3e-1** | Login + event picker | ✅ |
| **B3e-2** | `RunxUploadTransport` ของจริง → **ยิง production ผ่าน** | ✅ |
| **B3f-0** | ทดสอบพื้นหลัง | 🔴 **ไม่ผ่าน** 20/08 — คิวค้างสนิทใน deep idle · เป็นหลักฐานว่าต้องมี FGS |
| **B3f-1** | Foreground service (`dataSync`) | ✅ **ผ่าน** 20/08 ในเงื่อนไขที่โหดกว่าเดิม |

**บทเรียนที่ควรอยู่ต่อ:** B3c ถูกสร้างและพิสูจน์กับ `FakeUploadTransport` ก่อนมี backend จริง —
คิว, worker และ 6 สถานะทั้งหมดจึงถูกทดสอบจบก่อนที่ใครจะรู้ว่าปลายทางคือ R2 หรือ Runx
ตอนเปลี่ยนใจจาก R2 เป็น Runx จริง ๆ จึงแก้แค่ `UploadTransport` ตัวเดียว

---

### B3f — field test ของจริง (**ยังไม่ได้ทำ**)

> **ขอบเขตเปลี่ยนไปจากที่วางไว้** — เดิมข้อนี้คือ "ตั้ง bucket R2 + เขียน SigV4 presign ฝั่ง backend"
> งานนั้น**หายไปทั้งก้อน** เพราะใช้แพลตฟอร์ม Runx ที่มี presign ให้อยู่แล้ว
> สิ่งที่เหลือคือส่วนที่เดาจากโต๊ะทำงานไม่ได้: **เน็ตจริงและเวลาจริง**

**สิ่งที่ต้องเฝ้าในการทดสอบภาคสนามครั้งแรก:** อัตราการล้มของ PUT บน 4G · เวลาที่ใช้ต่อรูปเทียบกับขนาด ·
แบตที่หายไประหว่างระบายทั้งคิว · ความร้อน (worker ตัวนี้ทำงานบนเครื่องที่เพิ่ง extract UHD มา) ·
**เพดาน `dataSync` 6 ชม./วัน (Android 14+)** ชนเมื่อไหร่ · และ `path` ถูกส่งถึง server จริงหรือไม่ ([§2.3](#23--object-key-deterministic))

เก็บตัวเลข throughput / ความล้มเหลวเป็นรายงานเวอร์ชันตาม [REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md)

---

### B3g — ยังไม่วางแผนละเอียด

> **วิดีโอ chunk ถูกตัดออกจากขอบเขตแล้ว** ([§2.4](#24-ขอบเขต--รูปเทานน)) · **การลบไฟล์ในเครื่องถูกตัดออกทั้งหมด**
> ([§2.5](#25--ไมลบไฟลในเครอง)) · **Wi-Fi-only toggle ถูกตัดออก** ([§2.6](#26--เครอขาย))

| หัวข้อ | ทำไมแยกออกมา |
|--|--|
| **ลบแถวที่ `Abandoned` ออกจากคิว** | ลบ*แถวในคิว* ไม่ใช่ลบรูป · ต้องมี UI ให้คนตัดสินใจ ไม่ควรลบเงียบ |
| **ของกำพร้าบน bucket จาก requeue** | ผลพวงจาก [§2.3](#23--object-key-deterministic) · แก้ฝั่งแอปไม่ได้ ต้องคุยกับเจ้าของแพลตฟอร์ม |

## 7. UI — เชื่อมกับของที่ 0.1.5 ทำไว้

โครง Home menu จาก 0.1.5 รองรับเรื่องนี้พอดี ไม่ต้องรื้ออีก

```
┌─────────────────────────────────┐
│  AutoBots v0.1.6                │
│  IP 192.168.1.42:8080           │
├─────────────────────────────────┤
│  Processing status card         │
│  + Upload · 128 pending · 6 failed   ← บรรทัดใหม่
├─────────────────────────────────┤
│  Live capture                   │
│  Browse Video                   │
│  Network URL                    │
│  Upload            ● 128        │  ← ใหม่ · badge จาก Flow ของ Room
│  Session History                │
│  Gallery (1,204)                │
└─────────────────────────────────┘
```

| หน้า | เนื้อหา |
|--|--|
| **Upload Queue** *(ใหม่)* | รายการจัดกลุ่มตาม session · chip กรองสถานะ · ต่อแถว: thumbnail + ชื่อ + สถานะ + `attemptCount` + `lastError` · ปุ่ม **Retry failed** / **Pause uploads** |
| **Upload Settings** *(ใหม่)* | backend URL · device ID · token · **สแกน QR** · *Test connection* · **ไม่มี toggle ใดๆ** — Wi-Fi-only และการลบไฟล์ถูกตัดออกจากขอบเขตแล้ว |
| **Session History** *(แก้)* | เพิ่มบรรทัด `Uploaded 128/134` ต่อ session — [PipelineSessionRecord](../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt) มี id ให้ join กับตารางคิวได้แล้ว |
| **Home** *(แก้)* | แถวเมนู + badge + บรรทัดในการ์ดสถานะ |
| Live capture / Import Preview | **ไม่แตะ** — upload เป็นงานเบื้องหลัง ไม่ควรโผล่ระหว่างถ่ายหรือระหว่างตั้งค่า extract |

**หลักที่ควรยึด:** upload ไม่มีปุ่ม "อัปโหลดเดี๋ยวนี้" ที่ผู้ใช้ต้องกดเป็นประจำ — ถ้าต้องกด แปลว่าคิวออกแบบผิด · ปุ่มที่มีคือ *Retry failed* กับ *Pause* ซึ่งเป็นการแทรกแซง ไม่ใช่การใช้งานปกติ

---

## 8. สถานะการตัดสินใจ

**งานเขียนโค้ดของ B3 จบแล้ว** — ข้อ 1–9 ถูกใช้จริงทั้งหมด ยกเว้นข้อ 4 ที่ถูกหักล้างและกลับคำ (§2.3)

เรื่องที่ยังเปิดอยู่ และตั้งใจให้**ข้อมูลจริง**เป็นคนตอบ ไม่ใช่การเดา:

| เรื่อง | สถานะ | ใครเป็นคนตอบ |
|--|--|--|
| ~~ต้องมี foreground service ไหม~~ | ✅ **ตอบแล้ว: ต้องมี** — B3f-0 คิวค้างสนิทใน deep idle · B3f-1 แก้แล้ว | field test |
| อัตราการล้มของ PUT บน 4G | ❌ ยังไม่มีตัวเลข — ที่วัดมาทั้งหมดอยู่บน Wi-Fi/loopback | **B3f** |
| งานยาว 4 ชม. (ร้อน · แบต · Doze ซ้ำๆ) | ❌ ที่ผ่านคือ Doze ที่บังคับเอง 4 นาทีครึ่ง | **B3f** |
| เพดาน `dataSync` 6 ชม./วัน | ❌ ยังไม่รู้ว่าชนเมื่อไหร่ | **B3f** |
| `path` ถูกส่งถึง server จริงไหม | ❌ mutation ไม่ได้ประกาศ `$path` — ต้องดู object จริงบน bucket | ตรวจ bucket |

**ที่เก็บของการตัดสินใจ:** เอกสารนี้เป็นแหล่งอ้างอิงเดียว · ถ้าข้อไหนเปลี่ยนระหว่างทาง แก้ที่นี่ก่อนแก้โค้ด เพราะข้อ 1–4 มีผลต่อ contract ที่ backend ต้องทำตาม

---

## 9. เรื่อง KSP · ทางเลือกของ Room

### สิ่งที่ **ไม่ใช่** ความเสี่ยง

KSP กับ CMake/NDK **ไม่ยุ่งกัน** · KSP เป็น Kotlin compiler plugin ที่ผลิต source ส่วน `externalNativeBuild` เป็น task graph แยกที่ผลิต `.so` — ทั้งสองมาเจอกันตอน package เท่านั้น ไม่มี input/output ร่วมกัน

และโปรเจกต์นี้**ใช้ Kotlin compiler plugin อยู่แล้วสองตัว** (`kotlin.compose`, `kotlin.serialization`) การเพิ่ม KSP จึงไม่ใช่ของแปลกใหม่สำหรับ build นี้

### ความเสี่ยงจริง — lockstep กับ Kotlin

เวอร์ชันของ KSP เขียนเป็น `<kotlin>-<ksp>` และ**ส่วนหน้าต้องตรงกับ Kotlin ที่ใช้เป๊ะ** · โปรเจกต์นี้อยู่ที่ Kotlin `2.0.21` (AGP `8.7.3` · Gradle `8.11.1`) ดังนั้นทุกครั้งที่อัป Kotlin หลังจากนี้ **ต้องอัป KSP พร้อมกัน** ไม่งั้น build พัง — นี่เป็นภาระถาวร ไม่ใช่ต้นทุนครั้งเดียวตอนติดตั้ง

### วิธีลดความเสี่ยงของ B3a

| ขั้น | ทำอะไร |
|--|--|
| **1** | commit แรก: เพิ่ม **KSP plugin อย่างเดียว** ยังไม่มี Room · build ต้องผ่าน — พิสูจน์ว่า plugin อยู่ร่วมกับของเดิมได้ |
| **2** | commit ถัดไป: Room dependencies + entity + DAO |
| **3** | Room อยู่ใน `androidApp` เท่านั้น ห้ามแตะ `shared` |
| **4** | ตั้ง `room.schemaLocation` แล้ว **commit ไฟล์ schema JSON** เข้า repo — migration ทั้งหมดหลังจากนี้พึ่งมัน |
| **5** | ตรึงเวอร์ชันใน `libs.versions.toml` ทั้งคู่ ไม่ใช้ `+` |

### ทางเลือกแทน Room

| | ต้องใช้ codegen | ได้อะไร | เสียอะไร |
|--|--|--|--|
| **Room** *(แนะนำ)* | KSP | `Flow` ที่ invalidate เองเมื่อตารางเปลี่ยน · ตรวจ SQL ตอน compile · migration framework | lockstep กับ Kotlin |
| **SQLite เขียนเอง** (`androidx.sqlite` / `SQLiteOpenHelper`) | ไม่ต้อง | **ไม่เพิ่ม plugin เลย** · ตารางนี้มีตารางเดียว ~12 คอลัมน์ ~6 query · ประมาณ 150 บรรทัด | map cursor เอง · เขียน migration เอง · **ต้องทำ `Flow` เอง** (bump `MutableStateFlow` ทุกครั้งที่เขียน) ซึ่งเป็นจุดที่พลาดง่ายที่สุด |
| **SQLDelight** | Gradle plugin (ไม่ใช่ KSP) | SQL เป็นไฟล์ `.sq` ตรวจตอน compile · รองรับ KMP | plugin ใหม่เหมือนกัน + รูปแบบที่ทีมยังไม่คุ้น · ไม่ได้แก้ปัญหาอะไรที่ Room แก้ไม่ได้ในเคสนี้ |
| ~~JSON / Proto DataStore~~ | ไม่ต้อง | — | **ใช้ไม่ได้** — ต้องเขียนไฟล์ใหม่ทั้งก้อนทุกครั้งที่สถานะหนึ่งแถวเปลี่ยน · 134 แถวที่อัปเดตถี่ๆ และไม่มีการรับประกันความคงทนตอน process ตายกลางคัน ซึ่ง SQLite ให้ฟรี |

### ปัญหาที่ Room + KSP สร้างได้จริง — และข้อที่กระทบการออกแบบ

แยกตามจังหวะที่มันกัด:

| จังหวะ | อาการ | ความรุนแรง |
|--|--|--|
| **ตอนติดตั้ง** | เวอร์ชัน KSP ไม่ตรง Kotlin → build fail พร้อมข้อความชัดเจน · `room.schemaLocation` ไม่ได้ตั้ง → warning ตอน compile | ต่ำ — เห็นทันที แก้ทันที |
| **ทุกครั้งที่ build** | KSP เป็น task เพิ่มใน `androidApp` ซึ่งเป็น module ที่แก้บ่อยที่สุด · แตะไฟล์ UI ไฟล์เดียวก็ปลุก KSP | ต่ำ–กลาง — build นี้มี CMake อยู่แล้ว เทียบกันแล้วเล็ก |
| **ตอนอัป Kotlin** | ลืมอัป KSP ตาม → build พังทั้ง project | ต่ำ — พังทันที ไม่แอบ |
| ⚠️ **ตอน runtime · schema เปลี่ยน** | เพิ่มคอลัมน์แล้วลืมเขียน `Migration` → `IllegalStateException: A migration from X to Y was required but not found` · **แอปตายตอนเปิด** สำหรับเครื่องที่มีข้อมูลเดิม | **สูง** — ดูนโยบายด้านล่าง |
| ⚠️ **ตอน runtime · คิวโต** | `Flow<List<UploadItem>>` ที่ observe ทั้งตาราง จะ query ใหม่**ทั้งตาราง**ทุกครั้งที่ worker อัปเดตแถวเดียว | **สูง** — ดูด้านล่าง |

#### ⚠️ Flow ต้องไม่ observe ทั้งตาราง

worker อัปเดตสถานะแถวละหลายครั้ง (`PENDING → UPLOADING → UPLOADED → SUCCESS`) และ **[§2.5](#25--ไมลบไฟลในเครอง) บอกว่าไม่ลบอะไรเลย** ตารางจึงโตขึ้นเรื่อยๆ ตามจำนวน session ที่ผ่านมา ถ้า UI observe `SELECT * FROM upload_queue` ตรงๆ ทุกการอัปเดตหนึ่งแถวจะ map cursor ใหม่ทั้งตาราง — ที่ 134 แถวไม่รู้สึก ที่หลักหมื่นแถวคือ UI กระตุกระหว่างอัปโหลด

**กติกา:**

- badge กับการ์ดสถานะ observe **count เท่านั้น** — `SELECT status, COUNT(*) FROM upload_queue GROUP BY status`
- หน้า Upload Queue observe แบบมี `LIMIT` / paging และกรองตาม session ไม่ดึงทั้งตาราง
- worker อ่านงานด้วย `SELECT … WHERE status IN (…) AND nextAttemptAtMs <= ? LIMIT n` ไม่ใช่ `Flow`

#### ⚠️ นโยบาย migration

ปกติ `fallbackToDestructiveMigration()` เป็นสิ่งที่ห้ามใช้กับข้อมูลผู้ใช้ **แต่ในดีไซน์นี้มันปลอดภัยจริง** และเหตุผลมาจากการตัดสินใจสองข้อก่อนหน้า:

- [§2.5](#25--ไมลบไฟลในเครอง) — ไม่ลบไฟล์ ดังนั้นรูปต้นฉบับยังอยู่ครบใน DCIM เสมอ · ตารางนี้เก็บแค่ *สถานะ* ไม่ใช่ *ข้อมูล*
- [§2.3](#23--object-key-deterministic) — object key deterministic ดังนั้นการอัปซ้ำคือ **เขียนทับ** ไม่ใช่สร้างซ้ำ

ผลคือกรณีที่แย่ที่สุดของการ drop ตารางคือ **อัปทุกอย่างใหม่หนึ่งรอบ** ซึ่งสิ้นเปลือง bandwidth แต่ไม่ทำข้อมูลเสียหายและไม่สร้างขยะใน R2

**กติกา:** เขียน `Migration` จริงเมื่อทำได้ · ใส่ `fallbackToDestructiveMigration()` ไว้เป็นตาข่ายกันแอปตาย · **commit schema JSON ทุกครั้งที่ version เปลี่ยน** เพื่อให้รู้ว่าอะไรเปลี่ยนไปบ้าง

### สิ่งที่ทำให้ตัดสินใจนี้ย้อนกลับได้

`UploadRepository` เป็น interface และเป็น**สิ่งเดียวที่ pipeline, worker และ UI รู้จัก** · ตัวเลือกข้างบนทั้งหมดอยู่หลังมันได้ ดังนั้นถ้า KSP สร้างปัญหาใน B3a จริง การถอยไปเขียน SQLite เองคือการเปลี่ยน implementation ตัวเดียว ไม่กระทบ slice อื่นเลย

---

## 10. Transport ที่มีของจริงแล้ว — Runx GraphQL

ระบบที่ใช้งานจริงอยู่แล้วคือ `autobots-offline-box` (Python) ซึ่งอัปรูปเข้าแพลตฟอร์ม Runx **ยังไม่ตัดสินว่าจะใช้ตัวนี้แทน R2 หรือไม่** — บันทึกไว้เพราะมันเปลี่ยนรายละเอียดบางข้อในเอกสารนี้ และเพราะ B3e จะเขียนง่ายขึ้นมากถ้ารู้ล่วงหน้า

### สามขั้น — โครงเดิม คนละโปรโตคอล

```
presign   POST https://api.<host>/graphql          GraphQL: mutation photoUpload
              variables { provider: "gs", mimeType: "image/jpeg", path?: String }
          →  { uploadUrl, downloadUrl }

PUT       uploadUrl (GCS signed URL)               Content-Type: image/jpeg
                                                   ← ต้องตรงกับ mimeType ที่ sign เป๊ะ

complete  POST https://upload.<host>/success       ‼️ REST form-urlencoded ไม่ใช่ GraphQL
              Authorization: Bearer <token>
              eventId · key · name · uri
```

**เป็น GraphQL แค่ขั้นเดียว** ขั้น complete เป็น form POST ธรรมดาและอยู่คนละ host

### สิ่งที่กระทบดีไซน์ของเรา

| | ผล |
|--|--|
| 🔴 **ต้องมี schema v2** | `complete` ต้องการ `key` และ `uri` ซึ่งได้มาจาก `downloadUrl` **ที่ server สร้าง** ไม่ใช่ค่าที่คำนวณเองได้ · สถานะ `UPLOADED` จึง retry ไม่ได้ถ้าไม่เก็บไว้ → เพิ่ม `remoteKey` / `remoteUri` (nullable) ลง [UploadItem.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadItem.kt) |
| **`eventId` แทน session** | backend ไม่มีแนวคิด session · รูปจากหลาย session กองรวมใน event เดียว · `sessionId` ของเราเป็นของ local ล้วน ใช้จัดกลุ่มใน UI และประกอบ `relativeKey` เท่านั้น |
| **metadata ส่งไม่ได้** | `/success` รับแค่ 4 field · `capturedAtMs` / `sizeBytes` ที่คิวเก็บไว้ไม่มีที่ให้ส่ง — ต้องขยาย API หรือยอมรับว่าอยู่แค่ในเครื่อง |
| **ไม่มี batch** | `photoUpload` คืน URL ทีละใบ → 3N request ต่อ session ([§2.2](#22--ขนาดของ-round-trip)) |
| **`$path` เปิดประตูไว้** | มีในสกีมาแต่ client ปัจจุบันไม่ส่ง · ถ้าใช้ได้จริง deterministic key ตาม [§2.3](#23--object-key-deterministic) ก็ยังใช้ได้ |

### บทเรียนจากโค้ดที่รันอยู่ — สิ่งที่ต้องไม่ลอกมา

- **GraphQL คืน error เป็น HTTP 200** — `get_url_upload` เช็คแค่ `response.ok` แล้วเข้าถึง `data.photoUpload.uploadUrl` ตรงๆ พอ server ตอบ `{"errors": […]}` จึงโยน exception · **transport ฝั่งเราต้องเช็ค `errors` array เสมอ ห้ามใช้ HTTP status ตัดสิน**
- **notify ล้ม = object กำพร้า** — โค้ดเดิมโยนเฉพาะ `basename` ลงคิว fail แล้ว ack ของเดิมทิ้ง ไฟล์บน GCS จึงค้างโดยไม่มี metadata และย้อนกลับไปหา path เดิมไม่ได้ · **นี่คือหลักฐานว่าสถานะ `UPLOADED` แก้ปัญหาที่เกิดขึ้นจริง ไม่ใช่ปัญหาสมมติ**
- **ไม่มี backoff และคิว fail ไม่มีใครระบาย** — เน็ตสะดุดครั้งเดียว รูปนั้นตายถาวร
- **worker ค้างถาวรหลัง config ผิดครั้งแรก** — `current_time` ถูกอ่านครั้งเดียวนอกลูป `while True` พอ `stored_expiry_time = current_time + 5` เงื่อนไขก็เป็นจริงตลอดไป · กรณีเทียบเท่าของเราคือ "ยังไม่ได้ตั้งค่า endpoint" ซึ่งต้องกลับมาทำงานเองเมื่อผู้ใช้ตั้งค่าเสร็จ

### คำถามที่ต้องถามเจ้าของ backend

| # | คำถาม | ทำไมสำคัญ |
|--|--|--|
| 1 | **`CloudUploadProvider` มีค่าอะไรบ้างนอกจาก `gs`** | ถ้ามี R2/S3 อยู่ในนั้น คำถาม "R2 หรือ Runx" หายไปเลย — เป็น API เดียวกันแค่เปลี่ยนค่า enum · **ถามข้อนี้ก่อน** |
| 2 | ~~`$path` หมายถึงคีย์ของ object ใช่ไหม~~ | **ตอบแล้วด้วยการยิงจริง: `path` = โฟลเดอร์เท่านั้น** server ต่อ UUID ท้ายเสมอ → **deterministic key ทำไม่ได้** และ requeue ยังได้ object ซ้ำ · คำถามที่เหลือ: คุมชื่อไฟล์สุดท้ายได้ไหม |
| 3 | ~~presign ไม่ต้องมี token จริงหรือ~~ **ยืนยันแล้ว: ไม่ต้อง** (ยิง curl ไม่ใส่ `Authorization` ได้ signed URL มาปกติ) | โค้ดเดิมไม่ส่ง `Authorization` ในขั้น presign เลย ขณะที่ complete ต้องมี · ถ้าเป็นตามนั้น ใครก็เขียนไฟล์ลง bucket ได้ · **ถามเพื่อยืนยันว่าตั้งใจ ไม่ใช่เพื่อสรุปว่าเป็นช่องโหว่** |
| 4 | `/success` รับ field เพิ่มได้ไหม | capturedAt · ขนาด · มิติ |
| 5 | ~~token อายุเท่าไร มี refresh ไหม~~ | **ตอบแล้ว: 7 วัน ไม่มี refresh** → ตัดสินใจไม่เก็บ token ลงดิสก์เลย และ login ใหม่ทุกครั้งที่เปิดแอป (B3e-1) |
| 6 | บัญชีช่างภาพต้องใช้ filter `myEvent` หรือ `photographerEvent` | โค้ดเดิมใช้ `myEvent` แต่สกีมามี `photographerEvent` แยก และ response ของ login มี field `photographer` · เดาผิด = dropdown ว่างเปล่าโดยดูเหมือนแอปพัง · ตอนนี้ทำเป็นสวิตช์ให้เลือกเองไว้ก่อน |

---

## Related

- [IMPLEMENTATION.md](./IMPLEMENTATION.md) — B3 ในตาราง phase หลัก
- [RELEASE_0_1_5.md](./RELEASE_0_1_5.md) — ingest ขาเข้า · รูปแบบ HTTP layer ที่ B3e จะเดินตาม
- [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) · [SEQUENCE_FLOW.md](./SEQUENCE_FLOW.md) — เส้นทางก่อนถึง `WriteQueue`
- [ROADMAP.md](./ROADMAP.md) — "Cloud / remote upload" ย้ายมาอยู่ที่เอกสารนี้แล้ว
