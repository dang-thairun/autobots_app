package com.autobots.camera.delivery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast

/**
 * Open the phone's gallery on its own home screen.
 *
 * Deliberately **not** `ACTION_VIEW` on the last photo. That resolves to whichever
 * single-photo viewer is installed — on this device Photos Go's `ExternalOneUpActivity` —
 * which opens, reads the file, finishes itself and drops the operator straight back into
 * AutoBots. Looked exactly like the button doing nothing.
 *
 * `CATEGORY_APP_GALLERY` is the platform's own way of saying "the gallery app, at its front
 * door", so a session's photos are found the same way the operator finds any other photos on
 * the phone.
 */
object GalleryLauncher {
    fun open(context: Context) {
        val candidates = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Nothing claims CATEGORY_APP_GALLERY on some builds; a typed VIEW on the whole
            // image collection still lands on a grid rather than on one photo.
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            } catch (t: Throwable) {
                Toast.makeText(context, "Cannot open gallery: ${t.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(context, "No gallery app found", Toast.LENGTH_SHORT).show()
    }
}
