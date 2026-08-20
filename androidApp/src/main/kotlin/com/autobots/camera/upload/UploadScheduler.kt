package com.autobots.camera.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * When the queue gets drained.
 *
 * A single named job that the whole queue shares. Asking again while it is already scheduled
 * is a no-op ([ExistingWorkPolicy.KEEP]) — the pipeline calls this after every delivered
 * photo, and a session produces hundreds.
 */
object UploadScheduler {

    /**
     * `CONNECTED` rather than `UNMETERED`: the field uses Wi-Fi and mobile data alike, so
     * there is no Wi-Fi-only mode (`docs/PHASES.md` §2.6).
     *
     * `requiresBatteryNotLow` is the conservative half of that decision, kept until a field
     * test says whether these devices run on power.
     */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Ask for a drain. Safe to call from anywhere, as often as you like.
     *
     * Does nothing while paused — otherwise resuming would have to compete with a backlog of
     * jobs queued during the pause.
     */
    fun ensureScheduled(context: Context) {
        if (UploadSettings(context).isPaused) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            // Outer backoff, for a run that fell over as a whole. Per-row backoff lives in
            // the queue itself, so one poisoned photo cannot hold up the rest.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Stop claiming new photos. The one in flight finishes; it is seconds of work. */
    fun pause(context: Context, reason: String? = null) {
        UploadSettings(context).setPaused(true, reason)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun resume(context: Context) {
        UploadSettings(context).setPaused(false)
        ensureScheduled(context)
    }

    private const val WORK_NAME = "autobots_upload_queue"
}
