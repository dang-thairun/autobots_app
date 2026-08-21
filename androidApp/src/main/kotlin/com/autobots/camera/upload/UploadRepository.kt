package com.autobots.camera.upload

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only thing the pipeline, the worker and the UI know about upload storage.
 *
 * Everything behind it is replaceable: Room today, hand-written SQLite if KSP ever becomes a
 * problem (`docs/PHASES.md` §9). Nothing above this interface refers to a Room type.
 */
interface UploadRepository {

    /** Totals for the Home badge and the status card. Never the rows themselves. */
    fun observeCounts(): Flow<UploadQueueCounts>

    /** A bounded page for the queue screen, newest first. */
    fun observePage(limit: Int = DEFAULT_PAGE, offset: Int = 0): Flow<List<UploadItem>>

    /** One page of a single status, filtered by the database rather than by the screen. */
    fun observePageOf(
        status: UploadStatus,
        limit: Int = DEFAULT_PAGE,
        offset: Int = 0,
    ): Flow<List<UploadItem>>

    fun observeSession(sessionId: String, limit: Int = DEFAULT_PAGE): Flow<List<UploadItem>>

    /** How many of one session's photos have reached [UploadStatus.Success]. */
    fun observeSessionSuccessCount(sessionId: String): Flow<Int>

    /**
     * Add photos. Idempotent per `{sessionId}/{fileName}` — offering the same photo twice is
     * a no-op, whatever state the first copy reached.
     *
     * @return how many rows were actually inserted.
     */
    suspend fun enqueue(candidates: List<UploadCandidate>): Int

    /** Empty the queue. Records only — the photos stay in the gallery. */
    suspend fun clearAll(): Int

    /** Rows still owed an upload, counted once. Used to size the progress notification. */
    suspend fun outstandingCount(): Int

    /** Work a worker may pick up right now, oldest first, [UploadStatus.Uploaded] ahead of the rest. */
    suspend fun claimable(nowMs: Long = System.currentTimeMillis(), limit: Int = CLAIM_BATCH): List<UploadItem>

    suspend fun markUploading(id: Long)

    /** Bytes are in the bucket; metadata not committed yet. Stores what it ended up called. */
    suspend fun markUploaded(id: Long, key: String, uri: String)

    suspend fun markSuccess(id: Long)

    /**
     * Retryable failure — schedules the next attempt with exponential backoff.
     *
     * @param bytesUploaded true when the object is already in the bucket and only the
     *   completion call failed. Such a row stays [UploadStatus.Uploaded] so the retry sends
     *   the completion call alone; demoting it to [UploadStatus.Failed] would make the next
     *   attempt re-send the whole file.
     */
    suspend fun markFailed(id: Long, error: String?, attemptCount: Int, bytesUploaded: Boolean = false)

    /** Permanent failure. Only a human retry moves it out of here. */
    suspend fun markAbandoned(id: Long, error: String?)

    /** Put rows stranded in [UploadStatus.Uploading] by a killed process back in the queue. */
    suspend fun resetInterrupted(): Int

    /** Operator pressed *Retry failed*. */
    suspend fun retryAllFailed(): Int

    /** **Debug only.** Rewind every row to Pending so the drain can be run again. */
    suspend fun debugRequeueAll(): Int

    companion object {
        const val DEFAULT_PAGE = 200
        const val CLAIM_BATCH = 20

        /** Backoff for [markFailed]: 30 s, 1 m, 2 m, … capped at 30 m. */
        fun backoffDelayMs(attemptCount: Int): Long {
            val step = attemptCount.coerceIn(0, MAX_BACKOFF_SHIFT)
            return (BASE_BACKOFF_MS shl step).coerceAtMost(MAX_BACKOFF_MS)
        }

        /** Past this many network attempts a row is [UploadStatus.Abandoned]. */
        const val MAX_ATTEMPTS = 8

        private const val BASE_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val MAX_BACKOFF_SHIFT = 6

        fun relativeKey(sessionId: String, fileName: String): String = "$sessionId/$fileName"

        fun create(context: Context): UploadRepository =
            RoomUploadRepository(UploadDatabase.get(context).uploadDao())
    }
}

class RoomUploadRepository(
    private val dao: UploadDao,
) : UploadRepository {

    override fun observeCounts(): Flow<UploadQueueCounts> =
        dao.observeStatusCounts().map(UploadQueueCounts::from)

    override fun observePage(limit: Int, offset: Int): Flow<List<UploadItem>> =
        dao.observePage(limit, offset)

    override fun observePageOf(status: UploadStatus, limit: Int, offset: Int): Flow<List<UploadItem>> =
        dao.observePageByStatus(status, limit, offset)

    override fun observeSession(sessionId: String, limit: Int): Flow<List<UploadItem>> =
        dao.observeSession(sessionId, limit)

    override fun observeSessionSuccessCount(sessionId: String): Flow<Int> =
        dao.observeSessionCount(sessionId, UploadStatus.Success)

    override suspend fun enqueue(candidates: List<UploadCandidate>): Int {
        if (candidates.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val rows = candidates.map { candidate ->
            UploadItem(
                contentUri = candidate.contentUri,
                fileName = candidate.fileName,
                sessionId = candidate.sessionId,
                relativeKey = UploadRepository.relativeKey(candidate.sessionId, candidate.fileName),
                capturedAtMs = candidate.capturedAtMs,
                sizeBytes = candidate.sizeBytes,
                createdAtMs = now,
                updatedAtMs = now,
            )
        }
        // -1 marks a row the unique index rejected, i.e. a photo already queued.
        val inserted = dao.insertIgnore(rows).count { it != -1L }
        if (inserted != rows.size) {
            Log.i(TAG, "Enqueued $inserted/${rows.size} (rest already queued)")
        }
        return inserted
    }

    override suspend fun clearAll(): Int = dao.clearAll()

    override suspend fun outstandingCount(): Int = dao.countOutstanding()

    override suspend fun claimable(nowMs: Long, limit: Int): List<UploadItem> =
        dao.claimable(UploadStatus.CLAIMABLE, nowMs, limit)

    override suspend fun markUploading(id: Long) =
        dao.setStatus(id, UploadStatus.Uploading, null, System.currentTimeMillis())

    override suspend fun markUploaded(id: Long, key: String, uri: String) =
        dao.setUploaded(id, key, uri, System.currentTimeMillis())

    override suspend fun markSuccess(id: Long) =
        dao.setStatus(id, UploadStatus.Success, null, System.currentTimeMillis())

    override suspend fun markFailed(
        id: Long,
        error: String?,
        attemptCount: Int,
        bytesUploaded: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (attemptCount + 1 >= UploadRepository.MAX_ATTEMPTS) {
            // Abandoned either way, but remoteKey/remoteUri stay on the row, so a human retry
            // resumes at the completion call rather than re-uploading.
            dao.setFailure(id, UploadStatus.Abandoned, 0L, error, now)
            return
        }
        dao.setFailure(
            id = id,
            status = if (bytesUploaded) UploadStatus.Uploaded else UploadStatus.Failed,
            nextAttemptAtMs = now + UploadRepository.backoffDelayMs(attemptCount),
            error = error,
            nowMs = now,
        )
    }

    override suspend fun markAbandoned(id: Long, error: String?) =
        dao.setStatus(id, UploadStatus.Abandoned, error, System.currentTimeMillis())

    override suspend fun resetInterrupted(): Int {
        val reset = dao.resetInterrupted(System.currentTimeMillis())
        if (reset > 0) Log.w(TAG, "Requeued $reset row(s) interrupted mid-upload")
        return reset
    }

    override suspend fun retryAllFailed(): Int = dao.retryAllFailed(System.currentTimeMillis())

    override suspend fun debugRequeueAll(): Int = dao.debugRequeueAll(System.currentTimeMillis())

    private companion object {
        const val TAG = "UploadRepository"
    }
}
