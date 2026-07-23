package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobots.camera.ChunkProcessStatus
import com.autobots.camera.ChunkRecord
import com.autobots.camera.formatChunkBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HistoryCardBg = Color.Gray.copy(alpha = 0.25f)
private val HistoryCardShape = RoundedCornerShape(10.dp)

@Composable
fun ChunkHistoryPage(
    chunks: List<ChunkRecord>,
    modifier: Modifier = Modifier,
) {
    val expandedMap = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Chunk history",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (chunks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HistoryCardShape)
                    .background(HistoryCardBg)
                    .padding(12.dp),
            ) {
                Text(
                    text = "No chunks yet",
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chunks, key = { it.index }) { chunk ->
                    ChunkRecordCard(
                        chunk = chunk,
                        expanded = expandedMap[chunk.index] == true,
                        onToggleExpand = {
                            val current = expandedMap[chunk.index] == true
                            expandedMap[chunk.index] = !current
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChunkRecordCard(
    chunk: ChunkRecord,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val timeLabel = remember(chunk.recordedAtEpochMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(chunk.recordedAtEpochMs))
    }
    val videoSizeLabel = buildString {
        append(formatChunkBytes(chunk.videoSizeBytes))
        if (chunk.isPartialChunk) append(" (partial)")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HistoryCardShape)
            .background(HistoryCardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chunk #${chunk.index}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = chunk.resolution.label,
                color = Color(0xFF69F0AE),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            text = "Started $timeLabel",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelSmall,
        )

        Text(
            text = "Record ${chunk.recordDurationSec}s · $videoSizeLabel",
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelSmall,
        )

        Text(
            text = chunk.videoAbsolutePath,
            color = Color(0xFF78909C),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = chunk.status == ChunkProcessStatus.Done || chunk.status == ChunkProcessStatus.Failed,
                    onClick = onToggleExpand,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (chunk.status) {
                    ChunkProcessStatus.Pending -> "Extract · waiting"
                    ChunkProcessStatus.Processing -> "Extract · processing…"
                    ChunkProcessStatus.Failed -> "Extract · failed"
                    ChunkProcessStatus.Done -> chunk.extractSummary
                },
                color = when {
                    chunk.status == ChunkProcessStatus.Done && chunk.facesKept == 0 -> Color(0xFFFFAB91)
                    chunk.status == ChunkProcessStatus.Done -> Color(0xFF80CBC4)
                    else -> Color(0xFF90A4AE)
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            if (chunk.status == ChunkProcessStatus.Done || chunk.status == ChunkProcessStatus.Failed) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (expanded && chunk.status == ChunkProcessStatus.Done) {
            if (chunk.facesKept == 0) {
                Text(
                    text = "No face",
                    color = Color(0xFFFFAB91),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                chunk.extractedImages.forEach { image ->
                    Text(
                        text = "${image.fileName}  ${formatChunkBytes(image.sizeBytes)}",
                        color = Color(0xFFCFD8DC),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Text(
                        text = image.absolutePath,
                        color = Color(0xFF78909C),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}
