package com.autobots.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.autobots.camera.detection.NormalizedFaceBox

/**
 * Observation AF grid (visual, not hardware PDAF).
 * Default 9×11 = 99 points — highlight cells near the Subject Face when armed.
 */
@Composable
fun AfGridOverlay(
    subject: NormalizedFaceBox?,
    isArmed: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = AfGridDefaults.COLUMNS,
    rows: Int = AfGridDefaults.ROWS,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val marginX = size.width * AfGridDefaults.MARGIN_FRACTION
        val marginY = size.height * AfGridDefaults.MARGIN_FRACTION
        val gridW = size.width - marginX * 2f
        val gridH = size.height - marginY * 2f
        val cellW = gridW / columns
        val cellH = gridH / rows
        val boxPad = minOf(cellW, cellH) * 0.18f
        val pointSize = minOf(cellW, cellH) * 0.42f

        val subjectCx = subject?.let { (it.left + it.right) / 2f }
        val subjectCy = subject?.let { (it.top + it.bottom) / 2f }

        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val cx = marginX + (col + 0.5f) * cellW
                val cy = marginY + (row + 0.5f) * cellH
                val nx = cx / size.width
                val ny = cy / size.height

                val nearSubject = subject != null &&
                    nx >= subject.left &&
                    nx <= subject.right &&
                    ny >= subject.top &&
                    ny <= subject.bottom

                val nearestCenter = if (
                    !nearSubject &&
                    isArmed &&
                    subjectCx != null &&
                    subjectCy != null
                ) {
                    // Light up the single closest cell when armed but grid point isn't inside box
                    val dCol = ((subjectCx * size.width - marginX) / cellW).toInt().coerceIn(0, columns - 1)
                    val dRow = ((subjectCy * size.height - marginY) / cellH).toInt().coerceIn(0, rows - 1)
                    col == dCol && row == dRow
                } else {
                    false
                }

                val active = isArmed && (nearSubject || nearestCenter)
                val color = when {
                    active -> AfGridDefaults.Active
                    else -> AfGridDefaults.Idle
                }
                val stroke = if (active) 2.5f else 1.2f

                drawRect(
                    color = color,
                    topLeft = Offset(cx - pointSize / 2f, cy - pointSize / 2f),
                    size = Size(pointSize, pointSize),
                    style = Stroke(width = stroke),
                )

                if (active) {
                    // Inner tick — stronger “selected AF point” look
                    val inner = pointSize * 0.45f
                    drawRect(
                        color = color,
                        topLeft = Offset(cx - inner / 2f, cy - inner / 2f),
                        size = Size(inner, inner),
                        style = Stroke(width = 1.5f),
                    )
                } else {
                    // Keep a slight inset so empty cells read as AF brackets
                    drawRect(
                        color = color.copy(alpha = 0.35f),
                        topLeft = Offset(cx - pointSize / 2f + boxPad, cy - pointSize / 2f + boxPad),
                        size = Size(pointSize - boxPad * 2f, pointSize - boxPad * 2f),
                        style = Stroke(width = 1f),
                    )
                }
            }
        }
    }
}

object AfGridDefaults {
    const val COLUMNS = 9
    const val ROWS = 11
    const val MARGIN_FRACTION = 0.06f
    val Idle = Color(0xFF69F0AE).copy(alpha = 0.45f)
    val Active = Color(0xFF69F0AE)
}
