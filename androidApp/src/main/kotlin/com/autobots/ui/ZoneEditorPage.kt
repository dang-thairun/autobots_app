package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.autobots.camera.DetectZone
import androidx.compose.foundation.Canvas

/** Visual size of a corner grip. Its touch target is [HandleTouchDp], deliberately larger. */
private val HandleDp = 18.dp

/** Finger-sized hit radius. A grip drawn big enough to grab would swamp the frame. */
private val HandleTouchDp = 48.dp

private val ZoneStroke = Color(0xFF80CBC4)
private val Dimmed = Color.Black.copy(alpha = 0.55f)

private enum class DragMode { TopLeft, BottomRight, Move, None }

/**
 * Draw the Capture Zone over whatever the pipeline is about to look at.
 *
 * The backdrop is supplied by the caller — a frame lifted from an imported clip, or the live
 * camera — because the whole value of the page is drawing the zone against real pixels rather
 * than against an empty rectangle.
 *
 * Two grips — top-left and bottom-right — plus drag-anywhere-inside to move. OK commits,
 * back discards: a drag that went wrong has to be undoable without leaving the zone in a
 * state the operator did not choose.
 *
 * [frameWidth]/[frameHeight] must be **post-rotation**: a portrait 4K clip is 2160×3840 to
 * the pipeline, and a zone drawn against the raw 3840×2160 would land somewhere else.
 */
@Composable
fun ZoneEditorPage(
    initialZone: DetectZone?,
    frameWidth: Int,
    frameHeight: Int,
    onConfirm: (DetectZone) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: @Composable () -> Unit = {},
) {
    var zone by remember(initialZone) {
        mutableStateOf(initialZone ?: DetectZone.DEFAULT)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←  Detect zone",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(vertical = 4.dp),
            )
            Text(
                text = "Reset",
                color = Color(0xFF80CBC4),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { zone = DetectZone.DEFAULT }
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameWidth.toFloat() / frameHeight.toFloat()),
            ) {
                val density = LocalDensity.current
                val touchPx = with(density) { HandleTouchDp.toPx() }
                val handlePx = with(density) { HandleDp.toPx() }
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2B2B2B)),
                ) {
                    backdrop()
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(widthPx, heightPx) {
                            var mode = DragMode.None
                            detectDragGestures(
                                onDragStart = { start ->
                                    val left = zone.left * widthPx
                                    val top = zone.top * heightPx
                                    val right = zone.right * widthPx
                                    val bottom = zone.bottom * heightPx
                                    mode = when {
                                        near(start, Offset(left, top), touchPx) -> DragMode.TopLeft
                                        near(start, Offset(right, bottom), touchPx) -> DragMode.BottomRight
                                        start.x in left..right && start.y in top..bottom -> DragMode.Move
                                        else -> DragMode.None
                                    }
                                },
                                onDragEnd = { mode = DragMode.None },
                                onDragCancel = { mode = DragMode.None },
                            ) { change, delta ->
                                change.consume()
                                val dx = delta.x / widthPx
                                val dy = delta.y / heightPx
                                zone = when (mode) {
                                    DragMode.TopLeft -> DetectZone.of(
                                        zone.left + dx,
                                        zone.top + dy,
                                        zone.right,
                                        zone.bottom,
                                    )
                                    DragMode.BottomRight -> DetectZone.of(
                                        zone.left,
                                        zone.top,
                                        zone.right + dx,
                                        zone.bottom + dy,
                                    )
                                    // Move keeps the size: clamp the offset, not the edges,
                                    // or dragging into a wall would shrink the zone instead.
                                    DragMode.Move -> {
                                        val shiftX = dx.coerceIn(-zone.left, 1f - zone.right)
                                        val shiftY = dy.coerceIn(-zone.top, 1f - zone.bottom)
                                        DetectZone(
                                            zone.left + shiftX,
                                            zone.top + shiftY,
                                            zone.right + shiftX,
                                            zone.bottom + shiftY,
                                        )
                                    }
                                    DragMode.None -> zone
                                }
                            }
                        },
                ) {
                    val left = zone.left * size.width
                    val top = zone.top * size.height
                    val right = zone.right * size.width
                    val bottom = zone.bottom * size.height

                    // Dim everything the detectors will ignore.
                    drawRect(Dimmed, size = Size(size.width, top))
                    drawRect(Dimmed, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                    drawRect(Dimmed, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                    drawRect(Dimmed, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                    drawRect(
                        color = ZoneStroke,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 3f),
                    )
                    drawRect(ZoneStroke, Offset(left, top), Size(handlePx, handlePx))
                    drawRect(
                        ZoneStroke,
                        Offset(right - handlePx, bottom - handlePx),
                        Size(handlePx, handlePx),
                    )
                }
            }
        }

        Text(
            text = "X ${zone.xIn(frameWidth)}   Y ${zone.yIn(frameHeight)}   " +
                "W ${zone.widthIn(frameWidth)}   H ${zone.heightIn(frameHeight)}",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Button(
            onClick = { onConfirm(zone) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("OK")
        }
    }
}

private fun near(point: Offset, target: Offset, radius: Float): Boolean =
    (point - target).getDistance() <= radius
