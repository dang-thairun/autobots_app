package com.autobots.camera.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Queries for the upload queue.
 *
 * **Only aggregates and bounded pages are exposed as [Flow].** Nothing here returns a live
 * list of the whole table — see [UploadQueueCounts] for why.
 */
@Dao
interface UploadDao {

    /**
     * Idempotent by `relativeKey`: a photo already queued is left exactly as it is, whatever
     * state it reached. `IGNORE` rather than `REPLACE` because replacing would reset a row
     * that may already be [UploadStatus.Success] and cause a re-upload.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<UploadItem>): List<Long>

    @Query("SELECT status, COUNT(*) AS count FROM upload_queue GROUP BY status")
    fun observeStatusCounts(): Flow<List<UploadStatusCount>>

    @Query(
        """
        SELECT * FROM upload_queue
        ORDER BY id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun observePage(limit: Int, offset: Int): Flow<List<UploadItem>>

    @Query(
        """
        SELECT * FROM upload_queue
        WHERE sessionId = :sessionId
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun observeSession(sessionId: String, limit: Int): Flow<List<UploadItem>>

    /**
     * How much work is left, right now. A one-shot read rather than a Flow: the worker asks
     * once to size its progress bar and must not hold a subscription open for the run.
     */
    @Query("SELECT COUNT(*) FROM upload_queue WHERE status IN ('Pending', 'Uploading', 'Uploaded', 'Failed')")
    suspend fun countOutstanding(): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE sessionId = :sessionId AND status = :status")
    fun observeSessionCount(sessionId: String, status: UploadStatus): Flow<Int>

    /**
     * The worker's claim query — a plain suspend read, never a [Flow]. `Uploaded` sorts first
     * so a row that already has its bytes in the bucket only needs the cheap completion call
     * and leaves the queue immediately.
     */
    @Query(
        """
        SELECT * FROM upload_queue
        WHERE status IN (:statuses) AND nextAttemptAtMs <= :nowMs
        ORDER BY CASE status WHEN 'Uploaded' THEN 0 ELSE 1 END, id ASC
        LIMIT :limit
        """,
    )
    suspend fun claimable(
        statuses: List<UploadStatus>,
        nowMs: Long,
        limit: Int,
    ): List<UploadItem>

    @Query(
        """
        UPDATE upload_queue
        SET status = :status, lastError = :error, updatedAtMs = :nowMs
        WHERE id = :id
        """,
    )
    suspend fun setStatus(id: Long, status: UploadStatus, error: String?, nowMs: Long)

    /**
     * Bytes are up. Records what the storage side called the object, which the completion
     * call needs and cannot always be recomputed — see [UploadItem.remoteKey].
     */
    @Query(
        """
        UPDATE upload_queue
        SET status = 'Uploaded', remoteKey = :key, remoteUri = :uri,
            lastError = NULL, updatedAtMs = :nowMs
        WHERE id = :id
        """,
    )
    suspend fun setUploaded(id: Long, key: String, uri: String, nowMs: Long)

    @Query(
        """
        UPDATE upload_queue
        SET status = :status,
            attemptCount = attemptCount + 1,
            nextAttemptAtMs = :nextAttemptAtMs,
            lastError = :error,
            updatedAtMs = :nowMs
        WHERE id = :id
        """,
    )
    suspend fun setFailure(
        id: Long,
        status: UploadStatus,
        nextAttemptAtMs: Long,
        error: String?,
        nowMs: Long,
    )

    /**
     * A process killed mid-upload leaves rows stranded in [UploadStatus.Uploading] that no
     * worker will ever claim again. Called once when the queue is opened.
     *
     * They go back to [UploadStatus.Pending], not to [UploadStatus.Uploaded] — a PUT that was
     * interrupted proves nothing about whether the bucket got the whole object, and re-sending
     * is safe because the key is deterministic and overwrites (`docs/PHASES.md` §2.3).
     */
    @Query(
        """
        UPDATE upload_queue
        SET status = 'Pending', updatedAtMs = :nowMs
        WHERE status = 'Uploading'
        """,
    )
    suspend fun resetInterrupted(nowMs: Long): Int

    /**
     * Human retry: clears the backoff and the attempt count so the row starts over.
     *
     * A row that already has its bytes in the bucket goes back to [UploadStatus.Uploaded],
     * not to [UploadStatus.Pending] — resetting it to Pending would re-send a file that is
     * already there, which is the exact waste the Uploaded state exists to prevent.
     */
    @Query(
        """
        UPDATE upload_queue
        SET status = CASE
                WHEN remoteKey IS NOT NULL AND remoteUri IS NOT NULL THEN 'Uploaded'
                ELSE 'Pending'
            END,
            attemptCount = 0, nextAttemptAtMs = 0,
            lastError = NULL, updatedAtMs = :nowMs
        WHERE status IN ('Failed', 'Abandoned')
        """,
    )
    suspend fun retryAllFailed(nowMs: Long): Int

    /**
     * **Debug only.** Puts every row back to square one, forgetting that the bytes were ever
     * sent. Exists so the crash-resume path can be exercised on demand instead of waiting for
     * a real interruption — see `MainActivity.applyDebugUploadFailures`.
     */
    @Query(
        """
        UPDATE upload_queue
        SET status = 'Pending', attemptCount = 0, nextAttemptAtMs = 0,
            lastError = NULL, remoteKey = NULL, remoteUri = NULL, updatedAtMs = :nowMs
        """,
    )
    suspend fun debugRequeueAll(nowMs: Long): Int

    @Query("SELECT * FROM upload_queue WHERE id = :id")
    suspend fun byId(id: Long): UploadItem?

    @Query("SELECT COUNT(*) FROM upload_queue")
    suspend fun count(): Int
}
