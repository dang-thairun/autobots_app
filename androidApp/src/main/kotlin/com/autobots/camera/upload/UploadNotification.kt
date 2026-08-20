package com.autobots.camera.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The notification that keeps the upload worker alive.
 *
 * Not decoration: on Android this notification **is** the foreground service. Without it the
 * system defers the work in Doze and cuts the network — measured at 6 photos of 15 in seven
 * minutes before the queue stopped (`docs/PHASES.md` B3f-0).
 *
 * **The count lives in the title.** HyperOS's collapsed notification drops `contentText`
 * entirely once a progress bar is present, which left a bar and nothing else — a bar with no
 * number tells the operator the app is busy but not whether it is nearly done or barely
 * started. Title and sub-text survive that layout, so the numbers go there and the expanded
 * view carries the rest.
 *
 * Low importance and silent by design: it is a progress indicator, not an alert, and it shows
 * only while the queue has work.
 */
internal object UploadNotification {

    const val ID = 4711

    /**
     * @param done rows finished this run, successes and give-ups alike — it is a measure of
     *   progress through the queue, not of success.
     * @param failed shown only when non-zero; a "0 failed" line is noise on a healthy run.
     */
    fun build(
        context: Context,
        done: Int,
        total: Int,
        failed: Int = 0,
        eventTitle: String = "",
        destination: String = "",
    ): Notification {
        ensureChannel(context)

        val percent = if (total > 0) (done * 100) / total else 0
        val title = when {
            total <= 0 -> "Uploading photos"
            done >= total -> "Finishing up · $total photos"
            else -> "Uploading $done/$total · $percent%"
        }
        val detail = buildString {
            append(if (total > 0) "${total - done} left" else "Working")
            if (failed > 0) append(" · $failed failed")
            if (destination.isNotBlank()) append(" · $destination")
        }

        return NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(detail)
            // Header line, next to the app name. Survives the collapsed layout on every
            // launcher tried so far, which is why the event goes here.
            .apply { if (eventTitle.isNotBlank()) setSubText(eventTitle) }
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            // Updated once per photo; without this some launchers re-announce every update.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                // Determinate only when the size of the job is known; an indeterminate bar
                // that never resolves reads as a hang.
                if (total > 0) setProgress(total, done, false)
            }
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo upload", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while photos are being uploaded"
                setShowBadge(false)
            },
        )
    }

    private const val CHANNEL = "autobots_upload"
}
