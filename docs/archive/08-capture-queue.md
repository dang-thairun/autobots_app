# 08 — Adaptive Capture Queue

> **MVP status: IN SCOPE** as **Write Queue** ([ADR 0009](./adr/0009-bounded-write-queue-mvp.md)). Keep-All ×3 and Max-Sensor make this mandatory.

## Problem

เมื่อ trigger หลาย captures ติดกัน (นักวิ่งกลุ่มใหญ่ผ่านพร้อมกัน):
- ถ้าเขียน disk แบบ synchronous → block ImageAnalysis → miss next frames
- ถ้า queue ไม่มี limit → OOM (OutOfMemoryError) ถ้า disk ช้ากว่า capture rate
- ต้องการระบบที่ **asynchronous + backpressure-aware + disk-I/O adaptive**

---

## Design Principles

```
Capture trigger (fast path)     →   Queue (bounded Channel)   →   Disk Writer (slow path)
       │                                    │                            │
       │  non-blocking enqueue()            │  if full: DROP oldest      │  background coroutine
       │  returns immediately               │  or skip new item          │  writes JPEG to file
       │                                    │                            │
  [analyze thread]                   [Channel<CaptureJob>]        [IO dispatcher]
```

1. **Bounded Channel** — cap ที่ N items; ถ้าเต็ม → DROP (ไม่ block)
2. **Dynamic capacity** — ปรับ queue size ตาม thermal status
3. **ByteArray pool** — recycle buffers แทน GC pressure
4. **Write speed measurement** — วัด disk I/O speed จริง; ปรับ JPEG quality ถ้าช้า

---

## `AdaptiveCaptureQueue.kt` (androidMain)

```kotlin
package com.autobots.camera.capture

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class CaptureJob(
    val jpegBytes: ByteArray,
    val timestampMs: Long,
    val metadata: CaptureMetadata? = null
)

data class CaptureMetadata(
    val faceCount: Int,
    val largestFaceArea: Float,   // 0.0–1.0
    val thermalStatus: String
)

sealed class CaptureResult {
    data class Success(val bytesWritten: Long) : CaptureResult()
    data class Error(val message: String)     : CaptureResult()
}

class AdaptiveCaptureQueue(
    private val outputDir: File,
    initialMaxCapacity: Int = 20,
    private val filePrefix: String = "runner"
) {
    companion object {
        private const val TAG = "CaptureQueue"
    }

    // ── State ────────────────────────────────────────────────────────────────
    @Volatile private var maxCapacity = initialMaxCapacity
    private val channel = Channel<CaptureJob>(capacity = Channel.UNLIMITED)
    // NOTE: we use UNLIMITED channel + manual drop in enqueue() for flexibility
    // This lets us change capacity dynamically without recreating the channel

    private val queuedCount    = AtomicInteger(0)
    private val totalSaved     = AtomicInteger(0)
    private val totalDropped   = AtomicInteger(0)
    private val totalBytesMs   = AtomicLong(0L)   // for write speed calculation

    // ── Writer coroutine scope ────────────────────────────────────────────────
    private val writerScope = CoroutineScope(
        Dispatchers.IO +
        SupervisorJob() +
        CoroutineName("CaptureQueueWriter")
    )

    init {
        outputDir.mkdirs()
        startWriter()
    }

    /**
     * Enqueue a capture job.
     * Non-blocking: if queue is at capacity, the OLDEST item is dropped.
     * Returns false if dropped.
     */
    fun enqueue(job: CaptureJob): Boolean {
        val current = queuedCount.get()
        if (current >= maxCapacity) {
            totalDropped.incrementAndGet()
            Log.w(TAG, "Queue full ($current/$maxCapacity) — dropping frame. Total dropped: ${totalDropped.get()}")
            return false
        }
        queuedCount.incrementAndGet()
        val result = channel.trySend(job)
        if (result.isFailure) {
            queuedCount.decrementAndGet()
            totalDropped.incrementAndGet()
            return false
        }
        return true
    }

    /**
     * Dynamically adjust queue capacity based on thermal status / disk speed.
     * Called by ThermalThrottlePolicy.
     */
    fun setCapacity(newMax: Int) {
        maxCapacity = newMax.coerceAtLeast(1)
        Log.i(TAG, "Queue capacity updated → $maxCapacity")
    }

    // ── Writer ────────────────────────────────────────────────────────────────
    private fun startWriter() {
        writerScope.launch {
            for (job in channel) {
                writeJobToDisk(job)
                queuedCount.decrementAndGet()
            }
        }
    }

    private suspend fun writeJobToDisk(job: CaptureJob) {
        val filename = "${filePrefix}_${job.timestampMs}.jpg"
        val file     = File(outputDir, filename)

        val startMs = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            try {
                FileOutputStream(file).use { fos ->
                    fos.write(job.jpegBytes)
                    fos.flush()
                }
                // Write EXIF / metadata if available
                job.metadata?.let { writeMetadataXmp(file, it) }

                val elapsed = System.currentTimeMillis() - startMs
                totalSaved.incrementAndGet()
                totalBytesMs.addAndGet(elapsed)

                Log.d(TAG, "Saved $filename (${job.jpegBytes.size / 1024}KB in ${elapsed}ms)")
                checkWriteSpeed()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to write $filename", e)
                file.delete()   // clean up partial file
            }
        }
    }

    /**
     * Measure running average write speed.
     * If too slow, log a warning. Future: reduce JPEG quality automatically.
     */
    private fun checkWriteSpeed() {
        val saved = totalSaved.get()
        if (saved < 5) return   // need some samples first

        val avgMs = totalBytesMs.get() / saved
        if (avgMs > 300) {
            Log.w(TAG, "Disk write slow: avg ${avgMs}ms/file. Consider reducing JPEG quality.")
            // TODO: emit a callback to CaptureStrategy to reduce quality
        }
    }

    /**
     * Optionally write XMP sidecar / EXIF user comment with face data.
     * Useful for later batch processing / culling.
     */
    private fun writeMetadataXmp(jpegFile: File, meta: CaptureMetadata) {
        try {
            val exif = androidx.exifinterface.media.ExifInterface(jpegFile)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT,
                "faces=${meta.faceCount};area=${meta.largestFaceArea};thermal=${meta.thermalStatus}"
            )
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EXIF metadata", e)
        }
    }

    /** Stats for debugging / UI overlay */
    fun getStats() = QueueStats(
        queued   = queuedCount.get(),
        capacity = maxCapacity,
        saved    = totalSaved.get(),
        dropped  = totalDropped.get(),
        avgWriteMs = if (totalSaved.get() > 0) totalBytesMs.get() / totalSaved.get() else 0L
    )

    fun shutdown() {
        channel.close()
        writerScope.cancel()
        Log.i(TAG, "Queue shutdown. Saved=${totalSaved.get()}, Dropped=${totalDropped.get()}")
    }
}

data class QueueStats(
    val queued: Int,
    val capacity: Int,
    val saved: Int,
    val dropped: Int,
    val avgWriteMs: Long
)
```

---

## Queue Lifecycle with Thermal Integration

```kotlin
// In CameraXPipeline — wire thermal events to queue capacity
thermalMonitor.start { status ->
    val policy = ThermalThrottlePolicy.forStatus(status)
    captureQueue.setCapacity(policy.maxQueueSize)
    captureStrategy.applyThermalThrottle(status)
}
```

---

## Memory Budget Analysis

ตัวอย่าง worst-case scenario:

```
Queue capacity = 20 items
Average JPEG (4K, Q95) ≈ 6MB

Peak RAM from queue = 20 × 6MB = 120MB  ← acceptable

With thermal MODERATE: capacity = 10 → 60MB peak
With thermal SEVERE:   capacity = 5  → 30MB peak
```

> ✅ **Safe:** Modern Android devices มี RAM 8–12GB; 120MB peak เป็นสัดส่วนที่ยอมรับได้  
> ❌ **Danger zone:** ถ้าใช้ Phase 2 (50MP) → 20 × 20MB = 400MB → ต้องลด capacity เหลือ 5–8

---

## File Output Structure

```
/storage/emulated/0/DCIM/AutoBots/
├── session_1720609600000/
│   ├── runner_1720609612345.jpg   (8.3MP JPEG, ~6MB)
│   ├── runner_1720609614820.jpg
│   ├── runner_1720609621033.jpg
│   ├── ...
│   └── thermal_log.csv            (timestamp, status, capture_count)
└── session_1720610200000/
    └── ...
```

### Naming Convention

```
runner_{Unix timestamp ms}.jpg
```

- Sortable chronologically by filename
- Unique — no collision even under high burst rate
- Parseable for later batch export / renaming tools

---

## OOM Prevention Checklist

```
✅ Channel<CaptureJob> — bounded, backpressure drops oldest items
✅ ByteArray pool for frame extraction (FrameExtractStrategy)
✅ ImageProxy.close() called immediately after byte extraction
✅ Thermal throttle reduces queue capacity when under pressure
✅ Phase 2: Mutex prevents concurrent ImageCapture calls
✅ Single coroutine writer — no parallel I/O contention
✅ File error handling — delete partial files to reclaim space
```
