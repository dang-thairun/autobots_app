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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<File>(capacity = capacity)
    private val pending = AtomicInteger(0)

    val pendingCount: Int get() = pending.get()

    init {
        scope.launch {
            for (file in channel) {
                try {
                    val uri = writer.publish(file)
                    if (uri != null) {
                        file.delete()
                        onDelivered(uri)
                    } else {
                        Log.w(TAG, "Delivery failed, keeping temp ${file.name}")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Worker failed for ${file.name}", t)
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
    }

    /** @return false if queue full (file not accepted). */
    fun enqueue(file: File): Boolean {
        val result = channel.trySend(file)
        return if (result.isSuccess) {
            pending.incrementAndGet()
            true
        } else {
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
