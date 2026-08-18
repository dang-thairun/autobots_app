package com.autobots

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.lifecycleScope
import com.autobots.camera.detection.DetectorProbe
import com.autobots.camera.perf.CamPerf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autobots.camera.delivery.GalleryLauncher
import com.autobots.camera.network.AutobotsServer
import com.autobots.ui.OperatorShellScreen
import com.autobots.ui.OperatorViewModel
import com.autobots.ui.rememberCameraPermissionState

class MainActivity : ComponentActivity() {
    private val operatorViewModel: OperatorViewModel by viewModels()
    private var server: AutobotsServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Which detector backends this build and device can actually run. Off the main
        // thread: initialising QNN compiles the graph, which is not instant.
        if (CamPerf.enabled) {
            lifecycleScope.launch(Dispatchers.Default) {
                DetectorProbe.run(applicationContext)
            }
        }
        enableEdgeToEdge()

        val currentServer = AutobotsServer(applicationContext, operatorViewModel)
        server = currentServer
        currentServer.start()

        val localIp = AutobotsServer.getLocalIpAddress() ?: "127.0.0.1"
        operatorViewModel.setServerIp("$localIp:8080")

        setContent {
            MaterialTheme {
                val state by operatorViewModel.state.collectAsStateWithLifecycle()
                val cameraPermission = rememberCameraPermissionState()

                val videoPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) operatorViewModel.prepareImport(uri)
                }

                LaunchedEffect(cameraPermission.granted) {
                    if (cameraPermission.granted &&
                        !state.isCapturing &&
                        operatorViewModel.pendingStartAfterPermission
                    ) {
                        operatorViewModel.consumePendingStart()
                        operatorViewModel.startCapture()
                    }
                }

                OperatorShellScreen(
                    state = state,
                    cameraPermissionGranted = cameraPermission.granted,
                    pipelineCoordinator = operatorViewModel.pipelineCoordinator(),
                    onToggleCapture = {
                        if (state.isCapturing) {
                            operatorViewModel.stopCapture()
                        } else {
                            operatorViewModel.startCapture()
                        }
                    },
                    onRequestCameraPermission = {
                        operatorViewModel.markPendingStartAfterPermission()
                        cameraPermission.request()
                    },
                    onStreamResolution = operatorViewModel::setStreamResolution,
                    onExtractionTarget = operatorViewModel::setExtractionTarget,
                    onDetectorBackend = operatorViewModel::setDetectorBackend,
                    onRecordingProgress = operatorViewModel::onRecordingProgress,
                    onPhotoDelivered = operatorViewModel::onPhotoDelivered,
                    onExposureReadout = operatorViewModel::onExposureReadout,
                    onOpenGallery = {
                        val uri = state.lastGalleryUri?.let(Uri::parse)
                        GalleryLauncher.open(this@MainActivity, uri)
                    },
                    onImportVideo = {
                        operatorViewModel.clearImportError()
                        videoPicker.launch(arrayOf("video/*"))
                    },
                    onCheckNetworkUrl = operatorViewModel::checkNetworkUrl,
                    onClearNetworkUrlError = operatorViewModel::clearNetworkUrlError,
                    onConfirmImport = operatorViewModel::confirmPendingImport,
                    onCancelImport = operatorViewModel::cancelPendingImport,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        operatorViewModel.stopCapture()
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}
