package com.autobots.camera.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.autobots.camera.DetectorBackend
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/**
 * Runs **every** available detector over the same frames and records what each one saw.
 *
 * Two questions need answering and neither can be settled by importing the same clip once per
 * backend:
 *
 *  - **Is the `face_det_lite` box decode even correct?** Its output is a heatmap plus four
 *    numbers per cell, and the meaning of those numbers was inferred from quantisation ranges
 *    rather than read from documentation. A wrong reading still produces plausible boxes, and
 *    per-backend runs would report the resulting recall as if it meant something. Overlaying
 *    both detectors on the *same* frame makes a bad decode obvious: the boxes stop coinciding.
 *  - **Does anything see the runners ML Kit misses?** `no_subject` covered 64% of sampled
 *    frames in 0.1.4 and watching the footage said people were there. Comparing across
 *    separate runs cannot distinguish "different detector" from "different frame".
 *
 * The primary backend alone decides what the session keeps, so the pipeline's own numbers stay
 * exactly what they would have been. Everything here is observation.
 *
 * **`realtimeRatio` is meaningless while this is on** — the chunk does several times the
 * detection work. Throughput belongs to single-backend runs.
 */
class DetectorComparison private constructor(
    private val entries: List<Entry>,
    private val primary: DetectorBackend,
    private val unavailable: Map<String, String>,
) : AutoCloseable {

    private class Entry(val backend: DetectorBackend, val detector: SubjectFaceDetector) {
        var totalNs = 0L
        var frames = 0
        var framesWithDetection = 0
        var detections = 0
    }

    private val frameLog = Collections.synchronizedList(ArrayList<JSONObject>())

    /**
     * Runs every detector on [detectBitmap] and records the outcome.
     *
     * Called after the primary has already decided the frame's fate, so nothing here can
     * change what the session keeps.
     */
    suspend fun record(chunkIndex: Int, ptsUs: Long, detectBitmap: Bitmap) {
        val perBackend = JSONObject()
        var anyFound = false

        for (entry in entries) {
            val startNs = System.nanoTime()
            val boxes = runCatching { entry.detector.detect(detectBitmap) }.getOrElse {
                Log.w(TAG, "${entry.backend.slug} threw at ${ptsUs}us", it)
                emptyList()
            }
            val elapsedNs = System.nanoTime() - startNs

            entry.totalNs += elapsedNs
            entry.frames++
            entry.detections += boxes.size
            if (boxes.isNotEmpty()) {
                entry.framesWithDetection++
                anyFound = true
            }

            perBackend.put(
                entry.backend.slug,
                JSONObject().apply {
                    put("ms", round3(elapsedNs / 1e6))
                    put("n", boxes.size)
                    // The pipeline's size gate reads height against the detect bitmap, so
                    // recording the same ratio here makes the two directly comparable.
                    put(
                        "largestHeightRatio",
                        boxes.maxOfOrNull { it.height() }
                            ?.let { round3(it.toDouble() / detectBitmap.height) }
                            ?: JSONObject.NULL,
                    )
                    put("boxes", boxesJson(boxes))
                },
            )
        }

        // Frames nobody saw anything in are the bulk of a session and carry no information
        // beyond "all agreed on nothing" — keep them as a count, not as rows.
        if (!anyFound) {
            emptyFrames++
            return
        }
        if (frameLog.size >= MAX_FRAME_ROWS) {
            droppedFrames++
            return
        }
        frameLog.add(
            JSONObject().apply {
                put("chunk", chunkIndex)
                put("ptsUs", ptsUs)
                put("detectW", detectBitmap.width)
                put("detectH", detectBitmap.height)
                put("backends", perBackend)
                // Agreement with the ML Kit baseline: the box-decode check. Values near 1
                // mean the two detectors drew the same rectangle; near 0 with both finding
                // something means the decode is wrong, not that recall differs.
                put("iouVsMlKitFast", agreementJson(perBackend))
            },
        )
    }

    private var emptyFrames = 0
    private var droppedFrames = 0

    /**
     * Best IoU between each backend's boxes and ML Kit FAST's, per frame.
     *
     * Deliberately *not* a mean over the session: a decode bug shows up as consistently low
     * agreement on frames where both detectors fired, and averaging that together with frames
     * only one of them saw would hide it.
     */
    private fun agreementJson(perBackend: JSONObject): JSONObject {
        val baseline = perBackend.optJSONObject(DetectorBackend.MlKitFast.slug)
            ?.optJSONArray("boxes") ?: return JSONObject()
        if (baseline.length() == 0) return JSONObject()
        return JSONObject().apply {
            for (entry in entries) {
                if (entry.backend == DetectorBackend.MlKitFast) continue
                val theirs = perBackend.optJSONObject(entry.backend.slug)?.optJSONArray("boxes")
                if (theirs == null || theirs.length() == 0) continue
                var best = 0.0
                for (i in 0 until baseline.length()) {
                    for (j in 0 until theirs.length()) {
                        best = max(best, iou(rectOf(baseline, i), rectOf(theirs, j)))
                    }
                }
                put(entry.backend.slug, round3(best))
            }
        }
    }

    fun render(): String = JSONObject().apply {
        put("schema", SCHEMA_VERSION)
        put("primaryBackend", primary.slug)
        put(
            "note",
            "Every detector ran on the same frames; only the primary decided what was kept. " +
                "realtimeRatio in perf_report.json is not comparable to a single-backend run.",
        )
        put(
            "unavailable",
            JSONObject().apply { unavailable.forEach { (k, v) -> put(k, v) } },
        )
        put(
            "summary",
            JSONArray().apply {
                for (entry in entries) {
                    put(
                        JSONObject().apply {
                            put("backend", entry.backend.slug)
                            put("frames", entry.frames)
                            put("framesWithDetection", entry.framesWithDetection)
                            put("detections", entry.detections)
                            put(
                                "avgMs",
                                if (entry.frames == 0) {
                                    JSONObject.NULL
                                } else {
                                    round3(entry.totalNs / entry.frames / 1e6)
                                },
                            )
                        },
                    )
                }
            },
        )
        put("framesAllEmpty", emptyFrames)
        put("framesDropped", droppedFrames)
        put("frames", JSONArray(synchronized(frameLog) { ArrayList(frameLog) }))
    }.toString(2)

    override fun close() {
        entries.forEach { runCatching { it.detector.close() } }
    }

    companion object {
        private const val TAG = "DetectorCompare"
        const val FILE_NAME = "detector_compare.json"
        private const val SCHEMA_VERSION = 1

        /** Backstop so a long session cannot grow the file without bound. */
        private const val MAX_FRAME_ROWS = 4000
        private const val MAX_BOXES_PER_BACKEND = 6

        /**
         * Builds one detector per backend, skipping any that cannot start and recording why.
         *
         * @param primary the backend whose verdict the pipeline uses; included in the
         *   comparison so its timings are measured the same way as everyone else's.
         */
        fun create(context: Context, primary: DetectorBackend): DetectorComparison {
            val entries = mutableListOf<Entry>()
            val unavailable = mutableMapOf<String, String>()
            for (backend in DetectorBackend.entries) {
                if (backend == DetectorBackend.CompareAll) continue
                val detector: SubjectFaceDetector? = when {
                    backend.usesLiteRt -> FaceDetLiteDetector.create(context, backend)
                    backend == DetectorBackend.MlKitAccurate -> OfflineFaceDetector(accurate = true)
                    else -> OfflineFaceDetector(accurate = false)
                }
                if (detector == null) {
                    unavailable[backend.slug] =
                        if (backend == DetectorBackend.LiteRtNpu) {
                            QnnDelegate.unavailableReason(context) ?: "init failed"
                        } else {
                            "init failed"
                        }
                    continue
                }
                entries.add(Entry(backend, detector))
            }
            Log.i(
                TAG,
                "comparison ready: ${entries.joinToString { it.backend.slug }}" +
                    if (unavailable.isEmpty()) "" else " · unavailable=$unavailable",
            )
            return DetectorComparison(entries, primary, unavailable)
        }

        private fun boxesJson(boxes: List<Rect>) = JSONArray().apply {
            for (box in boxes.sortedByDescending { it.height() }.take(MAX_BOXES_PER_BACKEND)) {
                put(JSONArray().apply { put(box.left); put(box.top); put(box.right); put(box.bottom) })
            }
        }

        private fun rectOf(array: JSONArray, index: Int): Rect {
            val b = array.getJSONArray(index)
            return Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
        }

        private fun iou(a: Rect, b: Rect): Double {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (w <= 0 || h <= 0) return 0.0
            val inter = w.toDouble() * h
            val union = a.width().toDouble() * a.height() + b.width().toDouble() * b.height() - inter
            return if (union <= 0) 0.0 else inter / union
        }

        private fun round3(value: Double) = Math.round(value * 1000.0) / 1000.0
    }
}
