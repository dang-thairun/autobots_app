# 04 — Capture Pipeline (No Video Recording)

> **MVP note:** Still no video. Burst + **Keep-All** (not FrameScorer / keep-top-1).  
> See [`../CONTEXT.md`](../CONTEXT.md), [ADR 0001](./adr/0001-keep-all-no-scoring-mvp.md), [11-mvp-spec.md](./11-mvp-spec.md).  
> Code samples below that call `FrameScorer` are **post-MVP / historical**.

## Overview

Pipeline **ไม่มีการอัดวิดีโอ** — ใช้ ImageAnalysis สำหรับ AI และ Burst / ImageCapture สำหรับยังถ่าย stills

**ประโยชน์:**
- 🌡️ อุปกรณ์เย็นกว่า (ไม่มี H.265 encoder ทำงานตลอด)
- 💾 Disk ใช้เฉพาะตอน Passage fire เท่านั้น
- 🔋 Battery อยู่นานกว่า เหมาะสำหรับงานยาว
- 📸 Standard mode: Lean Burst Keep-All ~3 ใบต่อ Passage

---

## CameraX Use-Cases

```
✅  Preview        — แสดงผลบนหน้าจอ (optional ถ้าใช้ tripod mode)
✅  ImageAnalysis  — รับ YUV 640×360 สำหรับ Face Detection (30fps)
✅  ImageCapture   — ถ่ายภาพ full-resolution (burst mode)

❌  VideoCapture   — ถูกตัดออก (ลด thermal + disk I/O)
```

---

## `CameraXPipeline.kt` (Updated)

```kotlin
package com.autobots.camera.pipeline

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.autobots.camera.capture.AdaptiveCaptureQueue
import com.autobots.camera.capture.ImageCaptureStrategy
import com.autobots.camera.detection.TFLiteFaceDetector
import com.autobots.camera.detection.FaceFocusController
import com.autobots.camera.thermal.AndroidThermalMonitor
import java.io.File
import java.util.concurrent.Executors

class CameraXPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val outputDir: File
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val thermalMonitor   = AndroidThermalMonitor(context)
    private val captureQueue     = AdaptiveCaptureQueue(outputDir)
    private val faceDetector     = TFLiteFaceDetector(context)

    // ── CameraX use-cases ─────────────────────────────────────────────────
    private lateinit var imageCapture: ImageCapture
    private lateinit var focusController: FaceFocusController
    private lateinit var captureStrategy: ImageCaptureStrategy

    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // ── Preview (optional — hide for tripod-only mode) ─────────────
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1080, 1920))
                .build()

            // ── ImageCapture — full sensor resolution ──────────────────────
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .build()

            // ── ImageAnalysis — 640p for AI (lightweight) ──────────────────
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            // ── Bind to lifecycle ──────────────────────────────────────────
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )

            // ── Init controllers (require CameraControl/CameraInfo) ────────
            focusController = FaceFocusController(
                cameraControl = camera.cameraControl,
                cameraInfo    = camera.cameraInfo,
                surfaceWidth  = 640f,
                surfaceHeight = 360f
            )

            captureStrategy = ImageCaptureStrategy(
                imageCapture = imageCapture,
                executor     = analysisExecutor,
                queue        = captureQueue
            )

            // ── Init FrameScorer (+ optional SmileScorer) ─────────────────
            val smileScorer = if (FeatureFlags.enableSmileScore)
                SmileScorer(context) else null
            val scorer = FrameScorer(smileScorer)

            // ── BurstCapturer ──────────────────────────────────────────────
            val burstCapturer = BurstCapturer(
                imageCapture = imageCapture,
                executor     = analysisExecutor,
                scorer       = scorer,
                queue        = captureQueue
            )

            // ── Optional Pose Fallback (lazy model) ────────────────────────
            val poseFallback = if (FeatureFlags.enablePoseFallback)
                PoseFallbackDetector(context, faceDetector) else null

            // ── Attach face analyzer ───────────────────────────────────────
            imageAnalysis.setAnalyzer(analysisExecutor, FaceAnalyzer(
                detector        = faceDetector,
                burstCapturer   = burstCapturer,
                focusController = focusController,
                thermalMonitor  = thermalMonitor,
                poseFallback    = poseFallback
            ))

        }, ContextCompat.getMainExecutor(context))

        // Start thermal monitoring
        thermalMonitor.start { status ->
            captureQueue.setCapacity(ThermalThrottlePolicy.forStatus(status).maxQueueSize)
        }
    }

    fun stop() {
        thermalMonitor.stop()
        captureQueue.shutdown()
        faceDetector.close()
        analysisExecutor.shutdown()
    }
}
```

---

## `FaceAnalyzer.kt` (Updated with Focus Controller)

```kotlin
package com.autobots.camera.pipeline

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.autobots.camera.capture.CaptureStrategy
import com.autobots.camera.capture.FrameContext
import com.autobots.camera.detection.*
import com.autobots.camera.thermal.ThermalMonitor
import com.autobots.camera.thermal.ThermalThrottlePolicy
import com.autobots.camera.thermal.ThermalStatus
import kotlinx.coroutines.*

class FaceAnalyzer(
    private val detector: FaceDetector,
    private val captureStrategy: CaptureStrategy,
    private val focusController: FaceFocusController,
    private val thermalMonitor: ThermalMonitor,
    private val afStartThreshold: Float     = 0.10f,   // เริ่ม lock AF ตั้งแต่ face 10%
    private val captureThreshold: Float     = 0.40f,   // trigger capture ที่ face 40%
    private val focusUpdateIntervalMs: Long = 100L,    // update AF ทุก 100ms
) : ImageAnalysis.Analyzer {

    private val analyzerScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var policy       = ThermalThrottlePolicy.forStatus(ThermalStatus.NONE)
    private var lastCaptureMs  = 0L
    private var lastFocusMs    = 0L

    init {
        thermalMonitor.start { status ->
            policy = ThermalThrottlePolicy.forStatus(status)
            captureStrategy.applyThermalThrottle(status)
        }
    }

    override fun analyze(image: ImageProxy) {
        if (!policy.captureEnabled) { image.close(); return }

        val nv21  = imageProxyToNv21(image)
        val faces = detector.detect(nv21, image.width, image.height)
        val now   = System.currentTimeMillis()

        if (faces.isNotEmpty()) {
            val bestFace = faces.maxByOrNull { it.confidence }!!
            val faceArea = bestFace.boundingBox.area

            // ── 1. Lock AF/AE ล่วงหน้าตั้งแต่ face > 10% ──────────────────
            if (faceArea > afStartThreshold && now - lastFocusMs > focusUpdateIntervalMs) {
                focusController.lockOnFace(bestFace.boundingBox)
                lastFocusMs = now
            }

            // ── 2. Trigger capture เมื่อ face > threshold ──────────────────
            val effectiveThreshold = policy.faceThreshold.coerceAtLeast(captureThreshold)
            if (faceArea > effectiveThreshold && now - lastCaptureMs > policy.minCooldownMs) {
                lastCaptureMs = now
                analyzerScope.launch {
                    captureStrategy.capture(
                        FrameContext(
                            timestampMs = now,
                            rotationDeg = image.imageInfo.rotationDegrees,
                            faces       = faces
                        )
                    )
                }
            }
        } else {
            // ไม่มีใบหน้า → reset AF กลับ default
            focusController.reset()
        }

        image.close()
    }

    // Convert ImageProxy YUV_420_888 → NV21 byte array
    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize   = yBuffer.remaining()
        val uSize   = uBuffer.remaining()
        val vSize   = vBuffer.remaining()
        val nv21    = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }
}
```

---

## `ImageCaptureStrategy.kt` (Phase 1 MVP)

```kotlin
package com.autobots.camera.capture

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.autobots.camera.thermal.ThermalStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ImageCaptureStrategy(
    private val imageCapture: ImageCapture,
    private val executor: Executor,
    private val queue: AdaptiveCaptureQueue,
    private val jpegQuality: Int = 95
) : CaptureStrategy {

    @Volatile private var thermalStatus = ThermalStatus.NONE

    // ป้องกัน concurrent takePicture() — ImageCapture ไม่ re-entrant
    private val captureMutex = Mutex()

    override suspend fun capture(frameContext: FrameContext): CaptureResult {
        if (thermalStatus >= ThermalStatus.SEVERE) {
            return CaptureResult.Error("Thermal throttle: ${thermalStatus}")
        }
        if (!captureMutex.tryLock()) {
            return CaptureResult.Error("Capture in progress — skip")
        }

        return try {
            val jpegBytes = takePhotoSuspend()
            queue.enqueue(CaptureJob(
                jpegBytes   = jpegBytes,
                timestampMs = frameContext.timestampMs,
                metadata    = CaptureMetadata(
                    faceCount        = frameContext.faces.size,
                    largestFaceArea  = frameContext.faces.maxOfOrNull { it.boundingBox.area } ?: 0f,
                    thermalStatus    = thermalStatus.name
                )
            ))
            CaptureResult.Success(jpegBytes.size.toLong())
        } catch (e: Exception) {
            CaptureResult.Error(e.message ?: "ImageCapture failed")
        } finally {
            captureMutex.unlock()
        }
    }

    private suspend fun takePhotoSuspend(): ByteArray =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        try {
                            val buffer = imageProxy.planes[0].buffer
                            val bytes  = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            continuation.resume(bytes)
                        } finally {
                            imageProxy.close()   // ⚠️ CRITICAL — release RAM immediately
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }

    override fun applyThermalThrottle(status: ThermalStatus) { thermalStatus = status }
    override fun release() { /* managed by CameraX lifecycle */ }
}
```

---

## Threshold & Cooldown Logic

```
face area = bbox.width × bbox.height  (normalized 0.0–1.0)

  0%     10%              40%                    100%
  │       │                │                       │
  │  [ไกล]│  [เริ่มเข้า]   │  [burst trigger]      │
  │       │                │                       │
  └───────┼────────────────┼───────────────────────┘
          │                │
     lockOnFace()     BurstCapturer.trigger()
     AF/AE lock       → shot1, shot2, shot3
     settle...        → FrameScorer (async)
     ~200-400ms       → keep best → queue → disk
```

### Two separate cooldowns (inspired by Pi5 design)

```
exportCooldownMs  = 3000ms  ← min gap ระหว่าง save ไปดิสก์
burstCooldownMs   = 1500ms  ← min gap ระหว่าง burst trigger

ทำไมต้องแยก?
→ Burst อาจ trigger บ่อย แต่ export ออก disk ไม่บ่อย
→ ป้องกัน disk เต็มเร็วในกรณีนักวิ่งกลุ่มใหญ่
```

---

## Output

```
/storage/emulated/0/DCIM/AutoBots/
└── runner_1720609612345.jpg   ← best frame จาก burst (ไม่มี MP4)

Metadata ใน EXIF UserComment:
  faces=1; area=0.42; burst_score=0.847; burst_size=3; thermal=NONE
```
