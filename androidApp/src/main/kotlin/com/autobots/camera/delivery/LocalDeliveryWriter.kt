package com.autobots.camera.delivery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Local Delivery — publish JPEG into DCIM/AutoBots via MediaStore (gallery-visible).
 */
class LocalDeliveryWriter(
    context: Context,
) {
    private val resolver = context.applicationContext.contentResolver

    fun publish(file: File): Uri? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Skip missing/empty file: ${file.name}")
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore insert failed for ${file.name}")
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                Log.e(TAG, "No output stream for $uri")
                resolver.delete(uri, null, null)
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "Delivered ${file.name} → $uri")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish ${file.name}", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    companion object {
        private const val TAG = "LocalDelivery"
        const val RELATIVE_PATH = "DCIM/AutoBots"
        const val ALBUM_NAME = "AutoBots"
    }
}
