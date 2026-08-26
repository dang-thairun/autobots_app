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

    /** Subfolder under DCIM/AutoBots, e.g. `ext_v0_1_3_07082026_1415` or `v0_1_3_20260806_140532`. */
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

    /**
     * Session log / plain text next to JPEGs under DCIM/AutoBots when possible.
     * MediaStore.Files does not allow RELATIVE_PATH under DCIM on API 29+ (crashes on insert).
     */
    fun publishText(fileName: String, content: String): Uri? {
        val legacy = legacyPublishText(fileName, content)
        if (legacy != null) return legacy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishTextToDownloads(fileName, content)
        }
        return null
    }

    private fun publishTextToDownloads(fileName: String, content: String): Uri? {
        val relativePath = SessionAlbumNaming.downloadsRelativePath(albumSubfolder)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Delivered $fileName → $uri ($relativePath)")
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish $fileName to Downloads", t)
            null
        }
    }

    /**
     * MediaStore rewrites DISPLAY_NAME to match MIME_TYPE, so `perf_report.json`
     * declared as text/plain would land as `perf_report.json.txt` — and `photos.csv`
     * did exactly that until `.csv` was listed here too. Every extension this project
     * writes needs an entry; the default is the trap, not the safety net.
     */
    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".json", ignoreCase = true) -> "application/json"
        fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
        else -> "text/plain"
    }

    private fun galleryRelativePath(): String =
        SessionAlbumNaming.galleryRelativePath(albumSubfolder)

    /** Pre-API-29 path — same flat `AutoBots/{session}` shape as the MediaStore one. */
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
