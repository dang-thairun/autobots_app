package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun NetworkUrlPage(
    state: OperatorUiState,
    onBack: () -> Unit,
    onCheck: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPermission = rememberCameraPermissionState()
    var url by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf(false) }

    LaunchedEffect(cameraPermission.granted, pendingScan) {
        if (cameraPermission.granted && pendingScan) {
            scanning = true
            pendingScan = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "←  Network URL",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(bottom = 4.dp),
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Video URL") },
            placeholder = { Text("https://…/clip.mp4") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { if (url.isNotBlank() && !state.isCheckingNetworkUrl) onCheck(url) },
            ),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    when {
                        scanning -> scanning = false
                        cameraPermission.granted -> scanning = true
                        else -> {
                            pendingScan = true
                            cameraPermission.request()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color.White.copy(alpha = 0.14f),
                    disabledContentColor = Color.White.copy(alpha = 0.55f),
                ),
            ) {
                Text(
                    text = when {
                        scanning -> "Close scanner"
                        cameraPermission.granted -> "Scan QR"
                        else -> "Allow camera"
                    },
                    maxLines = 1,
                )
            }
            Button(
                onClick = { onCheck(url) },
                enabled = url.isNotBlank() &&
                    !state.isCheckingNetworkUrl &&
                    !state.isDownloading &&
                    state.canImportVideo,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color.White.copy(alpha = 0.14f),
                    disabledContentColor = Color.White.copy(alpha = 0.55f),
                ),
            ) {
                Text(
                    text = if (state.isDownloading) {
                        "Downloading…"
                    } else if (state.isCheckingNetworkUrl) {
                        "Checking…"
                    } else {
                        "Check"
                    },
                    maxLines = 1,
                )
            }
        }

        val error = state.networkUrlError
        if (error != null) {
            Text(
                text = error,
                color = Color(0xFFEF9A9A),
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(
                text = "Direct video file only. Scan a QR to fill the URL, then Check.",
                color = Color(0xFF78909C),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (scanning && cameraPermission.granted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                QrScanPreview(
                    onQr = { scanned ->
                        url = scanned
                        scanning = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
