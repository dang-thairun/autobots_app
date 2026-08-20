# v0.1.6 — Upload  *(ร่าง · ยังไม่ปล่อย)*

> ⚠️ **เวอร์ชันปัจจุบันยังเป็น 0.1.5** — งาน upload ทั้งหมดอยู่บน 0.1.5 ที่ยังไม่ได้ตัดเวอร์ชัน
> เอกสารนี้เขียนรอไว้ ยังไม่มีผลจนกว่าจะ bump `appVersionName` จริง
>
> รายงานการเปลี่ยนแปลงจาก **v0.1.5** → **v0.1.6**
>
> **นี่คือ feature release** — ไม่มี TC ใหม่ ไม่มีตัวเลข perf ใหม่ ทุกอย่างที่ค้างใน 0.1.5 ยังค้างเหมือนเดิม
>
> **✅ ยิงขึ้น production จริงแล้ว** (`api.photo.thai.run` · event `test-upload`) — 15 ใบจาก import คลิป 1 นาที 4K · PUT 15 · complete 15 · fail 0

---

## สรุปผู้บริหาร

0.1.5 เปิดทางเข้าของงานได้สามทาง (live · ไฟล์ในเครื่อง · Network URL) แต่ปลายทางมีทางเดียวคือ **แกลเลอรีในเครื่อง** รูปที่ extract แล้วนอนอยู่ใน `DCIM/AutoBots/` รอให้คนมาเสียบสายดึงออก

0.1.6 ต่อปลายทางนั้นเข้ากับแพลตฟอร์ม Runx: รูปทุกใบที่ถูกส่งมอบจะเข้า **คิวบนดิสก์** แล้ว **WorkManager** ระบายออกไปเองเมื่อมีเน็ต

| | v0.1.5 | **v0.1.6** |
|--|--|--|
| **ปลายทางของรูป** | แกลเลอรีในเครื่อง | + อัปขึ้น event บนแพลตฟอร์ม |
| **ตัวตน** | ไม่มี | login ด้วย username/password · token อยู่ใน memory เท่านั้น |
| **เลือกงาน** | ไม่มี | dropdown ดึงจาก backend |
| **ความทน** | — | คิว Room + retry แบบ exponential + 6 สถานะ |
| **หน้าแรก** | ไม่รู้เรื่อง upload | การ์ดบอก user · event · progress · สวิตช์ auto-upload |

---

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

## Related

- [PHASES.md](./PHASES.md) — แผน B3 ทั้งหมดและกติกาที่ล็อกไว้
- [RELEASE_0_1_5.md](./RELEASE_0_1_5.md) — ingest ขาเข้า
- [BUILD.md](./BUILD.md) — `.env` และการตั้งค่า backend
