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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autobots.camera.CaptureMode
import com.autobots.camera.PreviewCameraController
import com.autobots.camera.detection.FaceFrameResult
import com.autobots.camera.detection.NormalizedFaceBox

/**
 * Full-bleed CameraX Preview + face overlay + burst trigger hook.
 */
@Composable
fun CameraPreviewPane(
    active: Boolean,
    faces: List<NormalizedFaceBox>,
    subjectIndex: Int?,
    armThreshold: Float,
    fireThreshold: Float,
    burstShotCount: Int,
    captureMode: CaptureMode,
    onFaceResult: (FaceFrameResult) -> Boolean,
    onBurstComplete: (savedCount: Int) -> Unit,
    onPhotoDelivered: (uri: String) -> Unit,
    onExposureReadout: (line: String) -> Unit = {},
    onFrameEncoded: ((ByteArray) -> Unit)? = null,
    showFaceOverlay: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { PreviewCameraController(context.applicationContext) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        controller.setPhotoDeliveredListener { uri ->
            onPhotoDelivered(uri.toString())
        }
        controller.setExposureReadoutListener { readout ->
            onExposureReadout(readout.line)
        }
        controller.setFrameEncodedListener { jpeg ->
            onFrameEncoded?.invoke(jpeg)
        }
        onDispose { controller.shutdown() }
    }

    LaunchedEffect(armThreshold) {
        controller.setArmThreshold(armThreshold)
    }
    LaunchedEffect(fireThreshold) {
        controller.setFireThreshold(fireThreshold)
    }
    LaunchedEffect(burstShotCount) {
        controller.setBurstShotCount(burstShotCount)
    }
    LaunchedEffect(captureMode) {
        controller.setCaptureMode(captureMode)
        controller.setBurstShotCount(burstShotCount)
    }

    LaunchedEffect(active, previewView, captureMode) {
        val view = previewView ?: return@LaunchedEffect
        if (active) {
            controller.setArmThreshold(armThreshold)
            controller.setFireThreshold(fireThreshold)
            controller.setBurstShotCount(burstShotCount)
            controller.setCaptureMode(captureMode)
            controller.bindPreview(lifecycleOwner, view) { result ->
                mainExecutor.execute {
                    val shouldFire = onFaceResult(result)
                    if (shouldFire) {
                        controller.triggerBurst { saved ->
                            mainExecutor.execute { onBurstComplete(saved) }
                        }
                    }
                }
            }
        } else {
            controller.unbind()
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

        if (active && showFaceOverlay) {
            FaceOverlay(
                faces = faces,
                subjectIndex = subjectIndex,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC121212)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Stopped",
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
