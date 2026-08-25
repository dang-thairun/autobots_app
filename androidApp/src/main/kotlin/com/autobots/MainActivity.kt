package com.autobots

import com.autobots.BuildConfig
import com.autobots.camera.upload.UploadSettings
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
        applyDebugUploadFailures()

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
                val uploadCounts by operatorViewModel.uploadCounts.collectAsStateWithLifecycle()
                val uploadItems by operatorViewModel.uploadItems.collectAsStateWithLifecycle()
                val uploadPaused by operatorViewModel.uploadPaused.collectAsStateWithLifecycle()
                val uploadPauseReason by operatorViewModel.uploadPauseReason.collectAsStateWithLifecycle()
                val uploadFilter by operatorViewModel.uploadFilter.collectAsStateWithLifecycle()
                val uploadConfig by operatorViewModel.uploadConfig.collectAsStateWithLifecycle()
                val uploadAccount by operatorViewModel.uploadAccount.collectAsStateWithLifecycle()
                val uploadDestination by operatorViewModel.uploadDestinationLabel
                    .collectAsStateWithLifecycle()
                val uploadAuth by operatorViewModel.uploadAuth.collectAsStateWithLifecycle()
                val uploadRemembered by operatorViewModel.rememberedCredentials
                    .collectAsStateWithLifecycle()
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
                    onDetectZone = operatorViewModel::setDetectZone,
                    onShutterCeiling = operatorViewModel::setShutterCeilingFps,
                    onStepExposure = operatorViewModel::stepExposure,
                    onCameraCapabilities = operatorViewModel::onCameraCapabilities,
                    onRecordingProgress = operatorViewModel::onRecordingProgress,
                    onPhotoDelivered = operatorViewModel::onPhotoDelivered,
                    onExposureReadout = operatorViewModel::onExposureReadout,
                    onOpenGallery = { GalleryLauncher.open(this@MainActivity) },
                    onImportVideo = {
                        operatorViewModel.clearImportError()
                        videoPicker.launch(arrayOf("video/*"))
                    },
                    onCheckNetworkUrl = operatorViewModel::checkNetworkUrl,
                    onClearNetworkUrlError = operatorViewModel::clearNetworkUrlError,
                    onConfirmImport = operatorViewModel::confirmPendingImport,
                    onCancelImport = operatorViewModel::cancelPendingImport,
                    uploadCounts = uploadCounts,
                    uploadItems = uploadItems,
                    onRetryFailedUploads = operatorViewModel::retryFailedUploads,
                    uploadPaused = uploadPaused,
                    uploadPauseReason = uploadPauseReason,
                    uploadFilter = uploadFilter,
                    onSetUploadFilter = operatorViewModel::setUploadFilter,
                    onClearUploadQueue = operatorViewModel::clearUploadQueue,
                    uploadDestinationLabel = uploadDestination,
                    onSetUploadPaused = operatorViewModel::setUploadPaused,
                    uploadConfig = uploadConfig,
                    uploadAccount = uploadAccount,
                    uploadAuth = uploadAuth,
                    uploadRemembered = uploadRemembered,
                    onSaveUploadConfig = operatorViewModel::saveUploadConfig,
                    onUploadSignIn = operatorViewModel::signInToUpload,
                    onUploadSignOut = operatorViewModel::signOutOfUpload,
                    onLoadUploadEvents = operatorViewModel::loadUploadEvents,
                    onSetUploadEventScope = operatorViewModel::setUploadEventScope,
                    onSetUploadEventSearch = operatorViewModel::setUploadEventSearch,
                    onSelectUploadEvent = operatorViewModel::selectUploadEvent,
                    onClearUploadConfig = operatorViewModel::clearUploadConfig,
                    startDestination = if (BuildConfig.DEBUG) {
                        intent?.getStringExtra("dest")
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Debug-only failure injection for [com.autobots.camera.upload.FakeUploadTransport]:
     *
     * ```
     * adb shell am start -n com.autobots.camera/com.autobots.MainActivity \
     *     --es dest upload --ei failComplete 5
     * ```
     *
     * Counters, not rates, so a run is reproducible. This is the only way to reach the
     * `Uploaded → Success` retry path on a device that refuses `adb shell input`.
     */
    private fun applyDebugUploadFailures() {
        if (!BuildConfig.DEBUG) return
        val failPut = intent?.getIntExtra("failPut", -1) ?: -1
        val failComplete = intent?.getIntExtra("failComplete", -1) ?: -1
        val delayMs = intent?.getIntExtra("delayMs", -1) ?: -1
        val requeue = intent?.getBooleanExtra("requeueAll", false) ?: false
        // Feeds a provisioning payload through the exact code the QR scanner uses, so the
        // parse-and-persist path is testable on a device that cannot be pointed at a QR.
        intent?.getStringExtra("config")?.let { operatorViewModel.applyScannedUploadConfig(it) }
        // Sign-in cannot be typed on this device either. `--es signin 'user:password[:remember]'`
        // runs the real login call — same code path, same error handling, no keyboard.
        intent?.getStringExtra("signin")?.let { spec ->
            val parts = spec.split(':')
            operatorViewModel.signInToUpload(
                username = parts.getOrElse(0) { "" },
                password = parts.getOrElse(1) { "" },
                remember = parts.getOrNull(2) == "remember",
            )
        }
        intent?.getStringExtra("fake")?.let { UploadSettings(this).setFakeTransport(it == "on") }
        intent?.getIntExtra("enqueueExisting", 0)?.takeIf { it > 0 }
            ?.let { operatorViewModel.debugEnqueueExistingPhotos(it) }
        if (failPut < 0 && failComplete < 0 && delayMs < 0 && !requeue) return
        val settings = UploadSettings(this)
        if (failPut >= 0) settings.setFakeFailPut(failPut)
        if (failComplete >= 0) settings.setFakeFailComplete(failComplete)
        if (delayMs >= 0) settings.setFakeDelayMs(delayMs)
        if (requeue) operatorViewModel.debugRequeueUploads()
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
