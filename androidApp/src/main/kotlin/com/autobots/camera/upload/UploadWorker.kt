package com.autobots.camera.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Drains the upload queue.
 *
 * **One worker for the whole queue, not one per photo.** A `WorkRequest` per file would put
 * hundreds of rows in the system's `WorkSpec` table for a single session, give no ordering,
 * and pay the scheduler's overhead on every 800 KB JPEG. This claims a batch, works through
 * it, claims the next, and stops when there is nothing due.
 *
 * Everything it knows about the backend comes through [UploadTransport]; everything it knows
 * about persistence comes through [UploadRepository]. See `docs/PHASES.md` B3c.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val repo = UploadRepository.create(context)
    private val settings = UploadSettings(context)
    private val resolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext

    /** Where this run is sending photos, for the notification. Set once the transport is picked. */
    private var transportLabel: String = ""

    override suspend fun doWork(): Result {
        if (settings.isPaused) {
            Log.i(TAG, "Paused — nothing to do")
            return Result.success()
        }

        val transport = selectTransport() ?: return Result.success()
        transportLabel = transport.destinationLabel

        // Promote to a foreground service for the length of the drain. This is what buys the
        // network and the CPU while the device is idle; without it Doze cancels the run and
        // the queue stops half-finished (docs/PHASES.md B3f-0). Failing to promote is not
        // fatal — the run continues as ordinary background work, which is what happened
        // before this existed.
        val queued = runCatching { repo.outstandingCount() }.getOrDefault(0)
        runCatching { setForeground(foregroundInfo(0, queued)) }
            .onFailure { Log.w(TAG, "Could not run in foreground: ${it.message}") }

        // Rows a previous run left mid-flight. Nothing else would ever claim them again.
        runCatching { repo.resetInterrupted() }

        var done = 0
        var failed = 0
        while (true) {
            if (settings.isPaused) break
            val batch = runCatching { repo.claimable() }.getOrElse {
                Log.e(TAG, "Cannot read queue", it)
                return Result.retry()
            }
            if (batch.isEmpty()) break

            var progressed = false
            for (item in batch) {
                if (settings.isPaused) break
                when (uploadOne(item, transport)) {
                    Outcome.Done -> { done++; progressed = true }
                    Outcome.Failed -> { failed++; progressed = true }
                    Outcome.QueuePaused -> return finish(done, failed)
                }
                runCatching { setForeground(foregroundInfo(done + failed, queued, failed)) }
            }
            // Guard against a batch that changes nothing — without it a row that stayed
            // claimable would be re-read forever.
            if (!progressed) break
        }
        return finish(done, failed)
    }

    private fun finish(done: Int, failed: Int): Result {
        Log.i(TAG, "Run finished: $done uploaded, $failed failed")
        return Result.success()
    }

    private suspend fun uploadOne(item: UploadItem, transport: UploadTransport): Outcome {
        val suggestedKey = item.relativeKey
        // Decides what a later failure means: before this flips, a retry must re-send the
        // file; after it, the file is in the bucket and only the completion call is owed.
        var bytesUp = item.status == UploadStatus.Uploaded && item.remoteKey != null
        return try {
            // Already in the bucket — only the completion call is left. Re-sending the bytes
            // here is the waste that the Uploaded state exists to prevent.
            if (item.status == UploadStatus.Uploaded && item.remoteKey != null && item.remoteUri != null) {
                transport.complete(item, PresignResult("", item.remoteKey, item.remoteUri))
                repo.markSuccess(item.id)
                return Outcome.Done
            }

            repo.markUploading(item.id)
            val target = transport.presign(item, suggestedKey)

            val stream = try {
                resolver.openInputStream(android.net.Uri.parse(item.contentUri))
            } catch (t: FileNotFoundException) {
                null
            } ?: throw UploadException.Permanent("photo is no longer in the gallery")

            stream.use { transport.put(target, it, item.sizeBytes, UploadTransport.JPEG_CONTENT_TYPE) }
            repo.markUploaded(item.id, target.key, target.uri)
            bytesUp = true

            transport.complete(item, target)
            repo.markSuccess(item.id)
            Outcome.Done
        } catch (t: CancellationException) {
            throw t
        } catch (t: UploadException.Unauthorized) {
            // Not this photo's problem — every other row would fail the same way, and on a
            // backend where presign needs no auth each retry would upload a whole file before
            // finding out. Stop the queue and leave the row where it is.
            Log.w(TAG, "Auth rejected, pausing queue", t)
            settings.setPaused(true, t.message ?: "Sign-in required")
            Outcome.QueuePaused
        } catch (t: UploadException.Permanent) {
            Log.w(TAG, "Giving up on ${item.fileName}: ${t.message}")
            repo.markAbandoned(item.id, t.message)
            Outcome.Failed
        } catch (t: Throwable) {
            // Unclassified failures are treated as retryable on purpose: being wrong that way
            // costs a delayed retry, the other way loses a photo.
            Log.w(TAG, "Retryable failure on ${item.fileName}: ${t.message}")
            repo.markFailed(
                id = item.id,
                error = t.message ?: t::class.java.simpleName,
                attemptCount = item.attemptCount,
                bytesUploaded = bytesUp,
            )
            Outcome.Failed
        }
    }

    /**
     * Which backend this run talks to, decided once so a single drain cannot switch halfway.
     *
     * @return null when there is nowhere to send anything. That is a **successful** run doing
     *   nothing, not a failure: an unconfigured or signed-out device should stop quietly and
     *   wait to be told, and both sign-in and saving a configuration re-schedule the work.
     *   The alternative — falling back to the local sink — would report photos as uploaded
     *   while they sat in a folder on the phone.
     */
    private suspend fun selectTransport(): UploadTransport? {
        val config = settings.readConfig()
        val token = UploadSession.token ?: signInWithRememberedCredentials(config)
        return when {
            config.isComplete && token != null -> RunxUploadTransport(config, token)
            settings.useFakeTransport -> FakeUploadTransport(appContext, settings)
            !config.isComplete -> {
                Log.i(TAG, "No backend configured (${config.validate()}) — standing by")
                null
            }
            else -> {
                Log.i(TAG, "Not signed in — standing by")
                null
            }
        }
    }

    private fun foregroundInfo(done: Int, total: Int, failed: Int = 0): ForegroundInfo {
        val config = settings.readConfig()
        val notification = UploadNotification.build(
            context = applicationContext,
            done = done,
            total = total,
            failed = failed,
            eventTitle = config.eventTitle.ifBlank { config.eventId },
            destination = transportLabel,
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UploadNotification.ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(UploadNotification.ID, notification)
        }
    }

    /**
     * WorkManager asks for this when it decides to run the work expedited. Answering keeps
     * that path open; the drain calls [setForeground] itself either way.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private enum class Outcome { Done, Failed, QueuePaused }

    /**
     * Get a token back without a human.
     *
     * The token lives in memory only ([UploadSession]), which means every process death takes
     * it with it — and the background test showed the process dying while the device idled,
     * leaving a full queue and a worker that could only stand by. When the operator has ticked
     * *remember*, the credentials to fix that are already on the device, so the worker uses
     * them rather than waiting for someone to come back and tap.
     *
     * @return the new token, or null when nothing is stored or the sign-in was refused. A
     *   refusal is left quiet on purpose: the row is untouched and the screen already says
     *   what is wrong.
     */
    private suspend fun signInWithRememberedCredentials(config: UploadConfig): String? {
        val saved = settings.remembered.value ?: return null
        if (config.signInProblem() != null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                RunxAuthClient.login(
                    graphqlUrl = config.graphqlUrl,
                    platform = config.platform,
                    username = saved.username,
                    password = saved.password,
                )
            }.onSuccess {
                Log.i(TAG, "Signed in again as ${it.username} from stored credentials")
                UploadSession.signIn(it)
            }.onFailure {
                Log.w(TAG, "Stored credentials rejected: ${it.message}")
            }.getOrNull()?.token
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
    }
}
