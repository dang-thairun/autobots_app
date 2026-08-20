package com.autobots.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Permission to show the upload progress notification (API 33+).
 *
 * Asked when auto-upload is switched on rather than at launch, because that is the moment it
 * means something. **Refusing it does not stop uploads** — the foreground service still runs
 * and the queue still drains; the operator just cannot watch it from the status bar.
 */
@Composable
fun rememberNotificationPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var granted by remember {
        mutableStateOf(
            !needed || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    return CameraPermissionState(
        granted = granted,
        request = { if (needed && !granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
    )
}
