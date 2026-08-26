package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import com.autobots.camera.DetectZone
import com.autobots.camera.DetectorBackend
import com.autobots.camera.detection.FaceDetLiteDetector
import com.autobots.camera.ExtractionTarget

/** Miniature of the frame in the card — big enough to recognise the lane, no bigger. */
private val ZonePreviewHeight = 150.dp

/**
 * One switch per detector, and the combinations are the switches — not extra rows.
 *
 * Each entry names the single flag its row owns, so the row list and
 * [ExtractionTarget]'s flags stay one-to-one as detectors are added.
 */
private val DetectionRows = listOf(
    ExtractionTarget.Face,
    ExtractionTarget.Pose,
    ExtractionTarget.Person,
)

/** Tall enough for the three-line values (label + 3 × 11.sp) and no taller. */
private val StatChipHeight = 54.dp

/** Units and separators sit a notch below the numbers they belong to. */
private val ChipUnitFontSize = 8.sp

/**
 * Stack a chip value over several lines — `403` over `MB`, `2160` over `×` over `3840`.
 *
 * Lines carrying no digits are the glue (a unit, a `×`), so they are set smaller and the
 * numbers stay the thing the eye lands on.
 */
private fun stackedChipValue(vararg lines: String): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\n")
            if (line.any { it.isDigit() }) {
                append(line)
            } else {
                withStyle(SpanStyle(fontSize = ChipUnitFontSize)) { append(line) }
            }
        }
    }

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

@Composable
fun ImportPreviewPage(
    state: OperatorUiState,
    onBack: () -> Unit,
    onExtract: (ExtractionTarget, DetectorBackend, Long?, Long?) -> Unit,
    onCancel: () -> Unit,
    onZoneChange: (DetectZone?) -> Unit,
    onEditZone: () -> Unit,
    onStepFaceScore: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.pendingImport
    var activeTooltip by remember(pending?.uri) { mutableStateOf<String?>(null) }
    val initialBackend = remember(pending?.uri, state.detectorBackend, state.detectorUnavailable) {
        defaultImportBackend(state)
    }
    // Two independent switches; both on is its own mode, where a frame has to satisfy the
    // face gate and the torso gate before it is kept. Both off means nothing to extract.
    var faceOn by remember(pending?.uri, state.extractionTarget) {
        mutableStateOf(state.extractionTarget.usesFace)
    }
    var poseOn by remember(pending?.uri, state.extractionTarget) {
        mutableStateOf(state.extractionTarget.usesPose)
    }
    var personOn by remember(pending?.uri, state.extractionTarget) {
        mutableStateOf(state.extractionTarget.usesPerson)
    }
    val enabledTarget: ExtractionTarget? =
        ExtractionTarget(usesFace = faceOn, usesPose = poseOn, usesPerson = personOn)
            .takeIf { !it.isEmpty }
    var expandedTarget by remember(pending?.uri) {
        mutableStateOf<ExtractionTarget?>(null)
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
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pending.displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        Text(
                            text = if (pending.isRemote) "URL" else "LOCAL",
                            color = Color(0xFF90A4AE),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }

                    // Fixed height: every chip is the same box whether its value is one line
                    // or three, so the row reads as a single strip.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        StatChip(
                            label = "Type",
                            value = pending.typeLabel,
                            tooltip = "Type — สกุลไฟล์ต้นทาง",
                            active = activeTooltip,
                            onTooltip = { activeTooltip = it },
                            height = StatChipHeight,
                            modifier = Modifier.weight(1f),
                        )
                        StatChip(
                            label = "Size",
                            value = stackedChipValue(
                                pending.sizeChipNumber,
                                pending.sizeChipUnit,
                            ),
                            tooltip = "Size — ขนาดไฟล์",
                            valueMaxLines = 3,
                            active = activeTooltip,
                            onTooltip = { activeTooltip = it },
                            height = StatChipHeight,
                            modifier = Modifier.weight(1f),
                        )
                        StatChip(
                            label = "Res",
                            value = if (pending.hasResolution) {
                                stackedChipValue(
                                    "${pending.displayWidth}",
                                    "×",
                                    "${pending.displayHeight}",
                                )
                            } else {
                                AnnotatedString("—")
                            },
                            tooltip = "Res — ขนาดภาพจริงหลังหมุน ที่ pipeline เห็น",
                            valueMaxLines = 3,
                            active = activeTooltip,
                            onTooltip = { activeTooltip = it },
                            height = StatChipHeight,
                            modifier = Modifier.weight(1f),
                        )
                        StatChip(
                            label = "FPS",
                            value = pending.fpsChipValue,
                            tooltip = "FPS — เฟรมต่อวินาทีของไฟล์ต้นทาง",
                            active = activeTooltip,
                            onTooltip = { activeTooltip = it },
                            height = StatChipHeight,
                            modifier = Modifier.weight(1f),
                        )
                        StatChip(
                            label = "Dur.",
                            value = pending.durationLabel,
                            tooltip = "Duration — ความยาวคลิปทั้งไฟล์",
                            valueMaxLines = 2,
                            active = activeTooltip,
                            onTooltip = { activeTooltip = it },
                            height = StatChipHeight,
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
                        AutobotsSwitch(
                            checked = useFullLength,
                            onCheckedChange = { checked ->
                                fullLength = checked
                                if (!checked) {
                                    startText = "0:00"
                                    endText = formatTrimTime(pending.durationMs)
                                }
                            },
                            enabled = rangeEnabled,
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
                        text = "Detection",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    DetectionRows.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        val checked = when (option) {
                            ExtractionTarget.Pose -> poseOn
                            ExtractionTarget.Person -> personOn
                            else -> faceOn
                        }
                        val scored = option == ExtractionTarget.Face && backend.usesLiteRt
                        DetectionToggleRow(
                            title = "${option.label} Detection",
                            subtitle = buildString {
                                append("Process with ${importPreviewBackendLabel(backend)}")
                                // Only where the number is real: ML Kit reports no score, so
                                // showing a threshold next to it would imply a gate that is
                                // not there.
                                if (scored) {
                                    append(" · score ≥ ${formatFaceScore(state.minFaceScore)}")
                                    append(" (logit ${formatFaceLogit(state.minFaceScore)})")
                                }
                            },
                            checked = checked,
                            onCheckedChange = { on ->
                                when (option) {
                                    ExtractionTarget.Pose -> poseOn = on
                                    ExtractionTarget.Person -> personOn = on
                                    else -> faceOn = on
                                }
                                if (!on && expandedTarget == option) expandedTarget = null
                            },
                            expanded = expandedTarget == option,
                            onToggleExpanded = {
                                expandedTarget = if (expandedTarget == option) null else option
                            },
                        )
                        if (expandedTarget == option) {
                            ProcessWithPicker(
                                state = state,
                                target = option,
                                selected = backend,
                                onSelect = { backend = it },
                            )
                            if (option == ExtractionTarget.Face) {
                                MinScoreRow(
                                    value = state.minFaceScore,
                                    enabled = backend.usesLiteRt,
                                    onStep = onStepFaceScore,
                                )
                            }
                        }
                    }
                }

                ImportInfoCard {
                    val zone = state.detectZone
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Detect zone",
                                color = Color(0xFF90A4AE),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = if (zone == null) "Whole frame" else "Custom area",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        AutobotsSwitch(
                            checked = zone != null,
                            onCheckedChange = { on ->
                                onZoneChange(if (on) DetectZone.DEFAULT else null)
                            },
                        )
                    }
                    if (zone != null) {
                        // Tapping the preview is the way into the editor: the picture is the
                        // thing the operator is reasoning about, so it is also the target.
                        ZonePreview(
                            zone = zone,
                            frame = state.pendingImportFrame,
                            frameWidth = pending.displayWidth,
                            frameHeight = pending.displayHeight,
                            onClick = onEditZone,
                        )
                        Text(
                            text = zone.pixelSummary(pending.displayWidth, pending.displayHeight),
                            color = Color(0xFFB0BEC5),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                val estimateMs = enabledTarget?.let { active ->
                    estimateImportWallMs(
                        durationMs = if (useFullLength) pending.durationMs else clipRange.durationMs,
                        width = pending.width,
                        height = pending.height,
                        rotationDegrees = pending.rotationDegrees,
                        target = active,
                        backend = backend,
                    )
                }
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
                        text = estimateMs?.let(::formatImportEstimate) ?: "—",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = enabledTarget?.let { active ->
                            "${active.label} · ${importPreviewBackendLabel(backend)} · $clipLabel"
                        } ?: "Turn on a detection to extract",
                        color = Color(0xFFB0BEC5),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        val canExtract = pending != null &&
            enabledTarget != null &&
            !state.isPreparingImport &&
            !state.detectorUnavailable.containsKey(backend) &&
            clipRange.isValid
        Button(
            onClick = {
                if (pending == null) return@Button
                val active = enabledTarget ?: return@Button
                if (useFullLength) {
                    onExtract(active, backend, null, null)
                } else {
                    onExtract(active, backend, clipRange.startMs, clipRange.endMs)
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

/**
 * One detection, switched like [Range]: title, toggle, then a chevron that reveals the
 * backend picker. Collapsed by default — the backend rarely needs changing.
 */
@Composable
private fun DetectionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = if (checked) Color.White else Color(0xFFB0BEC5),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = subtitle,
                color = if (checked) Color(0xFF90A4AE) else Color(0xFF607D8B),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        AutobotsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = "|",
            color = Color.White.copy(alpha = 0.28f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            text = if (expanded) "\u25B4" else "\u25BE",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Which detector runs the pass. One picker serves whichever detection is switched on. */
@Composable
private fun ProcessWithPicker(
    state: OperatorUiState,
    target: ExtractionTarget,
    selected: DetectorBackend,
    onSelect: (DetectorBackend) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImportPreviewBackends.forEach { option ->
            val unavailable = state.detectorUnavailable[option]
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                enabled = unavailable == null,
                modifier = Modifier.weight(1f),
                label = {
                    // Hardware over model, inside the chip: the two belong to one choice.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = importPreviewBackendLabel(option),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = option.modelTag(target),
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                        )
                    }
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

/**
 * The confidence a face has to reach before the frame is even considered.
 *
 * Disabled on ML Kit rather than hidden: the operator should be able to see that the setting
 * exists and that this backend cannot honour it, which is also why the reason is printed
 * instead of leaving a greyed-out control to be puzzled over.
 */
@Composable
private fun MinScoreRow(
    value: Float,
    enabled: Boolean,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Min score",
                color = Color(0xFFB0BEC5),
                style = MaterialTheme.typography.labelSmall,
            )
            if (!enabled) {
                Text(
                    text = "ML Kit ไม่คืนคะแนน — ใช้ GPU หรือ NPU",
                    color = Color(0xFF78909C),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                )
            }
        }
        ScoreStepButton(label = "−", enabled = enabled) { onStep(-1) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatFaceScore(value),
                color = if (enabled) Color(0xFF80CBC4) else Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelMedium,
            )
            // Both units, because the two audiences differ: an operator reads the
            // probability, and every report written before v0.1.6 quotes the logit.
            Text(
                text = "logit ${formatFaceLogit(value)}",
                color = Color(0xFF78909C),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
            )
        }
        ScoreStepButton(label = "+", enabled = enabled) { onStep(1) }
    }
}

@Composable
private fun ScoreStepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Two decimals, so 0.55 and 0.60 do not read as the same number at a glance. */
internal fun formatFaceScore(value: Float): String = String.format("%.2f", value)

/** The same threshold in the model's own units — what `face_det_lite` actually compares. */
internal fun formatFaceLogit(value: Float): String =
    String.format("%.2f", FaceDetLiteDetector.logitOf(value))

/**
 * Read-only miniature of the zone over a frame of the clip. Tap opens [ZoneEditorPage].
 */
@Composable
private fun ZonePreview(
    zone: DetectZone,
    frame: Bitmap?,
    frameWidth: Int,
    frameHeight: Int,
    onClick: () -> Unit,
) {
    val ratio = if (frameWidth > 0 && frameHeight > 0) {
        frameWidth.toFloat() / frameHeight.toFloat()
    } else {
        16f / 9f
    }
    Box(
        // Height-constrained, not width-constrained: a portrait 4K clip is 9:16, and sizing
        // this by width would push the Estimate card off the screen.
        modifier = Modifier
            .height(ZonePreviewHeight)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2B2B2B))
            .clickable(onClick = onClick),
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = zone.left * size.width
            val top = zone.top * size.height
            val right = zone.right * size.width
            val bottom = zone.bottom * size.height
            val dim = Color.Black.copy(alpha = 0.5f)
            drawRect(dim, size = Size(size.width, top))
            drawRect(dim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(dim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(dim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
            drawRect(
                color = Color(0xFF80CBC4),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2f),
            )
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
