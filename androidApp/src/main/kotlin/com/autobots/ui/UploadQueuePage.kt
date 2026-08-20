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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autobots.camera.formatChunkBytes
import com.autobots.camera.upload.UploadItem
import com.autobots.camera.upload.UploadQueueCounts
import com.autobots.camera.upload.UploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

/**
 * The upload queue, read-only (B3b — see docs/PHASES.md).
 *
 * Nothing drains the queue yet, so every row here sits at [UploadStatus.Pending]. The screen
 * exists before the worker on purpose: once B3c starts moving rows, this is what makes the
 * state machine visible without reading logcat.
 */
@Composable
fun UploadQueuePage(
    counts: UploadQueueCounts,
    items: List<UploadItem>,
    paused: Boolean,
    pauseReason: String?,
    destinationLabel: String,
    signedInAs: String?,
    eventTitle: String,
    eventId: String,
    onBack: () -> Unit,
    onRetryFailed: () -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    filter: UploadStatus?,
    onFilter: (UploadStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←  Upload",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        SummaryCard(
            counts = counts,
            paused = paused,
            pauseReason = pauseReason,
            destinationLabel = destinationLabel,
            signedInAs = signedInAs,
            eventTitle = eventTitle,
            eventId = eventId,
            onRetryFailed = onRetryFailed,
            onSetPaused = onSetPaused,
            onOpenSettings = onOpenSettings,
        )

        if (counts.total > 0) {
            StatusFilters(
                counts = counts,
                selected = filter,
                onSelect = { onFilter(if (filter == it) null else it) },
            )
        }

        if (items.isEmpty()) {
            Text(
                text = if (counts.total == 0) {
                    "Nothing queued yet. Photos join the queue as they are delivered."
                } else {
                    "No photos in this state."
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            // Says when the list is a window rather than the whole thing. A page that
            // silently stops at its limit reads as "that is all of them".
            val shown = filter?.let { counts.of(it) } ?: counts.total
            if (shown > items.size) {
                Text(
                    text = "Showing the newest ${items.size} of $shown",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.id }) { item -> QueueRow(item) }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    counts: UploadQueueCounts,
    paused: Boolean,
    pauseReason: String?,
    destinationLabel: String,
    signedInAs: String?,
    eventTitle: String,
    eventId: String,
    onRetryFailed: () -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (counts.outstanding > 0) {
                "${counts.outstanding} waiting to upload"
            } else if (counts.total > 0) {
                "Queue clear"
            } else {
                "Queue empty"
            },
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            // Names where the bytes actually go. Until a backend is configured that is a
            // directory on this phone, and the screen should not imply otherwise.
            text = if (paused) {
                pauseReason?.let { "Paused — $it" } ?: "Paused"
            } else {
                "Sending to $destinationLabel"
            },
            color = if (paused && pauseReason != null) Color(0xFFFFCC80) else Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        // Who and where. Both are set on another screen and both are easy to get wrong —
        // a whole session can end up under yesterday's event without anything looking amiss,
        // and the queue is the screen someone actually watches while shooting.
        Text(
            text = when {
                signedInAs == null -> "Not signed in — uploads need a sign-in"
                eventId.isBlank() -> "$signedInAs · no event selected"
                else -> "$signedInAs → ${eventTitle.ifBlank { eventId }}"
            },
            color = if (signedInAs == null || eventId.isBlank()) {
                Color(0xFFFFCC80)
            } else {
                Color(0xFFA5D6A7)
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (counts.total > 0) {
            Text(
                text = "${counts.success} succeeded · ${counts.total} total",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onSetPaused(!paused) }) {
                Text(if (paused) "Resume" else "Pause")
            }
            if (counts.failed > 0 || counts.abandoned > 0) {
                TextButton(onClick = onRetryFailed) {
                    Text("Retry ${counts.failed + counts.abandoned}")
                }
            }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun StatusFilters(
    counts: UploadQueueCounts,
    selected: UploadStatus?,
    onSelect: (UploadStatus) -> Unit,
) {
    val present = listOf(
        UploadStatus.Pending to counts.pending,
        UploadStatus.Uploading to counts.uploading,
        UploadStatus.Uploaded to counts.uploaded,
        UploadStatus.Success to counts.success,
        UploadStatus.Failed to counts.failed,
        UploadStatus.Abandoned to counts.abandoned,
    ).filter { it.second > 0 }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        present.forEach { (status, count) ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(status) },
                label = { Text("${statusLabel(status)} $count") },
            )
        }
    }
}

@Composable
private fun QueueRow(item: UploadItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.fileName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = statusLabel(item.status),
                color = statusColor(item.status),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = buildString {
                append(item.sessionId)
                append(" · ")
                append(formatChunkBytes(item.sizeBytes))
                append(" · ")
                append(formatQueueTime(item.capturedAtMs))
            },
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.attemptCount > 0 || item.lastError != null) {
            Text(
                text = buildString {
                    if (item.attemptCount > 0) append("attempt ${item.attemptCount}")
                    item.lastError?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                },
                color = Color(0xFFEF9A9A),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The screen says exactly what the row says.
 *
 * Friendlier words were tried first ("Committing", "Done", "Given up") and made the screen
 * impossible to line up against a log line or a `SELECT status` — the person reading this
 * queue today is the person debugging it. `Uploaded` sitting next to `Success` looks odd
 * until you know they are different things, and that difference is the whole point of the
 * state machine, so it is worth showing rather than smoothing over.
 */
internal fun statusLabel(status: UploadStatus): String = status.name

private fun statusColor(status: UploadStatus): Color = when (status) {
    UploadStatus.Pending -> Color.White.copy(alpha = 0.65f)
    UploadStatus.Uploading, UploadStatus.Uploaded -> Color(0xFF80CBC4)
    UploadStatus.Success -> Color(0xFFA5D6A7)
    UploadStatus.Failed -> Color(0xFFFFCC80)
    UploadStatus.Abandoned -> Color(0xFFEF9A9A)
}

private fun formatQueueTime(epochMs: Long): String =
    SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
