package com.autobots.camera

/**
 * One candidate photo as the picker sees it — when it was taken, whose it is, how good it is.
 *
 * Deliberately not the pipeline's own candidate type: that one carries a `File`, and the whole
 * point of [pickKeepers] is that the decision depends on nothing that lives on disk.
 */
data class PickCandidate(
    val timestampUs: Long,
    val trackId: Int,
    val quality: Float,
)

/**
 * Which candidates to keep: the [maxPerWindow] best of every [windowUs] window **of one
 * person's frames**.
 *
 * ### Why a window per person and not per second of clock
 *
 * Through 0.1.6 the window was a second *of the clock*, which quietly assumed a second of
 * footage is one runner. Two runners going past together shared one budget of three, so all
 * three keepers could be the nearer of them and the other was never photographed — while every
 * counter still reported a healthy yield.
 *
 * ### Why this is a free function and not a method
 *
 * It is the only part of selection that decides anything, and it needs nothing but these three
 * numbers per candidate. Pure means it can be checked without a video, a device or a decoder,
 * and it can be re-run later over rows read back from `sightings.csv` to answer "what would a
 * different threshold have picked" — the reason Worker 2 exists at all.
 *
 * Candidates sharing a [PickCandidate.trackId] are one person. The id the tracker uses for "no
 * id" is just another group, windowed by clock like everybody else.
 *
 * @return the timestamps to keep. Every other candidate lost its window and its file may go.
 */
fun pickKeepers(
    candidates: List<PickCandidate>,
    windowUs: Long,
    maxPerWindow: Int,
): Set<Long> {
    val keepers = HashSet<Long>()
    for ((_, frames) in candidates.sortedBy { it.timestampUs }.groupBy { it.trackId }) {
        var windowStartUs = -1L
        val window = mutableListOf<PickCandidate>()

        fun closeWindow() {
            if (window.isEmpty()) return
            // Stable sort, so equal quality keeps capture order and the result is reproducible.
            window.sortedByDescending { it.quality }
                .take(maxPerWindow)
                .forEach { keepers.add(it.timestampUs) }
            window.clear()
        }

        for (entry in frames) {
            if (windowStartUs < 0 || entry.timestampUs - windowStartUs >= windowUs) {
                closeWindow()
                windowStartUs = entry.timestampUs
            }
            window.add(entry)
        }
        closeWindow()
    }
    return keepers
}
