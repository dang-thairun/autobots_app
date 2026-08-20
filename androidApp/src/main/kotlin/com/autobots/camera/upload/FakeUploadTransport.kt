package com.autobots.camera.upload

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * A backend that is just a directory.
 *
 * There is no Cloudflare R2 and no Runx endpoint to point at yet, and waiting for one would
 * mean the worker, the state machine and the backoff all arrive untested on the same day the
 * network does. This stands in so every part above the transport can be finished and proven
 * first — see `docs/PHASES.md` B3c.
 *
 * It is a real implementation of the contract, not a stub: it mints a key, writes bytes, and
 * records completions, so the code paths that persist [UploadItem.remoteKey] and re-run only
 * the completion step are genuinely exercised.
 *
 * Failures can be dialled in with [UploadSettings.fakeFailPut] / [UploadSettings.fakeFailComplete],
 * which is the only practical way to reach the `Uploaded → Success` retry on a device that
 * refuses `adb shell input`.
 */
class FakeUploadTransport(
    context: Context,
    private val settings: UploadSettings,
) : UploadTransport {

    private val root = File(context.filesDir, SINK_DIR)

    override val destinationLabel: String = "local test sink"

    override suspend fun presign(item: UploadItem, suggestedKey: String): PresignResult {
        // Honours the suggested key, which is what a backend with a `path` parameter would do.
        // Keeps the deterministic-key property of docs/PHASES.md §2.3 under test.
        val target = File(root, suggestedKey)
        return PresignResult(
            uploadUrl = target.absolutePath,
            key = suggestedKey,
            uri = "fake://${suggestedKey}",
        )
    }

    override suspend fun put(
        target: PresignResult,
        body: InputStream,
        sizeBytes: Long,
        contentType: String,
    ) {
        settings.fakeDelayMs.takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it.toLong()) }
        settings.consumeFakeFailPut()?.let {
            throw UploadException.Retryable("simulated PUT failure ($it left)")
        }
        val file = File(target.uploadUrl)
        file.parentFile?.mkdirs()
        // Same temp-then-rename reasoning as the QNN asset unpack: a half-written object that
        // looks complete is worse than no object at all.
        val tmp = File(file.parentFile, "${file.name}.part")
        try {
            tmp.outputStream().use { out -> body.copyTo(out) }
            check(tmp.renameTo(file)) { "cannot rename $tmp to $file" }
        } finally {
            tmp.delete()
        }
        Log.i(TAG, "PUT ${target.key} (${file.length()} bytes, $contentType)")
    }

    override suspend fun complete(item: UploadItem, target: PresignResult) {
        settings.consumeFakeFailComplete()?.let {
            throw UploadException.Retryable("simulated complete failure ($it left)")
        }
        // Appending rather than upserting on purpose: two lines for one key *within a single
        // drain* means the queue committed the same object twice, which the design is meant
        // to make impossible. Across deliberate re-runs repeats are expected — what must stay
        // constant is the number of distinct keys, since the object key is deterministic.
        File(root, MANIFEST).apply { parentFile?.mkdirs() }
            .appendText("${target.key}\t${item.sessionId}\t${item.capturedAtMs}\t${item.sizeBytes}\n")
        Log.i(TAG, "COMPLETE ${target.key}")
    }

    private companion object {
        const val TAG = "FakeUploadTransport"
        const val SINK_DIR = "fake_upload_sink"
        const val MANIFEST = "manifest.tsv"
    }
}
