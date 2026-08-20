package com.autobots.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobots.camera.AutobotsApp
import com.autobots.camera.DetectorBackend
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.StreamResolution
import com.autobots.camera.upload.RunxAuthClient
import com.autobots.camera.upload.UploadAuthUiState
import com.autobots.camera.upload.UploadConfig
import com.autobots.camera.upload.UploadSession
import com.autobots.camera.upload.UploadSettings
import com.autobots.camera.upload.UploadItem
import com.autobots.camera.upload.UploadQueueCounts
import com.autobots.camera.pipeline.CapturePipelineCoordinator

private val CardBg = Color.Gray.copy(alpha = 0.25f)
private val CardShape = RoundedCornerShape(12.dp)

/**
 * Tall enough for the worst routine layout — title, a two-line status, the bar and the
 * summary — so the card holds its size as the status line grows and shrinks.
 */
private val ProcessingCardMinHeight = 118.dp

private object OverlayPages {
    const val Controls = 0
    const val CleanPreview = 1
    const val Count = 2
}

private enum class OperatorDestination {
    Home,
    LiveCapture,
    SessionHistory,
    ImportPreview,
    NetworkUrl,
    UploadQueue,
    UploadSettings,
}

@Composable
fun OperatorShellScreen(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    pipelineCoordinator: CapturePipelineCoordinator?,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onExtractionTarget: (ExtractionTarget) -> Unit,
    onDetectorBackend: (DetectorBackend) -> Unit,
    onRecordingProgress: (Int, Long, Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") onPhotoDelivered: (String) -> Unit,
    onExposureReadout: (String) -> Unit,
    onOpenGallery: () -> Unit,
    onImportVideo: () -> Unit,
    onCheckNetworkUrl: (String) -> Unit,
    onClearNetworkUrlError: () -> Unit,
    onConfirmImport: (ExtractionTarget, DetectorBackend, Long?, Long?) -> Unit,
    onCancelImport: () -> Unit,
    uploadCounts: UploadQueueCounts,
    uploadItems: List<UploadItem>,
    onRetryFailedUploads: () -> Unit,
    uploadPaused: Boolean,
    uploadPauseReason: String?,
    uploadDestinationLabel: String,
    onSetUploadPaused: (Boolean) -> Unit,
    uploadConfig: UploadConfig,
    uploadAccount: UploadSession.SignedIn?,
    uploadAuth: UploadAuthUiState,
    uploadRemembered: UploadSettings.Credentials?,
    onSaveUploadConfig: (UploadConfig) -> Unit,
    onUploadSignIn: (username: String, password: String, remember: Boolean) -> Unit,
    onUploadSignOut: () -> Unit,
    onLoadUploadEvents: () -> Unit,
    onSetUploadEventScope: (RunxAuthClient.EventScope) -> Unit,
    onSetUploadEventSearch: (String) -> Unit,
    onSelectUploadEvent: (RunxAuthClient.EventSummary) -> Unit,
    onClearUploadConfig: () -> Unit,
    /**
     * Debug-only entry point, set from an `adb am start --es dest <name>` extra.
     * This device refuses `adb shell input` (MIUI gates event injection behind a setting
     * that will not stay on), so without this there is no way to reach a screen from a
     * script. Ignored in release builds — see [MainActivity].
     */
    startDestination: String? = null,
) {
    var destination by remember {
        mutableStateOf(destinationForArg(startDestination) ?: OperatorDestination.Home)
    }

    val goHome = {
        if (destination == OperatorDestination.LiveCapture && state.isCapturing) {
            onToggleCapture()
        }
        if (destination == OperatorDestination.ImportPreview) {
            onCancelImport()
        }
        destination = OperatorDestination.Home
    }

    LaunchedEffect(state.showImportPreview) {
        if (state.showImportPreview) {
            destination = OperatorDestination.ImportPreview
        } else if (destination == OperatorDestination.ImportPreview) {
            destination = OperatorDestination.Home
        }
    }

    BackHandler(enabled = destination != OperatorDestination.Home) {
        goHome()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
    ) {
        when (destination) {
            OperatorDestination.Home -> OperatorHomePage(
                state = state,
                uploadCounts = uploadCounts,
                uploadAccount = uploadAccount,
                uploadConfig = uploadConfig,
                uploadPaused = uploadPaused,
                uploadDestinationLabel = uploadDestinationLabel,
                capturing = state.isCapturing,
                onSetUploadPaused = onSetUploadPaused,
                onLiveCapture = { destination = OperatorDestination.LiveCapture },
                onBrowseVideo = onImportVideo,
                onUploadQueue = { destination = OperatorDestination.UploadQueue },
                onNetworkUrl = {
                    onClearNetworkUrlError()
                    destination = OperatorDestination.NetworkUrl
                },
                onSessionHistory = { destination = OperatorDestination.SessionHistory },
                onOpenGallery = onOpenGallery,
            )
            OperatorDestination.LiveCapture -> OperatorLiveCapturePage(
                state = state,
                cameraPermissionGranted = cameraPermissionGranted,
                pipelineCoordinator = pipelineCoordinator,
                onBack = goHome,
                onToggleCapture = onToggleCapture,
                onRequestCameraPermission = onRequestCameraPermission,
                onStreamResolution = onStreamResolution,
                onExtractionTarget = onExtractionTarget,
                onDetectorBackend = onDetectorBackend,
                onRecordingProgress = onRecordingProgress,
                onExposureReadout = onExposureReadout,
            )
            OperatorDestination.SessionHistory -> ChunkHistoryPage(
                sessions = state.sessionHistory,
                onBack = goHome,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
            OperatorDestination.ImportPreview -> ImportPreviewPage(
                state = state,
                onBack = goHome,
                onExtract = { target, backend, startMs, endMs ->
                    onConfirmImport(target, backend, startMs, endMs)
                    destination = OperatorDestination.Home
                },
                onCancel = goHome,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
            OperatorDestination.NetworkUrl -> NetworkUrlPage(
                state = state,
                onBack = goHome,
                onCheck = onCheckNetworkUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
            OperatorDestination.UploadSettings -> UploadSettingsPage(
                config = uploadConfig,
                account = uploadAccount,
                auth = uploadAuth,
                remembered = uploadRemembered,
                onSave = onSaveUploadConfig,
                onSignIn = onUploadSignIn,
                onSignOut = onUploadSignOut,
                onLoadEvents = onLoadUploadEvents,
                onSetScope = onSetUploadEventScope,
                onSetSearch = onSetUploadEventSearch,
                onSelectEvent = onSelectUploadEvent,
                onClear = onClearUploadConfig,
                // Back from settings returns to the queue, not Home — settings is reached
                // from there and that is where the effect of a change shows up.
                onBack = { destination = OperatorDestination.UploadQueue },
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
            OperatorDestination.UploadQueue -> UploadQueuePage(
                counts = uploadCounts,
                items = uploadItems,
                paused = uploadPaused,
                pauseReason = uploadPauseReason,
                destinationLabel = uploadDestinationLabel,
                signedInAs = uploadAccount?.username,
                eventTitle = uploadConfig.eventTitle,
                eventId = uploadConfig.eventId,
                onBack = goHome,
                onRetryFailed = onRetryFailedUploads,
                onSetPaused = onSetUploadPaused,
                onOpenSettings = { destination = OperatorDestination.UploadSettings },
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
        }
    }
}

@Composable
private fun OperatorHomePage(
    state: OperatorUiState,
    uploadCounts: UploadQueueCounts,
    uploadAccount: UploadSession.SignedIn?,
    uploadConfig: UploadConfig,
    uploadPaused: Boolean,
    uploadDestinationLabel: String,
    capturing: Boolean,
    onSetUploadPaused: (Boolean) -> Unit,
    onLiveCapture: () -> Unit,
    onBrowseVideo: () -> Unit,
    onUploadQueue: () -> Unit,
    onNetworkUrl: () -> Unit,
    onSessionHistory: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIdentityCard(state = state)
        ProcessingStatusCard(
            state = state,
            idleLine = "No video to process",
        )
        UploadStatusCard(
            counts = uploadCounts,
            account = uploadAccount,
            config = uploadConfig,
            paused = uploadPaused,
            destinationLabel = uploadDestinationLabel,
            capturing = capturing,
            onSetAutoUpload = { onSetUploadPaused(!it) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        val pipelineBusy = state.isImporting || state.isProcessing || state.isDownloading
        HomeMenuButton(
            label = "Live capture",
            enabled = !pipelineBusy,
            onClick = onLiveCapture,
        )
        HomeMenuButton(
            label = if (state.isImporting) "Browse Video…" else "Browse Video",
            enabled = state.canImportVideo,
            onClick = onBrowseVideo,
        )
        HomeMenuButton(
            label = "Network URL",
            enabled = state.canImportVideo,
            onClick = onNetworkUrl,
        )
        HomeMenuButton(
            label = "Session History",
            onClick = onSessionHistory,
        )
        HomeMenuButton(
            label = if (state.keptPhotoCount > 0) {
                "Gallery (${state.keptPhotoCount})"
            } else {
                "Gallery"
            },
            enabled = !pipelineBusy,
            onClick = onOpenGallery,
        )
        HomeMenuButton(
            // Below Gallery on purpose: delivery to the phone comes first, delivery onward
            // second. The count follows the same shape as Gallery's, and counts only
            // outstanding rows — a queue of 5,000 already-uploaded photos is not news.
            label = uploadCounts.badgeLabel?.let { "Upload ($it)" } ?: "Upload",
            onClick = onUploadQueue,
        )
    }
}

@Composable
private fun AppIdentityCard(
    state: OperatorUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = AutobotsApp.banner,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "IP ${state.serverIp}",
            color = Color(0xFF69F0AE),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Maps the debug `dest` extra onto a screen. Unknown or absent → null (stay Home). */
private fun destinationForArg(arg: String?): OperatorDestination? = when (arg?.lowercase()) {
    "upload" -> OperatorDestination.UploadQueue
    "uploadsettings" -> OperatorDestination.UploadSettings
    "history" -> OperatorDestination.SessionHistory
    "network" -> OperatorDestination.NetworkUrl
    "live" -> OperatorDestination.LiveCapture
    else -> null
}

@Composable
private fun HomeMenuButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color.White.copy(alpha = 0.14f),
            disabledContentColor = Color.White.copy(alpha = 0.55f),
        ),
    ) {
        Text(text = label, maxLines = 1)
    }
}

@Composable
private fun OperatorLiveCapturePage(
    state: OperatorUiState,
    cameraPermissionGranted: Boolean,
    pipelineCoordinator: CapturePipelineCoordinator?,
    onBack: () -> Unit,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onExtractionTarget: (ExtractionTarget) -> Unit,
    onDetectorBackend: (DetectorBackend) -> Unit,
    onRecordingProgress: (Int, Long, Long) -> Unit,
    onExposureReadout: (String) -> Unit,
) {
    val previewActive = state.isCapturing && cameraPermissionGranted
    KeepScreenOn(active = state.isCapturing)
    var settingsExpanded by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { OverlayPages.Count })

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewPane(
            active = previewActive,
            streamResolution = state.streamResolution,
            pipelineCoordinator = pipelineCoordinator,
            pipelinePaused = state.pipelinePaused,
            videoQueueDepth = state.videoQueueDepth,
            isProcessing = state.isProcessing,
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
                        pipelineExpanded = settingsExpanded,
                        onBack = onBack,
                        onPipelineToggle = { settingsExpanded = !settingsExpanded },
                        onToggleCapture = onToggleCapture,
                        onRequestCameraPermission = onRequestCameraPermission,
                        onStreamResolution = onStreamResolution,
                        onExtractionTarget = onExtractionTarget,
                        onDetectorBackend = onDetectorBackend,
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
    pipelineExpanded: Boolean,
    onBack: () -> Unit,
    onPipelineToggle: () -> Unit,
    onToggleCapture: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onExtractionTarget: (ExtractionTarget) -> Unit,
    onDetectorBackend: (DetectorBackend) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "← Home",
            color = Color(0xFFB0BEC5),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        CompactStatusCard(
            state = state,
            cameraPermissionGranted = cameraPermissionGranted,
            pipelineExpanded = pipelineExpanded,
            onPipelineToggle = onPipelineToggle,
            onStreamResolution = onStreamResolution,
            onExtractionTarget = onExtractionTarget,
            onDetectorBackend = onDetectorBackend,
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
            ProcessingStatusCard(state = state)

            Button(
                onClick = {
                    when {
                        state.isCapturing -> onToggleCapture()
                        state.canStartCapture && cameraPermissionGranted -> onToggleCapture()
                        !cameraPermissionGranted -> onRequestCameraPermission()
                    }
                },
                enabled = state.isCapturing || state.canStartCapture || !cameraPermissionGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        state.isCapturing -> "Stop"
                        state.isProcessing -> "Processing…"
                        cameraPermissionGranted -> "Start"
                        else -> "Allow & Start"
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Hold the screen awake while the camera is rolling.
 *
 * The device is on a tripod for the length of an event with nobody touching it, and a screen
 * timeout mid-session locks the phone. Scoped to capture only — the upload queue deliberately
 * does **not** hold the screen on, because a queue that only drains while someone is watching
 * is a queue that has not been proven to work.
 */
@Composable
private fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Upload, on the screen the operator actually looks at.
 *
 * The queue screen already says all of this, but nobody opens it while shooting. Both halves
 * are here for the same reason: **who** can be the wrong account or none at all, **where** can
 * be yesterday's event, and neither mistake looks like a mistake until the photos are missing
 * from the event they were supposed to be in.
 */
@Composable
private fun UploadStatusCard(
    counts: UploadQueueCounts,
    account: UploadSession.SignedIn?,
    config: UploadConfig,
    paused: Boolean,
    destinationLabel: String,
    capturing: Boolean,
    onSetAutoUpload: (Boolean) -> Unit,
) {
    val ready = account != null && config.eventId.isNotBlank()
    val notifications = rememberNotificationPermissionState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Upload · $destinationLabel",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Locked while the camera is rolling: whether a session uploads is a decision
            // taken before it starts, not something to flip halfway through and then have to
            // reason about which photos went where. The queue screen's Pause still works —
            // that one is the emergency stop, not a policy switch.
            Switch(
                checked = !paused,
                onCheckedChange = { on ->
                    // Ask at the moment the notification would start mattering, not at launch.
                    if (on) notifications.request()
                    onSetAutoUpload(on)
                },
                enabled = !capturing,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF26A69A),
                    checkedTrackColor = Color(0xFF26A69A).copy(alpha = 0.4f),
                ),
            )
        }
        Text(
            text = when {
                account == null -> "Not signed in"
                config.eventId.isBlank() -> "${account.username} · no event selected"
                else -> "${account.username} → ${config.eventTitle.ifBlank { config.eventId }}"
            },
            color = if (ready) Color(0xFFA5D6A7) else Color(0xFFFFCC80),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            // Fraction of the queue that is finished, not of the photo in flight. Per-file
            // progress would jump around as the worker moves between rows; this only ever
            // grows, which is what someone glancing at it wants to know.
            progress = {
                if (counts.total == 0) 0f else counts.success.toFloat() / counts.total
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = if (paused) Color(0xFFFFB74D) else Color(0xFF26A69A),
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Text(
            text = buildString {
                when {
                    counts.total == 0 -> append("Nothing queued")
                    // "All N uploaded" has to mean all of them. A row that was given up on
                    // still counts in the total, so claiming the full number here would
                    // report a photo as delivered that never went anywhere.
                    counts.outstanding == 0 && counts.abandoned == 0 ->
                        append("All ${counts.total} uploaded")
                    else -> append("${counts.success} of ${counts.total} uploaded")
                }
                if (counts.uploading > 0) append(" · sending")
                if (counts.failed > 0) append(" · ${counts.failed} retrying")
                if (counts.abandoned > 0) append(" · ${counts.abandoned} given up")
                if (paused) append(if (capturing) " · auto-upload off" else " · paused")
            },
            color = when {
                counts.abandoned > 0 -> Color(0xFFEF9A9A)
                paused -> Color(0xFFFFCC80)
                else -> Color(0xFF78909C)
            },
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ProcessingStatusCard(
    state: OperatorUiState,
    idleLine: String = "No processing",
) {
    val active = state.isProcessing || state.isImporting || state.isDownloading
    val title = buildString {
        append("${state.extractionTarget.label} extraction")
        state.extractionResolutionLabel?.let { append(" · $it") }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Floor rather than a hard height: the status line grows to two lines while the
            // splitter waits on the queue, and the import-failed message can take three. A
            // floor keeps the card from resizing on every state flip without clipping those.
            .heightIn(min = ProcessingCardMinHeight)
            .clip(CardShape)
            .background(CardBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = when {
                state.importError != null -> "Import failed: ${state.importError}"
                state.isDownloading ->
                    "Downloading ${state.importName ?: "video"} · ${state.downloadPercent}%"
                state.isImporting -> state.importLine
                state.isProcessing -> state.processingLine
                else -> idleLine
            },
            color = when {
                state.importError != null -> Color(0xFFEF9A9A)
                active -> Color(0xFF80CBC4)
                else -> Color(0xFF78909C)
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 3,
        )
        if (state.throughputLine.isNotEmpty()) {
            Text(
                text = state.throughputLine,
                color = if (state.isThroughputTooSlow) Color(0xFFFF7043) else Color(0xFF90A4AE),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        }

        LinearProgressIndicator(
            // One bar for the whole job. Split progress is deliberately not shown here: it is
            // throttled by the video queue and sits still for ~11 s at a time, which reads as
            // a hang. processingPercent advances every sampled frame instead.
            progress = {
                when {
                    state.isDownloading -> state.downloadPercent / 100f
                    state.isImporting || state.isProcessing -> state.processingPercent / 100f
                    else -> 0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = Color(0xFF26A69A),
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Text(
            text = when {
                state.isDownloading ->
                    "${state.downloadPercent}% download · ${state.extractionTarget.keptNoun} found ${state.facesKept}"
                !active -> "Idle · ${state.extractionTarget.keptNoun} found ${state.facesKept}"
                else -> "${state.processingPercent}% overall · ${state.extractionTarget.keptNoun} found ${state.facesKept}"
            },
            color = Color(0xFF78909C),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
        )
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
    pipelineExpanded: Boolean,
    onPipelineToggle: () -> Unit,
    onStreamResolution: (StreamResolution) -> Unit,
    onExtractionTarget: (ExtractionTarget) -> Unit,
    onDetectorBackend: (DetectorBackend) -> Unit,
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
                text = "${state.streamResolution.label} · ${state.extractionTarget.label} · IP ${state.serverIp}",
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
                tooltip = "Chunks — วิดีโอที่อัดเสร็จ (rotate ที่ 50 MB ทุก resolution)",
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
                label = state.extractionTarget.statLabel,
                value = "${state.facesKept}",
                tooltip = when (state.extractionTarget) {
                    ExtractionTarget.Face -> "เฟรมที่คัดได้ — มีหน้าและชัดพอ"
                    ExtractionTarget.Pose -> "เฟรมที่คัดได้ — มีท่าทางและชัดพอ"
                },
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

        if (pipelineExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Record chunks → extract sharp frames (Face or Pose, experimental)",
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExtractionTarget.entries.forEach { target ->
                        FilterChip(
                            selected = state.extractionTarget == target,
                            onClick = { onExtractionTarget(target) },
                            enabled = !state.isCapturing,
                            modifier = Modifier.weight(1f),
                            label = { Text(target.label) },
                        )
                    }
                }

                // Detector bench (0.1.4). Import the same clip once per backend and the
                // resulting perf_report.json files differ in exactly one variable.
                Text(
                    text = "Detector — same clip, one backend at a time",
                    color = Color(0xFF90A4AE),
                    style = MaterialTheme.typography.labelSmall,
                )
                DetectorBackend.entries.filter { it.selectableInUi }.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { candidate ->
                            FilterChip(
                                selected = state.detectorBackend == candidate,
                                onClick = { onDetectorBackend(candidate) },
                                // Greyed out rather than hidden: knowing the NPU exists but is
                                // unavailable here is the useful signal — the reason is spelled
                                // out below the row.
                                enabled = !state.isCapturing &&
                                    !state.detectorUnavailable.containsKey(candidate),
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(candidate.label, style = MaterialTheme.typography.labelSmall)
                                },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                state.detectorUnavailable
                    .filterKeys { it.selectableInUi }
                    .forEach { (backend, reason) ->
                        Text(
                            text = "${backend.label}: $reason",
                            color = Color(0xFFB0704A),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPipelineToggle)
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Video pipeline",
                color = Color(0xFF90A4AE),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = if (pipelineExpanded) " · Hide" else " · Show",
                color = Color(0xFFB0BEC5),
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
            onExtractionTarget = {},
            onDetectorBackend = {},
            onRecordingProgress = { _, _, _ -> },
            onPhotoDelivered = {},
            onExposureReadout = {},
            onOpenGallery = {},
            onImportVideo = {},
            onCheckNetworkUrl = {},
            onClearNetworkUrlError = {},
            onConfirmImport = { _, _, _, _ -> },
            onCancelImport = {},
            uploadCounts = UploadQueueCounts(),
            uploadItems = emptyList(),
            onRetryFailedUploads = {},
            uploadPaused = false,
            uploadPauseReason = null,
            uploadDestinationLabel = "local test sink",
            onSetUploadPaused = {},
            uploadConfig = UploadConfig(),
            uploadAccount = null,
            uploadAuth = UploadAuthUiState(),
            uploadRemembered = null,
            onSaveUploadConfig = {},
            onUploadSignIn = { _, _, _ -> },
            onUploadSignOut = {},
            onLoadUploadEvents = {},
            onSetUploadEventScope = {},
            onSetUploadEventSearch = {},
            onSelectUploadEvent = {},
            onClearUploadConfig = {},
        )
    }
}
