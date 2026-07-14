# Graph Report - .  (2026-07-14)

## Corpus Check
- Corpus is ~10,085 words - fits in a single context window. You may not need a graph.

## Summary
- 168 nodes · 214 edges · 16 communities detected
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 21 edges (avg confidence: 0.8)
- Token cost: 1,400 input · 920 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Capture Pipeline & Delivery|Capture Pipeline & Delivery]]
- [[_COMMUNITY_Camera Controller & Exposure|Camera Controller & Exposure]]
- [[_COMMUNITY_Operator ViewModel & Preview|Operator ViewModel & Preview]]
- [[_COMMUNITY_Operator Controls Screen|Operator Controls Screen]]
- [[_COMMUNITY_Face Detection & Thresholds|Face Detection & Thresholds]]
- [[_COMMUNITY_Device Load & Performance Readout|Device Load & Performance Readout]]
- [[_COMMUNITY_Photo Delivery Service Subsystem|Photo Delivery Service Subsystem]]
- [[_COMMUNITY_Main Activity & Permissions|Main Activity & Permissions]]
- [[_COMMUNITY_Camera Resolutions Configuration|Camera Resolutions Configuration]]
- [[_COMMUNITY_Face Focus Control|Face Focus Control]]
- [[_COMMUNITY_MlKitFaceAnalyzer Subsystem|MlKitFaceAnalyzer Subsystem]]
- [[_COMMUNITY_Bounded Write Queue|Bounded Write Queue]]
- [[_COMMUNITY_Gallery Launcher|Gallery Launcher]]
- [[_COMMUNITY_Autobots Application Entry|Autobots Application Entry]]
- [[_COMMUNITY_Implementation Phase Progress|Implementation Phase Progress]]
- [[_COMMUNITY_Post-MVP Feature Roadmap|Post-MVP Feature Roadmap]]

## God Nodes (most connected - your core abstractions)
1. `PreviewCameraController` - 19 edges
2. `OperatorViewModel` - 16 edges
3. `Architecture Decisions` - 10 edges
4. `OperatorShellScreen()` - 9 edges
5. `CameraPreviewPane()` - 8 edges
6. `FaceFocusController` - 7 edges
7. `DeviceLoadReader` - 7 edges
8. `CaptureResolutions` - 6 edges
9. `WriteQueue` - 6 edges
10. `LeanBurstCapturer` - 5 edges

## Surprising Connections (you probably didn't know these)
- `OperatorViewModel` --conceptually_related_to--> `Operator UI`  [EXTRACTED]
  androidApp/src/main/kotlin/com/autobots/ui/OperatorViewModel.kt → docs/DOCS.md
- `OperatorShellScreen()` --implements--> `Operator Preview`  [EXTRACTED]
  androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt → CONTEXT.md
- `OperatorShellScreen()` --conceptually_related_to--> `Operator UI`  [EXTRACTED]
  androidApp/src/main/kotlin/com/autobots/ui/OperatorShellScreen.kt → docs/DOCS.md
- `PreviewCameraController` --conceptually_related_to--> `Runtime Pipeline`  [EXTRACTED]
  PreviewCameraController.kt → docs/ARCHITECTURE.md
- `PreviewCameraController` --references--> `Package Descriptions`  [EXTRACTED]
  PreviewCameraController.kt → docs/STRUCTURE.md

## Hyperedges (group relationships)
- **Passage Capture Flow** — context_passage, context_face_lock, context_lean_burst, context_passage_gate [EXTRACTED 1.00]
- **Operator Interface Components** — context_operator_preview, context_device_load_readout, context_operator_controls [EXTRACTED 1.00]

## Communities (20 total, 9 thin omitted)

### Community 0 - "Capture Pipeline & Delivery"
Cohesion: 0.08
Nodes (24): CaptureMode, LeanBurstCapturer, Android-First Runtime, Burst, Candidate Shot, Capture Mode Option, Deferred Score Flags, Frame Scoring (+16 more)

### Community 1 - "Camera Controller & Exposure"
Cohesion: 0.11
Nodes (6): CameraExposureReadout, PreviewCameraController, Architecture, Package Descriptions, Runtime Pipeline, Codebase Structure

### Community 2 - "Operator ViewModel & Preview"
Cohesion: 0.13
Nodes (3): CameraPreviewPane(), FaceOverlay(), OperatorViewModel

### Community 3 - "Operator Controls Screen"
Cohesion: 0.18
Nodes (15): Operator UI, AfGridDefaults, AfGridOverlay(), CaptureModeChip(), CaptureSettingsCard(), ObservationPage(), OperatorControlsPage(), OperatorShellPreview() (+7 more)

### Community 4 - "Face Detection & Thresholds"
Cohesion: 0.21
Nodes (9): PassageThresholds, Arm Threshold, Face Lock, Face Proximity, Fire Threshold, Subject Face, FaceFrameResult, NormalizedFaceBox (+1 more)

### Community 5 - "Device Load & Performance Readout"
Cohesion: 0.22
Nodes (6): Device Load Readout, Operator Controls, Operator Preview, DeviceLoadReader, ThermalReading, DeviceLoadSnapshot

### Community 7 - "Main Activity & Permissions"
Cohesion: 0.33
Nodes (3): MainActivity, CameraPermissionState, rememberCameraPermissionState()

## Knowledge Gaps
- **15 isolated node(s):** `AutobotsApp`, `AfGridDefaults`, `OverlayPages`, `Burst`, `Deferred Score Flags` (+10 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PreviewCameraController` connect `Camera Controller & Exposure` to `Operator ViewModel & Preview`?**
  _High betweenness centrality (0.247) - this node is a cross-community bridge._
- **Why does `OperatorShellScreen()` connect `Operator Controls Screen` to `Operator ViewModel & Preview`, `Device Load & Performance Readout`, `Main Activity & Permissions`?**
  _High betweenness centrality (0.219) - this node is a cross-community bridge._
- **Why does `CameraPreviewPane()` connect `Operator ViewModel & Preview` to `Camera Controller & Exposure`, `Operator Controls Screen`, `Main Activity & Permissions`?**
  _High betweenness centrality (0.188) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `OperatorShellScreen()` (e.g. with `.onCreate()` and `CameraPreviewPane()`) actually correct?**
  _`OperatorShellScreen()` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `CameraPreviewPane()` (e.g. with `PreviewCameraController` and `.onPhotoDelivered()`) actually correct?**
  _`CameraPreviewPane()` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `AutobotsApp`, `AfGridDefaults`, `OverlayPages` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Capture Pipeline & Delivery` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._