package com.autobots.camera.network

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Browser-like headers so R2 and similar CDNs accept MediaExtractor / Retriever,
 * which Android's default `setDataSource(Context, Uri)` HTTP path often does not.
 */
internal object VideoHttp {
    val HEADERS: Map<String, String> = mapOf(
        "User-Agent" to CHROME_ANDROID_UA,
        "Accept" to "*/*",
        "Accept-Encoding" to "identity",
    )

    fun isRemote(source: Uri): Boolean {
        val scheme = source.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    fun bind(extractor: MediaExtractor, context: Context, source: Uri) {
        val headers = headersFor(source)
        if (headers != null) {
            extractor.setDataSource(source.toString(), headers)
        } else {
            extractor.setDataSource(context, source, null)
        }
    }

    fun bind(retriever: MediaMetadataRetriever, context: Context, source: Uri) {
        val headers = headersFor(source)
        if (headers != null) {
            retriever.setDataSource(source.toString(), headers)
        } else {
            retriever.setDataSource(context, source)
        }
    }

    private fun headersFor(source: Uri): Map<String, String>? =
        if (isRemote(source)) HEADERS else null

    private const val CHROME_ANDROID_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
}
