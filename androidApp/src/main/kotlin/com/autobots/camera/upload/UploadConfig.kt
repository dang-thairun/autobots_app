package com.autobots.camera.upload

import android.net.Uri
import org.json.JSONObject

/**
 * Where the app uploads to. **Not who it uploads as** — the token lives in [UploadSession] and
 * is deliberately never written to disk.
 *
 * The field list comes from the Runx API that already runs in production (`docs/PHASES.md`
 * §10) rather than invented: the GraphQL endpoint mints the signed URL, a separate host takes
 * the completion call, and `platform` identifies the tenant. `eventId` is here rather than in
 * the session because it is a choice about the day's work, not a credential — it should
 * survive a restart the way the queue does.
 */
data class UploadConfig(
    /** `https://api.<host>/graphql` — where `authAdminUser` and `photoUpload` live. */
    val graphqlUrl: String = "",
    /** `https://upload.<host>/success` — the completion call. Different host, plain form POST. */
    val completeUrl: String = "",
    /** `x-runx-platform` header value, e.g. `thai`. */
    val platform: String = "",
    /** Which event the photos belong to. The backend groups by this, not by our session. */
    val eventId: String = "",
    /**
     * The event's name as it was when picked. Display only.
     *
     * Stored so the settings screen can say which event is selected without a network call —
     * a device that opens offline should still be able to show what it is set to.
     */
    val eventTitle: String = "",
) {
    /** True when a backend could be reached. Says nothing about being signed in. */
    val isComplete: Boolean
        get() = validate() == null

    /**
     * Enough to sign in with: the login mutation needs only the endpoint and the tenant.
     * Checked separately so the sign-in form can refuse early with a useful reason.
     */
    fun signInProblem(): String? {
        httpsProblem(graphqlUrl, "GraphQL URL")?.let { return it }
        if (platform.isBlank()) return "Platform is required"
        return null
    }

    /** @return the first thing wrong with this config, or null when it is usable. */
    fun validate(): String? {
        signInProblem()?.let { return it }
        httpsProblem(completeUrl, "Complete URL")?.let { return it }
        if (eventId.isBlank()) return "Select an event"
        return null
    }

    private fun httpsProblem(value: String, label: String): String? {
        if (value.isBlank()) return "$label is required"
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "$label is not a URL"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "$label must be http or https"
        if (uri.host.isNullOrBlank()) return "$label has no host"
        return null
    }

    companion object {
        /**
         * Read a scanned QR.
         *
         * Fields that are absent leave the current value alone, so one code can carry the
         * endpoints for a whole fleet and a second can carry just the event of the day.
         *
         * A `token` in the payload is handed back separately rather than stored: provisioning
         * by QR is allowed to skip the login screen, but not to leave a credential on disk.
         *
         * @return null when the payload is not JSON at all — a QR from somewhere else should
         *   not quietly wipe a working configuration.
         */
        fun parseQr(current: UploadConfig, payload: String): Provisioning? {
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            fun pick(vararg names: String): String? = names
                .firstOrNull { json.has(it) && !json.isNull(it) }
                ?.let { json.optString(it).trim() }
                ?.takeIf { it.isNotEmpty() }

            val eventId = pick("eventId", "event")
            return Provisioning(
                config = current.copy(
                    graphqlUrl = pick("graphqlUrl", "graphql", "url") ?: current.graphqlUrl,
                    completeUrl = pick("completeUrl", "successUrl", "complete") ?: current.completeUrl,
                    platform = pick("platform") ?: current.platform,
                    eventId = eventId ?: current.eventId,
                    // A new event arriving without a name must not keep the old name.
                    eventTitle = pick("eventTitle") ?: if (eventId != null) "" else current.eventTitle,
                ),
                token = pick("token"),
            )
        }

        /** Never log or show a whole token; it is an admin credential. */
        fun tokenPreview(token: String?): String = when {
            token.isNullOrBlank() -> "—"
            token.length <= 8 -> "•".repeat(token.length)
            else -> "${token.take(4)}…${token.takeLast(4)} (${token.length} chars)"
        }

        /** What a provisioning QR should contain. Shown on the settings screen as a hint. */
        const val QR_EXAMPLE =
            """{"graphqlUrl":"https://api.example.com/graphql","completeUrl":"https://upload.example.com/success","platform":"thai","eventId":"…"}"""
    }

    /** A scanned payload split into the part that is kept and the part that is not. */
    data class Provisioning(val config: UploadConfig, val token: String?)
}
