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
import com.autobots.camera.PipelineSessionRecord
import com.autobots.camera.SessionSource
import com.autobots.camera.SessionStatus
import com.autobots.camera.formatChunkBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HistoryCardBg = Color.Gray.copy(alpha = 0.25f)
private val HistoryCardShape = RoundedCornerShape(10.dp)

@Composable
fun ChunkHistoryPage(
    sessions: List<PipelineSessionRecord>,
    modifier: Modifier = Modifier,
) {
    val expandedSessions = remember { mutableStateMapOf<String, Boolean>() }
    val expandedChunks = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Session history",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(HistoryCardShape)
                    .background(HistoryCardBg)
                    .padding(12.dp),
            ) {
                Text(
                    text = "No sessions yet",
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRecordCard(
                        session = session,
                        expanded = expandedSessions[session.id] == true,
                        onToggleExpand = {
                            val current = expandedSessions[session.id] == true
                            expandedSessions[session.id] = !current
                        },
                        expandedChunks = expandedChunks,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRecordCard(
    session: PipelineSessionRecord,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    expandedChunks: MutableMap<Int, Boolean>,
) {
    val startedLabel = remember(session.startedAtEpochMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(session.startedAtEpochMs))
    }
    val statusColor = when (session.status) {
        SessionStatus.Done -> Color(0xFF80CBC4)
        SessionStatus.Failed -> Color(0xFFFFAB91)
        else -> Color(0xFF90A4AE)
    }
    val visibleChunks = remember(session.chunks, session.source) {
        if (session.source == SessionSource.VideoImport) {
            session.chunks.filter {
                it.status == ChunkProcessStatus.Done && it.facesKept > 0
            }
        } else {
            session.chunks
        }
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
                text = session.displayName,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = session.statusLabel,
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            text = buildString {
                append(session.sourceLabel)
                append(" · ")
                append(session.resolutionLine)
                append(" · ")
                append(session.extractionTarget.label)
                append(" · started $startedLabel")
            },
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
        )

        session.videoDurationLabel?.let { duration ->
            Text(
                text = "Video $duration · ${formatChunkBytes(session.sourceSizeBytes ?: 0)}",
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            text = session.headlineSummary,
            color = Color(0xFF69F0AE),
            style = MaterialTheme.typography.labelMedium,
        )

        session.detectionSummary?.let { detection ->
            Text(
                text = detection,
                color = Color(0xFF80CBC4),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (session.totalDurationMs > 0) {
            Text(
                text = session.timingSummary,
                color = Color(0xFFCFD8DC),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (session.albumFolderName.isNotEmpty()) {
            Text(
                text = "Gallery: ${session.galleryPath}",
                color = Color(0xFF78909C),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }

        session.progressLine?.let { progress ->
            Text(
                text = progress,
                color = Color(0xFF90A4AE),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        session.errorMessage?.takeIf { session.status == SessionStatus.Failed }?.let { error ->
            Text(
                text = error,
                color = Color(0xFFFFAB91),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (visibleChunks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (session.source == SessionSource.VideoImport) {
                        "${visibleChunks.size} chunks with ${session.extractionTarget.keptNoun}"
                    } else {
                        "${visibleChunks.size} chunks"
                    },
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "Hide chunks" else "Show chunks",
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (expanded) {
            visibleChunks.forEach { chunk ->
                ChunkRecordCard(
                    chunk = chunk,
                    expanded = expandedChunks[chunk.index] == true,
                    onToggleExpand = {
                        val current = expandedChunks[chunk.index] == true
                        expandedChunks[chunk.index] = !current
                    },
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ChunkRecordCard(
    chunk: ChunkRecord,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeLabel = remember(chunk.recordedAtEpochMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(chunk.recordedAtEpochMs))
    }
    val videoSizeLabel = buildString {
        append(formatChunkBytes(chunk.videoSizeBytes))
        if (chunk.isPartialChunk) append(" (partial)")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Chunk #${chunk.index}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = chunk.resolution.label,
                color = Color(0xFF69F0AE),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            text = "Started $timeLabel · ${chunk.recordDurationLabel} · $videoSizeLabel",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
        )

        if (chunk.status == ChunkProcessStatus.Done && chunk.framesSampled > 0) {
            Text(
                text = "Sample ${chunk.sampleIntervalMs}ms · ${chunk.framesSampled} frames",
                color = Color(0xFF78909C),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
            )
        }

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
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            if (chunk.status == ChunkProcessStatus.Done || chunk.status == ChunkProcessStatus.Failed) {
                Text(
                    text = if (expanded) "Hide" else "Show",
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                )
            }
        }

        if (expanded && chunk.status == ChunkProcessStatus.Done) {
            chunk.processStatsLine?.let { stats ->
                Text(
                    text = stats,
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (chunk.facesKept == 0) {
                Text(
                    text = chunk.extractionTarget.noKeptLabel,
                    color = Color(0xFFFFAB91),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                chunk.extractedImages.forEach { image ->
                    Text(
                        text = "${image.fileName}  ${formatChunkBytes(image.sizeBytes)}",
                        color = Color(0xFFCFD8DC),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
