# Screen mirror ด้วย scrcpy (Mac ↔ Android)

ใช้ดูและควบคุมหน้าจอมือถือที่รัน **AutoBots** บน Mac — ไม่ต้องแก้แอพ (แอพ v0.1.2 ยังไม่มี in-app screen mirror)

อ้างอิง: [OPERATOR_FLOW.md](./OPERATOR_FLOW.md) · [BUILD.md](./BUILD.md)

---

## ติดตั้ง (Mac)

```bash
brew install scrcpy android-platform-tools
```

ตรวจว่าใช้ได้:

```bash
adb version
scrcpy --version
```

---

## วิธีที่ 1 — USB (ง่ายสุด, แนะนำครั้งแรก)

### บนมือถือ

1. **Settings → About phone** → แตะ **Build number** 7 ครั้ง → เปิด Developer options
2. **Settings → Developer options** → เปิด **USB debugging**
3. เสียบสาย USB กับ Mac → อนุญาต **Allow USB debugging** (ติ๊ก Always allow ได้)



### บน Mac

```bash
adb devices
```

ต้องเห็นประมาณ:

```
List of devices attached
XXXXXXXX    device
```

ถ้าเป็น `unauthorized` → ดูมือถือ กด Allow อีกครั้ง

```bash
scrcpy
```

หน้าต่าง mirror จะขึ้นบน Mac · คลิก/พิมพ์บนหน้าต่าง scrcpy ควบคุมมือถือได้

### ตัวเลือกที่ใช้บ่อย

```bash
# ลดความละเอียด (เร็วขึ้น, เบากว่า)
scrcpy -m 1024

# ไม่ปลุกหน้าจอมือถือตอนเริ่ม
scrcpy --no-control

# บันทึกหน้าจอ
scrcpy --record=file.mp4
```

---



## วิธีที่ 2 — Wi‑Fi (`adb tcpip`)

ใช้เมื่อไม่อยากเสียบสาย (มือถือกับ Mac ต้อง **Wi‑Fi เดียวกัน**)

### ขั้นตอน

```bash
# 1. เสียบ USB ก่อน (ครั้งแรก / หลังรีบูตมือถือ)
adb devices
# ต้องเห็น serial + "device"

# 2. เปิดโหมด TCP/IP — ต้องใส่พอร์ต (มักใช้ 5555)
adb tcpip 5555

# 3. ดู IP มือถือ
#    Settings → Wi‑Fi → แตะเครือข่ายที่ต่ออยู่ → IP address
#    สมมติได้ 192.168.1.42

# 4. ต่อผ่าน Wi‑Fi
adb connect 192.168.1.147:5555

# 5. ตรวจสอบ
adb devices
# ต้องเห็น 192.168.1.42:5555    device

# 6. ถอด USB ได้ (ถ้าต้องการ)

# 7. เปิด mirror
scrcpy
# หรือ
scrcpy --tcpip=192.168.1.42:5555
```



### ข้อผิดพลาดที่เจอบ่อย


| อาการ                             | สาเหตุ                                   | แก้                                |
| --------------------------------- | ---------------------------------------- | ---------------------------------- |
| `adb: tcpip requires an argument` | รัน `adb tcpip` โดยไม่ใส่พอร์ต           | ใช้ `adb tcpip 5555`               |
| `cannot connect to …:5555`        | IP ผิด / คนละ Wi‑Fi / ยังไม่ `adb tcpip` | เสียบ USB ทำขั้น 1–2 ใหม่          |
| `device offline`                  | หลับ / เปลี่ยน Wi‑Fi                     | `adb disconnect` แล้ว connect ใหม่ |
| หลังรีบูตมือถือ Wi‑Fi ADB หาย     | ปกติ                                     | ทำขั้น USB + `adb tcpip 5555` ใหม่ |


```bash
adb disconnect 192.168.1.42:5555
adb connect 192.168.1.42:5555
```

---



## วิธีที่ 3 — Wireless debugging (Android 11+)

ไม่ต้องใช้ `adb tcpip` ถ้ามือถือรองรับ

### บนมือถือ

**Settings → Developer options → Wireless debugging** → เปิด

เลือก **Pair device with pairing code** — จะได้ IP, พอร์ต pair และรหัส 6 หลัก

### บน Mac

```bash
# ใส่ IP และพอร์ต pair จากหน้าจอมือถือ (ไม่ใช่ 5555)
adb pair 192.168.1.42:37123
# พิมพ์ pairing code เมื่อถาม

# จากนั้น connect (พอร์ตมักเป็น 5555 หรือดูใน Wireless debugging screen)
adb connect 192.168.1.42:5555

adb devices
scrcpy
```

---



## ใช้ร่วมกับ AutoBots


| งาน                   | คำสั่ง                                                                |
| --------------------- | --------------------------------------------------------------------- |
| ติดตั้ง APK ใหม่      | `./gradlew :androidApp:installDebug` (ต้องมี device ใน `adb devices`) |
| ดู log pipeline       | `adb logcat -s CapturePipeline VideoFaceProcessor VideoChunkRecorder` |
| Mirror ขณะ field test | `scrcpy -m 1280` (ลด lag)                                             |


แอพเปิด HTTP server ที่ **IP:8080** (แสดงบนการ์ดสถานะ) — remote **Start/Stop** ได้ แต่ **ไม่ส่งภาพหน้าจอ** · ใช้ scrcpy สำหรับดู UI

---



## ปิด / กลับ USB

```bash
# ตัดการเชื่อมต่อ Wi‑Fi ADB
adb disconnect 192.168.1.42:5555

# กลับโหมด USB (เสียบสายอยู่)
adb usb
```

---



## สรุปเลือกวิธี


| สถานการณ์                | แนะนำ                                                    |
| ------------------------ | -------------------------------------------------------- |
| Dev ที่โต๊ะ, ครั้งแรก    | **USB** + `scrcpy`                                       |
| Field / tripod ไกล Mac   | **Wi‑Fi** (`adb tcpip 5555`) หรือ **Wireless debugging** |
| แค่ดู log ไม่ต้อง mirror | `adb logcat`                                             |


---



## ลิงก์

- scrcpy: [https://github.com/Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- Android platform tools: [https://developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)

