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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobots.camera.AutobotsApp
import com.autobots.camera.CaptureMode
import com.autobots.camera.CaptureResolutions
import com.autobots.camera.detection.FaceFrameResult

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

/**
 * Overlay pager pages (layer 2). Preview stays on layer 1 underneath.
 * Index 0 = operator controls; 1 = clean preview; later = observation pages.
 */
private object OverlayPages {
    const val Controls = 0
    const val CleanPreview = 1
    const val Observation = 2
    const val Count = 3
}

@Composable
fun OperatorShellScreen(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onCaptureMode: (CaptureMode) -> Unit,
    onFaceResult: (FaceFrameResult) -> Boolean,
    onBurstComplete: (Int) -> Unit,
    onPhotoDelivered: (String) -> Unit,
    onExposureReadout: (String) -> Unit,
    onArmThreshold: (Float) -> Unit,
    onFireThreshold: (Float) -> Unit,
    onOpenGallery: () -> Unit,
    onFrameEncoded: ((ByteArray) -> Unit)? = null,
) {
    val previewActive = state.isCapturing && cameraPermissionGranted
    var settingsExpanded by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { OverlayPages.Count })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
    ) {
        // Layer 1 — camera preview (fixed)
        CameraPreviewPane(
            active = previewActive,
            faces = state.faces,
            subjectIndex = state.subjectIndex,
            armThreshold = state.armThreshold,
            fireThreshold = state.fireThreshold,
            burstShotCount = state.burstShotCount,
            captureMode = state.captureMode,
            onFaceResult = onFaceResult,
            onBurstComplete = onBurstComplete,
            onPhotoDelivered = onPhotoDelivered,
            onExposureReadout = onExposureReadout,
            onFrameEncoded = onFrameEncoded,
            showFaceOverlay = pagerState.currentPage != OverlayPages.CleanPreview,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2 — swipeable chrome / future observation pages
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
                        onCaptureMode = onCaptureMode,
                        onArmThreshold = onArmThreshold,
                        onFireThreshold = onFireThreshold,
                        onOpenGallery = onOpenGallery,
                    )
                    OverlayPages.CleanPreview -> {
                        // Transparent — preview-only
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    OverlayPages.Observation -> ObservationPage(state = state)
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
private fun ObservationPage(state: OperatorUiState) {
    val subject = state.subjectIndex?.let { state.faces.getOrNull(it) }
    Box(modifier = Modifier.fillMaxSize()) {
        AfGridOverlay(
            subject = subject,
            isArmed = state.isArmed,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .clip(CardShape)
                .background(CardBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${AfGridDefaults.COLUMNS}×${AfGridDefaults.ROWS} AF points (visual)",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = state.exposureLine,
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = if (state.isArmed) "Active near Subject Face" else "Idle — arm to highlight",
                color = if (state.isArmed) Color(0xFF69F0AE) else Color(0xFF90A4AE),
                style = MaterialTheme.typography.labelSmall,
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
    onCaptureMode: (CaptureMode) -> Unit,
    onArmThreshold: (Float) -> Unit,
    onFireThreshold: (Float) -> Unit,
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
            CaptureSettingsCard(
                expanded = settingsExpanded,
                onToggle = onSettingsToggle,
                state = state,
                onCaptureMode = onCaptureMode,
                onArmThreshold = onArmThreshold,
                onFireThreshold = onFireThreshold,
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
private fun CaptureSettingsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    state: OperatorUiState,
    onCaptureMode: (CaptureMode) -> Unit,
    onArmThreshold: (Float) -> Unit,
    onFireThreshold: (Float) -> Unit,
) {
    val modeShort = when (state.captureMode) {
        CaptureMode.Standard -> "Standard"
        CaptureMode.MaxSensor -> "Max-Sensor"
    }
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
                    text = "Capture settings",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!expanded) {
                    Text(
                        text = "$modeShort · Arm ${(state.armThreshold * 100).toInt()}% · Min ${(state.fireThreshold * 100).toInt()}% · Zone ${if (state.inCaptureZone) "IN" else "—"}",
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
                ThresholdSliders(
                    armThreshold = state.armThreshold,
                    fireThreshold = state.fireThreshold,
                    onArmThreshold = onArmThreshold,
                    onFireThreshold = onFireThreshold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CaptureModeChip(
                        mode = CaptureMode.Standard,
                        selected = state.captureMode == CaptureMode.Standard,
                        onClick = { onCaptureMode(CaptureMode.Standard) },
                        modifier = Modifier.weight(1f),
                    )
                    CaptureModeChip(
                        mode = CaptureMode.MaxSensor,
                        selected = state.captureMode == CaptureMode.MaxSensor,
                        onClick = { onCaptureMode(CaptureMode.MaxSensor) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureModeChip(
    mode: CaptureMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val title = when (mode) {
        CaptureMode.Standard -> "Standard"
        CaptureMode.MaxSensor -> "Max-Sensor"
    }
    val resolution = remember(mode) { CaptureResolutions.label(context, mode) }
    Column(modifier = modifier) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(title) },
        )
        Text(
            text = resolution,
            color = Color(0xFF90A4AE),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}

@Composable
private fun ThresholdSliders(
    armThreshold: Float,
    fireThreshold: Float,
    onArmThreshold: (Float) -> Unit,
    onFireThreshold: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Arm (Face Lock)  ${(armThreshold * 100).toInt()}%",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = armThreshold,
            onValueChange = onArmThreshold,
            valueRange = 0.01f..0.25f,
        )
        Text(
            text = "Min size (Fire)  ${(fireThreshold * 100).toInt()}%",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = fireThreshold,
            onValueChange = onFireThreshold,
            valueRange = 0.02f..0.45f,
        )
    }
}

@Composable
private fun CompactStatusCard(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = AutobotsApp.banner,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "IP ${state.serverIp}",
                color = Color(0xFF69F0AE),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatChip("F", "${state.faceCount}", modifier = Modifier.weight(1f))
            StatChip(
                label = "Px",
                value = "${(state.proximity * 100).toInt()}%",
                highlight = state.isArmed,
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Arm",
                value = if (state.isArmed) "●" else "○",
                highlight = state.isArmed,
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Fir",
                value = if (state.lastFired) "●" else if (state.isBursting) "…" else "○",
                highlight = state.lastFired || state.isBursting,
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Gate",
                value = if (state.passageGateOpen) "O" else "C",
                highlight = state.passageGateOpen,
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "Zn",
                value = if (state.inCaptureZone) "IN" else "—",
                highlight = state.inCaptureZone,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatChip(
                label = "K",
                value = "${state.keptPhotoCount}",
                modifier = Modifier.weight(0.7f),
            )
            StatChip(
                label = "Th",
                value = state.thermalLabel,
                valueColor = thermalColor(state.thermalLevel),
                modifier = Modifier.weight(1f),
            )
            StatChip(
                label = "RAM",
                value = compactRamPair(state.usedRamMb, state.totalRamMb),
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = state.exposureLine,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

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
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueColor: Color? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.32f))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = Color(0xFF90A4AE),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            lineHeight = 10.sp,
        )
        Text(
            text = value,
            color = valueColor ?: if (highlight) Color(0xFF69F0AE) else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 1,
        )
    }
}

private fun compactRamPair(usedMb: Long, totalMb: Long): String {
    if (totalMb <= 0L) return "—"
    return "${formatRamShort(usedMb)}/${formatRamShort(totalMb)}"
}

private fun formatRamShort(mb: Long): String {
    return if (mb >= 1000L) {
        String.format("%.1fG", mb / 1024.0)
    } else {
        "${mb}M"
    }
}

/** Operator-friendly thermal colors (Flow 11 readout). */
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
            onToggleCapture = {},
            onRequestCameraPermission = {},
            onCaptureMode = {},
            onFaceResult = { false },
            onBurstComplete = {},
            onPhotoDelivered = {},
            onExposureReadout = {},
            onArmThreshold = {},
            onFireThreshold = {},
            onOpenGallery = {},
        )
    }
}
