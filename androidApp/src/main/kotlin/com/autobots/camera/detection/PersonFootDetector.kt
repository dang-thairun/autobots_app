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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One person, in the detect bitmap's own coordinate space. */
data class DetectedPerson(
    val bounds: Rect,
    val score: Float,
)

/**
 * Qualcomm AI Hub's `foot_track_net` (Person-Foot-Detection, w8a8) via LiteRT.
 *
 * ### Why a person detector at all
 *
 * Every gate the pipeline had through 0.1.6 hangs off a face, and a face is the first thing a
 * marathon loses: runners look down, wear caps and visors, and the races this is built for
 * start at 4 a.m. A person box survives all three. It cannot replace the face — a photo of the
 * back of someone's head does not sell — which is why [com.autobots.camera.ExtractionTarget]
 * runs them together rather than swapping one for the other.
 *
 * The second reason matters more than the first: this model reports **everyone in the frame**.
 * `face_det_lite` does too, but the pipeline immediately collapses that to the largest box, and
 * ML Kit's pose API returns exactly one person by construction. Per-runner tracking needs the
 * whole list, and this is the first detector in the app that can supply it.
 *
 * ### Why it letterboxes where `face_det_lite` tiles
 *
 * Same 640×480 input tensor, opposite geometry problem. [FaceDetLiteDetector] tiles because a
 * face is small and scaling the frame down would leave it ~17 px tall. A **person is not
 * small** — a runner worth photographing spans a quarter of the frame or more — and tiling a
 * 640×1138 detect bitmap into 4:3 strips would cut most of them across a seam, which is the one
 * thing a whole-body box must not be.
 *
 * So the frame is letterboxed whole: scaled by `min(640/w, 480/h)` and centred with black bars.
 * At the current detect bitmap that is 0.42×, taking a runner occupying 25% of frame height
 * from 284 px to 120 px in the tensor — ample for a model trained at this size.
 *
 * ### What is deliberately not read
 *
 * The graph also emits **face boxes** (class 0) and **17 landmarks with visibility**. Neither
 * is decoded here, for different reasons:
 *
 *  - *Faces*, because the letterbox that makes people the right size makes faces the wrong
 *    one: 0.42× puts a runner's face at ~14 px, against the ~34 px `face_det_lite` sees at
 *    native scale through its tiles. Reading this model's face head would be a worse face
 *    detector reached through a more expensive path. Face stays with `face_det_lite`.
 *  - *Landmarks*, because which of the 17 indices are the feet is not stated by the model card
 *    and the pipeline should not ship a guess at it. Feet visibility is the natural signal for
 *    "is the whole runner in shot" and is worth having; it needs the index mapping confirmed
 *    against real output first.
 */
class PersonFootDetector private constructor(
    private val interpreter: Interpreter,
    private val delegate: Delegate?,
    private val backend: DetectorBackend,
    private val effectiveBackend: DetectorBackend,
    private val heatmapIndex: Int,
    private val bboxIndex: Int,
    private val landmarkIndex: Int,
    private val visibilityIndex: Int,
    private val minScore: Float,
) : AutoCloseable {

    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(MODEL_W * MODEL_H * 3).order(ByteOrder.nativeOrder())
    private val heatmapBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * CLASSES).order(ByteOrder.nativeOrder())
    private val bboxBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val landmarkBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * LANDMARKS * 2).order(ByteOrder.nativeOrder())
    private val visibilityBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(GRID_W * GRID_H * LANDMARKS).order(ByteOrder.nativeOrder())

    private var pixelCache = IntArray(0)
    private var lastCount = 0

    @Volatile
    private var loggedRawSample = false

    val diagnostics: Map<String, Any>
        get() = mapOf(
            "requestedBackend" to backend.slug,
            "effectiveBackend" to effectiveBackend.slug,
            "minScore" to minScore,
            "lastCount" to lastCount,
        )

    /** Everyone the model found, largest score first after NMS. */
    fun detect(bitmap: Bitmap): List<DetectedPerson> {
        val box = LetterBox.of(bitmap.width, bitmap.height)

        if (pixelCache.size < bitmap.width * bitmap.height) {
            pixelCache = IntArray(bitmap.width * bitmap.height)
        }
        bitmap.getPixels(pixelCache, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        fillRgb(pixelCache, bitmap.width, bitmap.height, box)

        val outputs = mapOf(
            heatmapIndex to heatmapBuffer.also { it.rewind() },
            bboxIndex to bboxBuffer.also { it.rewind() },
            // Unused, but TFLite still requires a destination for every output the graph has.
            landmarkIndex to landmarkBuffer.also { it.rewind() },
            visibilityIndex to visibilityBuffer.also { it.rewind() },
        )
        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed", t)
            return emptyList()
        }

        val found = decode(box, bitmap.width, bitmap.height)
        val kept = nonMaxSuppression(found)
        lastCount = kept.size
        return kept
    }

    // ---------------------------------------------------------------- input

    /**
     * Aspect-preserving fit of the source into the tensor, with the leftover centred.
     *
     * [scale] and the two pads are all [decode] needs to undo it, so the mapping back to source
     * pixels is exact rather than approximate — the reference implementation carries the same
     * three numbers for the same reason.
     */
    private data class LetterBox(val scale: Float, val padX: Int, val padY: Int) {
        companion object {
            fun of(srcW: Int, srcH: Int): LetterBox {
                if (srcW <= 0 || srcH <= 0) return LetterBox(1f, 0, 0)
                val scale = min(MODEL_W.toFloat() / srcW, MODEL_H.toFloat() / srcH)
                val fitW = (srcW * scale).roundToInt()
                val fitH = (srcH * scale).roundToInt()
                return LetterBox(scale, (MODEL_W - fitW) / 2, (MODEL_H - fitH) / 2)
            }
        }
    }

    /**
     * Source → letterboxed RGB tensor.
     *
     * The input quantises at scale 1/255 with zero point 0, so a channel byte *is* the 0..255
     * sample and no rescaling is needed. Padding is written as black, which the model has seen
     * throughout training as the same letterbox convention.
     */
    private fun fillRgb(pixels: IntArray, srcW: Int, srcH: Int, box: LetterBox) {
        inputBuffer.rewind()
        for (ty in 0 until MODEL_H) {
            val sy = ((ty - box.padY) / box.scale).toInt()
            val inRow = sy in 0 until srcH
            val rowStart = if (inRow) sy * srcW else 0
            for (tx in 0 until MODEL_W) {
                val sx = ((tx - box.padX) / box.scale).toInt()
                if (!inRow || sx < 0 || sx >= srcW) {
                    inputBuffer.put(0).put(0).put(0)
                    continue
                }
                val p = pixels[rowStart + sx]
                inputBuffer.put(((p shr 16) and 0xFF).toByte())
                inputBuffer.put(((p shr 8) and 0xFF).toByte())
                inputBuffer.put((p and 0xFF).toByte())
            }
        }
        inputBuffer.rewind()
    }

    // ---------------------------------------------------------------- postprocess

    /**
     * Anchor-free centre decode at stride 4, person class only.
     *
     * `heatmap[gy][gx][PERSON_CLASS]` is already a sigmoid — unlike `face_det_lite`, whose
     * equivalent output is a raw logit — so the value read here is a probability and needs no
     * conversion. `bbox` holds that cell's distance to each of the four box edges in cells,
     * laid out as four channels per class.
     *
     * The reference postprocess takes the top 1000 cells and leans entirely on NMS to collapse
     * the cluster a single person lights up. This uses the 3×3 local-max test
     * [FaceDetLiteDetector] already uses instead: the same cheap stand-in for a max-pool, one
     * fewer full-grid sort, and NMS still runs behind it to merge what survives.
     */
    private fun decode(box: LetterBox, srcW: Int, srcH: Int): List<DetectedPerson> {
        val found = mutableListOf<DetectedPerson>()
        val hm = heatmapBuffer
        val bb = bboxBuffer

        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val score = dequantHeatmap(hm.get((gy * GRID_W + gx) * CLASSES + PERSON_CLASS))
                if (score < minScore) continue
                if (!isLocalMax(hm, gx, gy, score)) continue

                val base = ((gy * GRID_W + gx) * CLASSES + PERSON_CLASS) * 4
                val left = dequantBbox(bb.get(base))
                val top = dequantBbox(bb.get(base + 1))
                val right = dequantBbox(bb.get(base + 2))
                val bottom = dequantBbox(bb.get(base + 3))

                // Cells → tensor pixels → undo the letterbox → source pixels.
                val x1 = ((gx - left) * STRIDE - box.padX) / box.scale
                val y1 = ((gy - top) * STRIDE - box.padY) / box.scale
                val x2 = ((gx + right) * STRIDE - box.padX) / box.scale
                val y2 = ((gy + bottom) * STRIDE - box.padY) / box.scale
                if (x2 <= x1 || y2 <= y1) continue

                if (DUMP_RAW_SAMPLE && !loggedRawSample) {
                    loggedRawSample = true
                    Log.i(
                        TAG,
                        "raw decode sample: cell=($gx,$gy) score=$score " +
                            "ltrb=($left,$top,$right,$bottom) → src " +
                            "(${x1.roundToInt()},${y1.roundToInt()})-" +
                            "(${x2.roundToInt()},${y2.roundToInt()}) of ${srcW}x$srcH",
                    )
                }

                found.add(
                    DetectedPerson(
                        Rect(
                            x1.roundToInt().coerceIn(0, srcW),
                            y1.roundToInt().coerceIn(0, srcH),
                            x2.roundToInt().coerceIn(0, srcW),
                            y2.roundToInt().coerceIn(0, srcH),
                        ),
                        score,
                    ),
                )
            }
        }
        return found
    }

    private fun isLocalMax(hm: ByteBuffer, gx: Int, gy: Int, score: Float): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = gx + dx
                val ny = gy + dy
                if (nx < 0 || ny < 0 || nx >= GRID_W || ny >= GRID_H) continue
                val n = dequantHeatmap(hm.get((ny * GRID_W + nx) * CLASSES + PERSON_CLASS))
                if (n > score) return false
            }
        }
        return true
    }

    private fun dequantHeatmap(raw: Byte): Float =
        ((raw.toInt() and 0xFF) - HEATMAP_ZERO_POINT) * HEATMAP_SCALE

    private fun dequantBbox(raw: Byte): Float =
        ((raw.toInt() and 0xFF) - BBOX_ZERO_POINT) * BBOX_SCALE

    private fun nonMaxSuppression(boxes: List<DetectedPerson>): List<DetectedPerson> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val kept = mutableListOf<DetectedPerson>()
        for (candidate in sorted) {
            if (kept.none { iou(it.bounds, candidate.bounds) > NMS_IOU }) kept.add(candidate)
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
        private const val TAG = "PersonFoot"

        const val ASSET_PATH = "models/foot_track_net-tflite-w8a8/foot_track_net.tflite"

        private const val MODEL_W = 640
        private const val MODEL_H = 480

        /** Stride 4, against `face_det_lite`'s 8 — four times the cells for the same input. */
        private const val STRIDE = 4
        private const val GRID_W = MODEL_W / STRIDE
        private const val GRID_H = MODEL_H / STRIDE

        /**
         * The heatmap has three channels and `labels.txt` names two.
         *
         * The reference postprocess resolves it by slicing the third away (`hm = hm[:, :2]`)
         * before it looks at anything, so channel 2 is a head the shipped pipeline does not
         * use. Class 0 is face, class 1 is person; the bbox tensor is the same three classes
         * at four channels each, which is where its 12 comes from.
         */
        private const val CLASSES = 3
        private const val PERSON_CLASS = 1
        private const val LANDMARKS = 17

        // From the model's own metadata.json.
        private const val HEATMAP_SCALE = 0.00390625f
        private const val HEATMAP_ZERO_POINT = 0
        private const val BBOX_SCALE = 0.4654639661312103f
        private const val BBOX_ZERO_POINT = 83

        /**
         * The reference demo's person threshold.
         *
         * Unlike `face_det_lite`'s, this one needs no conversion: the head is sigmoid-activated
         * inside the graph and quantises at 1/256 from zero, so the tensor already holds a
         * probability and 0.7 means 0.7.
         */
        const val DEFAULT_SCORE_THRESHOLD = 0.7f

        /** The reference demo's person IOU. Faces use 0.2 there; people overlap more. */
        private const val NMS_IOU = 0.5f

        private const val DUMP_RAW_SAMPLE = true
        private const val CPU_THREADS = 2

        /** Identifies the compiled graph in QNN's on-disk cache; bump when the model changes. */
        private const val MODEL_TOKEN = "foot_track_net_w8a8_v1"

        /**
         * @return null when the model cannot run here. The caller reports it and carries on
         *   without the person gate rather than failing the session.
         */
        fun create(
            context: Context,
            backend: DetectorBackend,
            minScore: Float = DEFAULT_SCORE_THRESHOLD,
        ): PersonFootDetector? {
            val model = runCatching { loadModel(context) }.getOrElse {
                Log.e(TAG, "Cannot read $ASSET_PATH", it)
                return null
            }

            var delegate: Delegate? = null
            var effective = backend
            when (backend) {
                DetectorBackend.LiteRtGpu -> {
                    delegate = runCatching {
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
                else -> {
                    // ML Kit has no person model, so a person gate on an ML Kit session still
                    // runs this graph — on the CPU, which is the honest cost of asking for it.
                    effective = DetectorBackend.LiteRtCpu
                }
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

            // Output order is not guaranteed by name; the last dimensions are unambiguous.
            var heatmapIndex = -1
            var bboxIndex = -1
            var landmarkIndex = -1
            var visibilityIndex = -1
            for (i in 0 until interpreter.outputTensorCount) {
                when (interpreter.getOutputTensor(i).shape().last()) {
                    CLASSES -> heatmapIndex = i
                    CLASSES * 4 -> bboxIndex = i
                    LANDMARKS * 2 -> landmarkIndex = i
                    LANDMARKS -> visibilityIndex = i
                }
            }
            if (heatmapIndex < 0 || bboxIndex < 0 || landmarkIndex < 0 || visibilityIndex < 0) {
                Log.e(TAG, "Unexpected model outputs; cannot identify tensors")
                runCatching { interpreter.close() }
                return null
            }

            Log.i(
                TAG,
                "foot_track_net ready: requested=${backend.slug} effective=${effective.slug} " +
                    "hm=$heatmapIndex bbox=$bboxIndex lmk=$landmarkIndex vis=$visibilityIndex",
            )
            return PersonFootDetector(
                interpreter, delegate, backend, effective,
                heatmapIndex, bboxIndex, landmarkIndex, visibilityIndex, minScore,
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
    }
}
