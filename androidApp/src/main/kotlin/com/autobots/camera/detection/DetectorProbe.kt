package com.autobots.camera.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import com.autobots.camera.DetectorBackend
import com.autobots.camera.perf.CamPerf
import kotlinx.coroutines.runBlocking

/**
 * Startup check: which detector backends can actually run here, and roughly how fast.
 *
 * A backend failing is not exceptional — the QNN libraries are ~96 MB and a build may simply
 * not carry them, and the GPU delegate can decline a fully-quantised graph. Without this, the
 * discovery happens at the first frame of a session, and the fallback to ML Kit is silent
 * enough to be mistaken for a result.
 *
 * The timing is a smoke test, not a benchmark: it runs on a synthetic gradient, so detections
 * are meaningless and only the "did it initialise and execute" answer is trustworthy. Real
 * numbers come from importing the same clip once per backend and comparing `perf_report.json`.
 */
object DetectorProbe {
    private const val TAG = "DetectorProbe"

    /** Matches the detect bitmap the pipeline produces at UHD, so tiling behaves as it will. */
    private const val PROBE_W = 640
    private const val PROBE_H = 1138

    data class Result(
        val backend: DetectorBackend,
        val available: Boolean,
        val detail: String,
        val firstRunMs: Long = -1,
        val warmRunMs: Long = -1,
    )

    fun run(context: Context): List<Result> {
        val bitmap = syntheticFrame()
        return try {
            DetectorBackend.entries.map { probe(context, it, bitmap) }
                .also { results -> CamPerf.log { table(results) } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun probe(context: Context, backend: DetectorBackend, frame: Bitmap): Result {
        if (backend == DetectorBackend.LiteRtNpu) {
            QnnDelegate.unavailableReason(context)?.let {
                return Result(backend, available = false, detail = it)
            }
        }
        val detector = when {
            backend.usesLiteRt -> FaceDetLiteDetector.create(context, backend)
                ?: return Result(backend, available = false, detail = "detector init failed")
            backend == DetectorBackend.MlKitAccurate -> OfflineFaceDetector(accurate = true)
            else -> OfflineFaceDetector(accurate = false)
        }
        return try {
            // First call carries model load and, for QNN, graph compilation; the second is
            // what steady state looks like.
            val first = timed { runBlocking { detector.detect(frame) } }
            val warm = timed { runBlocking { detector.detect(frame) } }
            val detail = detector.diagnostics.entries.joinToString(" ") { "${it.key}=${it.value}" }
            Result(backend, available = true, detail = detail, firstRunMs = first, warmRunMs = warm)
        } catch (t: Throwable) {
            Log.w(TAG, "${backend.slug} threw during probe", t)
            Result(backend, available = false, detail = t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { detector.close() }
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    /** A gradient, not a face — this answers "does it execute", never "does it detect". */
    private fun syntheticFrame(): Bitmap =
        Bitmap.createBitmap(PROBE_W, PROBE_H, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRect(
                0f, 0f, PROBE_W.toFloat(), PROBE_H.toFloat(),
                Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, PROBE_W.toFloat(), PROBE_H.toFloat(),
                        0xFF202020.toInt(), 0xFFE0E0E0.toInt(), Shader.TileMode.CLAMP,
                    )
                },
            )
        }

    private fun table(results: List<Result>): String = buildString {
        append("┌─ detector backends\n")
        for (r in results) {
            append(
                if (r.available) {
                    String.format(
                        "│ %-18s OK    first %5d ms  warm %5d ms  %s%n",
                        r.backend.slug, r.firstRunMs, r.warmRunMs, r.detail,
                    )
                } else {
                    String.format("│ %-18s UNAVAILABLE  %s%n", r.backend.slug, r.detail)
                },
            )
        }
        append("└ synthetic input — timings are a smoke test, not a benchmark")
    }
}
