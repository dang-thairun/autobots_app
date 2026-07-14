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
import androidx.compose.ui.unit.dp
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(CardShape)
                .background(CardBg)
                .padding(12.dp),
        ) {
            Text(
                text = AutobotsApp.banner,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "IP Address: ${state.serverIp}",
                color = Color(0xFF69F0AE),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusReadout(state)
            if (state.isCapturing && !cameraPermissionGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Camera permission required",
                    color = Color(0xFFFFAB91),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

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

            Button(
                onClick = {
                    when {
                        state.isCapturing -> onToggleCapture()
                        cameraPermissionGranted -> onToggleCapture()
                        else -> onRequestCameraPermission()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.isCapturing -> "Stop Capture"
                        cameraPermissionGranted -> "Start Capture"
                        else -> "Allow Camera & Start"
                    },
                )
            }

            Button(
                onClick = onOpenGallery,
                enabled = state.keptPhotoCount > 0 || state.lastGalleryUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.keptPhotoCount > 0) {
                        "Open Gallery (${state.keptPhotoCount})"
                    } else {
                        "Open Gallery"
                    },
                )
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
                        text = "$modeShort · Arm ${(state.armThreshold * 100).toInt()}% · Fire ${(state.fireThreshold * 100).toInt()}%",
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
            text = "Fire (Burst)  ${(fireThreshold * 100).toInt()}%",
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
private fun StatusReadout(state: OperatorUiState) {
    val gateLabel = if (state.passageGateOpen) "OPEN" else "closed"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Faces: ${state.faceCount}  ·  Proximity: ${(state.proximity * 100).toInt()}%",
            color = Color(0xFF69F0AE),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Armed: ${if (state.isArmed) "YES" else "no"}  ·  Fired: ${if (state.lastFired) "YES" else "no"}  ·  Gate: $gateLabel",
            color = if (state.isArmed || state.lastFired) Color(0xFF69F0AE) else Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Kept (gallery): ${state.keptPhotoCount}" +
                if (state.lastBurstSaved > 0) "  (last burst ${state.lastBurstSaved} shots)" else "",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.isBursting) {
            Text(
                text = "Burst in progress…",
                color = Color(0xFFFFF176),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = state.exposureLine,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = state.deviceLoadLine,
            color = thermalColor(state.thermalLevel),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Operator-friendly thermal colors (ADR 0011 readout). */
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
