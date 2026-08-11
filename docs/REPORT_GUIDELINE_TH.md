# แนวทางเขียน Version Report (ภาษาไทย)

ฉบับภาษาไทยของ [REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md) — เนื้อหาเดียวกัน
ถ้าสองไฟล์ขัดกันเมื่อไร ให้ยึด **ฉบับภาษาอังกฤษ** เป็นหลัก

> ตัว **report จริง เขียนเป็นภาษาอังกฤษ** — ไฟล์นี้เป็นแค่คู่มือวิธีเขียนสำหรับคนไทยอ่าน

Report คือเอกสารที่ตอบคำถามเดียว: **เวอร์ชันนี้ใช้งานได้จริงไหม และดีแค่ไหน?**
เขียนหลังจาก build เสร็จและทดสอบภาคสนามแล้ว

เกี่ยวข้อง: [CONVENTIONS.md](./CONVENTIONS.md) (กฎการเขียน docs) · [CHANGELOG.md](./CHANGELOG.md) (อะไรที่ส่งไป) · [DOCS.md](./DOCS.md) (สารบัญ)

---

## 1. ทำไมต้องมีไฟล์นี้

| ไฟล์ | ตอบคำถาม | เขียนตอนไหน |
|------|----------|-------------|
| [`CHANGELOG.md`](./CHANGELOG.md) | เวอร์ชันนี้ส่งอะไรไปบ้าง? | ตอน release |
| `reports/vX.Y.Z/report.md` | **ใช้ได้จริงไหม ดีแค่ไหน อะไรยังค้างอยู่?** | หลังทดสอบ |

สองไฟล์นี้ **ไม่เหมือนกัน** — changelog คือรายการฟีเจอร์ ส่วน report คือ **หลักฐาน**:
input ที่ใช้ทดสอบ, สิ่งที่คาดหวัง, ผลที่วัดได้จริง, คะแนน, และคำตัดสิน

กฎจาก [CONVENTIONS.md §1](./CONVENTIONS.md) ใช้ที่นี่ด้วย: **ห้าม copy เนื้อหาข้ามไฟล์ — ให้ link แทน**
report ควร link ไป `PIPELINE_FLOW.md` เพื่ออธิบายว่า pipeline ทำงานยังไง ไม่ใช่เขียนซ้ำเอง

---

## 2. โครงสร้างโฟลเดอร์

หนึ่งโฟลเดอร์ต่อหนึ่งเวอร์ชัน ชื่อโฟลเดอร์ = `appVersionName` ใน `gradle.properties` เติม `v` ข้างหน้า

```
reports/
  .gitignore
  v0.1.2/
    report.md      ← ตัว report            (commit)
    inputs.md      ← manifest ของ input    (commit)
    input/         ← ไฟล์วิดีโอต้นทาง        (git-ignored)
    output/        ← JPEG, session_log.txt ที่ได้ (git-ignored)
    logs/          ← logcat ดิบ            (git-ignored)
    assets/        ← screenshot ที่ report.md ใช้ (commit ได้ ถ้าไฟล์เล็ก)
```

### ไฟล์มีเดียห้าม commit

วิดีโอทดสอบและ gallery ที่ได้ เป็นไฟล์ binary ขนาดใหญ่ ถ้า commit จะทำให้ repo บวมถาวร (ลบทีหลังก็ไม่หาย)

- เก็บไฟล์จริงไว้ใน `input/` และ `output/` ในเครื่องตัวเอง (หรือ storage ที่แชร์กัน)
- ให้ commit `inputs.md` แทน — บันทึกชื่อไฟล์, ความยาว, resolution, ขนาด, `sha256`, และไฟล์อยู่ที่ไหน
- ใน `report.md` ให้ paste **log สั้น ๆ เฉพาะท่อนสำคัญ** เท่านั้น log เต็มอยู่ใน `logs/`
- screenshot ใน `assets/` commit ได้ ถ้าไฟล์ละไม่เกิน ~500 KB

---

## 3. โครงสร้างของ report

ใช้ section ตามนี้ เรียงตามนี้ ชื่อหัวข้อตามนี้ **ห้ามข้าม** — ถ้ายังไม่รู้ให้ใส่ `TBD`

| # | Section | เนื้อหา |
|---|---------|--------|
| 1 | **Snapshot** | ตารางเดียวจบ: version, วันที่, phase, verdict, คะแนน, คนทดสอบ, build — คนที่มีเวลา 30 วินาที อ่านแค่นี้ |
| 2 | **What & why** | เวอร์ชันนี้ทำอะไร และแก้ปัญหาอะไร 5–10 บรรทัด link ไป `PRD.md` / `IMPLEMENTATION.md` |
| 3 | **Scope** | ทำอะไร / ไม่ทำอะไร เขียนสิ่งที่ไม่ทำให้ชัดเจน |
| 4 | **Open questions** | เรื่องที่ยังไม่เคาะ: คำถาม, ตัวเลือก, ผลกระทบ, คนตัดสิน — นี่คือส่วน "ยังคิดค้างอยู่" |
| 5 | **How it works** | ไดอะแกรม 1 ภาพ + คำอธิบายสั้น รายละเอียดเต็ม link ไป `PIPELINE_FLOW.md` |
| 6 | **Test setup** | เครื่อง, Android version, build ที่ใช้, setting (resolution, extraction target) |
| 7 | **Test inputs** | ตาราง test case ใช้ ID `TC-01`, `TC-02`, … หนึ่งแถว = หนึ่ง input |
| 8 | **Expected results** | ใช้ `TC-` ID เดิม แต่ละข้อ **ต้องวัดได้** (เป็นตัวเลข, จำนวนไฟล์, หรือ state) |
| 9 | **Actual results** | `TC-` ID เดิม วางคู่กับ expected พร้อมสถานะ `PASS` / `PARTIAL` / `FAIL` และส่วนต่าง |
| 10 | **Score** | rubric ถ่วงน้ำหนัก — ดู §5 |
| 11 | **Verdict & next actions** | สรุป 3–5 bullet แล้วตาราง action ที่ต้องทำต่อ พร้อมเจ้าของงานและเวอร์ชันเป้าหมาย |
| 12 | **Review comments** | คอมเมนต์จากคนอื่น: ใคร, วันที่, ความเห็น, สถานะ |

### กฎที่ทำให้ report อ่านง่าย

- **ใช้ TC ID เดียวกันตลอด §7, §8, §9** — `TC-01` ใน inputs ต้องเป็น `TC-01` ใน expected และ actual นี่คือสิ่งที่ทำให้คนอ่านเทียบได้ทันที
- **Expectation ต้องเป็นตัวเลข ไม่ใช่คำคุณศัพท์** — เขียนว่า `ได้ JPEG ≥ 40 รูป` ไม่ใช่ `จับหน้าได้ดี`
- **คำสถานะใช้ได้แค่ชุดนี้:** `PASS` · `PARTIAL` · `FAIL` · `BLOCKED` · `TBD` ห้ามใช้คำอื่น
- **ทุกคะแนนต้องอ้างหลักฐานได้** — ช่อง score ต้องอ้าง TC ID หรือ log ห้ามให้คะแนนลอย ๆ
- **ไม่รู้ให้ใส่ `TBD` ห้ามเดา** — report ที่มี `TBD` ตามตรงยังมีประโยชน์ แต่ report ที่ใส่ตัวเลขมั่ว แย่กว่าไม่มี report

---

## 4. รหัส ID

| Prefix | ความหมาย |
|--------|----------|
| `TC-NN` | Test case (input หนึ่งชุด รันตั้งแต่ต้นจนจบ) |
| `OQ-NN` | Open question — เรื่องค้าง (§4) |
| `NA-NN` | Next action — งานที่ต้องทำต่อ (§11) |
| `RC-NN` | Review comment (§12) |

ID ไม่ซ้ำกัน **ภายใน report เดียว** และ **ห้ามเปลี่ยนเลขใหม่** หลังส่งให้คนอื่นดูแล้ว

---

## 5. เกณฑ์ให้คะแนน

ให้คะแนนแต่ละด้าน **1–5** แล้วคูณน้ำหนัก รวมเต็ม 100

| ด้าน | น้ำหนัก | 5 คือ | 1 คือ |
|------|---------|-------|-------|
| **Functional correctness** — ทำงานถูกไหม | 30 | ผ่านทุก TC | flow หลักทำไม่จบ |
| **Output quality** — ผลลัพธ์ใช้ได้ไหม | 25 | เอาไปใช้งานจริงได้เลย | ใช้ไม่ได้ / ผิดเป็นส่วนใหญ่ |
| **Stability** — เสถียรไหม | 20 | ไม่ crash ไม่มี race หยุดสะอาดทุกครั้ง | crash หรือข้อมูลหายในการใช้งานปกติ |
| **Performance** — เร็วพอไหม | 15 | ได้ตามเป้า throughput และความร้อน | ตามไม่ทัน / เครื่องร้อนจนทำงานต่อไม่ได้ |
| **Operator UX** — คนหน้างานใช้เองได้ไหม | 10 | operator ใช้เองได้ ไม่ต้องมี dev | ต้องให้ dev มานั่งกดให้ |

`weighted = คะแนน / 5 × น้ำหนัก` · `total = ผลรวม` (เต็ม 100)

### ช่วงคะแนน → คำตัดสิน

| คะแนนรวม | Verdict | ความหมาย |
|----------|---------|----------|
| ≥ 85 | **Ship** | ปล่อยได้เลย |
| 70–84 | **Ship with caveats** | ปล่อยได้ แต่ต้องบันทึกข้อจำกัดและกำหนดว่าจะแก้เมื่อไร |
| 50–69 | **Needs work** | ยังไม่ปล่อย ต้องแก้ตามรายการ |
| < 50 | **Blocked** | แนวทางหลักยังพิสูจน์ไม่ได้ ต้องกลับไปวางแผนใหม่ |

ถ้ามีด้านใดได้ **1 คะแนน** verdict สูงสุดได้แค่ **Needs work** ไม่ว่าคะแนนรวมจะเท่าไร

---

## 6. ขั้นตอนการทำงาน

1. build และติดตั้งเวอร์ชันนั้นก่อน — ดู [BUILD.md](./BUILD.md)
2. สร้างโฟลเดอร์ `reports/vX.Y.Z/` โดย copy template ใน §7
3. กรอก §1–§8 **ก่อนเริ่มทดสอบ** — expectation ที่เขียนหลังเห็นผลแล้ว ไม่ถือเป็น expectation
4. รันทดสอบ เก็บ output ลง `output/` เก็บ log ลง `logs/`
5. กรอก §9–§11
6. ส่งให้คนอื่นรีวิว บันทึกความเห็นใน §12 เป็น `RC-NN` พร้อมสถานะ
7. ถ้า report เปลี่ยนความเข้าใจว่าอะไรส่งไปแล้วบ้าง ให้อัปเดต [CHANGELOG.md](./CHANGELOG.md) / [DOCS.md](./DOCS.md) ตาม

ตาม [CONVENTIONS.md §7](./CONVENTIONS.md) — การ release เวอร์ชันหนึ่ง หมายถึงต้องอัปเดต
`gradle.properties`, `AutobotsApp.version`, `CHANGELOG.md` **และ** เพิ่ม `reports/vX.Y.Z/report.md`

---

## 7. Template

Template อยู่ใน [REPORT_GUIDELINE.md §7](./REPORT_GUIDELINE.md) — copy จากไฟล์นั้นไปวางใน `reports/vX.Y.Z/report.md`
(ตัว template เป็นภาษาอังกฤษ เพราะ report จริงเขียนอังกฤษ)

ตัวอย่างจริงที่กรอกแล้ว: [reports/v0.1.2/report.md](../reports/v0.1.2/report.md)

---

## เกี่ยวข้อง

- [REPORT_GUIDELINE.md](./REPORT_GUIDELINE.md) — ฉบับภาษาอังกฤษ (ยึดเป็นหลัก)
- [CONVENTIONS.md](./CONVENTIONS.md) — กฎการเขียน docs, การตั้งชื่อ Phase, checklist
- [CHANGELOG.md](./CHANGELOG.md) — แต่ละเวอร์ชันส่งอะไรไปบ้าง
- [BUILD.md](./BUILD.md) — build และติดตั้ง APK ที่จะทดสอบ
- [PIPELINE_FLOW.md](./PIPELINE_FLOW.md) — รายละเอียด pipeline ที่ report link ไป
