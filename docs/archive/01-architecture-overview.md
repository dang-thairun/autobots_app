# 01 — System Architecture Overview (MVP)

> Aligned with [`../CONTEXT.md`](../CONTEXT.md) and [`adr/`](./adr/).  
> Pre-grill “score → keep top 1 → ThermalGuard” flow is superseded for MVP.

## Problem Statement

ถ่ายภาพนักวิ่งในงานมาราธอน โดยกล้องติดบน tripod (Android-first):

- ตรวจจับใบหน้าด้วย Edge-AI แบบ real-time
- **Subject Face** = ใบหน้าที่ใหญ่สุดในเฟรม
- **Arm** (~10%) → **Face Lock** (AF + AE) ล่วงหน้า
- **Fire** (~40%) → **Lean Burst** หนึ่งครั้งต่อ **Passage**
- **Standard:** Keep-All ~3 JPEG · **Max-Sensor:** 1 JPEG (ตัวเลือก operator)
- เขียนผ่าน **Write Queue** → **Local Delivery** (ดิสก์เครื่อง)
- **ไม่อัดวิดีโอ** · **ไม่อัปโหลดคลาวด์ใน MVP** · **ไม่ auto-throttle ความร้อน** (มี Device Load Readout อย่างเดียว)

---

## High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Android Device (Tripod) — MVP                   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Operator Preview                                             │   │
│  │  Start/Stop · Capture Mode · Armed/Fired · Count · Load UI   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ CameraX: Preview + ImageAnalysis (640p) + ImageCapture       │   │
│  └──────────────────────────┬───────────────────────┬───────────┘   │
│                             │ YUV                   │ stills        │
│                             ▼                       │               │
│                   ┌──────────────────┐              │               │
│                   │ Face Detector    │              │               │
│                   │ → Subject Face   │              │               │
│                   └────────┬─────────┘              │               │
│                            │                        │               │
│               ┌────────────┴──────────────┐         │               │
│          ≥ Arm (~10%)              ≥ Fire (~40%)    │               │
│               │                           │         │               │
│               ▼                           ▼         │               │
│   ┌───────────────────┐      ┌────────────────────┐ │               │
│   │ Face Lock         │      │ Lean Burst         │─┘               │
│   │ AF + AE on face   │      │ Standard ×3 /      │                 │
│   └───────────────────┘      │ Max-Sensor ×1      │                 │
│                              └────────┬───────────┘                 │
│                                       │ Keep-All (no scorers)       │
│                              ┌────────▼───────────┐                 │
│                              │ Write Queue        │                 │
│                              │ (bounded)          │                 │
│                              └────────┬───────────┘                 │
│                                       ▼                             │
│                              Local Delivery (JPEG / DCIM)           │
│                              then Passage Gate until face exits     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Passage lifecycle

```
face appears / grows
      │
      ▼
Subject Face = largest face
      │
      ├── Face Proximity ≥ Arm  → Face Lock (AF+AE), settle
      │
      └── Face Proximity ≥ Fire → Lean Burst (once)
              │
              ▼
        enqueue Kept Photos → Write Queue → disk
              │
              ▼
        Passage Gate CLOSED
              │
              ▼
        wait until Subject Face leaves zone (or < arm threshold)
              │
              ▼
        Passage may open again
```

---

## Capture modes

| | Standard (default) | Max-Sensor (option) |
|--|--------------------|---------------------|
| Resolution | Device normal full-quality still | Max sensor (e.g. 50MP) |
| Shots / Passage | ~3 Keep-All | 1 |
| When to use | Long events, lower stress | Operator opts in for max detail |

See [ADR 0007](./adr/0007-capture-mode-ab-toggle.md).

---

## Explicitly out of MVP critical path

| Item | ADR |
|------|-----|
| FrameScorer / keep top-1 | [0001](./adr/0001-keep-all-no-scoring-mvp.md) |
| Smile / Pose models on live path | [0001](./adr/0001-keep-all-no-scoring-mvp.md) |
| ThermalGuard auto-throttle | [0008](./adr/0008-defer-thermal-past-mvp.md) |
| Cloud upload | [0005](./adr/0005-local-delivery-mvp.md) |
| VideoCapture | [0004](./adr/0004-still-photos-only.md) |
| iOS delivery | [0006](./adr/0006-android-first-mvp.md) |

Detail for deferred designs remains in `07-thermal-management.md` and `10-burst-scoring.md` for a later phase.
