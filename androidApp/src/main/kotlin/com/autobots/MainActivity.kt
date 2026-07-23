package com.autobots

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                    onRecordingProgress = operatorViewModel::onRecordingProgress,
                    onPhotoDelivered = operatorViewModel::onPhotoDelivered,
                    onExposureReadout = operatorViewModel::onExposureReadout,
                    onOpenGallery = {
                        val uri = state.lastGalleryUri?.let(Uri::parse)
                        GalleryLauncher.open(this@MainActivity, uri)
                    },
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
