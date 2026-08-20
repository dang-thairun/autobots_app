package com.autobots.camera.upload

import java.io.InputStream

/**
 * The three steps that put one photo in front of a backend, and the only part of the upload
 * stack that knows what the backend is.
 *
 * Presign → PUT → complete is the shape both candidate backends share: the R2 plan in
 * `docs/PHASES.md` §4 and the Runx GraphQL API already running in production (§10). Swapping
 * between them replaces this implementation and nothing else — not the queue, not the worker,
 * not the state machine.
 *
 * **Every failure must be classified**, because the queue does completely different things
 * with the three kinds. Throwing a bare exception makes it retryable by default, which is the
 * safe end to be wrong on.
 */
interface UploadTransport {

    /** Human-readable name of where photos are going. Shown in the UI so nobody has to guess. */
    val destinationLabel: String

    /**
     * Ask for somewhere to put the bytes.
     *
     * @param suggestedKey the client's preferred object key. Backends that accept one should
     *   honour it — that is what keeps a retry overwriting instead of duplicating
     *   (`docs/PHASES.md` §2.3). Backends that mint their own return it in [PresignResult.key],
     *   and the queue stores whatever comes back.
     */
    suspend fun presign(item: UploadItem, suggestedKey: String): PresignResult

    /** Send the bytes. [contentType] must be exactly what [presign] was told, or signed URLs reject it. */
    suspend fun put(target: PresignResult, body: InputStream, sizeBytes: Long, contentType: String)

    /** Tell the backend the object is real. Must be safe to call twice for the same key. */
    suspend fun complete(item: UploadItem, target: PresignResult)

    companion object {
        const val JPEG_CONTENT_TYPE = "image/jpeg"
    }
}

/**
 * @property key what the object is called on the storage side — ours if the backend honoured
 *   the suggestion, theirs otherwise.
 * @property uri where the object can be read back from, as the completion call wants it.
 */
data class PresignResult(
    val uploadUrl: String,
    val key: String,
    val uri: String,
)

/**
 * Why an attempt failed, in the only three flavours the queue can act on differently.
 */
sealed class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Try again later: 5xx, timeout, connection dropped. Goes to backoff. */
    class Retryable(message: String, cause: Throwable? = null) : UploadException(message, cause)

    /**
     * Trying again will never help: the photo is gone from MediaStore, or the backend gave a
     * permanent 4xx. Goes straight to [UploadStatus.Abandoned] without burning attempts.
     */
    class Permanent(message: String, cause: Throwable? = null) : UploadException(message, cause)

    /**
     * The credentials are not good.
     *
     * Handled apart from [Retryable] because it is never about this one photo — every other
     * row will fail the same way. Worse, on backends where presign needs no auth (Runx does
     * not) the failure only surfaces at the completion call, *after* the whole file has been
     * sent. Retrying the queue would upload every remaining photo in full just to be rejected
     * at the last step, leaving an orphaned object behind each time.
     *
     * So this pauses the queue instead. The row keeps whatever status it reached — a row that
     * already got its bytes up stays [UploadStatus.Uploaded], and when a working token
     * arrives it finishes with the completion call alone.
     */
    class Unauthorized(message: String, cause: Throwable? = null) : UploadException(message, cause)
}
