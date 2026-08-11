package com.autobots.camera.perf

import java.util.Locale

/**
 * Wall-clock accumulator per named stage, rendered as one aligned Logcat table.
 * Stages keep insertion order so the table reads like the pipeline it measures.
 */
class StageStats {
    private val order = ArrayList<String>()
    private val counts = HashMap<String, Int>()
    private val totalNs = HashMap<String, Long>()
    private val maxNs = HashMap<String, Long>()

    @Synchronized
    fun add(stage: String, elapsedNs: Long) {
        val previous = counts[stage]
        if (previous == null) {
            order.add(stage)
            counts[stage] = 1
            totalNs[stage] = elapsedNs
            maxNs[stage] = elapsedNs
            return
        }
        counts[stage] = previous + 1
        totalNs[stage] = totalNs.getValue(stage) + elapsedNs
        if (elapsedNs > maxNs.getValue(stage)) maxNs[stage] = elapsedNs
    }

    @Synchronized
    fun isEmpty(): Boolean = order.isEmpty()

    /** Total wall time across every stage, in ms. */
    @Synchronized
    fun totalMs(): Long = order.sumOf { totalNs.getValue(it) } / 1_000_000L

    /** One row per stage, in pipeline order — the machine-readable form of [table]. */
    @Synchronized
    fun snapshot(): List<StageRow> = order.map { stage ->
        StageRow(
            stage = stage,
            n = counts.getValue(stage),
            totalNs = totalNs.getValue(stage),
            maxNs = maxNs.getValue(stage),
        )
    }

    data class StageRow(
        val stage: String,
        val n: Int,
        val totalNs: Long,
        val maxNs: Long,
    ) {
        val avgNs: Long get() = if (n == 0) 0L else totalNs / n
    }

    @Synchronized
    fun table(title: String): String = buildString {
        append("┌─ ").append(title).append('\n')
        append(row("stage", "n", "avg", "max", "total")).append('\n')
        var grandTotalNs = 0L
        for (stage in order) {
            val n = counts.getValue(stage)
            val total = totalNs.getValue(stage)
            grandTotalNs += total
            append(
                row(
                    stage,
                    n.toString(),
                    CamPerf.ms(total / n),
                    CamPerf.ms(maxNs.getValue(stage)),
                    CamPerf.ms(total),
                ),
            ).append('\n')
        }
        append(
            String.format(
                Locale.US,
                "└ %-18s %5s %10s %10s %10s",
                "TOTAL",
                "",
                "",
                "",
                CamPerf.ms(grandTotalNs),
            ),
        )
    }

    private fun row(stage: String, n: String, avg: String, max: String, total: String): String =
        String.format(Locale.US, "│ %-18s %5s %10s %10s %10s", stage, n, avg, max, total)
}
