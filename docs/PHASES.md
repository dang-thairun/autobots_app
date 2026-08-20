# Phases — Upload Pipeline (B3)

> แผนการทำ **upload ขาออก** ต่อจาก [RELEASE_0_1_5.md](./RELEASE_0_1_5.md) ซึ่งทำ ingest ขาเข้าไปแล้ว
> Phase ระดับบนสุดอยู่ที่ [IMPLEMENTATION.md](./IMPLEMENTATION.md) — เอกสารนี้แตก **B3** ออกเป็น slice ที่ ship ได้ทีละอัน
>
> **สถานะ: B3a–B3e ✅ ยิงขึ้น production จริงผ่านแล้ว · B3f-1 foreground service ✅ ทดสอบพื้นหลังผ่านแล้ว · เหลือ field test บนเน็ตจริง** · ยังไม่มี Cloudflare R2 · ยังไม่มี backend
> เอกสารนี้เขียนไว้เพื่อให้เริ่มได้โดยไม่ต้องรอ R2 — B3a–B3e ทั้งหมดทำและทดสอบจบได้ด้วย fake backend + Ktor server ในเครื่อง
>
> **ขอบเขต: อัปโหลด JPEG ที่ extract แล้วเท่านั้น** — วิดีโอ chunk 50 MB ไม่อัปโหลด ([§2.4](#24-ขอบเขต--รูปเทานน))
> **ไม่ลบไฟล์ในเครื่องไม่ว่ากรณีใด** — upload เป็นการ *คัดลอกขึ้นคลาวด์* ไม่ใช่การ *ย้าย* ([§2.5](#25--ไมลบไฟลในเครอง))

---

## สรุปสำหรับผู้ตัดสินใจ

**ทำได้ และเสี่ยงต่ำกว่าที่คิด** เพราะ pipeline ปัจจุบันมี**จุดต่อเดียว**ที่ต้องแตะ — callback `onDelivered(uri)` ใน [WriteQueue.kt](../androidApp/src/main/kotlin/com/autobots/camera/delivery/WriteQueue.kt) ที่ยิงหลังรูปถูก publish ลง MediaStore สำเร็จ ทุกอย่างที่เหลือเป็นโมดูลใหม่ที่ไม่มีใครในเส้นทาง capture → extract → deliver รู้จัก

### การตัดสินใจที่ล็อกแล้ว

| # | เรื่อง | ผล |
|--|--|--|
| **1** | **ไฟล์ที่จะอัป** | **`content://` URI ของ MediaStore** ไม่ใช่ path ในระบบไฟล์ — cache JPEG ถูกลบทันทีหลัง publish ([§2.1](#21--ไฟลอยทไหนตอนถงคว)) |
| **2** | **สถานะในคิว** | **6 สถานะ** — เพิ่ม `UPLOADED` และ `ABANDONED` เข้ากับ 4 ตัวเดิม ([§3](#3-state-machine)) |
| **3** | **presign / complete** | รับเป็น **array** ตั้งแต่ v1 ([§2.2](#22--ขนาดของ-round-trip)) |
| **4** | **object key** | **deterministic** — `{deviceId}/{sessionId}/{fileName}` ([§2.3](#23--object-key-deterministic)) |
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

### ยังไม่มี ❌

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

### 2.3 ✅ Object key deterministic

ถ้า backend สุ่ม UUID ใหม่ทุกครั้งที่ presign การ retry หลัง "ไม่รู้ว่าสำเร็จไหม" จะสร้าง object ซ้ำใน R2 ทุกครั้ง

```
{deviceId}/{sessionId}/{fileName}
```

> ⚠️ **ขึ้นกับ backend** — Runx `photoUpload` มีพารามิเตอร์ `$path: String` ในสกีมาแต่ client ปัจจุบันไม่ได้ส่ง · ถ้า `path` คือคีย์ของ object จริง ข้อนี้ใช้ได้ตามที่เขียน ถ้าไม่ใช่ ต้องเก็บคีย์ที่ server มินต์ลงแถวแทน ดู [§10](#10-transport-ทมของจรงแลว--runx-graphql)

**ตัดสินแล้ว** — ทั้งสามค่ามีอยู่แล้วฝั่งแอป · Android เป็นคนเสนอ key, backend เป็นคนอนุมัติหรือปรับ prefix แต่**ห้ามสุ่มใหม่** · `/uploads/complete` ต้อง **idempotent ต่อ objectKey** (upsert ไม่ใช่ insert)

ผลที่ตามมาที่ต้องยอมรับ: อัปไฟล์เดิมซ้ำ = **เขียนทับ object เดิม** ไม่ใช่สร้างใบใหม่ ซึ่งเป็นสิ่งที่ต้องการ เพราะ `{sessionId}/{fileName}` ระบุรูปหนึ่งใบได้ไม่ซ้ำอยู่แล้ว

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

## 6. Slice plan

### B3a — โครงคิว (ไม่มีเน็ต) · ✅ เสร็จ · ทดสอบบนเครื่องแล้ว

**เพิ่มแล้ว:** KSP `2.0.21-1.0.28` + Room `2.6.1` ใน build (WorkManager ยังไม่ใส่ — รอ B3c ที่ได้ใช้จริง) · [UploadStatus.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadStatus.kt) · [UploadItem.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadItem.kt) · [UploadDao.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadDao.kt) · [UploadDatabase.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadDatabase.kt) · [UploadRepository.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadRepository.kt) · hook `onPublished` ใน [WriteQueue.kt](../androidApp/src/main/kotlin/com/autobots/camera/delivery/WriteQueue.kt) → `enqueueForUpload` ใน [CapturePipelineCoordinator.kt](../androidApp/src/main/kotlin/com/autobots/camera/pipeline/CapturePipelineCoordinator.kt)

**ยืนยันแล้ว:** `assembleDebug` ผ่าน · KSP รันจริง (มี `UploadDao_Impl.java`) · schema ถูก export ที่ `androidApp/schemas/…/1.json` และ commit เข้า repo แล้ว

**ทดสอบบนเครื่องแล้ว** — Xiaomi peridot (SM8635) · `run1mins.mp4` UHD 391.8 MB · import → Face/NPU · 8 chunks

| ตรวจ | ผล |
|--|--|
| จำนวนแถวตรงกับของจริง | **15 แถว** = 15 ไฟล์ใน `DCIM/AutoBots/ext_v0_1_5_18082026_1637/` = `Photos kept: 15` ใน `session_log.txt` — ตรงกันสามทางอิสระ |
| สถานะเริ่มต้น | ทั้ง 15 แถวเป็น `Pending` |
| `relativeKey` ไม่ซ้ำ | 15 คีย์ต่างกัน 15 แถว · รูปแบบ `ext_v0_1_5_18082026_1637/face_c003_7200000.jpg` |
| `contentUri` เป็น MediaStore | `content://media/external_primary/images/media/1000003012` ไม่ใช่ path — ตามที่ [§2.1](#21--ไฟลอยทไหนตอนถงคว) ตั้งใจ |
| คงทนข้ามการปิดแอป | `am force-stop` แล้วเปิดใหม่ → ยังครบ 15 แถว ไม่มี crash ไม่มี migration error |
| ไม่กระทบของเดิม | QNN probe ยังทำงาน · session log ยังเขียนครบ · ratio/ผลการ extract ไม่เปลี่ยน |

**ยังไม่ถูกทดสอบ:** `resetInterrupted()` — สร้างแถวสถานะ `Uploading` ไม่ได้จนกว่าจะมี worker · ปิดใน **B3c** พร้อมกับการทดสอบ kill กลางทาง

**สิ่งที่ตัดสินระหว่างเขียน:**

- **`sessionId` = ชื่ออัลบั้ม** (`ext_v0_1_5_18082026_1430`) ไม่ใช่ `CapturePipelineCoordinator.sessionId` ซึ่งเป็นค่าต่อ *instance* ไม่ใช่ต่อ *การรัน* · ชื่ออัลบั้มคือที่ที่รูปอยู่จริง จึงย้อนกลับไปหาสิ่งที่ผู้ใช้เห็นได้เสมอ
- **เก็บ `relativeKey` = `{sessionId}/{fileName}` ไม่เก็บ deviceId** — deviceId ตั้งค่าใหม่ได้ ถ้าฝังลงแถวไว้ การเปลี่ยนเครื่อง/ออก token ใหม่จะทำให้คีย์ทั้งคิวใช้ไม่ได้ · transport เติม prefix ตอน presign
- **unique index บน `relativeKey` + `OnConflictStrategy.IGNORE`** ทำให้ `enqueue` idempotent — extract ซ้ำในอัลบั้มเดิมไม่สร้างแถวซ้ำ และไม่รีเซ็ตแถวที่ `Success` ไปแล้ว

**ความเสี่ยง:** ไม่ได้อยู่ที่ CMake/NDK อย่างที่เคยเขียนไว้ — อยู่ที่ **lockstep ระหว่าง Kotlin กับ KSP** และเป็นภาระถาวร ไม่ใช่ครั้งเดียว · วิธีลดความเสี่ยงและทางเลือกที่ไม่ใช้ KSP อยู่ที่ [§9](#9-เรอง-ksp--ทางเลอกของ-room)

**เก็บ Room ไว้ใน `androidApp` เท่านั้น ห้ามใส่ใน `shared`** — `shared` เป็น KMP module และ KSP บน KMP เป็นคนละเรื่องที่ยากกว่ามาก · คิว upload เป็นเรื่องของ Android ล้วน ไม่มีเหตุให้ข้ามไป common

---

### B3b — UI ของคิว (อ่านอย่างเดียว) · ✅ เสร็จ · ทดสอบบนเครื่องแล้ว

**เพิ่มแล้ว:** `OperatorDestination.UploadQueue` · [UploadQueuePage.kt](../androidApp/src/main/kotlin/com/autobots/ui/UploadQueuePage.kt) · แถวเมนู **Upload** บน Home พร้อมจำนวน · `uploadCounts` / `uploadItems` / `retryFailedUploads()` ใน [OperatorViewModel.kt](../androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt)

**เบี่ยงจากแผนหนึ่งข้อ:** ไม่ได้เพิ่มบรรทัดใน `ProcessingStatusCard` — ใส่จำนวนไว้ในป้ายปุ่มแทน (`Upload (45)`) ตามแบบเดียวกับ `Gallery (1204)` ที่มีอยู่แล้ว ได้ผลเท่ากันโดยไม่ต้องแตะการ์ดที่ live/import ใช้ร่วมกัน

**ทำตามกติกาของ [§9](#9-เรอง-ksp--ทางเลอกของ-room):**

- badge อ่านจาก `observeCounts()` (`GROUP BY status`) **ไม่ใช่** จากรายการแถว
- หน้า queue อ่าน `observePage(limit = 200)` มีขอบเขตเสมอ
- ทั้งคู่เป็น `stateIn(WhileSubscribed(5s))` — ออกจากหน้าแล้วเข้าใหม่ไม่รื้อ query ใหม่
- `badgeLabel` นับเฉพาะ `outstanding` — คิวที่อัปครบ 5,000 ใบแล้วไม่ต้องขึ้นตัวเลขให้กวนใจ

**ทดสอบบนเครื่องแล้ว:** ปุ่ม `Upload (45)` ตรงกับ 45 แถวใน DB · การ์ดสรุป `45 waiting to upload` / `0 done · 45 total` · ชิปกรองขึ้นเฉพาะ `Pending 45` (สถานะที่ไม่มีของไม่โผล่) · แต่ละแถวแสดงชื่อไฟล์ · session · ขนาด · เวลา ครบ · ปุ่ม Retry ไม่โผล่เพราะยังไม่มีแถวไหน failed

**ปุ่ม Upload ย้ายไปอยู่ใต้ Gallery** — Gallery คือ "ส่งถึงเครื่องแล้ว" Upload คือ "ส่งต่อขึ้นคลาวด์" เรียงตามลำดับของงานจริง

**Pause / Stop เลื่อนไป B3c** — ตอนนี้ยังไม่มี worker ให้พัก ปุ่มจะเป็นสวิตช์ที่ไม่มีใครอ่าน · ตัดสินแล้วว่าทำ **Pause/Resume อย่างเดียว ไม่มี Stop**: รูปหนึ่งใบ ~0.8–1.7 MB ใช้เวลาไม่กี่วินาที การ "หยุดรับงานใหม่ ปล่อยใบที่ค้างให้จบ" จึงหยุดได้จริงอยู่แล้ว ส่วน Stop ที่ตัดกลางคันทิ้งไบต์ที่ส่งไปแล้วและอาจทิ้ง object ครึ่งใบไว้ — จ่ายแพงกว่าเพื่อประหยัดไม่กี่วินาที

**เพิ่มระหว่างทาง: debug deep link** — เครื่องทดสอบ (HyperOS 3.0 / Android 16) ปฏิเสธ `adb shell input` แม้เปิด *USB debugging (Security settings)* แล้ว จึงใส่ทางลัดเฉพาะ debug build:

```bash
adb shell am start -n com.autobots.camera/com.autobots.MainActivity --es dest upload
#   dest: upload | history | network | live
```

ปิดตายใน release ด้วย `BuildConfig.DEBUG` · แก้ปัญหา *ไปให้ถึงหน้า* ได้ แต่ยังกดปุ่มในหน้านั้นไม่ได้ — ถ้า B3c ต้องกด Pause/Retry ซ้ำๆ ควรทำ instrumentation test

**ทำไมมาก่อน worker:** เพราะ UI ที่อ่าน `Flow` จาก Room ทำให้ทุก slice หลังจากนี้**ดีบักได้ด้วยตา** ไม่ต้องพึ่ง `adb logcat`

---

### B3c — ⭐ Worker + Fake backend · ✅ เสร็จ · ทดสอบบนเครื่องแล้ว

**เพิ่ม:** `UploadTransport` interface · `FakeUploadTransport` (presign ปลอม → เขียนลง `filesDir/fake_r2/` → complete ปลอม พร้อม option ให้ล้มแบบสุ่มได้) · `UploadWorker` · state machine เต็มตาม §3 · backoff สองชั้น · `Constraints(NetworkType.CONNECTED, requiresBatteryNotLow = true)` ตาม [§2.6](#26--เครอขาย)

**ทดสอบบนเครื่องแล้ว** — Xiaomi peridot · คิวจริง 45 แถว (import 15 + live 30)

| การทดสอบ | ผล |
|--|--|
| **migration v1 → v2** | 45 แถวเดิมรอดครบ ไม่ถูก drop — `MIGRATION_1_2` ทำงาน ไม่ได้ตกไปที่ตาข่าย destructive |
| **drain ปกติ** | 45 → `Success` · sink มี object 45 ชิ้น · manifest 45 บรรทัด **ไม่ซ้ำสักคีย์** · ไม่มี `.part` ตกค้าง |
| ⭐ **`Uploaded` → `Success`** | ฉีดให้ complete ล้ม 5 ครั้งแรก → 5 แถวค้างที่ `Uploaded` พร้อม `remoteKey`/`remoteUri` (ไม่ใช่ `Failed`) · รอบถัดมา **COMPLETE 5 ครั้ง PUT 0 ครั้ง** |
| ⭐ **kill กลางทาง** | หน่วง PUT 400 ms แล้ว `am force-stop` ระหว่าง drain → เหลือ `Uploading` ค้าง 1 แถว · เปิดใหม่ → `Requeued 1 row(s) interrupted mid-upload` → จบที่ 45 `Success` · manifest ยัง **45 คีย์ไม่ซ้ำ** |

**บั๊กที่เจอตอนเขียนและแก้ก่อนทดสอบ** — เดิม `complete` ที่ล้มหลัง PUT สำเร็จจะถูก `markFailed` ลดสถานะเป็น `Failed` ซึ่งทำให้ retry **ส่งไฟล์ทั้งก้อนซ้ำ** ลบล้างเหตุผลทั้งหมดที่มีสถานะ `Uploaded` · แก้เป็น `markFailed(bytesUploaded = true)` ที่คงสถานะ `Uploaded` ไว้พร้อม backoff และแก้ `retryAllFailed` ให้ส่งแถวที่มี `remoteKey` กลับไป `Uploaded` ไม่ใช่ `Pending`

| **Pause / Resume** | กด Pause ตอนคิวว่าง → `shared_prefs` เก็บ `paused=true` · ยัด 45 แถวกลับเข้าคิว → **0 PUT · 0 COMPLETE · worker ไม่รันเลย** แม้เปิดแอปใหม่ · กด Resume → ระบายครบ 45 ทันที |
| **object key deterministic** | drain ไฟล์ชุดเดิมสามรอบ → manifest 135 บรรทัด แต่ **45 คีย์ไม่ซ้ำ และ object 45 ชิ้น** — ส่งซ้ำเขียนทับ ไม่สร้างใบใหม่ ([§2.3](#23--object-key-deterministic)) |

**ยังไม่ได้ทดสอบ:** `Unauthorized` → พักทั้งคิว — ต้องมี backend จริงถึงจะเกิด ปิดใน B3e/B3f

**ป้ายสถานะบนจอ = ชื่อสถานะจริง** — ตอนแรกใช้คำที่อ่านง่ายกว่า (`Committing` / `Done` / `Given up`) แล้วพบว่าเทียบกับ log หรือ `SELECT status` ไม่ได้ · คนที่อ่านคิวนี้ตอนนี้คือคนที่กำลังดีบักมัน จึงใช้ชื่อสถานะตรงๆ · `Uploaded` ที่อยู่ข้าง `Success` ดูขัดตาจนกว่าจะรู้ว่ามันคนละเรื่อง ซึ่งความต่างนั้นคือหัวใจของ state machine จึงควรโชว์ ไม่ใช่กลบ

**เครื่องมือ debug ที่เพิ่มมา** — ทั้งหมดปิดตายใน release ด้วย `BuildConfig.DEBUG`:

```bash
adb shell am start -n com.autobots.camera/com.autobots.MainActivity \
    --es dest upload \
    --ei failComplete 5     # ให้ complete ล้ม N ครั้งแรก (นับถอย ไม่ใช่สุ่ม)
    --ei failPut 3          # ให้ PUT ล้ม N ครั้งแรก
    --ei delayMs 400        # หน่วงทุก PUT เพื่อให้แทรกการฆ่าโปรเซสได้
    --ez requeueAll true    # รีเซ็ตทุกแถวกลับ Pending เพื่อรัน drain ซ้ำ
```

**นี่คือ slice ที่ใหญ่ที่สุดและเป็นตัวที่ทำให้ "เตรียมพร้อมไว้ก่อน" มีความหมายจริง** — จบข้อนี้แล้วสิ่งที่เหลือคือเปลี่ยน implementation ของ interface เดียว

---

### B3d — ตั้งค่า endpoint + identity · ✅ เสร็จ · ทดสอบบนเครื่องแล้ว

**เพิ่ม:** `OperatorDestination.UploadSettings` · ที่เก็บค่าคงทน (DataStore) สำหรับ `backendBaseUrl` / `deviceId` / `token` — **ถ้าใช้ Runx ([§10](#10-transport-ทมของจรงแลว--runx-graphql)) คือ `platform` / `token` / `eventId`** · **สแกน QR เพื่อกรอกทั้งชุด** โดยใช้ `QrScanPreview` เดิม · ปุ่ม *Test connection*

**รูปแบบ QR ที่เสนอ** — JSON บรรทัดเดียว ให้หลังบ้านออก QR ให้เครื่องแต่ละตัว:

```json
{"url":"https://api.example.com","deviceId":"cam-07","token":"..."}
```

ถ้าภายหลังเปลี่ยนไปใช้ login ในแอป กระทบแค่หน้านี้ — คิว worker และ transport ไม่รู้จักที่มาของค่าอยู่แล้ว

**ทำแล้ว:** [UploadConfig.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadConfig.kt) · [UploadSettingsPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/UploadSettingsPage.kt) · เข้าจากปุ่ม **Settings** บนหน้า Upload

5 ค่า ตรงกับ contract ของ Runx ใน [§10](#10-transport-ทมของจรงแลว--runx-graphql): `graphqlUrl` · `completeUrl` · `platform` · `token` · `eventId`

**ทดสอบบนเครื่องแล้ว:**

| ตรวจ | ผล |
|--|--|
| provisioning ทั้งชุด | ป้อน JSON ผ่านเส้นทางเดียวกับ QR → 5 ค่าลง `shared_prefs` ครบ |
| รอดข้ามการรีสตาร์ต | force-stop แล้วเปิดใหม่ ค่ายังอยู่และแสดงบนจอครบ |
| **QR บางส่วน** | ส่งแค่ `{"eventId":"…"}` → เปลี่ยนเฉพาะ event · URL/token/platform คงเดิม |
| **QR ที่ไม่ใช่ของเรา** | ส่งสตริงที่ไม่ใช่ JSON → **ไม่แตะ config เดิมเลย** · หน้าจอขึ้นว่าไม่ใช่ upload configuration |
| การบดบัง token | บรรทัด "Token in use" แสดง `tok_…5678 (20 chars)` ไม่ใช่ค่าเต็ม |

**สามข้อที่เบี่ยงจากแผนเดิม โดยตั้งใจ:**

1. **`SharedPreferences` ไม่ใช่ DataStore** — แผนเขียนว่า DataStore แต่พอถึงจริงมันเก็บ boolean หนึ่งตัวกับสตริงสั้นห้าตัว อ่านโดย worker ที่อ่าน prefs แบบ synchronous ระหว่างไฟล์อยู่แล้ว · DataStore ให้ async กับ typed schema ที่ไม่มีใครต้องการที่นี่ แลกกับการมี**ที่เก็บค่าสองที่**อยู่ข้างกัน เพราะ flag pause อยู่ใน prefs ไปแล้ว
2. **ไม่มีปุ่ม *Test connection*** — ยังไม่มี transport จริงให้ทดสอบ ปุ่มที่กดแล้วไม่ได้ยิงอะไรจริงคือปุ่มที่โกหก · ตอนนี้ validate รูปแบบ URL แบบ inline แทน และเลื่อน probe จริงไป **B3e** ที่ยิง `photoUpload` ได้
3. **worker ยังใช้ fake transport ไม่ว่าจะตั้งค่าหรือไม่** — กติกา "ไม่ได้ตั้งค่า → worker ไม่ทำงาน" จะมีความหมายก็ต่อเมื่อมีของจริงให้ทำ · ตอนนี้ `isComplete` ขับแค่ข้อความบนจอ ส่วนการเลือก transport ตามค่า config เป็นงานของ B3e

**ค่าเริ่มต้นจาก `.env`** — บิลด์หนึ่งตัวสามารถมี backend ตั้งไว้แล้วได้ ([BUILD.md §0](./BUILD.md)) · Gradle อ่าน `.env` ตอน configure แล้วยัดเข้า `BuildConfig` · แอปคัดลอกเข้า settings **เฉพาะรอบแรกที่ยังไม่มีอะไรเก็บไว้เลย**

| ตรวจบนเครื่องแล้ว | ผล |
|--|--|
| `.env` มีค่า + ล้างข้อมูลแอป | 5 ค่าถูก seed ลง prefs ครบตั้งแต่เปิดครั้งแรก |
| แก้ค่าในแอปแล้วรีสตาร์ต | **ไม่ถูก .env เขียนทับ** — `OPERATOR_EDIT` ยังอยู่ |
| `.env` ว่าง + ล้างข้อมูลแอป | ไม่มีไฟล์ prefs ถูกสร้างเลย ช่องว่างให้ผู้ใช้กรอก |

กติกา "seed เฉพาะตอนไม่มีอะไรเก็บไว้เลย" รักษาสองสัญญาพร้อมกัน: เครื่องที่แฟลชจากบิลด์ที่ตั้งค่าไว้ใช้ได้ทันที และค่าที่ผู้ใช้แก้เองในสนามไม่ถูกย้อนกลับเงียบๆ · ผลพลอยได้คือ *Clear configuration* = กลับไปใช้ค่าของบิลด์

> ⚠️ `.env` อยู่ใน `.gitignore` แล้ว และ **ค่าที่ baked เข้า APK อ่านออกได้** — บิลด์ที่ไม่ได้คุมปลายทางเองควรเว้น token ว่างแล้วใช้ QR แทน

**เครื่องมือ debug เพิ่ม** — ป้อน payload เดียวกับที่ QR จะให้ ผ่านโค้ด `mergeFromQr` ตัวเดียวกัน:

```bash
adb shell "am start -n com.autobots.camera/com.autobots.MainActivity \
    --es dest uploadsettings --es config '{\"eventId\":\"…\"}'"
```

---

### B3e — Login · event picker · transport จริง

#### B3e-1 — เข้าสู่ระบบและเลือก event ✅

**เพิ่มแล้ว:** [RunxAuthClient.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/RunxAuthClient.kt) (`authAdminUser` + `eventItems`) · [UploadSession.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadSession.kt) · ส่วน *Sign in* และ dropdown เลือก event ใน [UploadSettingsPage.kt](../androidApp/src/main/kotlin/com/autobots/ui/UploadSettingsPage.kt)

**กติกาที่ล็อก**

| | |
|--|--|
| **token ไม่ลงดิสก์เลย** | อายุ 7 วัน เก็บได้ก็จริง แต่ไม่เก็บ — เครื่องภาคสนามถูกทิ้งไว้ในกระเป๋าระหว่างงาน · ต้อง login ใหม่ทุกครั้งที่เปิดแอป |
| **ติ๊ก "จำ username/password" ได้** | ปิดไว้เป็นค่าเริ่มต้น · เป็นสิ่งที่ทำให้ "login ใหม่ทุกครั้ง" เหลือแค่แตะปุ่มเดียว ไม่ใช่การกลับไปหาออฟฟิศ · **ไม่ auto login** ตามที่ตกลงไว้ |
| **prefs ถูกกันออกจาก backup** | [backup_rules.xml](../androidApp/src/main/res/xml/backup_rules.xml) + [data_extraction_rules.xml](../androidApp/src/main/res/xml/data_extraction_rules.xml) · `allowBackup=true` ทั้งแอป ถ้าไม่กัน รหัสผ่านจะไหลออกทาง `adb backup`/cloud restore |
| **หน้าแรกมีการ์ด Upload** | บอก user · event · progress bar · จำนวนที่ขึ้นแล้ว/ที่ยอมแพ้ · หน้าคิวบอกครบอยู่แล้วแต่ไม่มีใครเปิดตอนกำลังถ่าย |
| **หน้า Upload บอกว่า "ใครส่งเข้างานไหน"** | ทั้งสองค่าถูกตั้งจากอีกหน้าหนึ่งและผิดได้ง่ายทั้งคู่ · ทั้ง session ลงงานของเมื่อวานได้โดยไม่มีอะไรดูผิดปกติเลย และหน้าคิวคือหน้าที่คนเฝ้าจริงตอนถ่าย · ยังไม่ login หรือยังไม่เลือกงาน = สีเหลืองเตือน |
| **eventId อยู่ในค่าที่คงทน** | มันคือ "งานของวันนี้" ไม่ใช่ credential — ต้องรอดจากการรีสตาร์ตเหมือนคิว · เก็บ `eventTitle` คู่ไว้ด้วยเพื่อให้เครื่องที่เปิดแบบออฟไลน์ยังบอกได้ว่าตั้งไว้ที่งานไหน |
| **ตามหน้า (paging) จนครบ** | โค้ด Python อ่านแค่หน้าแรก · ที่นี่วนตาม `pageInfo.pageCount` (เพดาน 10 หน้า) และ**ประกาศบนจอเมื่อโดนตัด** — event ที่หายไปจาก dropdown เงียบๆ คือความล้มเหลวที่แย่ที่สุดของหน้านี้ |
| **dropdown คือหน้าตาหลัก ไม่ใช่ช่องค้นหา** | list โหลดเองหลัง login และมีครบทุกงานที่บัญชีนั้นเห็น · ช่องกรองด้วยชื่อโผล่**เฉพาะตอนที่ list ยาวเกินเพดาน** เท่านั้น — นอกจากกรณีนั้นมันคือช่องที่ขอให้คนพิมพ์งานที่ dropdown ทำให้แล้ว |
| **build default ใช้ครั้งเดียวต่อโปรเซส** | `UploadSettings` ถูกสร้างหลายที่ (ViewModel · worker · MainActivity) ถ้าไม่กัน ทุกการสร้างจะเอา URL จาก `.env` มาทับใหม่ กลายเป็น "ทับตอนไหนก็ได้" แทน "ทับตอนเปิดแอป" และลบค่าที่เพิ่งแก้ไปเมื่อ 2 วินาทีก่อนจาก background thread ได้ |
| **query เหลือ 5 field** | ของเดิมขอ ~40 field รวม `bankAccount`/`creditBalance` · payload ใหญ่บน 4G และ**ทั้ง query พังถ้าบัญชีไม่มีสิทธิ์อ่าน field ใด field หนึ่ง** |

**ทดสอบบนเครื่องแล้ว** ด้วย mock GraphQL server ผ่าน `adb reverse` — รหัสผ่านผิด (error ใน HTTP 200) · login สำเร็จ + ดึง 12 event ครบ 3 หน้า · เลือกจาก dropdown แล้ว `eventId`/`eventTitle` ลง prefs · ติ๊กจำแล้วรีสตาร์ต ฟอร์มเติมให้แต่**ไม่ login เอง** · token ถูกปฏิเสธ → เด้งกลับหน้า login โดย event ที่เลือกไว้ยังอยู่

**เสร็จแล้วใน B3e-2:** worker อ่าน token จาก `UploadSession` และเลือก transport ตามสถานะจริง

#### B3e-2 — Transport จริง ✅

**เพิ่มแล้ว:** [RunxUploadTransport.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/RunxUploadTransport.kt) — presign (GraphQL) → PUT (signed URL) → complete (form POST) ใช้ `HttpURLConnection` ตัวเดียวกับ `RunxAuthClient` ไม่ต้องดึง ktor client เข้ามา

**กติกาที่ล็อก**

| | |
|--|--|
| **ไม่ได้ตั้งค่า / ยังไม่ login = worker ไม่ทำงาน** | คืน `Result.success()` แบบไม่ทำอะไร ไม่ใช่ fallback ไป fake sink · ของเดิมจะรายงานว่า "อัปแล้ว" ทั้งที่ไฟล์นอนอยู่ในโฟลเดอร์บนเครื่อง · ทั้ง login และ save config เรียก `ensureScheduled` ให้เอง |
| **fake transport ต้องเปิดเอง** | `--es fake on` เท่านั้น · ปิดเป็นค่าเริ่มต้นเพราะ "อัปโหลดที่แอบเขียนลงเครื่องเงียบๆ" แย่กว่า "ไม่อัปโหลด" |
| **เลือก transport ครั้งเดียวต่อรอบ** | drain รอบเดียวจะสลับ backend หรือสลับตัวตนกลางทางไม่ได้ |
| **อ่านไฟล์เข้า memory ก่อน PUT** | `Content-Length` ตรงเป๊ะ · ถ้าใช้ `sizeBytes` จากแถวคิวมันคือการเดาขนาดไฟล์ที่ MediaStore เป็นเจ้าของ และ signed PUT ที่ length ไม่ตรงจะพังแบบอ่านไม่ออก · ทีละใบ ไม่กี่ MB |
| **ส่ง `$path` เป็นโฟลเดอร์** | ยิงถาม API จริงแล้ว (ดู §10 ข้อ 2): ทุก segment ถูกเก็บไว้ครบ แต่ **server ยังต่อชื่อไฟล์ UUID ของตัวเองท้ายเสมอ** · `path` จึงจัดระเบียบ bucket ได้ แต่ทำ deterministic key ไม่ได้ · ส่งเป็น `<eventId>/<sessionId>` เพื่อให้ตรวจสอบย้อนหลังได้ว่า session หนึ่งผลิตอะไรบ้าง |
| **`key` = คีย์เต็มของ object** | ทุก segment หลังชื่อ bucket ใน `downloadUrl` · ถ้าไม่ส่ง `path` มันยุบเหลือชื่อไฟล์เปล่าซึ่งเท่ากับที่ Python ส่งพอดี — เป็นการขยาย ไม่ใช่การเปลี่ยน · endpoint จริงรับทั้งสองแบบ (ทดสอบแล้ว) |
| **4xx = permanent · 5xx/timeout = retryable · 401/403 = พักทั้งคิว** | ยกเว้น 408/429 ที่ถือเป็น retryable |

> ⚠️ **ผลของการให้ server มินต์ key: requeue = object ซ้ำ** · แถวที่ถูกส่งกลับไป `Pending` จะ presign ใหม่ ได้ key ใหม่ ไฟล์เดิมจึงขึ้นไปเป็น object ที่สอง · เส้นทาง `Uploaded → Success` **ไม่ซ้ำ** เพราะใช้ key ที่เก็บไว้ · นี่คือเหตุผลที่ [§2.3](#23--object-key-deterministic) อยากได้ deterministic key และทำให้**คำถามข้อ 2 สำคัญกว่าที่คิดไว้ตอนแรก**

**ทดสอบบนเครื่องแล้ว** ด้วย mock backend เต็มรูปแบบ (GraphQL + PUT + `/success`) ผ่าน `adb reverse`:

| | ผล |
|--|--|
| ยังไม่ login | `Not signed in — standing by` · ไม่มี request ออก ไม่มีไฟล์ถูกเขียน |
| import วิดีโอจริง 1 นาที 4K | 15 ใบ → **SIGN 15 · PUT 15 · complete 15** เรียงถูกลำดับทุกใบ |
| header บน PUT | `Content-Type: image/jpeg` ตรงกับที่ sign · `User-Agent: autobots-android/0.1.5` |
| ฟิลด์ที่ `/success` | `eventId` · `key` (จาก `downloadUrl`) · `name` (ชื่อไฟล์เดิม) · `uri` + `Authorization: Bearer` |
| complete พัง 1 ครั้ง (500) แล้ว requeue ทั้งคิว | **PUT 30 ไม่ใช่ 31** — แถวที่ complete พังอยู่ที่ `Uploaded` แล้วยิงแค่ complete ซ้ำ ไม่ส่งไฟล์ใหม่ |

**ยิงกับ backend ของจริงแล้ว ✅** (`api.photo.thai.run` · event `test-upload`) — login ด้วย username/password จริงได้ JWT 1379 ตัวอักษร · `eventItems` คืน 1 งานตรงกับ `UPLOAD_EVENT_ID` · import คลิป 1 นาที 4K → **15 ใบ PUT 15 · COMPLETE 15 · fail 0** ประมาณ 1 วินาทีต่อใบ (ไฟล์ ~1.6 MB) · key ที่ server มินต์เป็น UUID `.jpeg`

**ยังไม่ได้ทดสอบกับของจริง:** `Unauthorized` → พักทั้งคิว (ต้องรอ token หมดอายุจริง 7 วัน หรือให้ backend ปฏิเสธ)

**ทดสอบกับ backend จริงไม่ได้ ก็ทดสอบกับ Ktor server ในแอปได้** — โปรเจกต์รัน `embeddedServer` อยู่แล้วที่พอร์ต 8080 ([AutobotsServer.kt](../androidApp/src/main/kotlin/com/autobots/camera/network/AutobotsServer.kt)) เพิ่ม route `/uploads/presign` + `/uploads/complete` แบบ stub เข้าไปชั่วคราว แล้วชี้แอปมาที่ตัวเองผ่านหน้าตั้งค่าใน B3d ได้เลย

**เสร็จเมื่อ:** วงจรครบผ่าน HTTP จริง (แม้จะเป็น loopback) · error 4xx/5xx แยกเป็น `ABANDONED`/`FAILED` ถูกต้อง

---

> **B3c ต้องทำ schema v2 ด้วย** ถ้าเลือก Runx — เพิ่ม `remoteKey` / `remoteUri` แบบ nullable ลง `UploadItem` มิฉะนั้นสถานะ `UPLOADED` จะ retry ไม่ได้ เหตุผลเต็มอยู่ที่ [§10](#10-transport-ทมของจรงแลว--runx-graphql)

### B3f-0 — ทดสอบพื้นหลัง 🔴 **ไม่ผ่าน** (20/08/2026)

คำถามที่ค้างมาตั้งแต่ [§2.6](#26--เครอขาย) — "คิวจะรอดไหมตอนไม่มีใครดู" — ตอบแล้ว **ไม่รอด**

| สถานการณ์ | ผล |
|--|--|
| จอดับ · เสียบสายชาร์จ | ✅ ผ่าน · 15 ใบครบ จบหลังจอดับ 12 วินาที |
| เครื่องนิ่งจนเข้า Doze (`battery unplug` + `deviceidle force-idle`) | 🔴 **ไม่ผ่าน · 7 นาทีได้แค่ 6 จาก 15 ใบแล้วหยุดสนิท** |

log ของรอบที่ไม่ผ่าน เล่าเรื่องพังสองชั้นซ้อนกัน:

```
15:18:51  Work […UploadWorker] was cancelled
15:19:40  Retryable failure: PUT failed … ETIMEDOUT
15:20:30  Work […UploadWorker] was cancelled
15:23:31  Not signed in — standing by      ← โปรเซสใหม่ (pid 21267 → 24104)
```

| ชั้น | อาการ | แก้ด้วย |
|--|--|--|
| **1 · Doze** | WorkManager ถูกยกเลิกซ้ำๆ · เน็ตถูกตัด (**แม้แต่ loopback ก็ ETIMEDOUT**) | foreground service |
| **2 · โปรเซสถูกฆ่า** | token อยู่ใน memory เท่านั้น ([UploadSession]) → หายไปพร้อมโปรเซส · worker ตื่นมาก็ยืนเฉย | foreground service (ลดโอกาสตาย) + auto re-login จากรหัสที่ติ๊กจำไว้ |

**ข้อสรุป:** เงื่อนไขที่ทำให้ Doze ทำงานคือ "วางเครื่องนิ่งไว้นานๆ" ซึ่งเป็น**ท่ามาตรฐานของกล้องบนขาตั้ง** ไม่ใช่กรณีขอบ · foreground service เลิกเป็นทางเลือกแล้ว

---

### B3f-1 — Foreground service ✅ **ผ่าน** (20/08/2026)

**เพิ่ม:** [UploadNotification.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadNotification.kt) · `setForeground()` ใน [UploadWorker.kt](../androidApp/src/main/kotlin/com/autobots/camera/upload/UploadWorker.kt) · permission + `foregroundServiceType="dataSync"` · auto re-login จากรหัสที่ติ๊กจำไว้

**ไม่ได้เขียน Service ใหม่** — WorkManager ให้ worker ยกระดับตัวเองได้ผ่าน `setForeground()` · Service แยกแปลว่ามีวงจรชีวิตที่สองที่ต้องซิงก์กับคิว ซึ่งเป็นแหล่งบั๊กที่ไม่จำเป็น · **ยกระดับไม่สำเร็จก็ไม่ตายทั้งรอบ** — log warning แล้วทำงานต่อแบบเดิม

**ผลทดสอบ** (เงื่อนไขเดียวกับ B3f-0 แต่โหดกว่า — บังคับ deep idle **ทันที** ที่เปิด auto-upload ไม่ให้อัปไปก่อนแม้แต่ใบเดียว):

| | B3f-0 (ไม่มี FGS) | **B3f-1 (มี FGS)** |
|--|--|--|
| ผลลัพธ์ | 6 จาก 15 ใบ แล้วหยุดสนิท | **16 จาก 16 ใบ ใน 4 นาที 30 วินาที** |
| `was cancelled` ระหว่าง idle | 3 ครั้ง | **0** |
| โปรเซส | ถูกฆ่า (pid 21267 → 24104) | **รอดทั้งรอบ (pid 28696 ตลอด)** |
| `mState` | IDLE | IDLE ตลอดเช่นกัน |

ระบบยืนยันเองใน log: `Background started FGS: Allowed … SystemForegroundService … code:PROC_STATE_FGS`

**ตัวเลขต้องอยู่ใน title** — HyperOS ตัด `contentText` ทิ้งทั้งบรรทัดเมื่อการแจ้งเตือนมี progress bar เหลือแต่แถบเปล่าๆ ที่บอกว่า "ยุ่งอยู่" แต่ไม่บอกว่าใกล้เสร็จหรือเพิ่งเริ่ม · `title` กับ `subText` รอดจาก layout นั้น จึงย้ายจำนวนและ % ไปไว้ที่ title และเอาชื่อ event ไว้ที่ subText → `Uploading 2/4 · 50% • Notif test`

> ⚠️ **FGS กันโปรเซสตายเฉพาะตอนที่มีงานเดินอยู่** · ระหว่างรอบ (คิวว่าง แอปปิด) โปรเซสยังถูกฆ่าได้ตามปกติ และ token ที่อยู่ใน memory ก็หายไปด้วย · **auto re-login จึงไม่ใช่ของแถม** — ถ้าไม่ติ๊ก "จำ username/password" worker ที่ถูกปลุกมาในโปรเซสใหม่จะขึ้น `Not signed in — standing by` แล้วคิวค้างอยู่ดี (เห็นเองระหว่างเก็บกวาดหลังทดสอบ)

---

### B3f — R2 + backend จริง (**บล็อกอยู่ · รอ infra**)

ตั้งค่า bucket · SigV4 presign ฝั่ง backend · field test บนเครือข่ายจริง · เก็บตัวเลข throughput/ความล้มเหลวเป็นรายงานเวอร์ชัน

**สิ่งที่ต้องเฝ้าในการทดสอบภาคสนามครั้งแรก:** อัตราการล้มของ PUT บน 4G · เวลาที่ใช้ต่อรูปเทียบกับขนาด · แบตที่หายไประหว่างระบาย 134 ไฟล์ · ความร้อน (pipeline ตัวนี้ทำงานบนเครื่องที่เพิ่ง extract UHD มา) · **คิวค้างเพราะระบบตัดงานเบื้องหลังหรือไม่** — ถ้าค้าง นั่นคือหลักฐานว่าต้องมี foreground service ([§2.6](#26--เครอขาย))

---

### B3g — หลังจากนั้น (ยังไม่วางแผนละเอียด)

> **วิดีโอ chunk ถูกตัดออกจากขอบเขตแล้ว** ([§2.4](#24-ขอบเขต--รปเทานน)) ไม่อยู่ในลิสต์นี้และไม่มีแผนรองรับ — ถ้าวันหนึ่งต้องการ ต้องออกแบบ multipart ใหม่ ไม่ใช่ต่อยอดจากที่ทำไว้

> **การลบไฟล์ในเครื่องถูกตัดออกทั้งหมด** ([§2.5](#25--ไมลบไฟลในเครอง)) · **Wi-Fi-only toggle ถูกตัดออก** ([§2.6](#26--เครอขาย)) — ทั้งสองอย่างไม่อยู่ในลิสต์นี้แล้ว

| หัวข้อ | ทำไมแยกออกมา |
|--|--|
| **Foreground service** | ทำเมื่อ field test พิสูจน์ว่าจำเป็นเท่านั้น · ต้องเพิ่ม permission + merge `foregroundServiceType="dataSync"` ([§1](#ยงไมม-)) |
| **ลบแถวที่ `ABANDONED` ออกจากคิว** | ลบ*แถวในคิว* ไม่ใช่ลบรูป · ต้องมี UI ให้คนตัดสินใจ ไม่ควรลบเงียบ |

---

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

**ไม่มีคำถามที่บล็อกอยู่แล้ว — เริ่ม B3a ได้ทันที**

การออกแบบทั้งหมดถูกล็อกไว้ใน [สรุปสำหรับผู้ตัดสินใจ](#สรปสำหรบผตดสนใจ) ข้อ 1–9 · เหลือเรื่องเดียวที่ยัง**ไม่ต้อง**ตอบตอนนี้ และตั้งใจเลื่อนไปให้ข้อมูลจริงเป็นคนตอบ:

| เรื่อง | ทำไมเลื่อนได้ | ใครเป็นคนตอบ |
|--|--|--|
| **ต้องมี foreground service ไหม** | ขึ้นกับว่าระบบตัดงานเบื้องหลังจริงหรือเปล่า ซึ่งเดาจากโค้ดไม่ได้ · ระหว่างนี้ `requiresBatteryNotLow = true` ทำให้พฤติกรรมปลอดภัยไว้ก่อน | **field test ใน B3f** — ถ้าคิวค้างโดยไม่มี error นั่นคือคำตอบ |

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
