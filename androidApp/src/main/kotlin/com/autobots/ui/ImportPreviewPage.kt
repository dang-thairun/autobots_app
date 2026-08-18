package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autobots.camera.DetectorBackend
import com.autobots.camera.ExtractionTarget

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

@Composable
fun ImportPreviewPage(
    state: OperatorUiState,
    onBack: () -> Unit,
    onExtract: (ExtractionTarget, DetectorBackend, Long?, Long?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.pendingImport
    val initialBackend = remember(pending?.uri, state.detectorBackend, state.detectorUnavailable) {
        defaultImportBackend(state)
    }
    var target by remember(pending?.uri, state.extractionTarget) {
        mutableStateOf(state.extractionTarget)
    }
    var backend by remember(pending?.uri, initialBackend) {
        mutableStateOf(initialBackend)
    }
    var fullLength by remember(pending?.uri) { mutableStateOf(true) }
    var startText by remember(pending?.uri) { mutableStateOf("0:00") }
    var endText by remember(pending?.uri, pending?.durationMs) {
        mutableStateOf(formatTrimTime(pending?.durationMs ?: 0L))
    }
    val rangeEnabled = (pending?.durationMs ?: 0L) > 0L
    val useFullLength = fullLength || !rangeEnabled
    val clipRange = resolveImportClipRange(
        fullLength = useFullLength,
        startText = startText,
        endText = endText,
        sourceDurationMs = pending?.durationMs ?: 0L,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "←  Import",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(bottom = 10.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pending == null) {
                ImportInfoCard {
                    Text(
                        text = if (state.isPreparingImport) "Reading video…" else "Cannot read video",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                ImportInfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = pending.displayName,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = pending.sizeLabel,
                                color = Color(0xFFB0BEC5),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = pending.resolutionLine,
                                color = Color(0xFFE0E0E0),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                            Text(
                                text = "${pending.durationLabel} · ${pending.fpsLabel}",
                                color = Color(0xFFB0BEC5),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }

                ImportInfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Range",
                                color = Color(0xFF90A4AE),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = "Full length",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Switch(
                            checked = useFullLength,
                            onCheckedChange = { checked ->
                                fullLength = checked
                                if (!checked) {
                                    startText = "0:00"
                                    endText = formatTrimTime(pending.durationMs)
                                }
                            },
                            enabled = rangeEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF80CBC4),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.28f),
                            ),
                        )
                    }
                    if (!useFullLength) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ImportTimeField(
                                value = startText,
                                onValueChange = { startText = it },
                                label = "Start",
                                modifier = Modifier.weight(1f),
                            )
                            ImportTimeField(
                                value = endText,
                                onValueChange = { endText = it },
                                label = "End",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        clipRange.error?.let { message ->
                            Text(
                                text = message,
                                color = Color(0xFFB0704A),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                ImportInfoCard {
                    Text(
                        text = "Target",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExtractionTarget.entries.forEach { option ->
                            FilterChip(
                                selected = target == option,
                                onClick = { target = option },
                                modifier = Modifier.weight(1f),
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text(
                        text = "Process with",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ImportPreviewBackends.forEach { option ->
                            val unavailable = state.detectorUnavailable[option]
                            FilterChip(
                                selected = backend == option,
                                onClick = { backend = option },
                                enabled = unavailable == null,
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        importPreviewBackendLabel(option),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                    ImportPreviewBackends.forEach { option ->
                        val reason = state.detectorUnavailable[option] ?: return@forEach
                        Text(
                            text = "${importPreviewBackendLabel(option)}: $reason",
                            color = Color(0xFFB0704A),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                val estimateMs = estimateImportWallMs(
                    durationMs = if (useFullLength) pending.durationMs else clipRange.durationMs,
                    width = pending.width,
                    height = pending.height,
                    rotationDegrees = pending.rotationDegrees,
                    target = target,
                    backend = backend,
                )
                val clipLabel = if (useFullLength) {
                    "${pending.durationLabel} clip"
                } else {
                    "${formatTrimTime(clipRange.startMs)}–${formatTrimTime(clipRange.endMs)}"
                }
                ImportInfoCard {
                    Text(
                        text = "Estimate",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatImportEstimate(estimateMs),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${target.label} · ${importPreviewBackendLabel(backend)} · $clipLabel",
                        color = Color(0xFFB0BEC5),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val canExtract = pending != null &&
            !state.isPreparingImport &&
            !state.detectorUnavailable.containsKey(backend) &&
            clipRange.isValid
        Button(
            onClick = {
                if (pending == null) return@Button
                if (useFullLength) {
                    onExtract(target, backend, null, null)
                } else {
                    onExtract(target, backend, clipRange.startMs, clipRange.endMs)
                }
            },
            enabled = canExtract,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color.White.copy(alpha = 0.14f),
                disabledContentColor = Color.White.copy(alpha = 0.55f),
            ),
        ) {
            Text("Extract")
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Cancel", color = Color(0xFFB0BEC5))
        }
    }
}

@Composable
private fun ImportInfoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = { content() },
    )
}

@Composable
private fun ImportTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(formatTimeInput(it)) },
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("m:ss") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedBorderColor = Color(0xFF80CBC4),
            unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
            focusedLabelColor = Color(0xFF80CBC4),
            unfocusedLabelColor = Color(0xFF90A4AE),
            focusedPlaceholderColor = Color(0xFF78909C),
            unfocusedPlaceholderColor = Color(0xFF78909C),
        ),
    )
}

internal data class ImportClipRange(
    val startMs: Long,
    val endMs: Long,
    val error: String? = null,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val isValid: Boolean get() = error == null
}

internal fun resolveImportClipRange(
    fullLength: Boolean,
    startText: String,
    endText: String,
    sourceDurationMs: Long,
): ImportClipRange {
    if (fullLength || sourceDurationMs <= 0L) {
        return ImportClipRange(0L, sourceDurationMs.coerceAtLeast(0L))
    }
    val startMs = parseTrimTime(startText)
    val endMs = parseTrimTime(endText)
    if (startMs == null || endMs == null) {
        return ImportClipRange(0L, sourceDurationMs, "Enter start and end as m:ss")
    }
    if (startMs < 0L || endMs < 0L) {
        return ImportClipRange(startMs, endMs, "Times cannot be negative")
    }
    if (startMs >= sourceDurationMs) {
        return ImportClipRange(startMs, endMs, "Start is past the clip")
    }
    if (endMs > sourceDurationMs) {
        return ImportClipRange(startMs, endMs, "End is past the clip")
    }
    if (startMs >= endMs) {
        return ImportClipRange(startMs, endMs, "Start must be before end")
    }
    return ImportClipRange(startMs, endMs)
}

/** Digits from a number pad, shown as `m:ss` or `h:mm:ss` (e.g. 130 → 1:30). */
internal fun formatTimeInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(6)
    if (digits.isEmpty()) return ""
    val padded = if (digits.length <= 4) {
        digits.padStart(4, '0')
    } else {
        digits.padStart(6, '0')
    }
    return if (padded.length == 4) {
        "${padded.substring(0, 2).toInt()}:${padded.substring(2, 4)}"
    } else {
        "${padded.substring(0, 2).toInt()}:${padded.substring(2, 4)}:${padded.substring(4, 6)}"
    }
}

internal fun formatTrimTime(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun parseTrimTime(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.all { it.isDigit() }) {
        return trimmed.toLongOrNull()?.times(1000L)
    }
    val parts = trimmed.split(':')
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    return when (nums.size) {
        2 -> {
            val minutes = nums[0]
            val seconds = nums[1]
            if (seconds !in 0L..59L || minutes < 0L) null
            else (minutes * 60L + seconds) * 1000L
        }
        3 -> {
            val hours = nums[0]
            val minutes = nums[1]
            val seconds = nums[2]
            if (hours < 0L || minutes !in 0L..59L || seconds !in 0L..59L) null
            else (hours * 3600L + minutes * 60L + seconds) * 1000L
        }
        else -> null
    }
}

private fun defaultImportBackend(state: OperatorUiState): DetectorBackend {
    val current = state.detectorBackend
    if (current in ImportPreviewBackends && !state.detectorUnavailable.containsKey(current)) {
        return current
    }
    return DetectorBackend.firstAvailable(state.detectorUnavailable)
}
