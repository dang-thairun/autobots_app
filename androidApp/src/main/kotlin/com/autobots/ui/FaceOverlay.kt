package com.autobots.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobots.camera.detection.NormalizedFaceBox

@Composable
fun FaceOverlay(
    faces: List<NormalizedFaceBox>,
    subjectIndex: Int?,
    modifier: Modifier = Modifier,
) {
    if (faces.isEmpty()) return

    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val padPx = with(density) { 3.dp.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        faces.forEachIndexed { index, face ->
            val isSubject = index == subjectIndex
            val boxColor = if (isSubject) Color(0xFF69F0AE) else Color(0xFFFFF176)
            val stroke = if (isSubject) 4f else 2f
            val left = face.left * size.width
            val top = face.top * size.height
            val boxW = face.width * size.width
            val boxH = face.height * size.height

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(width = stroke),
            )

            val scoreLabel = faceScoreLabel(face)
            drawIntoCanvas { canvas ->
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    setColor(android.graphics.Color.WHITE)
                    textSize = textSizePx
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                val textW = textPaint.measureText(scoreLabel)
                val labelH = textSizePx + padPx * 2f
                val labelW = textW + padPx * 2f
                val bgPaint = Paint().apply {
                    setColor(boxColor.copy(alpha = 0.85f).toArgb())
                }
                canvas.nativeCanvas.drawRect(left, top, left + labelW, top + labelH, bgPaint)
                canvas.nativeCanvas.drawText(
                    scoreLabel,
                    left + padPx,
                    top + labelH - padPx - textPaint.descent() / 2f,
                    textPaint,
                )
            }
        }
    }
}

/** Proximity score for this face (% of frame area). ML Kit has no box confidence in our config. */
private fun faceScoreLabel(face: NormalizedFaceBox): String {
    val pct = (face.area * 100f).toInt().coerceIn(0, 99)
    return "$pct%"
}
