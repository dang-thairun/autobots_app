package com.autobots.camera.delivery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Local Delivery — publish JPEG into DCIM/AutoBots via MediaStore (gallery-visible).
 * Photos can be grouped into a per-session subfolder under AutoBots.
 */
class LocalDeliveryWriter(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Subfolder under DCIM/AutoBots, e.g. `aa11_extraction` or `20260806_140532`. */
    @Volatile
    var albumSubfolder: String = ""

    fun publish(file: File): Uri? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Skip missing/empty file: ${file.name}")
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, galleryRelativePath())
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
            return legacyPublish(file)
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                Log.e(TAG, "No output stream for $uri")
                resolver.delete(uri, null, null)
                return legacyPublish(file)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.i(TAG, "Delivered ${file.name} → $uri (${galleryRelativePath()})")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish ${file.name}", t)
            runCatching { resolver.delete(uri, null, null) }
            legacyPublish(file)
        }
    }

    fun publishText(fileName: String, content: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, galleryRelativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return legacyPublishText(fileName, content)
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: run {
                    resolver.delete(uri, null, null)
                    return legacyPublishText(fileName, content)
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Log.i(TAG, "Delivered $fileName → $uri (${galleryRelativePath()})")
                uri
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to publish $fileName", t)
                runCatching { resolver.delete(uri, null, null) }
                legacyPublishText(fileName, content)
            }
        }
        return legacyPublishText(fileName, content)
    }

    private fun galleryRelativePath(): String =
        SessionAlbumNaming.galleryRelativePath(albumSubfolder)

    private fun legacyAlbumDir(): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            ALBUM_NAME,
        )
        return if (albumSubfolder.isNotEmpty()) File(base, albumSubfolder) else base
    }

    private fun legacyPublish(file: File): Uri? {
        return try {
            val dir = legacyAlbumDir().apply { mkdirs() }
            val dest = File(dir, file.name)
            file.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Delivered ${file.name} → ${dest.absolutePath} (legacy)")
            Uri.fromFile(dest)
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy publish failed for ${file.name}", t)
            null
        }
    }

    private fun legacyPublishText(fileName: String, content: String): Uri? {
        return try {
            val dir = legacyAlbumDir().apply { mkdirs() }
            val dest = File(dir, fileName)
            dest.writeText(content, Charsets.UTF_8)
            Log.i(TAG, "Delivered $fileName → ${dest.absolutePath} (legacy)")
            Uri.fromFile(dest)
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy text publish failed for $fileName", t)
            null
        }
    }

    companion object {
        private const val TAG = "LocalDelivery"
        const val RELATIVE_PATH = "DCIM/AutoBots"
        const val ALBUM_NAME = "AutoBots"
        const val SESSION_LOG_FILE = "session_log.txt"
    }
}
