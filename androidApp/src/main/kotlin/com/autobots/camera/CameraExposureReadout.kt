package com.autobots.camera

/**
 * Live capture metadata from Camera2 [CaptureResult] (display-only).
 */
data class CameraExposureReadout(
    val focalLengthMm: Float? = null,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
) {
    val line: String
        get() {
            val focal = focalLengthMm?.let { String.format("%.1fmm", it) } ?: "—mm"
            val shutter = formatShutter(exposureTimeNs)
            val isoLabel = iso?.let { "ISO $it" } ?: "ISO —"
            return "$focal  ·  $shutter  ·  $isoLabel"
        }

    companion object {
        fun formatShutter(exposureTimeNs: Long?): String {
            val ns = exposureTimeNs ?: return "—"
            if (ns <= 0L) return "—"
            val sec = ns / 1_000_000_000.0
            return if (sec >= 1.0) {
                String.format("%.1fs", sec)
            } else {
                val denom = (1.0 / sec).toInt().coerceAtLeast(1)
                "1/$denom"
            }
        }
    }
}
