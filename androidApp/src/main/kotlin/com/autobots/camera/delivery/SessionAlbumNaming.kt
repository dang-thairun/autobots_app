package com.autobots.camera.delivery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionAlbumNaming {
    /** Video import → `ext_07082026_1415` from session start time. */
    fun importFolder(startedAtEpochMs: Long): String {
        val stamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date(startedAtEpochMs))
        return "ext_$stamp"
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
}
