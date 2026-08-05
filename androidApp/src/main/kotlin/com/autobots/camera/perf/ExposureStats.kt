package com.autobots.camera.perf

import java.util.Locale

/**
 * Rolling stats over what the sensor *actually chose*, sampled from every
 * `CaptureResult` on the repeating request while preview/recording is live.
 *
 * The number that matters is [overBudgetPercent]: the share of recorded frames
 * whose exposure exceeded the motion-blur budget. Those frames are blurred at
 * capture time and no amount of offline scoring can recover them.
 */
class ExposureStats(private val blurBudgetNs: Long = BLUR_BUDGET_NS) {
    private var samples = 0
    private var overBudget = 0
    private var minExposureNs = Long.MAX_VALUE
    private var maxExposureNs = 0L
    private var sumExposureNs = 0L
    private var minIso = Int.MAX_VALUE
    private var maxIso = 0
    private var sumIso = 0L
    private var lastFocusDistance: Float? = null
    private val afStates = HashMap<Int, Int>()
    private val aeStates = HashMap<Int, Int>()

    /** @return running sample count, so callers can dump a summary every N frames. */
    @Synchronized
    fun add(
        exposureNs: Long,
        iso: Int,
        aeState: Int?,
        afState: Int?,
        focusDistance: Float?,
    ): Int {
        samples++
        if (exposureNs > blurBudgetNs) overBudget++
        if (exposureNs < minExposureNs) minExposureNs = exposureNs
        if (exposureNs > maxExposureNs) maxExposureNs = exposureNs
        sumExposureNs += exposureNs
        if (iso < minIso) minIso = iso
        if (iso > maxIso) maxIso = iso
        sumIso += iso
        focusDistance?.let { lastFocusDistance = it }
        afState?.let { afStates[it] = (afStates[it] ?: 0) + 1 }
        aeState?.let { aeStates[it] = (aeStates[it] ?: 0) + 1 }
        return samples
    }

    @Synchronized
    fun sampleCount(): Int = samples

    @Synchronized
    fun overBudgetPercent(): Int = if (samples == 0) 0 else overBudget * 100 / samples

    @Synchronized
    fun summary(title: String): String {
        if (samples == 0) return "┌─ $title\n└ no CaptureResult samples yet"
        val avgExposureNs = sumExposureNs / samples
        return buildString {
            append("┌─ ").append(title).append('\n')
            append(
                String.format(
                    Locale.US,
                    "│ exposure   avg %s   min %s   max %s",
                    CamPerf.shutter(avgExposureNs),
                    CamPerf.shutter(minExposureNs),
                    CamPerf.shutter(maxExposureNs),
                ),
            ).append('\n')
            append(
                String.format(
                    Locale.US,
                    "│            avg %,dns (budget %,dns)",
                    avgExposureNs,
                    blurBudgetNs,
                ),
            ).append('\n')
            append(
                String.format(
                    Locale.US,
                    "│ OVER BUDGET %d%% of %d frames  <-- these are blurred at capture",
                    overBudgetPercent(),
                    samples,
                ),
            ).append('\n')
            append(
                String.format(
                    Locale.US,
                    "│ iso        avg %d   min %d   max %d",
                    (sumIso / samples).toInt(),
                    minIso,
                    maxIso,
                ),
            ).append('\n')
            append("│ af         ").append(histogram(afStates, CamPerf::afStateLabel)).append('\n')
            append("│ ae         ").append(histogram(aeStates, CamPerf::aeStateLabel)).append('\n')
            append(
                String.format(
                    Locale.US,
                    "└ focus      %s",
                    lastFocusDistance?.let {
                        if (it <= 0f) {
                            "0.0 diopters (infinity / fixed)"
                        } else {
                            String.format(Locale.US, "%.2f diopters (%.2f m)", it, 1f / it)
                        }
                    } ?: "not reported",
                ),
            )
        }
    }

    private fun histogram(states: Map<Int, Int>, label: (Int?) -> String): String {
        if (states.isEmpty()) return "not reported"
        val total = states.values.sum()
        return states.entries
            .sortedByDescending { it.value }
            .joinToString("  ") { (state, count) -> "${label(state)} ${count * 100 / total}%" }
    }

    companion object {
        /** 2 ms (1/500s) — the Phase 1 target from the sports-camera brief. */
        const val BLUR_BUDGET_NS = 2_000_000L
    }
}
