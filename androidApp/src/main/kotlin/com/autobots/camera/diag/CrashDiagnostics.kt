package com.autobots.camera.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.autobots.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Why the last process died.
 *
 * A `perf_stream.jsonl` says what the pipeline was doing when it stopped. It cannot say what
 * killed it, because a file stops being written *before* the thing that ends the process.
 * These two sources answer that, and they answer different halves of it:
 *
 * - [installCrashHandler] catches an uncaught Kotlin/Java exception, with a stack.
 * - [reportLastExit] reads the system's own record of the previous process's death.
 *
 * The second is the one that matters. A 4K pipeline driving MediaCodec and an NPU dies most
 * plausibly by **native crash** or by **the low-memory killer**, and neither of those runs a
 * single line of our code on the way out — no handler, no stack, no final write. Only the
 * platform sees them, and [ApplicationExitInfo] is where it writes them down.
 */
object CrashDiagnostics {

    const val EXIT_FILE_NAME = "last_exit.json"
    const val CRASH_FILE_NAME = "crash.txt"

    /**
     * Record the previous process's exit reason, if the platform kept one.
     *
     * Call once at startup. Writes [EXIT_FILE_NAME] into [dir] and returns the reason so the
     * caller can log it; returns null on API < 30 or when there is no history.
     *
     * @param dir where to write. Use the diagnostics directory, not a session directory —
     *   at startup the session that died is not necessarily identifiable.
     */
    fun reportLastExit(context: Context, dir: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val history = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        }.getOrElse {
            Log.w(TAG, "getHistoricalProcessExitReasons failed: ${it.message}")
            return null
        }
        if (history.isEmpty()) return null

        val array = JSONArray()
        for (info in history) array.put(exitJson(info))
        val newest = history.first()

        runCatching {
            dir.mkdirs()
            File(dir, EXIT_FILE_NAME).writeText(
                JSONObject().apply {
                    put("generatedAtEpochMs", System.currentTimeMillis())
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("exits", array)
                    put("note", NOTE)
                }.toString(2),
            )
        }.onFailure { Log.w(TAG, "Cannot write $EXIT_FILE_NAME: ${it.message}") }

        val summary = "${reasonName(newest.reason)} · ${newest.description ?: "no description"}"
        Log.i(TAG, "Previous exit: $summary (pss=${newest.pss}kB rss=${newest.rss}kB)")
        return summary
    }

    private fun exitJson(info: ApplicationExitInfo): JSONObject = JSONObject().apply {
        put("timestamp", info.timestamp)
        put("reason", reasonName(info.reason))
        put("reasonCode", info.reason)
        put("description", info.description ?: JSONObject.NULL)
        put("status", info.status)
        put("importance", info.importance)
        // The two numbers that separate "we were killed for memory" from everything else.
        put("pssKb", info.pss)
        put("rssKb", info.rss)
        // Present for ANR and native crashes; this is the actual trace the platform captured.
        runCatching { info.traceInputStream?.readBytes()?.decodeToString() }
            .getOrNull()
            ?.take(MAX_TRACE_CHARS)
            ?.let { put("trace", it) }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Write a stack trace before handing the crash on to whoever had the handler.
     *
     * Chaining to the previous handler is not optional: replacing it would suppress the
     * platform's own crash reporting, which is the thing that populates
     * [ApplicationExitInfo] in the first place. This handler adds a file; it must not take
     * anything away.
     *
     * Deliberately small and allocation-light. The process is already dying, possibly of
     * memory exhaustion, and a handler that needs a large allocation to run is a handler that
     * does not run in the case it was written for.
     */
    fun installCrashHandler(dir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(dir, CRASH_FILE_NAME).writeText(
                    buildString {
                        appendLine("at=${System.currentTimeMillis()}")
                        appendLine("version=${BuildConfig.VERSION_NAME}")
                        appendLine("thread=${thread.name}")
                        appendLine("error=${error::class.java.name}: ${error.message}")
                        appendLine()
                        append(trace.toString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val TAG = "CrashDiagnostics"
    private const val MAX_EXITS = 5
    private const val MAX_TRACE_CHARS = 64 * 1024

    private const val NOTE =
        "reason CRASH_NATIVE or LOW_MEMORY means no Kotlin handler ran and there is no " +
            "crash.txt — check perf_stream.jsonl for what the pipeline was doing and the " +
            "rssKb here for how much memory the process held when it died."
}
