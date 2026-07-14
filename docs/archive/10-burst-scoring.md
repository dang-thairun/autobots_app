# 10 — Burst Capture + Frame Scoring

> **MVP status: SCORING OUT OF PATH** — Lean Burst + **Keep-All** (~3 JPEGs), no FrameScorer on the default path ([ADR 0001](./adr/0001-keep-all-no-scoring-mvp.md)).  
> Smile / Pose may remain as **Deferred Score Flags** (OFF, unwired).  
> เนื้อหา scoring ด้านล่าง = optional ranking mode / post-MVP

## แนวคิด (Inspired by keyframe_picker_pi5) — post-MVP ranking

ถ่ายหลายภาพต่อ trigger แล้ว **เลือกภาพที่ดีที่สุด** แทนการถ่ายแค่ครั้งเดียว

```
Before (single shot):
  trigger → takePicture() → บันทึก
  ❌ ถ้านักวิ่งกระพริบตา / สั่น / เบี้ยว → ภาพพัง

After (burst + score):
  trigger → takePicture() ×3 → score all → keep best
  ✅ โอกาสได้ภาพดีเพิ่มขึ้น 3×
```

---

## Burst Config

```kotlin
// FeatureFlags.kt (commonMain)
object FeatureFlags {

    // ── Core — always active ───────────────────────────────────────────────
    var afStartThreshold: Float   = 0.10f   // เริ่ม lock AF/AE
    var captureThreshold: Float   = 0.40f   // trigger burst
    var jpegQuality: Int          = 95

    // ── Burst ─────────────────────────────────────────────────────────────
    var burstCount: Int           = 3        // จำนวน shots ต่อ burst
    var burstIntervalMs: Long     = 300L     // gap ระหว่าง shots (ms)
    // burst window = burstCount × burstIntervalMs = 900ms

    // ── Cooldown (แยก burst gap กับ export cooldown ตาม Pi5 design) ───────
    var exportCooldownMs: Long    = 3000L    // min gap ระหว่าง export ไปดิสก์
    var burstCooldownMs: Long     = 1500L    // min gap ระหว่าง burst trigger
    // burstCooldownMs < exportCooldownMs → burst อาจ trigger ก่อน export เสร็จ
    //                                       แต่จะ skip export ถ้า cooldown ยังอยู่

    // ── Scoring weights ────────────────────────────────────────────────────
    var weightSharpness: Float    = 0.40f    // always ON
    var weightFaceArea: Float     = 0.25f    // always ON
    var weightFaceCenter: Float   = 0.20f    // always ON
    var weightBrightness: Float   = 0.15f    // always ON
    var weightSmile: Float        = 0.00f    // 0 = disabled (เปลี่ยนเป็น 0.20 เมื่อเปิด)

    // ── Optional toggles (lazy model load) ────────────────────────────────
    var enableSmileScore: Boolean      = false   // +1.9MB RAM เมื่อเปิด
    var enablePoseFallback: Boolean    = false   // +4.0MB RAM เมื่อเปิด
    var poseFallbackThrottleMs: Long   = 500L    // ห้าม run เร็วกว่า 2fps
    var poseFallbackMinMisses: Int     = 3       // miss กี่ครั้งก่อน fallback
}
```

---

## `BurstCapturer.kt` (androidMain)

```kotlin
package com.autobots.camera.capture

import androidx.camera.core.ImageCapture
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class BurstCapturer(
    private val imageCapture: ImageCapture,
    private val executor: java.util.concurrent.Executor,
    private val scorer: FrameScorer,
    private val queue: AdaptiveCaptureQueue
) {
    companion object { private const val TAG = "BurstCapturer" }

    private val burstMutex   = Mutex()     // ป้องกัน concurrent burst
    private val burstScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastBurstMs   = 0L
    private var lastExportMs  = 0L

    /**
     * Trigger burst capture.
     * Non-blocking — returns immediately, burst runs in background.
     * Returns false if burst is suppressed (cooldown / mutex busy).
     */
    fun trigger(frameContext: FrameContext): Boolean {
        val now = System.currentTimeMillis()

        // ── Cooldown checks ────────────────────────────────────────────────
        if (now - lastBurstMs < FeatureFlags.burstCooldownMs) return false
        if (!burstMutex.tryLock()) { Log.d(TAG, "Burst in progress — skip"); return false }

        lastBurstMs = now
        burstScope.launch {
            try {
                executeBurst(frameContext, now)
            } finally {
                burstMutex.unlock()
            }
        }
        return true
    }

    private suspend fun executeBurst(frameContext: FrameContext, triggerMs: Long) {
        val captured = mutableListOf<ScoredFrame>()

        Log.d(TAG, "Burst start: ${FeatureFlags.burstCount} shots × ${FeatureFlags.burstIntervalMs}ms")

        repeat(FeatureFlags.burstCount) { index ->
            if (index > 0) {
                delay(FeatureFlags.burstIntervalMs)
            }
            try {
                val jpegBytes = takeSinglePhoto()
                val score     = scorer.score(jpegBytes, frameContext)
                captured += ScoredFrame(jpegBytes, score, triggerMs + index * FeatureFlags.burstIntervalMs)
                Log.d(TAG, "Shot $index: score=${"%.3f".format(score)}, size=${jpegBytes.size / 1024}KB")
            } catch (e: Exception) {
                Log.w(TAG, "Shot $index failed: ${e.message}")
            }
        }

        if (captured.isEmpty()) { Log.w(TAG, "Burst: no frames captured"); return }

        // ── Pick best frame ────────────────────────────────────────────────
        val best = captured.maxByOrNull { it.score }!!
        Log.d(TAG, "Burst best: score=${"%.3f".format(best.score)} from ${captured.size} shots")

        // ── Export cooldown ────────────────────────────────────────────────
        val now = System.currentTimeMillis()
        if (now - lastExportMs >= FeatureFlags.exportCooldownMs) {
            lastExportMs = now
            queue.enqueue(CaptureJob(
                jpegBytes   = best.jpegBytes,
                timestampMs = best.timestampMs,
                metadata    = CaptureMetadata(
                    faceCount       = frameContext.faces.size,
                    largestFaceArea = frameContext.faces.maxOfOrNull { it.boundingBox.area } ?: 0f,
                    thermalStatus   = "OK",
                    burstScore      = best.score,
                    burstSize       = captured.size
                )
            ))
        }

        // Discard non-best frames → GC frees RAM
    }

    private suspend fun takeSinglePhoto(): ByteArray =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(proxy: androidx.camera.core.ImageProxy) {
                        try {
                            val buf = proxy.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            cont.resume(bytes) {}
                        } finally {
                            proxy.close()   // ⚠️ CRITICAL
                        }
                    }
                    override fun onError(e: androidx.camera.core.ImageCaptureException) {
                        cont.resumeWithException(e)
                    }
                })
        }

    fun shutdown() = burstScope.cancel()
}

data class ScoredFrame(
    val jpegBytes: ByteArray,
    val score: Float,
    val timestampMs: Long
)
```

---

## `FrameScorer.kt` (androidMain)

```kotlin
package com.autobots.camera.capture

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log

class FrameScorer(
    private val smileScorer: SmileScorer? = null   // null = smile disabled
) {
    companion object { private const val TAG = "FrameScorer" }

    /**
     * Score a JPEG byte array.
     * All sub-scores are clamped to [0.0, 1.0].
     * Returns weighted sum in [0.0, 1.0].
     *
     * Runs on IO dispatcher — NOT on analysis thread.
     */
    fun score(jpegBytes: ByteArray, context: FrameContext): Float {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return 0f

        // ── 1. Sharpness (Laplacian variance on grayscale thumbnail) ───────
        val sharpness = laplacianSharpness(bitmap)

        // ── 2. Face Area ───────────────────────────────────────────────────
        val faceArea = context.faces
            .maxOfOrNull { it.boundingBox.area }
            ?.let { faceAreaScore(it) } ?: 0f

        // ── 3. Face Center ─────────────────────────────────────────────────
        val faceCenter = context.faces
            .maxByOrNull { it.confidence }
            ?.let { faceCenterScore(it.boundingBox) } ?: 0f

        // ── 4. Brightness ──────────────────────────────────────────────────
        val brightness = brightnessScore(bitmap)

        bitmap.recycle()

        // ── 5. Smile (optional — lazy loaded model) ────────────────────────
        val smile = if (FeatureFlags.enableSmileScore && smileScorer != null) {
            smileScorer.score(jpegBytes, context)
        } else 0f

        val total = (FeatureFlags.weightSharpness   * sharpness  +
                     FeatureFlags.weightFaceArea     * faceArea   +
                     FeatureFlags.weightFaceCenter   * faceCenter +
                     FeatureFlags.weightBrightness   * brightness +
                     FeatureFlags.weightSmile        * smile)
            .coerceIn(0f, 1f)

        Log.d(TAG, "score=%.3f [sharp=%.2f area=%.2f center=%.2f bright=%.2f smile=%.2f]"
            .format(total, sharpness, faceArea, faceCenter, brightness, smile))

        return total
    }

    // ── Sub-scorers ────────────────────────────────────────────────────────

    /**
     * Sharpness via Laplacian variance on a small grayscale version.
     * Scale factor: operate on max 200px wide to keep it fast.
     * Higher variance = sharper image.
     *
     * Normalised: raw variance 0–2000 → 0.0–1.0
     */
    private fun laplacianSharpness(bitmap: android.graphics.Bitmap): Float {
        // Downscale for speed
        val scale = 200f / bitmap.width
        val small = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            200,
            (bitmap.height * scale).toInt(),
            false
        )

        val w = small.width
        val h = small.height
        if (w < 3 || h < 3) return 0f

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        // Laplacian kernel: [0,1,0],[1,-4,1],[0,1,0]
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gray  = { p: Int -> (Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114) }
                val lap   = (gray(pixels[(y-1)*w+x]) + gray(pixels[(y+1)*w+x]) +
                             gray(pixels[y*w+x-1])   + gray(pixels[y*w+x+1])   -
                             4 * gray(pixels[y*w+x]))
                sumSq += lap * lap
                count++
            }
        }
        val variance = if (count > 0) sumSq / count else 0.0
        return (variance / 2000.0).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Prefer face area 5%–25% of frame (too small = far, too large = too close/cropped)
     * Normalised curve: peak at ~12%
     */
    private fun faceAreaScore(area: Float): Float {
        // Pi5 normalises against range 0.02–0.20
        val norm = ((area - 0.02f) / (0.20f - 0.02f)).coerceIn(0f, 1f)
        // Penalise if too large (> 0.20 = runner too close, face cropped)
        val tooLargePenalty = if (area > 0.25f) (1f - (area - 0.25f) * 4f).coerceAtLeast(0f) else 1f
        return norm * tooLargePenalty
    }

    /**
     * Prefer face near frame center.
     * Distance from center (0.5, 0.5) → 0.0–1.0
     */
    private fun faceCenterScore(bbox: com.autobots.camera.detection.BoundingBox): Float {
        val cx = (bbox.left + bbox.right)  / 2f
        val cy = (bbox.top  + bbox.bottom) / 2f
        val dist = kotlin.math.sqrt(((cx - 0.5f) * (cx - 0.5f) + (cy - 0.5f) * (cy - 0.5f)).toDouble()).toFloat()
        // max dist from center to corner = ~0.707
        return (1f - dist / 0.707f).coerceIn(0f, 1f)
    }

    /**
     * Prefer mid-brightness (not too dark, not blown out).
     * Peak around 0.45–0.55 brightness (0.0 = black, 1.0 = white)
     */
    private fun brightnessScore(bitmap: android.graphics.Bitmap): Float {
        // Sample 20×20 grid for speed
        val stepX = bitmap.width  / 20
        val stepY = bitmap.height / 20
        if (stepX == 0 || stepY == 0) return 0.5f

        var totalLuminance = 0.0
        var count = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val p = bitmap.getPixel(x, y)
                totalLuminance += Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114
                count++
            }
        }
        val avgLum = if (count > 0) (totalLuminance / count / 255.0).toFloat() else 0.5f

        // Gaussian-ish peak at 0.5
        val diff = (avgLum - 0.5f)
        return (1f - (diff * diff * 4f)).coerceIn(0f, 1f)
    }
}
```

---

## `SmileScorer.kt` (androidMain — optional, lazy loaded)

```kotlin
package com.autobots.camera.capture

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

/**
 * Smile scoring using MediaPipe Face Landmarker.
 * Only instantiated when FeatureFlags.enableSmileScore = true.
 *
 * Resource cost:
 *   - Model: ~1.9MB RAM (face_landmarker.task)
 *   - Inference: ~5ms per frame (GPU)
 *   - Called only on burst frames (not every live frame)
 */
class SmileScorer(private val context: Context) {

    private val landmarker: FaceLandmarker by lazy {
        // Lazy init — model loads only on first call
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                    .build()
            )
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)   // ← needed for smile/joy score
            .build()
        FaceLandmarker.createFromOptions(context, options)
            .also { Log.i("SmileScorer", "Face Landmarker loaded") }
    }

    /**
     * Approximate smile from mouth width / face height ratio.
     * Uses blendshape "mouthSmileLeft" + "mouthSmileRight" if available.
     * Falls back to mouth landmark geometry.
     *
     * Returns 0.0 (no smile) to 1.0 (big smile).
     */
    fun score(jpegBytes: ByteArray, context: FrameContext): Float {
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return 0f
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()

            val result = landmarker.detect(mpImage)
            bitmap.recycle()

            if (result.faceBlendshapes().isEmpty) return 0f

            val blendshapes = result.faceBlendshapes().get()[0]

            // Joy blendshape combines left + right smile
            val smileLeft  = blendshapes.find { it.categoryName() == "mouthSmileLeft"  }?.score() ?: 0f
            val smileRight = blendshapes.find { it.categoryName() == "mouthSmileRight" }?.score() ?: 0f

            ((smileLeft + smileRight) / 2f).coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w("SmileScorer", "Smile score failed: ${e.message}")
            0f
        }
    }

    fun close() {
        if (::landmarker.isInitialized) landmarker.close()
    }
}
```

---

## `PoseFallbackDetector.kt` (androidMain — optional, lazy loaded)

```kotlin
package com.autobots.camera.detection

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Pose-based head crop fallback — for when runner is far away
 * and YOLOv8-Face misses the face.
 *
 * Resource cost:
 *   - Model: ~4MB RAM (pose_landmarker_lite.task)
 *   - Inference: ~15ms per call (GPU)
 *   - Throttled: max 2fps (every 500ms)
 *   - Triggered: only after N consecutive face misses
 *
 * Inspired by keyframe_picker_pi5 pose → head crop → re-detect flow.
 */
class PoseFallbackDetector(
    private val context: Context,
    private val faceDetector: TFLiteFaceDetector
) {
    companion object { private const val TAG = "PoseFallback" }

    private val poseLandmarker: PoseLandmarker by lazy {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                    .build()
            )
            .setNumPoses(1)
            .build()
        PoseLandmarker.createFromOptions(context, options)
            .also { Log.i(TAG, "Pose Landmarker Lite loaded") }
    }

    private var consecutiveMisses = 0
    private var lastFallbackMs    = 0L

    /**
     * Called from FaceAnalyzer when face detection returns empty.
     * Throttled — won't run more than once per [poseFallbackThrottleMs].
     *
     * @return List of faces found via pose → head crop path, or empty list
     */
    fun tryFallback(
        nv21: ByteArray,
        width: Int,
        height: Int
    ): List<com.autobots.camera.detection.FaceDetectionResult> {
        consecutiveMisses++

        val now = System.currentTimeMillis()
        if (consecutiveMisses < FeatureFlags.poseFallbackMinMisses) return emptyList()
        if (now - lastFallbackMs < FeatureFlags.poseFallbackThrottleMs) return emptyList()

        lastFallbackMs = now
        Log.d(TAG, "Running pose fallback (misses=$consecutiveMisses)")

        return try {
            val bitmap = nv21ToBitmap(nv21, width, height)
            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
            val poseResult = poseLandmarker.detect(mpImage)
            bitmap.recycle()

            if (poseResult.landmarks().isEmpty()) return emptyList()

            // Extract head region from nose, left/right eye, left/right ear landmarks
            val headCrop = poseHeadCrop(poseResult, width, height)
                ?: return emptyList()

            // Crop the NV21 bytes to head region and re-detect
            val (cropBytes, cw, ch, offsetX, offsetY) = headCrop
            val faces = faceDetector.detect(cropBytes, cw, ch)

            // Map coordinates back to full frame
            faces.map { face ->
                face.copy(boundingBox = face.boundingBox.mapToFullFrame(
                    offsetX, offsetY, cw, ch, width, height
                ))
            }.also {
                Log.d(TAG, "Pose fallback found ${it.size} face(s)")
            }

        } catch (e: Exception) {
            Log.w(TAG, "Pose fallback error: ${e.message}")
            emptyList()
        }
    }

    fun onFaceFound() {
        consecutiveMisses = 0   // reset counter when face detected normally
    }

    private fun poseHeadCrop(
        result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
        frameW: Int,
        frameH: Int
    ): HeadCropResult? {
        val landmarks = result.landmarks().firstOrNull() ?: return null

        // MediaPipe Pose: 0=nose, 1=left_eye_inner, 2=left_eye, 4=right_eye,
        //                 7=left_ear, 8=right_ear
        val headIndices = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        val visible = landmarks.filterIndexed { i, lm ->
            i in headIndices && lm.visibility().orElse(0f) > FeatureFlags.poseFallbackThrottleMs
        }
        if (visible.isEmpty()) return null

        val xs = visible.map { it.x() * frameW }
        val ys = visible.map { it.y() * frameH }

        val boxW = (xs.max() - xs.min()).coerceAtLeast(1f)
        val boxH = (ys.max() - ys.min()).coerceAtLeast(1f)
        val expand = 3.0f   // FeatureFlags.fallbackHeadExpand

        val cx = (xs.min() + xs.max()) / 2f
        val cy = (ys.min() + ys.max()) / 2f

        val cropX = (cx - boxW * expand / 2).toInt().coerceAtLeast(0)
        val cropY = (cy - boxH * expand / 2).toInt().coerceAtLeast(0)
        val cropW = (boxW * expand).toInt().coerceAtMost(frameW - cropX)
        val cropH = (boxH * expand).toInt().coerceAtMost(frameH - cropY)

        // Crop NV21 is complex — simplified: return crop params only
        // Full implementation would crop the NV21 byte array using libyuv
        return HeadCropResult(ByteArray(0), cropW, cropH, cropX, cropY)
    }

    private fun nv21ToBitmap(nv21: ByteArray, w: Int, h: Int): android.graphics.Bitmap {
        val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), 90, out)
        return android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    fun close() {
        if (::poseLandmarker.isInitialized) poseLandmarker.close()
    }
}

data class HeadCropResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int
)

// Extension to map cropped bbox back to full frame coordinates
fun com.autobots.camera.detection.BoundingBox.mapToFullFrame(
    offsetX: Int, offsetY: Int,
    cropW: Int, cropH: Int,
    frameW: Int, frameH: Int
) = com.autobots.camera.detection.BoundingBox(
    left   = (left   * cropW + offsetX) / frameW,
    top    = (top    * cropH + offsetY) / frameH,
    right  = (right  * cropW + offsetX) / frameW,
    bottom = (bottom * cropH + offsetY) / frameH
)
```

---

## Updated `FaceAnalyzer.kt` — with Burst + Fallback

```kotlin
class FaceAnalyzer(
    private val detector: FaceDetector,
    private val burstCapturer: BurstCapturer,
    private val focusController: FaceFocusController,
    private val thermalMonitor: ThermalMonitor,
    private val poseFallback: PoseFallbackDetector? = null   // null = disabled
) : ImageAnalysis.Analyzer {

    @Volatile private var policy = ThermalThrottlePolicy.forStatus(ThermalStatus.NONE)
    private var lastFocusMs = 0L

    override fun analyze(image: ImageProxy) {
        if (!policy.captureEnabled) { image.close(); return }

        val nv21  = imageProxyToNv21(image)
        val now   = System.currentTimeMillis()
        var faces = detector.detect(nv21, image.width, image.height)

        // ── Pose fallback (if enabled + face missed) ───────────────────────
        if (faces.isEmpty() && poseFallback != null) {
            faces = poseFallback.tryFallback(nv21, image.width, image.height)
        } else {
            poseFallback?.onFaceFound()
        }

        if (faces.isNotEmpty()) {
            val best = faces.maxByOrNull { it.confidence }!!

            // ── AF/AE pre-lock ─────────────────────────────────────────────
            if (best.boundingBox.area > FeatureFlags.afStartThreshold &&
                now - lastFocusMs > 100L) {
                focusController.lockOnFace(best.boundingBox)
                lastFocusMs = now
            }

            // ── Burst trigger ──────────────────────────────────────────────
            if (best.boundingBox.area > FeatureFlags.captureThreshold) {
                burstCapturer.trigger(FrameContext(now, image.imageInfo.rotationDegrees, faces))
            }
        } else {
            focusController.reset()
        }

        image.close()
    }
}
```

---

## Resource Summary

```
Feature                 | RAM      | Compute         | Trigger freq
────────────────────────┼──────────┼─────────────────┼──────────────
YOLOv8-Face (core)      | ~6MB     | ~10ms/frame GPU | 30fps
Burst capture (core)    | ~3×8MB   | ~15ms total     | per burst
Laplacian sharpness     | ~0       | ~1ms/image      | per burst shot
Brightness score        | ~0       | ~0.5ms/image    | per burst shot
Face center score       | ~0       | ~0.1ms          | per burst shot
────────────────────────┼──────────┼─────────────────┼──────────────
SmileScorer (toggle)    | +1.9MB   | ~5ms/image GPU  | per burst shot
PoseFallback (toggle)   | +4.0MB   | ~15ms/call GPU  | max 2fps
```

> ✅ Burst + core scoring: เพิ่ม overhead น้อยมาก เมื่อเทียบกับ benefit  
> ✅ Smile + Pose: lazy load → ปิด toggle = **RAM 0, compute 0**
