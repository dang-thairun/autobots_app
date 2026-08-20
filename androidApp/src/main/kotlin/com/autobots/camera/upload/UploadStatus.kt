package com.autobots.camera.upload

/**
 * Where one photo stands on its way to the bucket. Six states, not four — see
 * `docs/PHASES.md` §3.
 *
 * The two that are easy to leave out are the two that matter:
 *
 * - [Uploaded] separates *"R2 has the bytes"* from *"the backend knows about them"*. Without
 *   it, a failed `/uploads/complete` looks identical to a failed PUT, so the retry re-sends
 *   the whole file — or worse, gives up and leaves an object in the bucket that no metadata
 *   row points at.
 * - [Abandoned] separates *"try again later"* from *"trying again will never help"*. Without
 *   it, a photo the operator deleted from the gallery stays in [Failed] and burns battery
 *   forever.
 */
enum class UploadStatus {
    /** In the queue, never attempted. */
    Pending,

    /** A worker holds it. Anything still here at startup was interrupted — see
     * [UploadRepository.resetInterrupted]. */
    Uploading,

    /** Bytes are in the bucket under [UploadItem.relativeKey]; metadata not committed yet.
     * Retrying skips the PUT and re-sends only the completion call. */
    Uploaded,

    /** Backend committed the metadata. Terminal — the local file is **not** deleted
     * (`docs/PHASES.md` §2.5). */
    Success,

    /** Failed in a way that a later attempt could fix: 5xx, timeout, no network. */
    Failed,

    /** Failed in a way no retry fixes: the file is gone from MediaStore, a permanent 4xx,
     * or [UploadItem.attemptCount] ran out. Only a human retry moves it. */
    Abandoned,
    ;

    /** A worker may pick this up; [Uploading] is excluded so two workers cannot claim one row. */
    val isClaimable: Boolean
        get() = this == Pending || this == Uploaded || this == Failed

    /** Nothing more will happen on its own. */
    val isTerminal: Boolean
        get() = this == Success || this == Abandoned

    companion object {
        /** Statuses a worker looks for, in the order they should be drained. */
        val CLAIMABLE: List<UploadStatus> = listOf(Uploaded, Pending, Failed)
    }
}
