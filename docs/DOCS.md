# AutoBots Sports Camera — Project Documentation

Welcome to the documentation for the AutoBots Sports Camera, an edge-AI marathon photography application running on tripod-mounted Android devices.

---

## 1. Documentation Index

To explore different aspects of the codebase and product design, choose a guide below:

* **[Product Requirements Document (PRD)](PRD.md)**
  * Contains core domain terminology (Passages, Burst, Keep-All Policy, etc.), target actors, MVP scope definition, and behavioral acceptance criteria.
* **[Systems Architecture Guide](ARCHITECTURE.md)**
  * Details the modular KMP design, runtime execution pipelines, subsystem components (Camera, Detection, Focus, Capture, Delivery), and consolidated Architectural Decision Records (ADRs 0001 - 0012).
* **[Codebase Structure & Directory Layout](STRUCTURE.md)**
  * Details the Kotlin Multiplatform package structures, file layouts, and build dependencies.

---

## 2. Development Implementation Phases (MVP)

All core MVP milestones (P0 to P8) have been fully implemented and verified:

| Phase | Description | Status | Completion Date |
|-------|-------------|--------|-----------------|
| **P0** | Empty KMP project + Android template shell setup | ✅ Complete | 2026-07-13 |
| **P1** | Operator UI shell (Start/Stop controls, capture toggles, mock data) | ✅ Complete | 2026-07-13 |
| **P2** | CameraX Preview implementation | ✅ Complete | 2026-07-13 |
| **P3** | ImageAnalysis + ML Kit integration + Subject Face overlay display | ✅ Complete | 2026-07-13 |
| **P4** | Proximity check (Arming) → Face Lock (AF/AE metering targeting) | ✅ Complete | 2026-07-13 |
| **P5** | Proximity check (Firing) → Lean Burst capture + Passage Gate lockout | ✅ Complete | 2026-07-13 |
| **P6** | Write Queue async processing + Local MediaStore delivery | ✅ Complete | 2026-07-13 |
| **P7** | Capture Mode Option (Standard Mode vs Max-Sensor Mode support) | ✅ Complete | 2026-07-14 |
| **P8** | Device Load Readout display (thermal & memory usage metrics) | ✅ Complete | 2026-07-14 |

---

## 3. Post-MVP Feature Roadmap

The following features are deferred past the shippable MVP phase:

| Topic | Notes / Description |
|-------|---------------------|
| **ThermalGuard Auto-Throttle** | Implement adaptive analysis backoffs or capture pauses based on Device Load Readouts. |
| **On-Device Frame Scoring** | Add pose, smile, and sharpness ranking parameters to select the single best photo per passage. |
| **YOLO / TFLite Detector** | Replace ML Kit Face Detection with a custom body/face detector if better recall/performance is needed. |
| **Cloud/Remote Upload** | Build a background syncing engine to push kept photos to remote marathon galleries. |
| **iOS Operator Remote** | Provide an iPad control panel UI using KMP cross-module networking. |
| **Continuous AF Lock** | Optimize CameraX parameters to disable auto-cancel timers on Face Locks. |
| **Face Tracking IDs** | Add light tracking algorithms to persist runner identity states across frames. |
