package com.autobots.camera.network

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.autobots.ui.OperatorViewModel
import com.autobots.ui.OperatorUiState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class AutobotsServer(
    private val context: Context,
    private val viewModel: OperatorViewModel
) {
    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())
    private val previewSessions = Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())

    fun start(port: Int = 8080) {
        if (server != null) return
        try {
            server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(WebSockets)
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    // Control endpoint
                    webSocket("/ws/control") {
                        controlSessions.add(this)
                        // Send initial state
                        val initialState = createRemoteState(viewModel.state.value)
                        send(Frame.Text(Json.encodeToString(initialState)))
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    handleControlCommand(text)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Control WebSocket error", e)
                        } finally {
                            controlSessions.remove(this)
                        }
                    }

                    // Preview stream endpoint
                    webSocket("/ws/preview") {
                        previewSessions.add(this)
                        try {
                            for (frame in incoming) {
                                // Client doesn't need to send anything, but keep connection open
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Preview WebSocket error", e)
                        } finally {
                            previewSessions.remove(this)
                        }
                    }

                    // HTTP Endpoint to download JPEGs from MediaStore
                    get("/photos/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null) {
                            call.respond(HttpStatusCode.BadRequest, "Invalid photo ID")
                            return@get
                        }
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val resolver = this@AutobotsServer.context.contentResolver
                        val inputStream = try {
                            resolver.openInputStream(contentUri)
                        } catch (e: Exception) {
                            null
                        }

                        if (inputStream != null) {
                            try {
                                call.respondOutputStream(ContentType.Image.JPEG) {
                                    inputStream.use { input ->
                                        input.copyTo(this)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing image to stream", e)
                            }
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }.apply {
                start(wait = false)
            }
            Log.i(TAG, "Server started successfully on port $port")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Ktor server", t)
            val msg = t.message ?: t.javaClass.simpleName
            viewModel.setServerIp("Error: $msg")
        }

        // Monitor ViewModel state flow and broadcast to control sessions
        viewModel.state.onEach { uiState ->
            broadcastStateUpdate(uiState)
        }.launchIn(scope)
    }

    private fun handleControlCommand(jsonText: String) {
        try {
            val cmd = Json.decodeFromString<RemoteControlCommand>(jsonText)
            scope.launch(Dispatchers.Main) {
                when (cmd.action) {
                    RemoteControlCommand.ACTION_TOGGLE_CAPTURE -> {
                        val state = viewModel.state.value
                        if (state.isCapturing) {
                            viewModel.stopCapture()
                        } else {
                            viewModel.startCapture()
                        }
                    }
                    RemoteControlCommand.ACTION_SET_CAPTURE_MODE -> {
                        cmd.captureMode?.let { modeStr ->
                            val resolution = when (modeStr) {
                                "Uhd", "4K" -> com.autobots.camera.StreamResolution.Uhd
                                else -> com.autobots.camera.StreamResolution.Fhd
                            }
                            viewModel.setStreamResolution(resolution)
                        }
                    }
                    RemoteControlCommand.ACTION_SET_ARM_THRESHOLD -> Unit
                    RemoteControlCommand.ACTION_SET_FIRE_THRESHOLD -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command: $jsonText", e)
        }
    }

    private suspend fun broadcastStateUpdate(uiState: OperatorUiState) {
        if (controlSessions.isEmpty()) return
        val remoteUpdate = createRemoteState(uiState)
        val textFrame = Frame.Text(Json.encodeToString(remoteUpdate))
        val iterator = controlSessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            try {
                session.send(textFrame)
            } catch (e: Exception) {
                iterator.remove()
            }
        }
    }

    fun broadcastPreviewFrame(jpegData: ByteArray) {
        if (previewSessions.isEmpty()) return
        scope.launch {
            val binaryFrame = Frame.Binary(true, jpegData)
            val iterator = previewSessions.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    session.send(binaryFrame)
                } catch (e: Exception) {
                    iterator.remove()
                }
            }
        }
    }

    private fun createRemoteState(uiState: OperatorUiState): RemoteStateUpdate {
        val lastPhotoId = uiState.lastGalleryUri?.let { uriStr ->
            uriStr.substringAfterLast('/')
        }
        val serverIp = getLocalIpAddress() ?: "127.0.0.1"
        val lastPhotoUrl = lastPhotoId?.let { "http://$serverIp:8080/photos/$it" }

        return RemoteStateUpdate(
            isCapturing = uiState.isCapturing,
            captureMode = uiState.streamResolution.name,
            isArmed = false,
            lastFired = false,
            passageGateOpen = true,
            keptPhotoCount = uiState.keptPhotoCount,
            lastBurstSaved = uiState.videoChunksRecorded,
            lastGalleryUri = uiState.lastGalleryUri,
            isBursting = false,
            thermalLabel = uiState.thermalLabel,
            thermalLevel = uiState.thermalLevel,
            usedRamMb = uiState.usedRamMb,
            totalRamMb = uiState.totalRamMb,
            faceCount = uiState.facesKept,
            proximity = 0f,
            armThreshold = 0f,
            fireThreshold = 0f,
            exposureLine = when {
                uiState.isCapturing && uiState.recordingLine.isNotEmpty() -> uiState.recordingLine
                uiState.statusLine.isNotEmpty() -> "${uiState.statusLine} · ${uiState.streamResolution.label}"
                else -> uiState.streamResolution.label
            },
            deviceLoadLine = uiState.deviceLoadLine,
            lastPhotoUrl = lastPhotoUrl
        )
    }

    fun stop() {
        scope.cancel()
        server?.stop(1000, 2000)
        server = null
        controlSessions.clear()
        previewSessions.clear()
        Log.i(TAG, "Server stopped")
    }

    companion object {
        private const val TAG = "AutobotsServer"

        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                val list = Collections.list(interfaces)
                // 1. First, search for Wi-Fi or Hotspot interfaces
                for (ni in list) {
                    if (ni.isLoopback || !ni.isUp) continue
                    val name = ni.name.lowercase()
                    if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                        val addresses = ni.inetAddresses
                        for (addr in Collections.list(addresses)) {
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val ip = addr.hostAddress
                                if (!ip.isNullOrEmpty()) return ip
                            }
                        }
                    }
                }
                // 2. Fallback to any other active non-loopback IPv4 interface
                for (ni in list) {
                    if (ni.isLoopback || !ni.isUp) continue
                    val addresses = ni.inetAddresses
                    for (addr in Collections.list(addresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrEmpty()) return ip
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP", e)
            }
            return "192.168.1.129"
        }
    }
}
