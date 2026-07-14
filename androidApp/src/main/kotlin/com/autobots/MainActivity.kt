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
import com.autobots.ui.OperatorShellScreen
import com.autobots.ui.OperatorViewModel
import com.autobots.ui.rememberCameraPermissionState

class MainActivity : ComponentActivity() {
    private val operatorViewModel: OperatorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    onCaptureMode = operatorViewModel::setCaptureMode,
                    onFaceResult = operatorViewModel::onFaceResult,
                    onBurstComplete = operatorViewModel::onBurstComplete,
                    onPhotoDelivered = operatorViewModel::onPhotoDelivered,
                    onExposureReadout = operatorViewModel::onExposureReadout,
                    onArmThreshold = operatorViewModel::setArmThreshold,
                    onFireThreshold = operatorViewModel::setFireThreshold,
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
}
