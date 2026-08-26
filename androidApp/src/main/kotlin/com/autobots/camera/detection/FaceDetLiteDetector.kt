package com.autobots.camera.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.autobots.camera.DetectorBackend
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Qualcomm AI Hub's `face_det_lite` (Lightweight-Face-Detection, w8a8) via LiteRT.
 *
 * Same weights on every backend — only the delegate changes — so a difference between
 * [DetectorBackend.LiteRtCpu], `LiteRtGpu` and `LiteRtNpu` is a difference in *hardware*, not
 * in what the model sees. Full model shape and quantisation parameters are in
 * `assets/models/README.md`.
 *
 * ### Why it tiles
 *
 * The input is a fixed **640×480 grayscale** tensor; an upright frame is 9:16. Letterboxing the
 * whole thing would scale by 0.125 and leave a runner's face ~17 px tall, well under the ~40 px
 * ML Kit gets today — the model would look worse purely from geometry. Instead the frame is cut
 * into overlapping 4:3 tiles, each mapped 1:1 into the tensor where possible.
 *
 * At the current 640-wide detect bitmap this works out especially cleanly: tiles come out
 * exactly 640×480 with **no rescaling at all**, so the model sees the same pixels ML Kit does,
 * merely partitioned. That is what makes the comparison honest. Feeding tiles from a *larger*
 * source, which is what would make faces bigger than ML Kit ever sees, is a later step and a
 * separate variable.
 */
class FaceDetLiteDetector private constructor(
    private val interpreter: Interpreter,
    private val delegate: Delegate?,
    private val backend: DetectorBackend,
    private val effectiveBackend: DetectorBackend,
    private val heatmapIndex: Int,
    private val bboxIndex: Int,
    /**
     * Probability a cell must reach to be considered a face at all.
     *
     * Below this the cell is dropped before NMS ever sees it, so raising it cannot recover
     * anything later in the pipeline — it is the one gate whose rejects leave no trace.
     */
    private val minScore: Float,
) : SubjectFaceDetector {

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(MODEL_W * MODEL_H).order(ByteOrder.nativeOrder())
    private val heatmapBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H).order(ByteOrder.nativeOrder())
    private val bboxBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * 4).order(ByteOrder.nativeOrder())
    private val landmarkBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * 10).order(ByteOrder.nativeOrder())

    /**
     * [minScore] as a raw heatmap value, so the hot loop compares in the model's own units.
     * Monotone, therefore an identical cut — see [sigmoid].
     */
    private val minLogit: Float = logitOf(minScore)

    /** Reused across frames; the detect bitmap is a constant size within a session. */
    private var pixelCache = IntArray(0)

    private var tilesPerFrame = 0
    @Volatile private var loggedRawSample = false

    override val diagnostics: Map<String, Any>
        get() = mapOf(
            "requestedBackend" to backend.slug,
            "effectiveBackend" to effectiveBackend.slug,
            "tileCount" to tilesPerFrame,
            "minScore" to minScore,
        )

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> {
        val tiles = planTiles(bitmap.width, bitmap.height)
        tilesPerFrame = tiles.size

        if (pixelCache.size < bitmap.width * bitmap.height) {
            pixelCache = IntArray(bitmap.width * bitmap.height)
        }
        bitmap.getPixels(pixelCache, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val found = mutableListOf<Scored>()
        for (tile in tiles) {
            fillGrayscale(pixelCache, bitmap.width, bitmap.height, tile)
            val outputs = mapOf(
                heatmapIndex to heatmapBuffer.also { it.rewind() },
                bboxIndex to bboxBuffer.also { it.rewind() },
            ) + landmarkOutput()
            try {
                interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            } catch (t: Throwable) {
                Log.e(TAG, "Inference failed on tile $tile", t)
                return emptyList()
            }
            decodeTile(tile, found)
        }
        return nonMaxSuppression(found).map { DetectedFace(it.rect, sigmoid(it.score)) }
    }

    private fun landmarkOutput(): Map<Int, Any> {
        // The graph has three outputs and landmarks are unused, but TFLite still requires a
        // destination buffer for every one of them.
        val index = (0..2).first { it != heatmapIndex && it != bboxIndex }
        return mapOf(index to landmarkBuffer.also { it.rewind() })
    }

    // ---------------------------------------------------------------- tiling

    /** A crop of the source, in source pixels, that becomes one 640×480 tensor. */
    private data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Overlapping 4:3 crops covering the frame top to bottom.
     *
     * Tiles overlap by [TILE_OVERLAP] so a face landing on a seam is whole in at least one of
     * them; the duplicates that produces are merged by [nonMaxSuppression] afterwards.
     */
    private fun planTiles(width: Int, height: Int): List<Tile> {
        // Tile width always spans the frame — subjects are distributed vertically in an
        // upright frame, so splitting horizontally would only add inferences.
        val tileH = (width * MODEL_H / MODEL_W).coerceAtLeast(1)
        if (height <= tileH) return listOf(Tile(0, 0, width, height))

        val stride = (tileH * (1f - TILE_OVERLAP)).roundToInt().coerceAtLeast(1)
        val count = ceil((height - tileH).toFloat() / stride).toInt() + 1
        val step = if (count > 1) (height - tileH).toFloat() / (count - 1) else 0f
        return (0 until count).map { Tile(0, (it * step).roundToInt(), width, tileH) }
    }

    /**
     * Crop → grayscale → the input tensor.
     *
     * The model wants luminance, which is exactly what a video frame's Y plane already holds —
     * the reason this detector can eventually skip YUV→RGB entirely. Here the source is still
     * an ARGB bitmap, so luminance is recomputed with the usual integer BT.601 weights.
     */
    private fun fillGrayscale(pixels: IntArray, srcW: Int, srcH: Int, tile: Tile) {
        inputBuffer.rewind()
        val xScale = tile.w.toFloat() / MODEL_W
        val yScale = tile.h.toFloat() / MODEL_H
        for (ty in 0 until MODEL_H) {
            val sy = (tile.y + ty * yScale).toInt().coerceIn(0, srcH - 1)
            val rowStart = sy * srcW
            for (tx in 0 until MODEL_W) {
                val sx = (tile.x + tx * xScale).toInt().coerceIn(0, srcW - 1)
                val p = pixels[rowStart + sx]
                val luma = (
                    77 * ((p shr 16) and 0xFF) +
                        150 * ((p shr 8) and 0xFF) +
                        29 * (p and 0xFF)
                    ) shr 8
                inputBuffer.put(luma.toByte())
            }
        }
        inputBuffer.rewind()
    }

    // ---------------------------------------------------------------- postprocess

    private data class Scored(val rect: Rect, val score: Float)

    /**
     * Anchor-free centre decode at stride 8.
     *
     * `heatmap` scores one cell per 8×8 patch and `bbox` holds that cell's distance to each of
     * the four box edges, in cells. A cell is only considered when it beats its eight
     * neighbours, which is the cheap stand-in for the 3×3 max-pool the reference postprocess
     * uses to stop one face lighting up a cluster of cells.
     *
     * The edge-distance reading is inferred from the quantisation ranges rather than from
     * documentation: `bbox` dequantises to at most ~79, and 79 cells × 8 px ≈ 635 px, which is
     * the input width almost exactly. A different convention would not land there by accident.
     * It is still an inference, which is why [DUMP_RAW_SAMPLE] exists and why the first runs
     * should be checked against ML Kit on frames where both fire.
     */
    private fun decodeTile(tile: Tile, into: MutableList<Scored>) {
        val hm = heatmapBuffer
        val bb = bboxBuffer
        val xScale = tile.w.toFloat() / MODEL_W
        val yScale = tile.h.toFloat() / MODEL_H

        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val score = dequantHeatmap(hm.get(gy * GRID_W + gx))
                if (score < minLogit) continue
                if (!isLocalMax(hm, gx, gy, score)) continue

                val base = (gy * GRID_W + gx) * 4
                val left = dequantBbox(bb.get(base))
                val top = dequantBbox(bb.get(base + 1))
                val right = dequantBbox(bb.get(base + 2))
                val bottom = dequantBbox(bb.get(base + 3))

                if (DUMP_RAW_SAMPLE && !loggedRawSample) {
                    loggedRawSample = true
                    Log.i(
                        TAG,
                        "raw decode sample: cell=($gx,$gy) score=$score " +
                            "ltrb=($left,$top,$right,$bottom) → px " +
                            "(${(gx - left) * STRIDE},${(gy - top) * STRIDE})-" +
                            "(${(gx + right) * STRIDE},${(gy + bottom) * STRIDE})",
                    )
                }

                // Tensor space → tile space → source bitmap space.
                val x1 = ((gx - left) * STRIDE * xScale + tile.x)
                val y1 = ((gy - top) * STRIDE * yScale + tile.y)
                val x2 = ((gx + right) * STRIDE * xScale + tile.x)
                val y2 = ((gy + bottom) * STRIDE * yScale + tile.y)
                if (x2 <= x1 || y2 <= y1) continue

                into.add(
                    Scored(
                        Rect(x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt()),
                        score,
                    ),
                )
            }
        }
    }

    private fun isLocalMax(hm: ByteBuffer, gx: Int, gy: Int, score: Float): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = gx + dx
                val ny = gy + dy
                if (nx < 0 || ny < 0 || nx >= GRID_W || ny >= GRID_H) continue
                if (dequantHeatmap(hm.get(ny * GRID_W + nx)) > score) return false
            }
        }
        return true
    }

    private fun dequantHeatmap(raw: Byte): Float =
        ((raw.toInt() and 0xFF) - HEATMAP_ZERO_POINT) * HEATMAP_SCALE

    private fun dequantBbox(raw: Byte): Float =
        ((raw.toInt() and 0xFF) - BBOX_ZERO_POINT) * BBOX_SCALE

    /** Merges duplicates, including the same face seen in two overlapping tiles. */
    private fun nonMaxSuppression(boxes: List<Scored>): List<Scored> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val kept = mutableListOf<Scored>()
        for (candidate in sorted) {
            if (kept.none { iou(it.rect, candidate.rect) > NMS_IOU }) kept.add(candidate)
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interW = min(a.right, b.right) - max(a.left, b.left)
        val interH = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (interW <= 0 || interH <= 0) return 0f
        val inter = interW.toFloat() * interH
        val union = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { (delegate as? AutoCloseable)?.close() }
    }

    companion object {
        private const val TAG = "FaceDetLite"

        const val ASSET_PATH = "models/face_det_lite-tflite-w8a8/face_det_lite.tflite"

        private const val MODEL_W = 640
        private const val MODEL_H = 480
        private const val GRID_W = 80
        private const val GRID_H = 60
        private const val STRIDE = 8

        // From the model's own metadata.json — see assets/models/README.md.
        private const val HEATMAP_SCALE = 0.027428148314356804f
        private const val HEATMAP_ZERO_POINT = 191
        private const val BBOX_SCALE = 0.3230103850364685f
        private const val BBOX_ZERO_POINT = 9

        /** Matches the reference implementation's default. */
        /**
         * The cut every result up to v0.1.6 was produced with, expressed as a probability.
         *
         * The model's `heatmap` output is **not** a probability: dequantised it spans about
         * −5.24 … +1.76, i.e. a logit. The original constant was `0.55` **in those units**,
         * and `sigmoid(0.55) = 0.634`, so this is the same cut written in units an operator
         * can reason about. Because sigmoid is monotone, nothing about which faces pass has
         * changed.
         */
        val DEFAULT_SCORE_THRESHOLD: Float = sigmoid(LEGACY_LOGIT_THRESHOLD)

        /**
         * The constant as it was written from 0.1.3 to 0.1.6, in the model's own units.
         * Kept here so the default is *derived* from it — writing 0.634 by hand moved the
         * effective cut to 0.549416, which is not the same threshold, only one that looks
         * like it.
         */
        private const val LEGACY_LOGIT_THRESHOLD = 0.55f

        /**
         * The highest probability this model can report.
         *
         * `(255 - 191) * 0.027428 = 1.755` is the largest value the quantised heatmap can
         * hold, and `sigmoid(1.755) = 0.853`. A threshold above that rejects every frame —
         * which is why the UI stops well short of it.
         */
        const val MAX_REACHABLE_SCORE = 0.853f

        /** Probability back to the model's own units — for showing both side by side. */
        fun logitOf(p: Float): Float {
            val clamped = p.coerceIn(0.0001f, 0.9999f).toDouble()
            return kotlin.math.ln(clamped / (1.0 - clamped)).toFloat()
        }

        private fun sigmoid(x: Float): Float = (1.0 / (1.0 + kotlin.math.exp(-x.toDouble()))).toFloat()


        private const val NMS_IOU = 0.3f

        /** Fraction of a tile's height shared with the next, so seams do not split faces. */
        private const val TILE_OVERLAP = 0.2f

        /** Logs one decoded cell per detector, to sanity-check the bbox convention. */
        private const val DUMP_RAW_SAMPLE = true

        /**
         * @return null when the backend cannot run here — a missing model file, or QNN
         *   libraries absent from the build. The caller reports the reason and falls back
         *   rather than failing the session.
         */
        fun create(
            context: Context,
            backend: DetectorBackend,
            minScore: Float = DEFAULT_SCORE_THRESHOLD,
        ): FaceDetLiteDetector? {
            val model = runCatching { loadModel(context) }.getOrElse {
                Log.e(TAG, "Cannot read $ASSET_PATH", it)
                return null
            }

            var delegate: Delegate? = null
            var effective = backend
            when (backend) {
                DetectorBackend.LiteRtGpu -> {
                    delegate = runCatching {
                        // The model is fully uint8; without this the GPU delegate declines it.
                        GpuDelegate(GpuDelegate.Options().setQuantizedModelsAllowed(true))
                    }.getOrElse {
                        Log.w(TAG, "GPU delegate unavailable, falling back to CPU", it)
                        effective = DetectorBackend.LiteRtCpu
                        null
                    }
                }
                DetectorBackend.LiteRtNpu -> {
                    delegate = QnnDelegate.create(context, QnnDelegate.Backend.Htp, MODEL_TOKEN)
                    if (delegate == null) {
                        Log.w(
                            TAG,
                            "QNN unavailable (${QnnDelegate.unavailableReason(context)}); " +
                                "falling back to CPU",
                        )
                        effective = DetectorBackend.LiteRtCpu
                    }
                }
                else -> Unit
            }

            val options = Interpreter.Options().apply {
                delegate?.let { addDelegate(it) }
                if (delegate == null) setNumThreads(CPU_THREADS)
            }

            val interpreter = runCatching { Interpreter(model, options) }.getOrElse {
                Log.e(TAG, "Interpreter init failed for $backend", it)
                runCatching { (delegate as? AutoCloseable)?.close() }
                return null
            }

            // Output order is not guaranteed by name, but the shapes are unambiguous:
            // last dimension 1 = heatmap, 4 = bbox, 10 = landmark.
            var heatmapIndex = -1
            var bboxIndex = -1
            for (i in 0 until interpreter.outputTensorCount) {
                when (interpreter.getOutputTensor(i).shape().last()) {
                    1 -> heatmapIndex = i
                    4 -> bboxIndex = i
                }
            }
            if (heatmapIndex < 0 || bboxIndex < 0) {
                Log.e(TAG, "Unexpected model outputs; cannot identify heatmap/bbox tensors")
                runCatching { interpreter.close() }
                return null
            }

            Log.i(
                TAG,
                "face_det_lite ready: requested=${backend.slug} effective=${effective.slug} " +
                    "heatmapIdx=$heatmapIndex bboxIdx=$bboxIndex",
            )
            return FaceDetLiteDetector(
                interpreter, delegate, backend, effective, heatmapIndex, bboxIndex, minScore,
            )
        }

        /** Memory-mapped straight out of the APK — see `noCompress` in build.gradle.kts. */
        private fun loadModel(context: Context): ByteBuffer {
            context.assets.openFd(ASSET_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    return stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        fd.startOffset,
                        fd.declaredLength,
                    )
                }
            }
        }

        /** Identifies the compiled graph in QNN's on-disk cache; bump when the model changes. */
        private const val MODEL_TOKEN = "face_det_lite_w8a8_v1"
        private const val CPU_THREADS = 2
    }
}
