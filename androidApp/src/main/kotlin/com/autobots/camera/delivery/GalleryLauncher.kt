package com.autobots.camera.delivery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast

object GalleryLauncher {
    fun open(
        context: Context,
        lastImageUri: Uri?,
    ) {
        val intent = when {
            lastImageUri != null -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(lastImageUri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(context, "Cannot open gallery: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
