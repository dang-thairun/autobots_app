package com.autobots.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autobots.camera.StreamResolution
import com.autobots.camera.VideoPreviewController
import com.autobots.camera.pipeline.CapturePipelineCoordinator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Full-bleed CameraX Preview + Plan B video chunk recording.
 */
@Composable
fun CameraPreviewPane(
    active: Boolean,
    streamResolution: StreamResolution,
    pipelineCoordinator: CapturePipelineCoordinator?,
    pipelinePaused: Boolean = false,
    videoQueueDepth: Int = 0,
    isProcessing: Boolean = false,
    onRecordingProgress: (Int, Long, Long) -> Unit = { _, _, _ -> },
    onExposureReadout: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { VideoPreviewController(context.applicationContext) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val activeState by rememberUpdatedState(active)

    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }

    LaunchedEffect(active, previewView, streamResolution, pipelineCoordinator) {
        if (active) {
            val view = previewView ?: return@LaunchedEffect
            controller.bindPreview(lifecycleOwner, view, streamResolution) {
                if (!activeState) return@bindPreview
                val coordinator = pipelineCoordinator ?: return@bindPreview
                if (!coordinator.hasStorageForRecording()) return@bindPreview
                controller.startChunkRecording(
                    sessionDir = coordinator.sessionDirectory(),
                    maxChunkBytes = streamResolution.chunkTargetBytes,
                    canAcceptChunk = coordinator::canAcceptVideoChunk,
                    onChunkReady = coordinator::onChunkRecorded,
                    onProgress = onRecordingProgress,
                    onPaused = coordinator::onRecorderPaused,
                    onResumed = coordinator::onRecorderResumed,
                )
            }
        }
    }

    LaunchedEffect(active, pipelineCoordinator) {
        if (active) return@LaunchedEffect
        suspendCancellableCoroutine { cont ->
            controller.stopChunkRecording {
                pipelineCoordinator?.onRecorderStopSettled()
                controller.unbindCamera()
                cont.resume(Unit)
            }
        }
    }

    LaunchedEffect(active, pipelinePaused, videoQueueDepth) {
        if (active && pipelinePaused && pipelineCoordinator?.canAcceptVideoChunk() == true) {
            controller.resumeRecordingIfPaused()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC121212)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isProcessing) "Processing chunks…" else "Stopped",
                    color = Color(0xFF9E9E9E),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

data class CameraPermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    return CameraPermissionState(
        granted = granted,
        request = { launcher.launch(Manifest.permission.CAMERA) },
    )
}
