package com.autobots.camera.upload

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo waiting to reach the bucket.
 *
 * **The row points at MediaStore, not at a file path.** `WriteQueue` deletes the cache JPEG
 * the moment `LocalDeliveryWriter` publishes it, so by the time anything lands here the only
 * copy left is the one in `DCIM/AutoBots/…` addressed by [contentUri]. That copy belongs to
 * the operator — it can disappear from under us at any time, and the uploader has to read
 * that as [UploadStatus.Abandoned] rather than as a network fault. See `docs/PHASES.md` §2.1.
 *
 * @property relativeKey `{sessionId}/{fileName}` — the object key **without** the device
 *   prefix. The device id is configured separately and can be re-issued, so keeping it out of
 *   the row means re-configuring a device does not invalidate a queue full of keys; the
 *   transport prepends it at presign time. Unique, which is what makes [UploadRepository.enqueue]
 *   idempotent: re-running an extraction that produces the same filename in the same album
 *   updates nothing and inserts nothing.
 */
@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["relativeKey"], unique = true),
        // The worker's claim query filters on exactly these two, in this order.
        Index(value = ["status", "nextAttemptAtMs"]),
        Index(value = ["sessionId"]),
    ],
)
data class UploadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** `content://media/…` handed back by `LocalDeliveryWriter.publish`. */
    val contentUri: String,

    /** Display name as published, e.g. `face_00123.jpg`. */
    val fileName: String,

    /** The session's album folder (`ext_v0_1_5_18082026_1430`) — the same string the photos
     * live under in DCIM, so a row can always be traced back to what the operator sees. */
    val sessionId: String,

    /** `{sessionId}/{fileName}`. See the class doc for why the device id is not in here. */
    val relativeKey: String,

    val status: UploadStatus = UploadStatus.Pending,

    /** When the JPEG was written, not when it was queued — this is the value the backend
     * stores as capture time. */
    val capturedAtMs: Long,

    val sizeBytes: Long,

    /** Attempts that reached the network. Reset to 0 by a human retry. */
    @ColumnInfo(defaultValue = "0")
    val attemptCount: Int = 0,

    /** Per-row backoff. Survives a reboot, which `WorkManager`'s own backoff does not do at
     * row granularity — a single poisoned photo must not hold up the rest of the queue. */
    @ColumnInfo(defaultValue = "0")
    val nextAttemptAtMs: Long = 0,

    val lastError: String? = null,

    /**
     * Object key the storage side actually used, once known.
     *
     * Null until the bytes are up. It is stored rather than recomputed because the key is not
     * always ours to choose: the Runx `photoUpload` mutation mints one server-side and the
     * completion call needs that exact value back (docs/PHASES.md §10). Without this column a
     * row in [UploadStatus.Uploaded] could never finish — the bytes would be in the bucket
     * with no way to name them.
     */
    val remoteKey: String? = null,

    /** URL the completion call reports as the stored object. Null until the bytes are up. */
    val remoteUri: String? = null,

    val createdAtMs: Long,

    val updatedAtMs: Long,
)

/**
 * What the pipeline hands over the moment a photo is published. Deliberately free of Room
 * types so `WriteQueue` does not have to know a database exists.
 */
data class UploadCandidate(
    val contentUri: String,
    val fileName: String,
    val sessionId: String,
    val capturedAtMs: Long,
    val sizeBytes: Long,
)

/** One row of the `GROUP BY status` projection. */
data class UploadStatusCount(
    val status: UploadStatus,
    val count: Int,
)

/**
 * Queue totals for the Home badge and the status card.
 *
 * This exists so the UI never observes the rows themselves. Nothing deletes rows
 * (`docs/PHASES.md` §2.5), so the table only grows; a `Flow<List<UploadItem>>` over the whole
 * table would re-map every row each time the worker touched one — invisible at 134 rows,
 * a stutter at 10,000.
 */
data class UploadQueueCounts(
    val pending: Int = 0,
    val uploading: Int = 0,
    val uploaded: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val abandoned: Int = 0,
) {
    /** Rows a worker would still act on. */
    val outstanding: Int get() = pending + uploading + uploaded + failed

    val total: Int get() = outstanding + success + abandoned

    /** How many rows are in one state, for a screen that shows one state at a time. */
    fun of(status: UploadStatus): Int = when (status) {
        UploadStatus.Pending -> pending
        UploadStatus.Uploading -> uploading
        UploadStatus.Uploaded -> uploaded
        UploadStatus.Success -> success
        UploadStatus.Failed -> failed
        UploadStatus.Abandoned -> abandoned
    }

    /** Badge text for the Home menu row; null when there is nothing to say. */
    val badgeLabel: String?
        get() = when {
            outstanding > 0 -> outstanding.toString()
            abandoned > 0 -> "!"
            else -> null
        }

    companion object {
        fun from(rows: List<UploadStatusCount>): UploadQueueCounts {
            fun count(status: UploadStatus) = rows.firstOrNull { it.status == status }?.count ?: 0
            return UploadQueueCounts(
                pending = count(UploadStatus.Pending),
                uploading = count(UploadStatus.Uploading),
                uploaded = count(UploadStatus.Uploaded),
                success = count(UploadStatus.Success),
                failed = count(UploadStatus.Failed),
                abandoned = count(UploadStatus.Abandoned),
            )
        }
    }
}
