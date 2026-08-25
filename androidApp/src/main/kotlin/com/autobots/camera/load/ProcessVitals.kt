package com.autobots.camera.load

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File

/**
 * What **this process** is using, as opposed to what the device is using.
 *
 * [DeviceLoadReader] samples `ActivityManager.MemoryInfo`, which is the whole machine: it
 * moves when another app opens and it cannot answer the only question that matters after a
 * two-hour run dies — *were we the ones holding the memory?* Everything here is scoped to
 * our own pid, and all of it is readable without a permission because `/proc/self` is always
 * ours.
 *
 * ### Why native heap and not just the Java heap
 *
 * Since Android 8 a `Bitmap`'s pixels live in the **native** heap, not the Java one. The
 * pipeline's largest allocations by far are 4K bitmaps, so a leak there moves
 * [nativeHeapKb] and leaves [javaHeapKb] flat. Sampling only the Java heap would have been
 * a diagnostic that is blind to the most likely failure.
 *
 * ### What "cpu" means here
 *
 * [cpuJiffies] is cumulative CPU **time**, not a percentage — a percentage needs two samples
 * and the wall time between them, which the report can compute after the fact and this
 * reader deliberately does not. Storing the raw counter means a gap in sampling cannot
 * silently produce a wrong rate.
 */
data class ProcessVitals(
    /** utime + stime for the whole process, in kernel ticks. Cumulative since start. */
    val cpuJiffies: Long,
    /** Cumulative CPU ticks per thread name, for the few threads worth naming. */
    val threadCpuJiffies: Map<String, Long>,
    /** Java heap in use (total − free). */
    val javaHeapKb: Long,
    /** Java heap ceiling — `javaHeapKb` approaching this is an OOM in progress. */
    val javaHeapMaxKb: Long,
    /** Native heap in use. **Bitmaps are here.** */
    val nativeHeapKb: Long,
    /** Graphics/gralloc/EGL attribution from `Debug.MemoryInfo`, or 0 when unavailable. */
    val graphicsKb: Long,
    /** Total PSS for this process, or 0 when the read failed. */
    val totalPssKb: Long,
)

/**
 * Reads [ProcessVitals]. Cheap enough to call on every load sample; the per-thread walk is
 * the only part with a cost worth thinking about and it is capped.
 */
class ProcessVitalsReader(context: Context) {

    private val activityManager =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pid = Process.myPid()

    /**
     * PSS is the expensive one — the kernel walks the whole address space for it, which on a
     * process holding 4K bitmaps is not something to do twice a second. Sampled on a slower
     * cadence than the rest and carried forward in between.
     */
    private var lastPssAtMs = 0L
    private var lastGraphicsKb = 0L
    private var lastTotalPssKb = 0L

    fun sample(nowMs: Long): ProcessVitals {
        val runtime = Runtime.getRuntime()
        maybeRefreshPss(nowMs)
        return ProcessVitals(
            cpuJiffies = readSelfCpuJiffies(),
            threadCpuJiffies = readThreadCpuJiffies(),
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            javaHeapMaxKb = runtime.maxMemory() / 1024L,
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
            graphicsKb = lastGraphicsKb,
            totalPssKb = lastTotalPssKb,
        )
    }

    private fun maybeRefreshPss(nowMs: Long) {
        if (nowMs - lastPssAtMs < PSS_INTERVAL_MS) return
        lastPssAtMs = nowMs
        runCatching {
            val info = activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull() ?: return
            lastGraphicsKb = info.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L
            lastTotalPssKb = info.totalPss.toLong()
        }.onFailure {
            Log.w(TAG, "getProcessMemoryInfo failed: ${it.message}")
        }
    }

    /** Fields 14 and 15 of `/proc/self/stat` are utime and stime. */
    private fun readSelfCpuJiffies(): Long = readStatCpu(SELF_STAT)

    /**
     * Per-thread CPU, which is what distinguishes *"the detect workers are saturated"* from
     * *"the detect workers are waiting"* — two readings that `queue_wait` alone cannot tell
     * apart, and that point at opposite fixes.
     *
     * Only threads whose name matches something the pipeline owns are reported: a phone runs
     * dozens of framework threads whose names would bury the four that matter. Names are
     * normalised by stripping the trailing worker index so `DefaultDispatcher-worker-3` and
     * `-worker-7` aggregate into one row.
     */
    private fun readThreadCpuJiffies(): Map<String, Long> {
        val dir = File("/proc/self/task")
        val tasks = runCatching { dir.listFiles() }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Long>()
        var walked = 0
        for (task in tasks) {
            if (walked++ >= MAX_THREADS_WALKED) break
            val stat = File(task, "stat")
            val line = runCatching { stat.readText() }.getOrNull() ?: continue
            val name = threadNameOf(line) ?: continue
            val bucket = bucketFor(name) ?: continue
            out[bucket] = (out[bucket] ?: 0L) + cpuFromStatLine(line)
        }
        return out
    }

    private fun readStatCpu(file: File): Long =
        runCatching { cpuFromStatLine(file.readText()) }.getOrDefault(0L)

    /**
     * A `stat` line cannot be split on whitespace: field 2 is the thread name in parentheses
     * and a thread name may itself contain spaces or a `)`. The stable parse is to cut at the
     * **last** `)` and count from there, which is what every /proc reader in the wild does.
     */
    private fun cpuFromStatLine(line: String): Long {
        val tail = line.substringAfterLast(')').trim().split(' ')
        // After the closing paren, index 0 is field 3 (state). utime is field 14, stime 15.
        val utime = tail.getOrNull(11)?.toLongOrNull() ?: return 0L
        val stime = tail.getOrNull(12)?.toLongOrNull() ?: return 0L
        return utime + stime
    }

    private fun threadNameOf(line: String): String? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return line.substring(open + 1, close)
    }

    /**
     * Which pipeline role a thread belongs to, or null to ignore it.
     *
     * Kept as an explicit allow-list rather than "report everything": the point of this field
     * is a four-row table that can be read at a glance, not a process dump.
     */
    private fun bucketFor(name: String): String? = when {
        name.startsWith("DefaultDispatcher") -> "detect"
        name.startsWith("Dispatchers.IO") || name.startsWith("pool-") -> "io"
        name.startsWith("Codec") || name.contains("codec") -> "codec"
        name == "main" -> "main"
        else -> null
    }

    companion object {
        private const val TAG = "ProcessVitals"
        private val SELF_STAT = File("/proc/self/stat")

        /** A phone process has ~60 threads; this is a runaway guard, not a real limit. */
        private const val MAX_THREADS_WALKED = 256

        /** PSS walks the address space — once every few seconds is plenty for a trend. */
        private const val PSS_INTERVAL_MS = 5_000L

        /**
         * Kernel ticks per second. Effectively always 100 on Android, and there is no public
         * API for `sysconf(_SC_CLK_TCK)` — a wrong constant would only scale the CPU numbers,
         * never change their shape, so a hardcoded value is the right trade against JNI.
         */
        const val JIFFIES_PER_SECOND = 100.0
    }
}
