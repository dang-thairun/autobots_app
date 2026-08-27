# Roadmap (unscheduled)

Ideas past the active **Plan B** slices in [IMPLEMENTATION.md](./IMPLEMENTATION.md).  
Current operator build: **v0.1.6** — [OPERATOR_FLOW.md](./OPERATOR_FLOW.md).  
Naming: [CONVENTIONS.md](./CONVENTIONS.md).

| Topic | Notes |
|-------|--------|
| ~~4K face extract tuning~~ | **ปิดแล้ว (B2)** — `realtimeRatio` 0.502 · sharpness แยกตามความละเอียด · reject reason นับแยกได้ · ดู "B2 ปิดยังไง" ใน [IMPLEMENTATION.md](./IMPLEMENTATION.md) |
| ~~Body / person pre-filter~~ | **ทำแล้ว v0.1.6** — `foot_track_net` บน NPU |
| **ThermalGuard auto-throttle** ⬆️ | เลื่อนความสำคัญขึ้น — เหตุผลเดิมที่ไม่ทำ ("throttle เงียบๆ แล้วพลาดนักวิ่ง") ถูกเขียนไว้สำหรับ pipeline แบบ real-time และ **ใช้ไม่ได้กับ Plan B**: วิดีโออัดไว้หมดแล้ว การยืด sample interval หรือหยุด upload ชั่วคราวไม่ทำให้พลาดใครเลย ดู Flow 8 ใน [ARCHITECTURE.md](./ARCHITECTURE.md) |
| **ยืนยันความละเอียดปลายทาง** | เฟรม 4K = 8.3 MP · ครอปแล้วพอขายไหม · ถ้าไม่พอคือต้องคิด hybrid (วิดีโอตัดสินใจ → ยิง `ImageCapture` จริง) ซึ่งเป็นงานใหญ่และ **แก้ทีหลังไม่ได้** |
| ~~On-device frame scoring~~ | **ทำแล้ว v0.1.6** — `FrameQuality` 5 ด้าน · เหลือแต่ smile ที่ยังไม่ทำ |
| YOLO / TFLite detector | **ปฏิเสธสำหรับ v0.1.7** — AGPL (YOLO) · QNN ไม่รับ postprocess op (EfficientDet-Lite) · ยังไม่มีข้อมูลว่า recall ไม่พอ — [V_0_1_7_PLAN.md §7](./V_0_1_7_PLAN.md) |
| **Person ID เป็นแกน (ByteTrack + quality ต่อคน)** | **แผน v0.1.7** — pipeline วันนี้เป็น frame-centric · คนที่วิ่งคู่กับคนตัวใหญ่กว่าได้ 0 รูป — [V_0_1_7_PLAN.md](./V_0_1_7_PLAN.md) |
| 🔁 **ขยาย `CHUNK_TARGET_BYTES` 50 → 200 MB** | **เลื่อนไว้ ไม่ใช่ปฏิเสธ** — chunk ที่ 4K ยาวแค่ ~10–12 วิ ทำให้นักวิ่งถูกหั่นที่รอยต่อ · v0.1.7 แก้ด้วยการเย็บ metadata แทน · เงื่อนไขที่ต้องกลับมาดู: [V_0_1_7_PLAN.md §10](./V_0_1_7_PLAN.md) |
| ~~Cloud / remote upload~~ | **ทำแล้ว v0.1.5** — ย้ายไปบรรทัด "Already shipped" ข้างล่าง |
| iOS / iPad operator | Wi‑Fi preview + remote controls |
| Face AF fallback UI | When tripod moves / focus not calibrated (v0.1 P9) |
| Denser Capture Zone grid | 11×15+ only if 9×11 is too coarse (v0.1 P10) |
| ~~Face tracking IDs~~ | **ทำแล้ว v0.1.6** — `SubjectTracker` + `tracks.csv` |
| In-app screen mirror | Use scrcpy today; `/ws/preview` reserved |

**Already shipped (not roadmap):** Plan B live + import pipeline · Network URL ingest · session history ·
`session_log.txt` · Face/Pose/Person extraction flags · **upload pipeline (Room + WorkManager + presign → GCS, v0.1.5)** ·
**per-runner tracking + 5-term frame ranking + `tracks.csv` (v0.1.6)** · Capture Zone editor · เพดานชัตเตอร์ + EV.

When starting one of these, add a Phase slice to [IMPLEMENTATION.md](./IMPLEMENTATION.md) or [DOCS.md](./DOCS.md) — do not resurrect archived scaffolds blindly.

---

## Related

- Doc index: [DOCS.md](./DOCS.md)
- Active slices: [IMPLEMENTATION.md](./IMPLEMENTATION.md)
- Pipeline reference: [PIPELINE_FLOW.md](./PIPELINE_FLOW.md)
