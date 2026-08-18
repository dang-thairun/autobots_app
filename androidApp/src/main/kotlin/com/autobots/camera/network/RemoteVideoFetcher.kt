package com.autobots.camera.network

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.autobots.camera.capture.ImportedVideoSplitter
import com.autobots.camera.capture.VideoProbeResult
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HEAD + probe + GET download for a **direct** video file URL.
 * Page URLs (YouTube, Drive share) are out of scope.
 *
 * Extract streams the URL through [ImportedVideoSplitter] when the HTTP source is
 * readable. [download] is the fallback when Check or split cannot open the URL
 * (R2 and similar CDNs that refuse MediaHTTPConnection).
 */
object RemoteVideoFetcher {
    data class HeadInfo(
        val sizeBytes: Long,
        val contentType: String?,
        val fileName: String,
    )

    private const val TAG = "RemoteVideoFetcher"

    fun normalize(raw: String): String = raw.trim()

    fun validate(raw: String): String? {
        val value = normalize(raw)
        if (value.isEmpty()) return "Enter a video URL"
        val uri = runCatching { Uri.parse(value) }.getOrNull()
            ?: return "Invalid URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "URL must be http or https"
        }
        if (uri.host.isNullOrBlank()) return "Invalid URL"
        return null
    }

    fun head(url: String): HeadInfo {
        val connection = open(url, "HEAD")
        return try {
            val code = connection.responseCode
            if (code !in 200..399 && code != HttpURLConnection.HTTP_BAD_METHOD) {
                throw IllegalStateException("Server returned $code")
            }
            // Some hosts reject HEAD; fall back to a 1-byte GET just for headers.
            if (code == HttpURLConnection.HTTP_BAD_METHOD || code == 501) {
                headViaGet(url)
            } else {
                parseHead(connection, url)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun probe(context: Context, url: String): VideoProbeResult? =
        probeWithHeaders(url) ?: ImportedVideoSplitter.probe(context, Uri.parse(url))

    fun probeFile(context: Context, file: File): VideoProbeResult? =
        ImportedVideoSplitter.probe(context, Uri.fromFile(file))

    fun download(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val connection = open(url, "GET")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Download failed ($code)")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var readTotal = 0L
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        if (total > 0) {
                            onProgress(((readTotal * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            onProgress(100)
            if (dest.length() <= 0L) {
                dest.delete()
                throw IllegalStateException("Downloaded file is empty")
            }
        } catch (t: Throwable) {
            dest.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    fun rejectIfNotVideo(contentType: String?): String? {
        val type = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (type.isEmpty()) return null
        if (type.startsWith("video/")) return null
        if (type == "application/octet-stream" || type == "application/mp4") return null
        if (type.startsWith("text/html") || type.startsWith("text/")) {
            return "Not a video file (got $type)"
        }
        return null
    }

    private fun probeWithHeaders(url: String): VideoProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, VideoHttp.HEADERS)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val captureFps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.takeIf { it > 0f }
            VideoProbeResult(
                width = width,
                height = height,
                rotationDegrees = rotation,
                durationMs = durationMs,
                frameRate = captureFps ?: readFrameRate(url),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP probe failed for $url", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readFrameRate(url: String): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(url, VideoHttp.HEADERS)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
                ?.takeIf { it > 0f }
                ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    ?.takeIf { it > 0f }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun headViaGet(url: String): HeadInfo {
        val connection = open(url, "GET")
        connection.setRequestProperty("Range", "bytes=0-0")
        return try {
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("Server returned $code")
            }
            parseHead(connection, url)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHead(connection: HttpURLConnection, url: String): HeadInfo {
        val length = connection.contentLengthLong.takeIf { it > 0 }
            ?: contentRangeTotal(connection.getHeaderField("Content-Range"))
            ?: 0L
        val type = connection.contentType
        val name = fileNameFromDisposition(connection.getHeaderField("Content-Disposition"))
            ?: fileNameFromUrl(url)
        return HeadInfo(sizeBytes = length, contentType = type, fileName = name)
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 20_000
            VideoHttp.HEADERS.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return connection
    }

    private fun fileNameFromUrl(url: String): String {
        val path = Uri.parse(url).lastPathSegment?.substringBefore('?')
        val decoded = runCatching { Uri.decode(path) }.getOrNull()
        return decoded?.takeIf { it.isNotBlank() } ?: "video.mp4"
    }

    private fun fileNameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val utf = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
        if (!utf.isNullOrBlank()) return utf.trim('"')
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
        return plain?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun contentRangeTotal(header: String?): Long? {
        val total = header?.substringAfter('/', "")?.toLongOrNull()
        return total?.takeIf { it > 0 }
    }
}
