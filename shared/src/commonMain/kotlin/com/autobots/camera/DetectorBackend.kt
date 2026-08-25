package com.autobots.camera

/**
 * Which detector Worker 2 runs, and on what hardware.
 *
 * Introduced in 0.1.4 to settle a question measurement could not: `no_subject` accounted for
 * 64% of every sampled frame, and watching the footage showed people were in fact present.
 * That points at the detector rather than the pipeline — but "ML Kit is missing people" is a
 * claim, not a number, so the backend became a run-time choice. Import the same clip once per
 * entry and the resulting `perf_report.json` files differ in exactly one variable.
 *
 * The `face_det_lite` entries share one model file; only the delegate changes. Its input is
 * **640×480 grayscale**, which is why tiling exists — see `assets/models/README.md`.
 */
enum class DetectorBackend(val label: String) {
    /** ML Kit, `PERFORMANCE_MODE_FAST`. The 0.1.3 baseline every number is compared against. */
    MlKitFast("ML Kit FAST"),

    /**
     * ML Kit, `PERFORMANCE_MODE_ACCURATE`. The code has supported this since 0.1.2 and it has
     * never once been run — with detect workers idle 46.5% of wall in 0.1.4, it is free.
     */
    MlKitAccurate("ML Kit ACCURATE"),

    /** `face_det_lite` on CPU via XNNPACK. The portable floor; needs nothing extra shipped. */
    LiteRtCpu("face_det_lite CPU"),

    /**
     * `face_det_lite` on the GPU via TFLite's OpenCL delegate. `libOpenCL_adreno.so` already
     * ships on the device, so this costs no APK size at all — which is what makes it the
     * result that decides whether the NPU is worth its ~96 MB.
     */
    LiteRtGpu("face_det_lite GPU"),

    /**
     * `face_det_lite` on the Hexagon NPU through the QNN HTP backend. Reports itself
     * unavailable when the QNN libraries are absent from the build rather than failing.
     */
    LiteRtNpu("face_det_lite NPU"),

    /**
     * Runs every available detector over the same frames, writing `detector_compare.json`.
     *
     * [MlKitFast] still decides what the session keeps, so the pipeline's own counts stay
     * comparable with earlier releases; the rest is observation. Answers the two questions
     * that per-backend runs cannot — whether `face_det_lite`'s box decode is right, and
     * whether anything sees the runners ML Kit misses — because both need the detectors
     * looking at *the same frame*, not the same file.
     *
     * `realtimeRatio` is meaningless in this mode: the chunk does several times the work.
     */
    CompareAll("Compare all"),
    ;

    /**
     * Whether the operator can pick this from the detector row.
     *
     * `MlKitAccurate` and `LiteRtCpu` stay in the enum but off the picker: both were measured
     * and neither earns a slot. ACCURATE costs 30% more time for five genuinely new frames —
     * its apparent recall edge turned out to be a box-size artefact at the size gate. CPU is
     * *slower than ML Kit* on real frames (107 ms vs 95 ms) once the pipeline is under load.
     *
     * They still run in [CompareAll], and ML Kit FAST remains the fallback when a LiteRT
     * backend cannot start — see `VideoFrameProcessor.DetectorSet`.
     */
    val selectableInUi: Boolean
        get() = this == MlKitFast || this == LiteRtGpu || this == LiteRtNpu || this == CompareAll

    /** True for entries that load `face_det_lite`; they differ only by delegate. */
    val usesLiteRt: Boolean
        get() = this == LiteRtCpu || this == LiteRtGpu || this == LiteRtNpu

    /** Short token for file names and `perf_report.json`. */
    val slug: String
        get() = when (this) {
            MlKitFast -> "mlkit_fast"
            MlKitAccurate -> "mlkit_accurate"
            LiteRtCpu -> "litert_cpu"
            LiteRtGpu -> "litert_gpu"
            LiteRtNpu -> "litert_npu"
            CompareAll -> "compare_all"
        }

    /** Short hardware tag for status cards (`ML Kit` / `GPU` / `NPU`). */
    val hardwareLabel: String
        get() = when (this) {
            MlKitFast, MlKitAccurate -> "ML Kit"
            LiteRtCpu -> "CPU"
            LiteRtGpu -> "GPU"
            LiteRtNpu -> "NPU"
            CompareAll -> "Compare all"
        }

    /** The model file or SDK detector this backend actually loads for Face. */
    val modelName: String
        get() = when (this) {
            MlKitFast -> "Face Detection FAST"
            MlKitAccurate -> "Face Detection ACCURATE"
            LiteRtCpu, LiteRtGpu, LiteRtNpu -> "face_det_lite.tflite"
            CompareAll -> "all backends"
        }

    /**
     * Short model tag for the picker chips — what this backend actually loads.
     *
     * Pose ignores the backend entirely: `OfflinePoseDetector` runs ML Kit's pose model
     * whichever chip is selected, so saying `face_det_lite` under it would be a lie.
     */
    fun modelTag(target: ExtractionTarget): String = when (target) {
        ExtractionTarget.Pose, ExtractionTarget.FaceAndPose -> "pose_detection"
        ExtractionTarget.Face -> when (this) {
            MlKitFast -> "FAST"
            MlKitAccurate -> "ACCURATE"
            LiteRtCpu, LiteRtGpu, LiteRtNpu -> "face_det_lite"
            CompareAll -> "all models"
        }
    }

    companion object {
        val DEFAULT = LiteRtNpu

        /** NPU first, then GPU, then ML Kit — skip anything this device cannot start. */
        fun firstAvailable(unavailable: Map<DetectorBackend, *>): DetectorBackend =
            listOf(LiteRtNpu, LiteRtGpu, MlKitFast)
                .firstOrNull { it !in unavailable }
                ?: MlKitFast
    }
}
