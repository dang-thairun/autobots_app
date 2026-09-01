# S0 · Baseline ก่อนเริ่ม v0.1.7

> **Column note (added after S4).** The measurements below read the `frames` column of v0.1.6
> `tracks.csv`. That column is now `framesSeen`, and `trackedRatio` sits next to it. The 34.3%
> figure for one-frame tracks still stands as measured, but S4 showed those tracks score
> `trackedRatio` 1.0 — they were seen once because they were only there once, not because the
> tracker lost them. **The number for S6 to improve is `trackedRatio`, not this one.**


**วัดเมื่อ:** 2026-08-28 · **จาก:** session v0.1.6 ที่มีอยู่บนเครื่อง `24069PC21G` (ไม่ได้รันใหม่)
**ข้อมูลดิบ:** [`raw/`](./raw/) — `tracks.csv` + `session_log.txt` ของ 11 session

---

## ผลสรุป

| | คำถาม | ผล | ตัดสิน |
|---|---|---|---|
| **S0.1** | ตอนแอปสลับไฟล์ ภาพขาดไปนานเท่าไร | ⛔ **วัดไม่ได้** | รอ S4 |
| **S0.2** | มีนักวิ่งกี่คนถูกหั่นที่รอยต่อไฟล์ | **4.2%** (13/312) | Bundle 3 คุ้มน้อยกว่าที่ประมาณ |
| **S0.3** | มีนักวิ่งกี่คนที่เห็นเฟรมเดียวแล้วหลุด | **34.3%** (107/312) | ✅ **Bundle 2 คุ้มทำ** |

---

## S0.1 — วัดไม่ได้ ยังไม่ใช่ "ผ่าน"

`session_log.txt` **ไม่ได้พิมพ์ `recordedAtEpochMs`** ([`PipelineSessionRecord.kt:296`](../../shared/src/commonMain/kotlin/com/autobots/camera/PipelineSessionRecord.kt#L296) บล็อก Chunk เขียนแค่ `Video` / `Duration` / `Sample interval`) ⇒ คำนวณช่องว่างระหว่างไฟล์ไม่ได้

และ session ทั้งหมดบนเครื่องเป็น **import** (`2026-asicsmeta-full-1.mp4`) — การตัดไฟล์ตอน import เป็น remux ที่ keyframe **ช่องว่างอาจเป็นศูนย์** ส่วน live เป็นการหมุน recorder จริง **คนละเรื่องกัน**

⇒ **ต้องมี 2 อย่างก่อนวัดได้:** S4 พิมพ์ `recordedAtEpochMs` ลง log · แล้วอัด **live** 3–5 นาที (ไม่ต้องมีคนในเฟรม — ข้อนี้วัดพฤติกรรม recorder)

---

## S0.2 — ถูกหั่นที่รอยต่อไฟล์ · **4.2%**

`ext_v0_1_6_26082026_1340` · 17 chunk · 312 tracks

```
track ที่จบติดท้ายไฟล์ (< 120 ms)           31
track ที่เริ่มติดต้นไฟล์ถัดไป (< 120 ms)      14
จับคู่ได้ (ระยะ centre < 0.25 · ขนาดต่างไม่เกิน 2 เท่า)   13   = 4.2%
```

ยืนยันกับ `_1343` ได้ **13/309 = 4.2%** เท่ากัน

> **แผนเคยประมาณไว้ 8–17%** ([PLAN §3.5](../../docs/V_0_1_7_PLAN.md)) — **วัดจริงได้ต่ำกว่าครึ่งของขอบล่าง**
> เหตุผลที่ประมาณสูงเกิน: คิดจาก "เวลาที่นักวิ่งอยู่ในเฟรม ÷ ความยาว chunk" แต่ track ส่วนใหญ่สั้นกว่าที่คิดมาก (มัธยฐาน 2 เฟรม = 0.24 วิ ไม่ใช่ 1–2 วิ)

---

## S0.3 — เห็นเฟรมเดียวแล้วหลุด · **34.3%**

```
กระจายจำนวนเฟรมต่อ track (312 tracks)
  1 เฟรม : 107  ██████████████████████████
  2 เฟรม :  51  ████████████
  3 เฟรม :  31  ███████
  4 เฟรม :  20  █████
  5 เฟรม :  20  █████
  6+     :  83  ████████████████████
```

**ไม่ใช่ noise** — ใน 107 ตัวนั้น:

| | จำนวน |
|---|---|
| `meanHeight ≥ 0.25` (ตัวใหญ่พอจะเป็น subject ได้จริง) | **32** |
| ได้รูปจริง | 7 |

เทียบกับ track ที่ได้รูป 33 ตัว ซึ่ง**เฉลี่ย 5.4 เฟรม** ⇒ 32 ตัวนี้คือคนที่ตัวใหญ่พอแต่**ระบบตามไม่ติด**

ยืนยันกับ session อื่น: `_1343` = **33.7%** · `_1336` = 56.1%

---

## ผลต่อแผน

| | เดิม | หลังวัด |
|---|---|---|
| **Bundle 2** (Score Each Person · 12d) | "ความเสี่ยงกลาง คุณค่ายังเป็นสมมติฐาน" | ✅ **ยืนยันแล้ว — ทำ** · 34% คือช่องว่างจริง |
| **Bundle 3** (Fix Split Runners · 4d) | รอ S0.1 | ⚠️ **ลดความสำคัญ** — 4.2% · ยังรอ S0.1 อยู่ดี |
| ตัวเลข 8–17% ใน PLAN §3.5 | ประมาณ | **แทนด้วย 4.2% ที่วัดจริง** |

**ลำดับที่แนะนำ:** Bundle 1 → Bundle 2 → (S0.1 หลัง S4 + live) → ตัดสิน Bundle 3

---

## วิธีทำซ้ำ

```bash
adb shell ls /sdcard/Download/AutoBots            # session ที่มี tracks.csv
adb pull /sdcard/Download/AutoBots/<session>/tracks.csv
# S0.3 : นับแถวที่ frames == 1  ÷  แถวทั้งหมด
# S0.2 : แถวที่ (durationChunk - lastUs) < 120ms  จับคู่กับ  แถว chunk ถัดไปที่ firstUs < 120ms
```
