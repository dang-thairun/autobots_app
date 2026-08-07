package com.autobots.camera.delivery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionAlbumNaming {
    private val invalidChars = Regex("""[\\/:*?"<>|]""")

    /** `aa11.mp4` → `aa11_extraction` */
    fun importFolder(videoDisplayName: String): String {
        val base = videoDisplayName.substringBeforeLast('.').ifEmpty { videoDisplayName }
        val sanitized = sanitize(base).ifEmpty { "video" }
        return "${sanitized}_extraction"
    }

    /** Live capture → `20260806_140532` from session start time. */
    fun liveFolder(startedAtEpochMs: Long): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtEpochMs))
    }

    fun galleryRelativePath(albumSubfolder: String): String {
        return if (albumSubfolder.isNotEmpty()) {
            "${LocalDeliveryWriter.RELATIVE_PATH}/$albumSubfolder"
        } else {
            LocalDeliveryWriter.RELATIVE_PATH
        }
    }

    private fun sanitize(name: String): String {
        return name.trim()
            .replace(invalidChars, "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(64)
    }
}
