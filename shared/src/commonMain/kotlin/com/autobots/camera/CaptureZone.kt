package com.autobots.camera

import com.autobots.camera.detection.NormalizedFaceBox

/**
 * Composition sweet spot on the analysis / preview frame (Flow 17).
 * Grid matches the observation overlay (default 9×11). Detector finds the face;
 * this type decides whether the face is in the capture zone for Fire.
 *
 * Cell indices are 0-based inclusive ranges.
 */
data class CaptureZone(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val colStart: Int = DEFAULT_COL_START,
    val colEndInclusive: Int = DEFAULT_COL_END,
    val rowStart: Int = DEFAULT_ROW_START,
    val rowEndInclusive: Int = DEFAULT_ROW_END,
    val marginFraction: Float = DEFAULT_MARGIN,
) {
    init {
        require(columns > 0 && rows > 0)
        require(colStart in 0 until columns && colEndInclusive in colStart until columns)
        require(rowStart in 0 until rows && rowEndInclusive in rowStart until rows)
        require(marginFraction in 0f..0.4f)
    }

    companion object {
        const val DEFAULT_COLUMNS = 9
        const val DEFAULT_ROWS = 11
        /** Center-ish band — wide enough for stride bob on a short zone. */
        const val DEFAULT_COL_START = 2
        const val DEFAULT_COL_END = 6
        const val DEFAULT_ROW_START = 3
        const val DEFAULT_ROW_END = 7
        const val DEFAULT_MARGIN = 0.06f

        val DEFAULT: CaptureZone = CaptureZone()
    }
}

data class GridCell(val col: Int, val row: Int)

/**
 * Pure mapping helpers — no Android types.
 * Coordinates are normalized 0..1 in the same space as [NormalizedFaceBox].
 */
object CaptureZoneEvaluator {

    fun cellForPoint(x: Float, y: Float, zone: CaptureZone = CaptureZone.DEFAULT): GridCell? {
        if (x !in 0f..1f || y !in 0f..1f) return null
        val m = zone.marginFraction
        val innerW = 1f - 2f * m
        val innerH = 1f - 2f * m
        if (innerW <= 0f || innerH <= 0f) return null
        if (x < m || x > 1f - m || y < m || y > 1f - m) return null

        val col = ((x - m) / innerW * zone.columns).toInt().coerceIn(0, zone.columns - 1)
        val row = ((y - m) / innerH * zone.rows).toInt().coerceIn(0, zone.rows - 1)
        return GridCell(col, row)
    }

    fun isCenterInZone(box: NormalizedFaceBox, zone: CaptureZone = CaptureZone.DEFAULT): Boolean {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val cell = cellForPoint(cx, cy, zone) ?: return false
        return cell.col in zone.colStart..zone.colEndInclusive &&
            cell.row in zone.rowStart..zone.rowEndInclusive
    }
}
