package com.autobots.camera.delivery

import android.os.Environment
import com.autobots.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a session's output lands, and what it is called.
 *
 * The album stays **flat** under `DCIM/AutoBots/` — the app version is carried in the folder
 * *name* instead of a directory level, so field runs across a version bump can still be told
 * apart without doubling the folder depth a phone file browser has to walk.
 *
 * `ext_v0_1_3_07082026_1415` · `v0_1_3_20260806_140532`
 */
object SessionAlbumNaming {

    /**
     * `v0_1_3` — from `gradle.properties` via BuildConfig, so it cannot drift from the build.
     * Dots become underscores because the tag sits inside a filename-like folder name.
     */
    val versionTag: String = "v" + BuildConfig.VERSION_NAME.replace(Regex("[^A-Za-z0-9]"), "_")

    /** Video import → `ext_v0_1_3_07082026_1415` from session start time. */
    fun importFolder(startedAtEpochMs: Long): String {
        val stamp = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date(startedAtEpochMs))
        return "ext_${versionTag}_$stamp"
    }

    /** Live capture → `v0_1_3_20260806_140532` from session start time. */
    fun liveFolder(startedAtEpochMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtEpochMs))
        return "${versionTag}_$stamp"
    }

    /** `DCIM/AutoBots/ext_v0_1_3_07082026_1415` — JPEG deliverables. */
    fun galleryRelativePath(albumSubfolder: String): String =
        albumPath(LocalDeliveryWriter.RELATIVE_PATH, albumSubfolder)

    /** `Download/AutoBots/ext_v0_1_3_07082026_1415` — session log and perf report. */
    fun downloadsRelativePath(albumSubfolder: String): String =
        albumPath(
            "${Environment.DIRECTORY_DOWNLOADS}/${LocalDeliveryWriter.ALBUM_NAME}",
            albumSubfolder,
        )

    private fun albumPath(root: String, albumSubfolder: String): String =
        if (albumSubfolder.isEmpty()) root else "$root/$albumSubfolder"
}
