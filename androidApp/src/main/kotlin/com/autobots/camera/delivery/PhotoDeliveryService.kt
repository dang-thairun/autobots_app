package com.autobots.camera.delivery

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.File

interface PhotoDeliveryService {
    fun setPhotoDeliveredListener(listener: (Uri) -> Unit)
    fun enqueueAll(files: List<File>): Int
    fun close()
}

class LocalPhotoDeliveryService(
    private val context: Context,
    private val capacity: Int = WriteQueue.DEFAULT_CAPACITY
) : PhotoDeliveryService {
    private val writer = LocalDeliveryWriter(context)
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var onPhotoDelivered: ((Uri) -> Unit)? = null
    private var writeQueue: WriteQueue? = null

    override fun setPhotoDeliveredListener(listener: (Uri) -> Unit) {
        onPhotoDelivered = listener
        ensureWriteQueue()
    }

    private fun ensureWriteQueue(): WriteQueue {
        writeQueue?.let { return it }
        val queue = WriteQueue(
            writer = writer,
            capacity = capacity,
            onDelivered = { uri ->
                mainExecutor.execute { onPhotoDelivered?.invoke(uri) }
            }
        )
        writeQueue = queue
        return queue
    }

    override fun enqueueAll(files: List<File>): Int {
        return ensureWriteQueue().enqueueAll(files)
    }

    override fun close() {
        writeQueue?.close()
        writeQueue = null
    }
}
