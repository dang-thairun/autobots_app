package com.autobots.camera.upload

import android.net.Uri
import android.util.Log
import com.autobots.BuildConfig
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The real backend: Runx GraphQL for the signed URL, Google Cloud Storage for the bytes, and a
 * plain form POST on a third host to make the photo real (`docs/PHASES.md` §10).
 *
 * Deliberately the same three steps as [FakeUploadTransport], because everything above this —
 * the queue, the worker, the six states — was built and proven against that one. This class is
 * the only part that knows any of the above exists.
 *
 * It follows the production Python client closely on the wire (headers, field names, the
 * unauthenticated presign) and departs from it in exactly one way that matters: **failures are
 * classified**, so a dropped connection retries and a rejected token stops the queue instead of
 * every photo dying the same anonymous death.
 *
 * @property token from [UploadSession]. Passed in rather than read, so a worker cannot start a
 *   run with one identity and finish it with another.
 */
class RunxUploadTransport(
    private val config: UploadConfig,
    private val token: String,
) : UploadTransport {

    override val destinationLabel: String =
        Uri.parse(config.completeUrl).host ?: "backend"

    /**
     * Ask GraphQL for a signed URL.
     *
     * `$path` is sent as a **folder**, not a name. Probing the live API settled what it does
     * (§10 question 2): every segment is kept verbatim and the server still appends its own
     * UUID filename, so `path` can organise the bucket but cannot make an object key
     * predictable. [suggestedKey] is therefore still ignored, the server mints the name, and
     * the queue stores what comes back in `remoteKey`/`remoteUri`.
     *
     * What it buys is auditability: everything one session produced lands under
     * `<eventId>/<sessionId>/`, so a missing photo or a duplicate left by a re-queue can be
     * seen by looking rather than by cross-referencing UUIDs.
     */
    override suspend fun presign(item: UploadItem, suggestedKey: String): PresignResult {
        val body = JSONObject().apply {
            put("query", PHOTO_UPLOAD_MUTATION)
            put(
                "variables",
                JSONObject().apply {
                    put("provider", PROVIDER)
                    put("mimeType", UploadTransport.JPEG_CONTENT_TYPE)
                    put("path", "${config.eventId}/${item.sessionId}")
                },
            )
        }

        // No Authorization, matching the client that is in production today. Whether that is
        // intended is question 3 for the backend owner; copying it is the low-risk choice for
        // a first integration, and adding a header later is one line.
        val json = postJson(config.graphqlUrl, body, token = null)

        firstError(json)?.let { message ->
            throw if (looksLikeAuth(message)) {
                UploadException.Unauthorized(message)
            } else {
                UploadException.Retryable(message)
            }
        }

        val photo = json.optJSONObject("data")?.optJSONObject("photoUpload")
            ?: throw UploadException.Retryable("photoUpload returned nothing")
        val uploadUrl = photo.optString("uploadUrl").takeIf { it.isNotBlank() }
            ?: throw UploadException.Retryable("photoUpload returned no uploadUrl")
        val downloadUrl = photo.optString("downloadUrl").takeIf { it.isNotBlank() }
            ?: throw UploadException.Retryable("photoUpload returned no downloadUrl")

        return PresignResult(uploadUrl = uploadUrl, key = keyOf(downloadUrl), uri = downloadUrl)
    }

    /**
     * Send the bytes to the signed URL.
     *
     * The photo is read into memory first so `Content-Length` is exactly what is sent. A
     * streamed length taken from the queue row would be a guess about a file MediaStore owns,
     * and a signed PUT that disagrees with its own length fails in ways that are hard to read.
     * One JPEG at a time is a few MB — the worker never holds two.
     */
    override suspend fun put(
        target: PresignResult,
        body: InputStream,
        sizeBytes: Long,
        contentType: String,
    ) {
        val bytes = body.readBytes()
        if (bytes.isEmpty()) throw UploadException.Permanent("photo is empty")

        val connection = open(target.uploadUrl, "PUT")
        try {
            // Must match the mimeType the URL was signed for, exactly.
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.readText().orEmpty().take(ERROR_SNIPPET)
                throw classify(code, "PUT failed ($code) $detail")
            }
            Log.i(TAG, "PUT ${target.key} (${bytes.size} bytes)")
        } catch (t: UploadException) {
            throw t
        } catch (t: Throwable) {
            throw UploadException.Retryable("PUT failed: ${t.message}", t)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Tell the platform the object exists. Form-encoded, on a different host, and the only
     * step that carries the token.
     *
     * Safe to call twice for the same key by contract; the queue relies on that to finish a
     * row whose bytes went up before an earlier attempt died.
     */
    override suspend fun complete(item: UploadItem, target: PresignResult) {
        val form = mapOf(
            "eventId" to config.eventId,
            "key" to target.key,
            // The original file name, so the platform shows something recognisable rather
            // than the server-minted key.
            "name" to item.fileName,
            "uri" to target.uri,
        ).entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val connection = open(config.completeUrl, "POST")
        try {
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            val bytes = form.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.readText().orEmpty().take(ERROR_SNIPPET)
                throw classify(code, "complete failed ($code) $detail")
            }
            Log.i(TAG, "COMPLETE ${target.key} → event ${config.eventId}")
        } catch (t: UploadException) {
            throw t
        } catch (t: Throwable) {
            throw UploadException.Retryable("complete failed: ${t.message}", t)
        } finally {
            connection.disconnect()
        }
    }

    // --- plumbing -------------------------------------------------------------------------

    /**
     * `https://storage.googleapis.com/<bucket>/a/b/9f3c….jpeg` → `a/b/9f3c….jpeg`.
     *
     * The completion call wants the object's key, which only the download URL carries: the
     * bucket is the first path segment and everything after it is the key. With no `path` set
     * this collapses to the bare filename, which is exactly what the production Python client
     * sends — so this is a generalisation of the proven behaviour, not a change to it.
     *
     * The live endpoint accepts both this and a bare filename; the full key is used because
     * it is the one that actually names the object.
     */
    private fun keyOf(downloadUrl: String): String {
        val segments = Uri.parse(downloadUrl).pathSegments.orEmpty()
        return when {
            segments.size > 1 -> segments.drop(1).joinToString("/")
            segments.size == 1 -> segments.first()
            else -> downloadUrl.substringAfterLast('/').substringBefore('?')
        }
    }

    private fun postJson(url: String, body: JSONObject, token: String?): JSONObject {
        val connection = open(url, "POST")
        return try {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.readText().orEmpty()
            runCatching { JSONObject(text) }.getOrNull()
                ?: throw classify(code, "Server did not return JSON ($code)")
        } catch (t: UploadException) {
            throw t
        } catch (t: Throwable) {
            throw UploadException.Retryable("${t.message}", t)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /**
     * HTTP status → what the queue should do about it.
     *
     * 4xx other than auth is permanent on purpose: retrying a request the server called
     * malformed just burns battery and keeps a dead row alive. Everything else retries,
     * because being wrong in that direction costs a delay rather than a photo.
     */
    private fun classify(code: Int, message: String): UploadException = when {
        code == 401 || code == 403 -> UploadException.Unauthorized(message)
        code in 400..499 && code != 408 && code != 429 -> UploadException.Permanent(message)
        else -> UploadException.Retryable(message)
    }

    private fun firstError(json: JSONObject): String? {
        val errors = json.optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        return errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "Request rejected"
    }

    private fun looksLikeAuth(message: String): Boolean {
        val lower = message.lowercase()
        return AUTH_HINTS.any { it in lower }
    }

    private fun InputStream.readText(): String =
        bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val TAG = "RunxUploadTransport"

        /** The only `CloudUploadProvider` value anyone has confirmed — question 1 in §10. */
        const val PROVIDER = "gs"

        const val PHOTO_UPLOAD_MUTATION = """
            mutation photoUpload(${'$'}provider: CloudUploadProvider!, ${'$'}mimeType: String!) {
              photoUpload(provider: ${'$'}provider, mimeType: ${'$'}mimeType) {
                uploadUrl
                downloadUrl
              }
            }
        """

        // Says what this actually is. The Python client sends "mangobot"; if the platform
        // turns out to filter on that, this is the line to change.
        val USER_AGENT = "autobots-android/${BuildConfig.VERSION_NAME}"

        const val CONNECT_TIMEOUT_MS = 15_000

        /** Generous: this covers a whole JPEG going up a phone's uplink. */
        const val READ_TIMEOUT_MS = 60_000

        /** Enough of a server error to be useful in a log line, not enough to flood it. */
        const val ERROR_SNIPPET = 300

        val AUTH_HINTS = listOf("unauthor", "unauthen", "forbidden", "token", "permission")
    }
}
