package com.autobots.camera.delivery

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded Write Queue — drains cache JPEGs to Local Delivery without blocking capture.
 */
class WriteQueue(
    private val writer: LocalDeliveryWriter,
    capacity: Int = DEFAULT_CAPACITY,
    private val onDelivered: (Uri) -> Unit,
    private val onDropped: (File) -> Unit = {},
    /** Instrumentation hook: the source file that was just published. */
    private val onDeliveredFile: (File) -> Unit = {},
    /**
     * Called with the published photo **while the cache file still exists**, so the callee can
     * still read its size and timestamp. This is the only moment both halves are available:
     * a step later the file is gone and the MediaStore [Uri] is all that is left of it, which
     * is exactly why the upload queue stores that Uri rather than a path (docs/PHASES.md §2.1).
     *
     * Runs on the queue's own IO coroutine — keep it to handing the pair somewhere else.
     */
    private val onPublished: (uri: Uri, file: File) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<File>(capacity = capacity)
    private val pending = AtomicInteger(0)

    val pendingCount: Int get() = pending.get()

    init {
        scope.launch {
            for (file in channel) {
                var delivered: Uri? = null
                try {
                    val uri = writer.publish(file)
                    if (uri != null) {
                        onDeliveredFile(file)
                        runCatching { onPublished(uri, file) }
                            .onFailure { Log.e(TAG, "onPublished failed for ${file.name}", it) }
                        file.delete()
                        delivered = uri
                    } else {
                        Log.w(TAG, "Delivery failed, keeping temp ${file.name}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Worker failed for ${file.name}", t)
                } finally {
                    pending.decrementAndGet()
                }
                // Fires *after* the counter drops. Callers use [pendingCount] to decide the
                // pipeline has drained; invoking this while the last file still counted as
                // pending meant the final photo of a session never triggered that check, so
                // session_log.txt and perf_report.json were never written.
                delivered?.let(onDelivered)
            }
        }
    }

    /**
     * @return false if queue full (file not accepted).
     *
     * The counter goes up **before** the file is offered, and comes back down if the offer is
     * refused. Incrementing afterwards would leave a window where the worker has already
     * taken the file — and could have finished and decremented — while this call has not yet
     * counted it, so [pendingCount] could read 0 or below with work still to publish.
     *
     * Today's only drain caller happens to be shielded from that: `CapturePipelineCoordinator`
     * enqueues a chunk's photos while its own `workerBusy` flag is set and clears the flag
     * afterwards, and that flag is an `AtomicBoolean`, so anyone who sees it false also sees
     * every increment. Relying on it would mean every future caller has to know. Counting
     * first costs one rollback branch and makes [pendingCount] true on its own terms.
     */
    fun enqueue(file: File): Boolean {
        pending.incrementAndGet()
        val result = channel.trySend(file)
        return if (result.isSuccess) {
            true
        } else {
            pending.decrementAndGet()
            Log.w(TAG, "Queue full — dropped ${file.name}")
            onDropped(file)
            false
        }
    }

    fun enqueueAll(files: List<File>): Int = files.count { enqueue(it) }

    fun close() {
        channel.close()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WriteQueue"
        const val DEFAULT_CAPACITY = 8
    }
}
