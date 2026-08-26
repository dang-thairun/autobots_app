package com.autobots.ui

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autobots.camera.ChunkRecordingProgress
import com.autobots.camera.PipelineSessionRecord
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import com.autobots.camera.CameraCapabilities
import com.autobots.camera.DetectZone
import com.autobots.camera.detection.FaceDetLiteDetector
import com.autobots.camera.DetectorBackend
import com.autobots.camera.detection.DetectorAvailability
import com.autobots.camera.ExtractionTarget
import com.autobots.camera.PipelineStats
import com.autobots.camera.SessionSource
import com.autobots.camera.StreamResolution
import com.autobots.camera.capture.ImportedVideoSplitter
import com.autobots.camera.compactResolutionLabel
import com.autobots.camera.formatChunkBytes
import com.autobots.camera.formatDurationMs
import com.autobots.camera.load.DeviceLoadReader
import com.autobots.camera.load.DeviceLoadSnapshot
import com.autobots.camera.network.RemoteVideoFetcher
import com.autobots.camera.pipeline.CapturePipelineCoordinator
import com.autobots.camera.upload.uploadDestinationLabel
import com.autobots.camera.upload.RunxAuthClient
import com.autobots.camera.upload.UploadAuthUiState
import com.autobots.camera.upload.UploadCandidate
import com.autobots.camera.upload.UploadConfig
import com.autobots.camera.upload.UploadItem
import com.autobots.camera.upload.UploadQueueCounts
import com.autobots.camera.upload.UploadRepository
import com.autobots.camera.upload.UploadScheduler
import com.autobots.camera.upload.UploadStatus
import com.autobots.camera.upload.UploadSession
import com.autobots.camera.upload.UploadSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class OperatorUiState(
    val isCapturing: Boolean = false,
    val streamResolution: StreamResolution = StreamResolution.Fhd,
    val extractionTarget: ExtractionTarget = ExtractionTarget.Face,
    val detectorBackend: DetectorBackend = DetectorBackend.DEFAULT,
    /** Backend → why it cannot run here, or null when it can. See [DetectorAvailability]. */
    val detectorUnavailable: Map<DetectorBackend, String> = emptyMap(),
    val videoChunksRecorded: Int = 0,
    val videoQueueDepth: Int = 0,
    val facesKept: Int = 0,
    val facesSkipped: Int = 0,
    val lastChunkProcessMs: Long = 0,
    val storageFreeMb: Long = 0,
    val pipelinePaused: Boolean = false,
    val keptPhotoCount: Int = 0,
    val lastGalleryUri: String? = null,
    val thermalLabel: String = "—",
    val thermalLevel: Int = -1,
    val usedRamMb: Long = 0,
    val availRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val exposureLine: String = "—mm  ·  —  ·  ISO —",
    /** Read at every bind; null until the camera has been bound once. */
    val cameraCapabilities: CameraCapabilities? = null,
    /** Longest exposure AE may choose, as a pinned frame rate. Null lets AE decide. */
    val shutterCeilingFps: Int? = null,
    /** AE bias in device steps. Meaningful only through [CameraCapabilities]. */
    val exposureIndex: Int = 0,
    val serverIp: String = "—",
    val storageBlocked: Boolean = false,
    val recordingProgress: ChunkRecordingProgress = ChunkRecordingProgress(),
    val isProcessing: Boolean = false,
    val processingPercent: Int = 0,
    val currentChunkPercent: Int = 0,
    val chunksProcessed: Int = 0,
    val processingChunkName: String? = null,
    val imageQueuePending: Int = 0,
    val isImporting: Boolean = false,
    val importPercent: Int = 0,
    /** Projected chunk total while importing; 0 when unknown. */
    val expectedChunks: Int = 0,
    val importName: String? = null,
    val importError: String? = null,
    val lastRealtimeRatio: Float = 0f,
    val avgPhotoLatencyMs: Long = 0,
    val sessionHistory: List<PipelineSessionRecord> = emptyList(),
    val isPreparingImport: Boolean = false,
    val pendingImport: PendingVideoImport? = null,
    /** Capture Zone for the next run. Null means the whole frame is scanned. */
    val detectZone: DetectZone? = null,
    /** Confidence a face must reach to count. LiteRT backends only — ML Kit has no score. */
    val minFaceScore: Float = FaceDetLiteDetector.DEFAULT_SCORE_THRESHOLD,
    /** A frame from the pending clip, drawn under the zone editor. Null while loading. */
    val pendingImportFrame: Bitmap? = null,
    val isCheckingNetworkUrl: Boolean = false,
    val networkUrlError: String? = null,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
) {
    val deviceLoadLine: String
        get() = if (totalRamMb > 0) {
            "RAM ${formatRam(usedRamMb)}/${formatRam(totalRamMb)} (free ${formatRam(availRamMb)})"
        } else {
            "RAM —"
        }

    val statusLine: String
        get() = if (isCapturing) "" else "IDLE"

    val recordingLine: String
        get() {
            if (!isCapturing) return ""
            if (pipelinePaused) return "PAUSED · queue full — waiting to resume"
            val progress = recordingProgress
            if (progress.chunkIndex <= 0) return "REC · starting chunk…"
            val size = formatChunkBytes(progress.bytesWritten)
            val target = formatChunkBytes(progress.targetBytes)
            val rate = progress.rateKbPerSec
            val rateLine = if (rate > 0) " · ~${rate} KB/s" else ""
            return "REC #${progress.chunkIndex} · $size / $target · ${progress.elapsedSec}s$rateLine"
        }

    val processingLine: String
        get() {
            if (!isProcessing) return ""
            val chunkLabel = processingChunkName?.substringBefore('.') ?: "chunk"
            val gallery = imageQueuePending
            return buildString {
                append("Processing $chunkLabel")
                append(" · ${chunksProcessed}/${videoChunksRecorded} chunks")
                if (currentChunkPercent in 1..99) append(" · scan $currentChunkPercent%")
                if (gallery > 0) append(" · save $gallery")
            }
        }

    val canStartCapture: Boolean
        get() = !isCapturing && !isProcessing && !isImporting && !isDownloading

    /** Importing needs the pipeline free, but not the camera. */
    val canImportVideo: Boolean
        get() = !isCapturing && !isProcessing && !isImporting &&
            !isPreparingImport && pendingImport == null &&
            !isCheckingNetworkUrl && !isDownloading

    val showImportPreview: Boolean
        get() = isPreparingImport || pendingImport != null

    val importLine: String
        get() {
            if (!isImporting) return ""
            val name = importName ?: "video"
            // Splitting spends ~95% of its time blocked on the video queue by design, so the
            // percentage is reported as a chunk count with an explicit wait state rather than
            // a number that looks frozen.
            val total = if (expectedChunks > 0) "~$expectedChunks" else "?"
            return buildString {
                append("Importing $name · split $videoChunksRecorded/$total chunks")
                if (videoQueueDepth >= CapturePipelineCoordinator.VIDEO_QUEUE_CAPACITY) {
                    append("\nwaiting for extractor")
                }
            }
        }

    /**
     * Extraction time over footage length, for every source.
     *
     * It used to be suppressed for imports — and, because the check looked at the whole
     * session list, for live capture too once anything had ever been imported. The number is
     * exactly as meaningful for a browsed file or a network URL: it is what says whether this
     * phone can chew through a clip faster than the clip is long.
     */
    val throughputLine: String
        get() {
            if (lastRealtimeRatio <= 0f) return ""
            return buildString {
                append(String.format("%.2fx realtime", lastRealtimeRatio))
                if (avgPhotoLatencyMs > 0) {
                    append(String.format(" · photo in ~%.1fs", avgPhotoLatencyMs / 1000.0))
                }
                if (isThroughputTooSlow) append(" · TOO SLOW, queue will back up")
            }
        }

    /** Frame rates worth offering as a shutter ceiling, from what the device advertises. */
    val shutterCeilingChoices: List<Int>
        get() {
            val caps = cameraCapabilities ?: return emptyList()
            return SHUTTER_CEILING_FPS.filter { fps ->
                caps.supportedFrameRateRanges.any { it.lower <= fps && it.upper >= fps }
            }
        }

    /** `+1.0 EV` — the index means nothing to an operator, the stops do. */
    val exposureLabel: String
        get() {
            val step = cameraCapabilities?.aeCompensationStepEv ?: return "0 EV"
            val ev = exposureIndex * step
            return when {
                ev > 0 -> String.format("+%.1f EV", ev)
                ev < 0 -> String.format("%.1f EV", ev)
                else -> "0 EV"
            }
        }

    val canCompensateExposure: Boolean
        get() = (cameraCapabilities?.aeCompensationRange?.upper ?: 0) > 0

    /** True when the run is reading a file rather than a live camera. */
    val isImportRun: Boolean
        get() = sessionHistory.firstOrNull()?.source == SessionSource.VideoImport

    /**
     * Only a live capture can fall behind: its footage keeps arriving whether or not the
     * queue is draining. An import above 1× is slow, not broken — nothing is piling up
     * behind it — so it gets the number without the alarm.
     */
    val isThroughputTooSlow: Boolean
        get() = lastRealtimeRatio >= 1f && !isImportRun

    /**
     * Compact size for the processing card title (`FHD` / `4K` / `720p`).
     * Only while a job is running — idle has no file, so nothing to show.
     * Import waits for the probe; live capture can use the selected profile immediately.
     */
    val extractionResolutionLabel: String?
        get() {
            val jobActive = isCapturing || isProcessing || isImporting || isDownloading
            if (!jobActive) return null
            val session = sessionHistory.firstOrNull()
            compactResolutionLabel(
                session?.sourceVideoWidth,
                session?.sourceVideoHeight,
                session?.sourceRotationDegrees ?: 0,
            )?.let { return it }
            if (isCapturing || session?.source == SessionSource.LiveCapture) {
                return streamResolution.compactLabel
            }
            return null
        }
}

/** Scratch copies of videos downloaded because a CDN refused `MediaHTTPConnection`. */
internal const val NETWORK_IMPORT_DIR = "network_import"

/**
 * Shutter ceilings offered in the UI, as pinned frame rates: 1/30 s and 1/60 s.
 *
 * Anything slower than 1/30 is already a smear at running pace; anything faster costs more
 * ISO than a phone sensor can pay for at 5 a.m.
 */
internal val SHUTTER_CEILING_FPS = listOf(30, 60)

/**
 * Bounds for the face score control.
 *
 * The ceiling is not a taste decision: a w8a8 `face_det_lite` cannot report more than
 * [FaceDetLiteDetector.MAX_REACHABLE_SCORE] (0.853), so a threshold at or above that rejects
 * every frame and the session keeps nothing while looking healthy. Stopping at 0.80 leaves
 * the operator inside the range the model can actually reach.
 *
 * The floor is a guess and is labelled as one — nothing has measured where `face_det_lite`
 * starts calling texture a face on race footage. That is what `photos.csv` now collects.
 */
internal const val FACE_SCORE_MIN = 0.40f
internal const val FACE_SCORE_MAX = 0.80f
internal const val FACE_SCORE_STEP = 0.02f

data class PendingVideoImport(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val frameRate: Float?,
    val remoteUrl: String? = null,
) {
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width

    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    val resolutionLine: String
        get() {
            val compact = compactResolutionLabel(width, height, rotationDegrees)
            val pixels = "${displayWidth}×${displayHeight}"
            return if (compact != null) "$compact · $pixels" else pixels
        }

    val durationLabel: String
        get() = if (durationMs > 0) formatDurationMs(durationMs) else "—"

    val fpsLabel: String
        get() {
            val fps = frameRate ?: return "— fps"
            return if (fps % 1f == 0f) "${fps.toInt()} fps" else "%.2f fps".format(fps)
        }

    val sizeLabel: String
        get() = if (sizeBytes > 0) formatChunkBytes(sizeBytes) else "—"

    /**
     * A URL import that had to be downloaded first (R2 and friends) arrives here with
     * [remoteUrl] null and [uri] pointing at the scratch copy — the path is the only
     * thing left that still says where it came from.
     */
    val isRemote: Boolean
        get() = remoteUrl != null || uri.path?.contains("/$NETWORK_IMPORT_DIR/") == true

    /** Container of the source file, e.g. `MP4`. Extensions only — no probing. */
    val typeLabel: String
        get() {
            val ext = displayName.substringAfterLast('.', "")
            if (ext.isEmpty() || ext.length > 4 || !ext.all { it.isLetterOrDigit() }) return "—"
            return ext.uppercase()
        }

    /** Size for a stat chip, switching to GB once MB stops being readable. */
    val sizeChipNumber: String
        get() = when {
            sizeBytes <= 0L -> "—"
            sizeBytes >= 1_073_741_824L -> "%.1f".format(sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576L -> "%.0f".format(sizeBytes / 1_048_576.0)
            else -> "%.0f".format(sizeBytes / 1024.0)
        }

    /** Unit for [sizeChipNumber] — its own line in the chip, set smaller. */
    val sizeChipUnit: String
        get() = when {
            sizeBytes <= 0L -> ""
            sizeBytes >= 1_073_741_824L -> "GB"
            sizeBytes >= 1_048_576L -> "MB"
            else -> "KB"
        }

    /** True once [displayWidth] and [displayHeight] are worth showing. */
    val hasResolution: Boolean
        get() = displayWidth > 0 && displayHeight > 0

    /** Bare number — the chip label already says FPS. */
    val fpsChipValue: String
        get() {
            val fps = frameRate ?: return "—"
            return if (fps % 1f == 0f) "${fps.toInt()}" else "%.2f".format(fps)
        }
}

/** Backends the import preview lets the operator pick — matches the Home mockup. */
internal val ImportPreviewBackends: List<DetectorBackend> = listOf(
    DetectorBackend.MlKitFast,
    DetectorBackend.LiteRtGpu,
    DetectorBackend.LiteRtNpu,
)

internal fun importPreviewBackendLabel(backend: DetectorBackend): String = backend.hardwareLabel

/**
 * Rough wall-clock for one import. Sample interval is fixed; per-frame cost is a field
 * guess so the operator can compare backends before committing. Not a benchmark.
 */
internal fun estimateImportWallMs(
    durationMs: Long,
    width: Int,
    height: Int,
    rotationDegrees: Int,
    target: ExtractionTarget,
    backend: DetectorBackend,
): Long {
    if (durationMs <= 0) return 0L
    val frames = (durationMs / StreamResolution.FRAME_SAMPLE_INTERVAL_MS).coerceAtLeast(1L)
    val detectMs = when (backend) {
        DetectorBackend.LiteRtGpu -> 75L
        DetectorBackend.LiteRtNpu -> 50L
        else -> 110L
    }
    // Pose is ML Kit on the CPU whatever the backend. In the combined mode it runs only on
    // frames the face gate already passed, so it costs a fraction of a full pose pass.
    // Person is foot_track_net on the same NPU as face and costs about what face costs;
    // running both is close to two passes rather than one.
    var poseMul = 1.0
    if (target.usesPose) poseMul *= if (target.usesFace) 1.5 else 1.3
    if (target.usesPerson) poseMul *= if (target.enabledCount > 1) 1.8 else 1.1
    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val longEdge = maxOf(
        if (rotated) height else width,
        if (rotated) width else height,
    )
    val resMul = if (longEdge >= 2160) 1.4 else 1.0
    return (frames * detectMs * poseMul * resMul).toLong()
}

internal fun formatImportEstimate(ms: Long): String {
    if (ms <= 0L) return "—"
    val seconds = (ms / 1000L).coerceAtLeast(1L)
    if (seconds < 45L) return "~ ${seconds}s"
    val minutes = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
    return "~ $minutes min"
}

/** ≥1000 MB → "X.X GB", else "NNN MB". */
internal fun formatRam(mb: Long): String {
    return if (mb >= 1000L) {
        val gb = mb / 1024.0
        String.format("%.1f GB", gb)
    } else {
        "$mb MB"
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OperatorViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(OperatorUiState())
    val state: StateFlow<OperatorUiState> = _state.asStateFlow()

    private var pipeline: CapturePipelineCoordinator? = null

    /**
     * Read side of the upload queue. Same database the pipeline writes to — [UploadDatabase]
     * is a singleton, so this instance and the coordinator's see each other's rows.
     */
    private val uploadQueue: UploadRepository = UploadRepository.create(application)

    /**
     * Totals only. The rows themselves are never observed here: nothing deletes from the
     * queue (docs/PHASES.md §2.5), so the table only grows, and a whole-table Flow would
     * re-map every row each time a worker touched one.
     */
    val uploadCounts: StateFlow<UploadQueueCounts> = uploadQueue.observeCounts()
        .retryWhenFailed("upload counts")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UploadQueueCounts())

    /** Which status the queue screen is showing, or null for everything. */
    private val _uploadFilter = MutableStateFlow<UploadStatus?>(null)
    val uploadFilter: StateFlow<UploadStatus?> = _uploadFilter.asStateFlow()

    fun setUploadFilter(status: UploadStatus?) {
        _uploadFilter.value = status
    }

    /**
     * One bounded page for the queue screen. Only collected while that screen is open.
     *
     * The filter is applied by the query, not to the loaded page: the chips above the list
     * count the whole table, so filtering in Kotlin let the screen promise "Abandoned 2" and
     * then show nothing once the queue outgrew a page.
     */
    val uploadItems: StateFlow<List<UploadItem>> = _uploadFilter
        .flatMapLatest { status ->
            if (status == null) uploadQueue.observePage() else uploadQueue.observePageOf(status)
        }
        .retryWhenFailed("upload page")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val uploadSettings = UploadSettings(application)

    val uploadPaused: StateFlow<Boolean> = uploadSettings.paused

    /** Set when the queue paused itself — a rejected token, not an operator decision. */
    val uploadPauseReason: StateFlow<String?> = uploadSettings.pauseReason

    val uploadConfig: StateFlow<UploadConfig> = uploadSettings.config

    fun saveUploadConfig(config: UploadConfig) {
        uploadSettings.writeConfig(config)
        // A newly configured device should not sit idle waiting for the next photo.
        UploadScheduler.ensureScheduled(getApplication())
    }

    fun clearUploadConfig() = uploadSettings.clearConfig()

    /**
     * Apply a scanned provisioning payload. Same entry point the QR scanner uses.
     * @return false when the payload was not an upload configuration; the current one stands.
     */
    fun applyScannedUploadConfig(payload: String): Boolean {
        val parsed = UploadConfig.parseQr(uploadSettings.readConfig(), payload) ?: return false
        saveUploadConfig(parsed.config)
        // A provisioning QR may carry a token. It signs this process in and is not written
        // anywhere — same rule as a login, and the same one login every launch.
        parsed.token?.let { UploadSession.signIn(UploadSession.SignedIn(it, "provisioning QR")) }
        return true
    }

    // --- sign-in (B3e) -------------------------------------------------------------------

    /** Who is signed in, for this process only. Null after every cold start, by design. */
    val uploadAccount: StateFlow<UploadSession.SignedIn?> = UploadSession.current

    /** What the sign-in box should be pre-filled with, when the operator asked us to remember. */
    val rememberedCredentials: StateFlow<UploadSettings.Credentials?> = uploadSettings.remembered

    private val _uploadAuth = MutableStateFlow(UploadAuthUiState())
    val uploadAuth: StateFlow<UploadAuthUiState> = _uploadAuth.asStateFlow()

    /**
     * Sign in, then immediately fetch the events that token can see.
     *
     * The two are one action from the operator's point of view: nobody signs in for its own
     * sake, they sign in to pick today's event.
     */
    fun signInToUpload(username: String, password: String, remember: Boolean) {
        if (_uploadAuth.value.busy) return
        val config = uploadSettings.readConfig()
        config.signInProblem()?.let { problem ->
            _uploadAuth.update { it.copy(error = problem) }
            return
        }
        if (username.isBlank() || password.isBlank()) {
            _uploadAuth.update { it.copy(error = "Username and password are required") }
            return
        }

        _uploadAuth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                RunxAuthClient.login(config.graphqlUrl, config.platform, username, password)
            }
            outcome.onSuccess { account ->
                UploadSession.signIn(account)
                uploadSettings.saveCredentials(username, password, remember)
                // A queue that stopped itself over a rejected token has just been given a
                // good one; making the operator also find the Resume button would be rude.
                if (uploadSettings.pauseReason.value != null) uploadSettings.setPaused(false)
                UploadScheduler.ensureScheduled(getApplication())
                _uploadAuth.update { it.copy(busy = false, error = null) }
                loadUploadEvents()
            }.onFailure { t ->
                _uploadAuth.update { it.copy(busy = false, error = t.message ?: "Sign-in failed") }
            }
        }
    }

    /** Drops the token and, if it was stored, the password too. */
    fun signOutOfUpload() {
        UploadSession.signOut()
        uploadSettings.saveCredentials("", "", remember = false)
        _uploadAuth.value = UploadAuthUiState()
    }

    fun setUploadEventScope(scope: RunxAuthClient.EventScope) {
        if (_uploadAuth.value.scope == scope) return
        _uploadAuth.update { it.copy(scope = scope, events = emptyList(), loaded = false) }
        loadUploadEvents()
    }

    fun setUploadEventSearch(search: String) {
        _uploadAuth.update { it.copy(search = search) }
    }

    /** Refresh the event picker with whatever scope and search are currently set. */
    fun loadUploadEvents() {
        val token = UploadSession.token ?: run {
            _uploadAuth.update { it.copy(error = "Sign in first") }
            return
        }
        if (_uploadAuth.value.loadingEvents) return
        val config = uploadSettings.readConfig()
        val state = _uploadAuth.value

        _uploadAuth.update { it.copy(loadingEvents = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                RunxAuthClient.events(
                    graphqlUrl = config.graphqlUrl,
                    platform = config.platform,
                    token = token,
                    scope = state.scope,
                    search = state.search,
                )
            }.onSuccess { list ->
                _uploadAuth.update {
                    it.copy(
                        loadingEvents = false,
                        loaded = true,
                        events = list.events,
                        truncated = list.truncated,
                    )
                }
            }.onFailure { t ->
                // An expired token shows up here, not at sign-in — this is usually the first
                // call made after the app has been open for a week.
                if (t is com.autobots.camera.upload.UploadException.Unauthorized) {
                    UploadSession.signOut()
                }
                _uploadAuth.update {
                    it.copy(loadingEvents = false, error = t.message ?: "Could not load events")
                }
            }
        }
    }

    /** Picking an event is what actually writes `eventId` into the durable config. */
    fun selectUploadEvent(event: RunxAuthClient.EventSummary) {
        saveUploadConfig(
            uploadSettings.readConfig().copy(eventId = event.id, eventTitle = event.title),
        )
    }

    fun clearUploadAuthError() = _uploadAuth.update { it.copy(error = null) }

    /**
     * Where uploads actually go. Derived from the same three inputs the worker's selection
     * rule uses, so the screen can never claim a destination the worker is not using.
     */
    val uploadDestinationLabel: StateFlow<String> =
        combine(uploadSettings.config, UploadSession.current) { config, account ->
            uploadDestinationLabel(config, account != null, uploadSettings.useFakeTransport)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            uploadDestinationLabel(
                uploadSettings.readConfig(),
                UploadSession.isSignedIn,
                uploadSettings.useFakeTransport,
            ),
        )

    private val deviceLoadReader = DeviceLoadReader(
        context = application,
        mainExecutor = ContextCompat.getMainExecutor(application),
    )

    var pendingStartAfterPermission: Boolean = false
        private set

    init {
        // Off the main thread: probing the GPU delegate touches the driver, and the QNN probe
        // unpacks ~96 MB of DSP libraries out of the APK on first run. IO rather than Default
        // because that unpacking is blocking disk work, not computation.
        viewModelScope.launch(Dispatchers.IO) {
            sweepImportCache()
            val unavailable = DetectorAvailability.checkAll(getApplication())
                .mapNotNull { (backend, reason) -> reason?.let { backend to it } }
                .toMap()
            _state.update { state ->
                // Never leave the picker pointing at something that cannot run.
                val fallback = if (state.detectorBackend in unavailable) {
                    DetectorBackend.firstAvailable(unavailable)
                } else {
                    state.detectorBackend
                }
                state.copy(detectorUnavailable = unavailable, detectorBackend = fallback)
            }
            // Anything left in the queue from a previous run should move without waiting for
            // the next photo to be delivered.
            UploadScheduler.ensureScheduled(getApplication())
        }

        deviceLoadReader.start(::applyDeviceLoad)
        viewModelScope.launch {
            while (isActive) {
                delay(LOAD_POLL_MS)
                applyDeviceLoad(deviceLoadReader.sample())
            }
        }
    }

    override fun onCleared() {
        pipeline?.close()
        pipeline = null
        deviceLoadReader.stop()
        super.onCleared()
    }

    /** Operator pressed *Retry failed* — clears backoff and attempt counts. */
    fun retryFailedUploads() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { uploadQueue.retryAllFailed() }
            UploadScheduler.ensureScheduled(getApplication())
        }
    }

    /** **Debug only.** Rewind the queue so the drain can be run again from scratch. */
    fun debugRequeueUploads() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { uploadQueue.debugRequeueAll() }
            UploadScheduler.ensureScheduled(getApplication())
        }
    }

    /**
     * Debug: queue photos that are already in `DCIM/AutoBots`.
     *
     * The queue lives in app storage and the photos do not, so clearing app data leaves a
     * gallery full of images and nothing to upload. Without this, exercising the transport
     * means shooting a fresh session with real faces in front of the lens — which is not
     * something that can be arranged while debugging an HTTP header.
     *
     * Deliberately reuses the normal enqueue path, unique index and all, so re-running it
     * cannot duplicate a row.
     */
    fun debugEnqueueExistingPhotos(limit: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
            )
            val candidates = mutableListOf<UploadCandidate>()
            runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("DCIM/AutoBots/%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && candidates.size < limit) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        // `DCIM/AutoBots/<album>/` — the album is our sessionId.
                        val album = cursor.getString(2).orEmpty()
                            .trimEnd('/').substringAfterLast('/')
                        if (album.isBlank()) continue
                        candidates += UploadCandidate(
                            contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id,
                            ).toString(),
                            fileName = name,
                            sessionId = album,
                            capturedAtMs = cursor.getLong(4).takeIf { it > 0 }
                                ?: System.currentTimeMillis(),
                            sizeBytes = cursor.getLong(3),
                        )
                    }
                }
            }.onFailure { Log.e("OperatorViewModel", "Cannot read gallery", it) }

            if (candidates.isEmpty()) return@launch
            runCatching { uploadQueue.enqueue(candidates) }
                .onSuccess {
                    Log.i("OperatorViewModel", "Debug enqueued ${candidates.size} existing photos")
                    UploadScheduler.ensureScheduled(getApplication())
                }
                .onFailure { Log.e("OperatorViewModel", "Debug enqueue failed", it) }
        }
    }

    /**
     * Empty the upload queue.
     *
     * Records only — the photos are untouched in the gallery. Rows that had not uploaded yet
     * are gone for good, because nothing rebuilds the queue from what is on disk; the screen
     * says so before this runs.
     */
    fun clearUploadQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { uploadQueue.clearAll() }.getOrDefault(0)
            Log.i("OperatorViewModel", "Upload queue cleared ($removed rows)")
            setUploadFilter(null)
        }
    }

    fun setUploadPaused(paused: Boolean) {
        val app = getApplication<Application>()
        if (paused) UploadScheduler.pause(app) else UploadScheduler.resume(app)
    }

    fun setServerIp(ip: String) {
        _state.update { it.copy(serverIp = ip) }
    }

    fun pipelineCoordinator(): CapturePipelineCoordinator? = pipeline

    fun markPendingStartAfterPermission() {
        pendingStartAfterPermission = true
    }

    fun consumePendingStart() {
        pendingStartAfterPermission = false
    }

    fun startCapture() {
        if (pipeline?.isBusy() == true) return

        pipeline?.close()
        pipeline = null

        val coordinator = CapturePipelineCoordinator.create(
            context = getApplication(),
            onStats = ::applyPipelineStats,
            onPhotoDelivered = { uri -> onPhotoDelivered(uri.toString()) },
            onDrainComplete = ::onPipelineDrainComplete,
        )
        val resolution = _state.value.streamResolution
        val extractionTarget = _state.value.extractionTarget
        coordinator.setResolution(resolution)
        coordinator.setExtractionTarget(extractionTarget)
        coordinator.setDetectorBackend(_state.value.detectorBackend)
        coordinator.setDetectZone(_state.value.detectZone)
        coordinator.setMinFaceScore(_state.value.minFaceScore)
        coordinator.setExposureSettings(
            shutterCeilingFps = _state.value.shutterCeilingFps,
            exposureIndex = _state.value.exposureIndex,
            stepEv = _state.value.cameraCapabilities?.aeCompensationStepEv,
        )

        if (!coordinator.hasStorageForRecording()) {
            _state.update { it.copy(storageBlocked = true) }
            coordinator.close()
            return
        }

        pipeline = coordinator
        coordinator.onRecordingStarted()
        _state.update {
            it.copy(
                isCapturing = true,
                storageBlocked = false,
                videoChunksRecorded = 0,
                videoQueueDepth = 0,
                facesKept = 0,
                facesSkipped = 0,
                lastChunkProcessMs = 0,
                pipelinePaused = false,
                recordingProgress = ChunkRecordingProgress(),
                isProcessing = false,
                expectedChunks = 0,
                processingPercent = 0,
                currentChunkPercent = 0,
                chunksProcessed = 0,
                processingChunkName = null,
                imageQueuePending = 0,
                lastRealtimeRatio = 0f,
                avgPhotoLatencyMs = 0,
                sessionHistory = emptyList(),
            )
        }
        applyDeviceLoad(deviceLoadReader.sample())
    }

    /**
     * Probe a picked video and park it on the import preview. Extraction starts only
     * after [confirmPendingImport].
     */
    fun prepareImport(uri: Uri) {
        if (!_state.value.canImportVideo) return
        takePersistableRead(uri)
        _state.update {
            it.copy(isPreparingImport = true, importError = null, pendingImport = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val displayName = resolveDisplayName(uri) ?: "video"
            val sizeBytes = resolveSizeBytes(uri)
            val probe = ImportedVideoSplitter.probe(app, uri)
            if (probe == null) {
                _state.update {
                    it.copy(
                        isPreparingImport = false,
                        pendingImport = null,
                        importError = "Cannot read video",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    isPreparingImport = false,
                    pendingImport = PendingVideoImport(
                        uri = uri,
                        displayName = displayName,
                        sizeBytes = sizeBytes,
                        width = probe.width,
                        height = probe.height,
                        rotationDegrees = probe.rotationDegrees,
                        durationMs = probe.durationMs,
                        frameRate = probe.frameRate,
                    ),
                )
            }
            loadPreviewFrame(uri, remoteUrl = null, durationMs = probe.durationMs)
        }
    }


    /**
     * Grab one frame to draw the Capture Zone against.
     *
     * Drawing a zone on an empty rectangle is guessing where the lane is; drawing it on a
     * real frame is seeing it. Failure is not worth reporting — the editor falls back to a
     * plain backdrop and stays usable.
     */
    private fun loadPreviewFrame(uri: Uri, remoteUrl: String?, durationMs: Long) {
        previewFrameJob?.cancel()
        previewFrameJob = viewModelScope.launch(Dispatchers.IO) {
            val bitmap = withTimeoutOrNull(PREVIEW_FRAME_TIMEOUT_MS) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (remoteUrl != null) {
                        retriever.setDataSource(remoteUrl, HashMap())
                    } else {
                        retriever.setDataSource(getApplication(), uri)
                    }
                    // Midpoint: the first frame of a race clip is often an empty lane.
                    val atUs = (durationMs.coerceAtLeast(0L) / 2) * 1000L
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (t: Throwable) {
                    Log.i("OperatorViewModel", "No preview frame: ${t.message}")
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
            _state.update { it.copy(pendingImportFrame = bitmap) }
        }
    }

    fun cancelPendingImport() {
        // A cancelled network import leaves its downloaded copy behind otherwise, and those
        // are whole videos — gigabytes, not kilobytes.
        _state.value.pendingImport?.uri?.let(::deleteIfImportCache)
        previewFrameJob?.cancel()
        _state.update {
            it.copy(isPreparingImport = false, pendingImport = null, pendingImportFrame = null)
        }
    }

    /** Delete a file only if it is ours: a scratch copy under `cacheDir/network_import`. */
    private fun deleteIfImportCache(uri: Uri) {
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val root = File(getApplication<Application>().cacheDir, NETWORK_IMPORT_DIR).absolutePath
        if (!path.startsWith("$root/")) return
        runCatching { File(path).delete() }
    }

    /**
     * Throw away scratch downloads left by an earlier run.
     *
     * `cacheDir/network_import` holds whole videos that were downloaded because the CDN
     * refused `MediaHTTPConnection`. Each one is kept on purpose until its import finishes —
     * but a crash, a force-stop or a cancelled extraction leaves it behind, and Android only
     * reclaims cache under storage pressure, which is far too late on a device that also has
     * to hold a session's worth of 4K chunks.
     *
     * Safe to do at process start: a pending import does not survive process death, so
     * anything still in here belongs to nobody.
     */
    private fun sweepImportCache() {
        val dir = File(getApplication<Application>().cacheDir, NETWORK_IMPORT_DIR)
        val stale = dir.listFiles().orEmpty()
        if (stale.isEmpty()) return
        var freed = 0L
        stale.forEach { file ->
            freed += file.length()
            file.delete()
        }
        Log.i("OperatorViewModel", "Cleared ${stale.size} stale import file(s), ${freed / 1_048_576} MB")
    }

    private var previewFrameJob: Job? = null

    fun clearNetworkUrlError() {
        _state.update { it.copy(networkUrlError = null) }
    }

    fun checkNetworkUrl(raw: String) {
        if (!_state.value.canImportVideo) return
        val url = RemoteVideoFetcher.normalize(raw)
        val invalid = RemoteVideoFetcher.validate(url)
        if (invalid != null) {
            _state.update { it.copy(networkUrlError = invalid, isCheckingNetworkUrl = false) }
            return
        }
        _state.update {
            it.copy(isCheckingNetworkUrl = true, networkUrlError = null, importError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val head = runCatching { RemoteVideoFetcher.head(url) }.getOrNull()
                RemoteVideoFetcher.rejectIfNotVideo(head?.contentType)?.let {
                    throw IllegalStateException(it)
                }
                val displayName = head?.fileName
                    ?: File(url.toUri().lastPathSegment ?: "video.mp4").name
                var probe = try {
                    withTimeout(12_000) {
                        RemoteVideoFetcher.probe(getApplication(), url)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    null
                }
                var localFile: File? = null
                if (probe == null) {
                    // R2 and similar CDNs often 200 HEAD but refuse MediaHTTPConnection.
                    // Download once, probe the file, then Extract can skip a second GET.
                    val safeName = File(displayName).name.ifBlank { "video.mp4" }
                    val dest = File(
                        getApplication<Application>().cacheDir,
                        "$NETWORK_IMPORT_DIR/$safeName",
                    )
                    _state.update {
                        it.copy(
                            isDownloading = true,
                            downloadPercent = 0,
                            importName = safeName,
                        )
                    }
                    RemoteVideoFetcher.download(url, dest) { percent ->
                        _state.update { it.copy(downloadPercent = percent) }
                    }
                    localFile = dest
                    probe = RemoteVideoFetcher.probeFile(getApplication(), dest)
                        ?: throw IllegalStateException("Downloaded file is not a readable video")
                }
                val ready = probe ?: throw IllegalStateException("Cannot read video from URL")
                val sizeBytes = head?.sizeBytes?.takeIf { it > 0 }
                    ?: localFile?.length()
                    ?: 0L
                _state.update {
                    it.copy(
                        isCheckingNetworkUrl = false,
                        isDownloading = false,
                        downloadPercent = if (localFile != null) 100 else it.downloadPercent,
                        networkUrlError = null,
                        pendingImport = PendingVideoImport(
                            uri = localFile?.let { file -> Uri.fromFile(file) } ?: url.toUri(),
                            displayName = displayName,
                            sizeBytes = sizeBytes,
                            width = ready.width,
                            height = ready.height,
                            rotationDegrees = ready.rotationDegrees,
                            durationMs = ready.durationMs,
                            frameRate = ready.frameRate,
                            remoteUrl = if (localFile == null) url else null,
                        ),
                    )
                }
                loadPreviewFrame(
                    uri = localFile?.let { file -> Uri.fromFile(file) } ?: url.toUri(),
                    remoteUrl = if (localFile == null) url else null,
                    durationMs = ready.durationMs,
                )
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                _state.update {
                    it.copy(
                        isCheckingNetworkUrl = false,
                        isDownloading = false,
                        pendingImport = null,
                        networkUrlError = "Timed out reading video",
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isCheckingNetworkUrl = false,
                        isDownloading = false,
                        pendingImport = null,
                        networkUrlError = t.message ?: "Cannot read video from URL",
                    )
                }
            }
        }
    }

    fun confirmPendingImport(
        target: ExtractionTarget,
        backend: DetectorBackend,
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
    ) {
        val pending = _state.value.pendingImport ?: return
        if (_state.value.detectorUnavailable.containsKey(backend)) return
        _state.update {
            it.copy(
                pendingImport = null,
                isPreparingImport = false,
                extractionTarget = target,
                detectorBackend = backend,
            )
        }
        // Remote URLs stream into the splitter; the coordinator downloads only if
        // MediaExtractor cannot read the HTTP source. Check-time downloads (R2) already
        // leave [PendingVideoImport.remoteUrl] null and [uri] pointing at the cache file.
        importVideo(pending.uri, pending.displayName, rangeStartMs, rangeEndMs)
    }

    /**
     * Runs a device video through the same pipeline as a live Passage. No camera is
     * bound — [OperatorUiState.isCapturing] stays false so the preview stays idle.
     */
    fun importVideo(
        uri: Uri,
        displayName: String? = resolveDisplayName(uri),
        rangeStartMs: Long? = null,
        rangeEndMs: Long? = null,
    ) {
        val busy = _state.value.let { it.isCapturing || it.isProcessing || it.isImporting }
        if (busy) return

        pipeline?.close()
        val coordinator = CapturePipelineCoordinator.create(
            context = getApplication(),
            onStats = ::applyPipelineStats,
            onPhotoDelivered = { delivered -> onPhotoDelivered(delivered.toString()) },
            onDrainComplete = ::onPipelineDrainComplete,
        )
        coordinator.setResolution(_state.value.streamResolution)
        coordinator.setExtractionTarget(_state.value.extractionTarget)
        coordinator.setDetectorBackend(_state.value.detectorBackend)
        coordinator.setDetectZone(_state.value.detectZone)
        coordinator.setMinFaceScore(_state.value.minFaceScore)
        // No exposure settings: they drive the camera, and an imported clip was exposed
        // before it ever reached this phone. Recording them here would put a shutter and an
        // EV bias on a session they had no part in.

        if (!coordinator.hasStorageForRecording()) {
            _state.update { it.copy(storageBlocked = true) }
            coordinator.close()
            return
        }

        pipeline = coordinator
        _state.update {
            it.copy(
                storageBlocked = false,
                importError = null,
                isImporting = true,
                importName = displayName,
                importPercent = 0,
                expectedChunks = 0,
                videoChunksRecorded = 0,
                videoQueueDepth = 0,
                facesKept = 0,
                facesSkipped = 0,
                chunksProcessed = 0,
                processingPercent = 0,
                currentChunkPercent = 0,
                imageQueuePending = 0,
                lastRealtimeRatio = 0f,
                avgPhotoLatencyMs = 0,
                sessionHistory = emptyList(),
            )
        }

        viewModelScope.launch {
            val result = coordinator.importVideo(uri, displayName, rangeStartMs, rangeEndMs)
            if (result.error != null || result.segments == 0) {
                _state.update {
                    it.copy(importError = result.error ?: "No video segments produced")
                }
            }
        }
    }

    fun clearImportError() {
        _state.update { it.copy(importError = null) }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment

    private fun resolveSizeBytes(uri: Uri): Long = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) cursor.getLong(index) else 0L
                } else {
                    0L
                }
            }
    }.getOrNull() ?: 0L

    private fun takePersistableRead(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun stopCapture() {
        pipeline?.stopRecording()
        _state.update {
            it.copy(
                isCapturing = false,
                pipelinePaused = false,
                exposureLine = "—mm  ·  —  ·  ISO —",
                recordingProgress = ChunkRecordingProgress(),
            )
        }
    }

    private fun onPipelineDrainComplete() {
        pipeline?.close()
        pipeline = null
        _state.update {
            it.copy(
                isProcessing = false,
                processingPercent = 0,
                currentChunkPercent = 0,
                processingChunkName = null,
                imageQueuePending = 0,
                isImporting = false,
                importPercent = 0,
                importName = null,
            )
        }
    }

    fun onRecordingProgress(chunkIndex: Int, elapsedMs: Long, bytesWritten: Long) {
        _state.update {
            it.copy(
                recordingProgress = ChunkRecordingProgress(
                    chunkIndex = chunkIndex,
                    elapsedMs = elapsedMs,
                    bytesWritten = bytesWritten,
                    targetBytes = it.streamResolution.chunkTargetBytes,
                ),
            )
        }
    }

    fun onPhotoDelivered(uriString: String) {
        _state.update {
            it.copy(
                keptPhotoCount = it.keptPhotoCount + 1,
                lastGalleryUri = uriString,
            )
        }
    }

    fun setStreamResolution(resolution: StreamResolution) {
        if (_state.value.isCapturing) return
        _state.update { it.copy(streamResolution = resolution) }
    }

    /** Steps by [FACE_SCORE_STEP], clamped to a range where the model still behaves. */
    fun stepMinFaceScore(delta: Int) {
        _state.update { current ->
            val next = current.minFaceScore + delta * FACE_SCORE_STEP
            current.copy(
                minFaceScore = next.coerceIn(FACE_SCORE_MIN, FACE_SCORE_MAX),
            )
        }
    }

    /** Null clears the zone back to the whole frame. */
    fun setDetectZone(zone: DetectZone?) {
        if (_state.value.isCapturing) return
        _state.update { it.copy(detectZone = zone?.takeUnless { z -> z.isFullFrame }) }
    }

    fun setExtractionTarget(target: ExtractionTarget) {
        if (_state.value.isCapturing) return
        _state.update { it.copy(extractionTarget = target) }
    }

    /** Bench control: pick the detector, import the same clip, compare `perf_report.json`. */
    fun setDetectorBackend(backend: DetectorBackend) {
        if (_state.value.isCapturing) return
        if (_state.value.detectorUnavailable.containsKey(backend)) return
        // Applied when the coordinator is built for the next session; the chip is disabled
        // while capturing, so there is never a live pipeline to retarget.
        _state.update { it.copy(detectorBackend = backend) }
    }

    fun onExposureReadout(line: String) {
        _state.update { it.copy(exposureLine = line) }
    }

    fun onCameraCapabilities(capabilities: CameraCapabilities?) {
        _state.update { current ->
            val range = capabilities?.aeCompensationRange
            current.copy(
                cameraCapabilities = capabilities,
                // Another camera may not reach as far; keep the operator's intent but never
                // hold a value the device would reject.
                exposureIndex = if (range == null) {
                    current.exposureIndex
                } else {
                    current.exposureIndex.coerceIn(range.lower, range.upper)
                },
            )
        }
    }

    /** Null = let AE decide how long to open the shutter. */
    fun setShutterCeilingFps(fps: Int?) {
        _state.update { it.copy(shutterCeilingFps = fps) }
    }

    fun stepExposure(delta: Int) {
        _state.update { current ->
            val range = current.cameraCapabilities?.aeCompensationRange ?: return@update current
            current.copy(
                exposureIndex = (current.exposureIndex + delta).coerceIn(range.lower, range.upper),
            )
        }
    }

    private fun applyPipelineStats(stats: PipelineStats) {
        _state.update {
            it.copy(
                streamResolution = stats.resolution,
                extractionTarget = stats.extractionTarget,
                videoChunksRecorded = stats.videoChunksRecorded,
                videoQueueDepth = stats.videoQueueDepth,
                chunksProcessed = stats.chunksProcessed,
                facesKept = stats.facesKept,
                facesSkipped = stats.facesSkipped,
                lastChunkProcessMs = stats.lastChunkProcessMs,
                storageFreeMb = stats.storageFreeMb,
                pipelinePaused = stats.pipelinePaused,
                isProcessing = stats.isProcessing,
                // The denominator is a projection while splitting and can still grow by a
                // chunk or two, which would otherwise walk the bar backwards. Progress within
                // a job only ever moves forward; the reset paths put it back to 0.
                processingPercent = if (stats.isImporting || stats.isProcessing) {
                    maxOf(it.processingPercent, stats.overallProcessingPercent)
                } else {
                    stats.overallProcessingPercent
                },
                currentChunkPercent = stats.currentChunkPercent,
                processingChunkName = stats.processingChunkName,
                imageQueuePending = stats.imageQueuePending,
                isImporting = stats.isImporting,
                importPercent = stats.importPercent,
                expectedChunks = stats.expectedChunks,
                importName = stats.importName ?: it.importName,
                lastRealtimeRatio = stats.lastRealtimeRatio,
                avgPhotoLatencyMs = stats.avgPhotoLatencyMs,
                sessionHistory = stats.sessionHistory,
            )
        }
    }

    private fun applyDeviceLoad(snapshot: DeviceLoadSnapshot) {
        _state.update {
            it.copy(
                thermalLabel = snapshot.thermalLabel,
                thermalLevel = snapshot.thermalLevel,
                usedRamMb = snapshot.usedRamMb,
                availRamMb = snapshot.availRamMb,
                totalRamMb = snapshot.totalRamMb,
            )
        }
    }

    companion object {
        private const val LOAD_POLL_MS = 2_000L


        /**
         * How long the queue Flows stay warm after the last collector goes away. Long enough
         * that navigating Home → Upload → Home does not tear down and rebuild the Room query.
         */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** A remote clip may never yield a frame in reasonable time; the editor copes. */
        private const val PREVIEW_FRAME_TIMEOUT_MS = 4_000L

    }
}

/** How long to wait before re-observing a query stream that failed. */
private const val RETRY_OBSERVE_MS = 1_000L

/**
 * Keep observing after a failure instead of giving up on the stream.
 *
 * `catch { emit(fallback) }` reads like error handling but **ends the flow**: one transient
 * database error and the screen it feeds is frozen at whatever it showed last, for as long as
 * the process lives. `stateIn(WhileSubscribed)` does not rescue it either, because the
 * collector never goes away — the Activity holds it for the whole session, so the subscriber
 * count never drops to zero and nothing is ever restarted.
 *
 * That is a bad trade for counters someone watches through a two-hour job. Re-observing keeps
 * the screen honest: a hiccup costs a second of stale numbers instead of all of them.
 */
private fun <T> Flow<T>.retryWhenFailed(label: String): Flow<T> = retryWhen { cause, attempt ->
    Log.w("OperatorViewModel", "$label stream failed (attempt $attempt), re-observing", cause)
    delay(RETRY_OBSERVE_MS)
    true
}
