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

@Composable
fun FaceOverlay(
    faces: List<NormalizedFaceBox>,
    subjectIndex: Int?,
    modifier: Modifier = Modifier,
) {
    if (faces.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        faces.forEachIndexed { index, face ->
            val isSubject = index == subjectIndex
            val color = if (isSubject) Color(0xFF69F0AE) else Color(0xFFFFF176)
            val stroke = if (isSubject) 4f else 2f
            drawRect(
                color = color,
                topLeft = Offset(face.left * size.width, face.top * size.height),
                size = Size(face.width * size.width, face.height * size.height),
                style = Stroke(width = stroke),
            )
        }
    }
}
