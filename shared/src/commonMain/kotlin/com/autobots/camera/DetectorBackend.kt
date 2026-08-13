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
    ;

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
        }

    companion object {
        val DEFAULT = MlKitFast
    }
}
