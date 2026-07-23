package com.autobots.ui

import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobots.camera.AutobotsApp
import com.autobots.camera.StreamResolution
import com.autobots.camera.pipeline.CapturePipelineCoordinator

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

private object OverlayPages {
    const val Controls = 0
    const val CleanPreview = 1
    const val Count = 2
}

@Composable
fun OperatorShellScreen(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    pipelineCoordinator: CapturePipelineCoordinator?,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onRecordingProgress: (Int, Long, Long) -> Unit,
    onPhotoDelivered: (String) -> Unit,
    onExposureReadout: (String) -> Unit,
    onOpenGallery: () -> Unit,
) {
    val previewActive = state.isCapturing && cameraPermissionGranted
    var settingsExpanded by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { OverlayPages.Count })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
    ) {
        CameraPreviewPane(
            active = previewActive,
            streamResolution = state.streamResolution,
            pipelineCoordinator = pipelineCoordinator,
            pipelinePaused = state.pipelinePaused,
            videoQueueDepth = state.videoQueueDepth,
            onRecordingProgress = onRecordingProgress,
            onExposureReadout = onExposureReadout,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    OverlayPages.Controls -> OperatorControlsPage(
                        state = state,
                        cameraPermissionGranted = cameraPermissionGranted,
                        settingsExpanded = settingsExpanded,
                        onSettingsToggle = { settingsExpanded = !settingsExpanded },
                        onToggleCapture = onToggleCapture,
                        onRequestCameraPermission = onRequestCameraPermission,
                        onStreamResolution = onStreamResolution,
                        onOpenGallery = onOpenGallery,
                    )
                    OverlayPages.CleanPreview -> Box(modifier = Modifier.fillMaxSize())
                    else -> Box(modifier = Modifier.fillMaxSize())
                }
            }

            OverlayPageIndicator(
                pageCount = OverlayPages.Count,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun OperatorControlsPage(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    settingsExpanded: Boolean,
    onSettingsToggle: () -> Unit,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CompactStatusCard(
            state = state,
            cameraPermissionGranted = cameraPermissionGranted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PipelineSettingsCard(
                expanded = settingsExpanded,
                onToggle = onSettingsToggle,
                state = state,
                onStreamResolution = onStreamResolution,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        when {
                            state.isCapturing -> onToggleCapture()
                            cameraPermissionGranted -> onToggleCapture()
                            else -> onRequestCameraPermission()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when {
                            state.isCapturing -> "Stop"
                            cameraPermissionGranted -> "Start"
                            else -> "Allow & Start"
                        },
                        maxLines = 1,
                    )
                }

                Button(
                    onClick = onOpenGallery,
                    enabled = state.keptPhotoCount > 0 || state.lastGalleryUri != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = if (state.keptPhotoCount > 0) {
                            "Gallery (${state.keptPhotoCount})"
                        } else {
                            "Gallery"
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineSettingsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    state: OperatorUiState,
    onStreamResolution: (StreamResolution) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Video pipeline",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!expanded) {
                    Text(
                        text = if (state.isCapturing) "Recording" else state.streamResolution.label,
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = if (expanded) "Hide" else "Show",
                color = Color(0xFFB0BEC5),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Record 4K/1080p chunks (~20 MB) → extract sharp face frames",
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StreamResolutionChip(
                        resolution = StreamResolution.Fhd,
                        selected = state.streamResolution == StreamResolution.Fhd,
                        enabled = !state.isCapturing,
                        onClick = { onStreamResolution(StreamResolution.Fhd) },
                        modifier = Modifier.weight(1f),
                    )
                    StreamResolutionChip(
                        resolution = StreamResolution.Uhd,
                        selected = state.streamResolution == StreamResolution.Uhd,
                        enabled = !state.isCapturing,
                        onClick = { onStreamResolution(StreamResolution.Uhd) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamResolutionChip(
    resolution: StreamResolution,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(resolution.label) },
        )
        Text(
            text = "${resolution.width}×${resolution.height}",
            color = Color(0xFF90A4AE),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun OverlayPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (selected) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Color.White.copy(alpha = 0.85f)
                        else Color.White.copy(alpha = 0.35f),
                    ),
            )
        }
    }
}

@Composable
private fun CompactStatusCard(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    var activeTooltip by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(AutobotsApp.banner)
                    if (!state.isCapturing) append(" · IDLE")
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "${state.streamResolution.label} · IP ${state.serverIp}",
                color = Color(0xFF69F0AE),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.isCapturing && state.recordingLine.isNotEmpty()) {
            Text(
                text = state.recordingLine,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
            LinearProgressIndicator(
                progress = { state.recordingProgress.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = Color(0xFFFF5252),
                trackColor = Color.White.copy(alpha = 0.2f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            StatChip(
                label = "Ch",
                value = "${state.videoChunksRecorded}",
                tooltip = "Chunks — วิดีโอที่อัดเสร็จ (~20 MB)",
                highlight = state.isCapturing,
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "VQ",
                value = "${state.videoQueueDepth}",
                tooltip = "Video Queue — รอประมวลผลหาใบหน้า",
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Face",
                value = "${state.facesKept}",
                tooltip = "เฟรมที่คัดได้ — มีหน้าและชัดพอ",
                highlight = state.facesKept > 0,
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "K",
                value = "${state.keptPhotoCount}",
                tooltip = "Kept — รูปใน Gallery",
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Th",
                value = state.thermalLabel,
                tooltip = "Thermal — ความร้อนเครื่อง",
                valueColor = thermalColor(state.thermalLevel),
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Disk",
                value = "${state.storageFreeMb}M",
                tooltip = "Disk — พื้นที่ว่าง (MB)",
                active = activeTooltip,
                onTooltip = { activeTooltip = it },
                modifier = Modifier.weight(1f),
            )
        }

        activeTooltip?.let { hint ->
            Text(
                text = hint,
                color = Color(0xFFB0BEC5),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }

        Text(
            text = state.deviceLoadLine,
            color = Color(0xFF78909C),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (state.storageBlocked) {
            Text(
                text = "Storage low — need 2 GB free to record",
                color = Color(0xFFFFAB91),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (state.isCapturing && !cameraPermissionGranted) {
            Text(
                text = "Need camera permission",
                color = Color(0xFFFFAB91),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueColor: Color? = null,
    active: String? = null,
    onTooltip: (String?) -> Unit = {},
) {
    val selected = active == tooltip
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) Color.White.copy(alpha = 0.12f)
                else Color.Black.copy(alpha = 0.32f),
            )
            .clickable { onTooltip(if (selected) null else tooltip) }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
        )
        Text(
            text = value,
            color = valueColor ?: if (highlight) Color(0xFF69F0AE) else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
        )
    }
}

private fun thermalColor(level: Int): Color = when (level) {
    PowerManager.THERMAL_STATUS_NONE, -1 -> Color.White
    PowerManager.THERMAL_STATUS_LIGHT -> Color(0xFF26A69A)
    PowerManager.THERMAL_STATUS_MODERATE -> Color(0xFFFF9800)
    PowerManager.THERMAL_STATUS_SEVERE -> Color(0xFFFF1744)
    else -> Color(0xFFB71C1C)
}

@Preview(showBackground = true)
@Composable
private fun OperatorShellPreview() {
    MaterialTheme {
        OperatorShellScreen(
            state = OperatorUiState(isCapturing = false),
            cameraPermissionGranted = true,
            pipelineCoordinator = null,
            onToggleCapture = {},
            onRequestCameraPermission = {},
            onStreamResolution = {},
            onRecordingProgress = { _, _, _ -> },
            onPhotoDelivered = {},
            onExposureReadout = {},
            onOpenGallery = {},
        )
    }
}
