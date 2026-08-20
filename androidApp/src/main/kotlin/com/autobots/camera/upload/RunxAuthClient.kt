package com.autobots.camera.upload

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sign-in and event lookup against the Runx GraphQL endpoint.
 *
 * Two calls, both taken from the production Python worker (`docs/PHASES.md` §10): a login
 * mutation that mints a token, and an event query that turns that token into a list the
 * operator can pick from. Together they replace the two values nobody should be typing on a
 * phone — a bearer token and a 24-character MongoID.
 *
 * `HttpURLConnection` rather than a client library, matching
 * [com.autobots.camera.network.RemoteVideoFetcher]. Two JSON POSTs do not justify pulling
 * Ktor's client into the APK.
 *
 * Every call blocks. Callers are responsible for being off the main thread.
 */
object RunxAuthClient {

    /** One row of the event picker. */
    data class EventSummary(
        val id: String,
        val title: String,
        val startDate: String?,
        val photoCount: Int?,
        val approved: Boolean?,
    ) {
        /** `2026-08-19T…` → `2026-08-19`. The API returns ISO; only the day is worth showing. */
        val day: String? get() = startDate?.substringBefore('T')?.takeIf { it.isNotBlank() }
    }

    /**
     * Which events to ask for.
     *
     * The production worker sends `myEvent: true`, so that is the default here. The schema
     * also has `photographerEvent`, and the login response carries a `photographer` field —
     * strong hints that a photographer account needs the other filter. Which one is right is
     * question 6 for the backend owner; until it is answered the operator can switch, because
     * guessing wrong shows up as an empty list that looks exactly like a broken feature.
     */
    enum class EventScope(val variable: String, val label: String) {
        MyEvents("myEvent", "My events"),
        AssignedToMe("photographerEvent", "Assigned to me"),
    }

    /**
     * @property truncated true when the account has more pages than [MAX_PAGES] allows.
     *   Surfaced rather than swallowed: an event missing from the picker with no explanation
     *   is the worst possible failure for this screen.
     */
    data class EventList(
        val events: List<EventSummary>,
        val totalCount: Int,
        val truncated: Boolean,
    )

    /**
     * @return the token and who it belongs to.
     * @throws UploadException.Unauthorized for bad credentials — which arrive as **HTTP 200**
     *   with an `errors` array, not as a 401.
     * @throws UploadException.Retryable when the endpoint could not be reached.
     */
    fun login(
        graphqlUrl: String,
        platform: String,
        username: String,
        password: String,
    ): UploadSession.SignedIn {
        val response = post(
            url = graphqlUrl,
            platform = platform,
            token = null,
            body = JSONObject().apply {
                put("query", LOGIN_MUTATION)
                put(
                    "variables",
                    JSONObject().apply {
                        put("username", username)
                        put("password", password)
                    },
                )
            },
        )

        response.error?.let { throw UploadException.Unauthorized(it) }
        val account = response.data?.optJSONObject("authAdminUser")
            ?: throw UploadException.Unauthorized("Sign-in failed — no account returned")
        val token = account.optString("token").takeIf { it.isNotBlank() }
            ?: throw UploadException.Unauthorized("Sign-in returned no token")

        return UploadSession.SignedIn(
            token = token,
            username = account.optString("username").ifBlank { username },
            role = account.optString("role").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Every event the token can see, following [pageInfo.pageCount] rather than trusting that
     * one page is everything — the production worker reads page 1 only, which silently hides
     * the rest of an account's events.
     */
    fun events(
        graphqlUrl: String,
        platform: String,
        token: String,
        scope: EventScope = EventScope.MyEvents,
        search: String? = null,
    ): EventList {
        val collected = mutableListOf<EventSummary>()
        var page = 1
        var pageCount = 1
        var itemCount = 0

        while (page <= pageCount && page <= MAX_PAGES) {
            val response = post(
                url = graphqlUrl,
                platform = platform,
                token = token,
                body = JSONObject().apply {
                    put("query", EVENTS_QUERY)
                    put(
                        "variables",
                        JSONObject().apply {
                            put(scope.variable, true)
                            put("page", page)
                            search?.trim()?.takeIf { it.isNotEmpty() }?.let { put("titleSearch", it) }
                        },
                    )
                },
            )

            response.error?.let { message ->
                throw if (response.code == 401 || response.code == 403 || looksLikeAuth(message)) {
                    UploadException.Unauthorized(message)
                } else {
                    UploadException.Retryable(message)
                }
            }

            val items = response.data?.optJSONObject("eventItems")
                ?: throw UploadException.Retryable("Event list came back empty-handed")
            collected += parseEvents(items.optJSONArray("items"))

            val info = items.optJSONObject("pageInfo")
            pageCount = info?.optInt("pageCount", 1) ?: 1
            itemCount = info?.optInt("itemCount", collected.size) ?: collected.size
            page++
        }

        val truncated = pageCount > MAX_PAGES
        if (truncated) {
            Log.w(TAG, "Event list truncated at $MAX_PAGES of $pageCount pages ($itemCount events)")
        }
        return EventList(collected, totalCount = itemCount, truncated = truncated)
    }

    private fun parseEvents(array: JSONArray?): List<EventSummary> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EventSummary(
                id = id,
                title = item.optString("title").ifBlank { id },
                startDate = item.optString("startDate").takeIf { it.isNotBlank() && it != "null" },
                photoCount = if (item.isNull("photoCount")) null else item.optInt("photoCount"),
                approved = if (item.isNull("approved")) null else item.optBoolean("approved"),
            )
        }
    }

    // --- transport ----------------------------------------------------------------------

    /**
     * @property error the first GraphQL error message, or an HTTP-level one. Null when the
     *   call succeeded. Kept as data rather than thrown here because "wrong password" and
     *   "token expired" are the same shape on the wire and only the caller knows which it
     *   asked for.
     */
    private data class GraphQlResponse(val code: Int, val data: JSONObject?, val error: String?)

    private fun post(
        url: String,
        platform: String,
        token: String?,
        body: JSONObject,
    ): GraphQlResponse {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-runx-platform", platform)
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        } catch (t: Throwable) {
            throw UploadException.Retryable("Cannot reach $url: ${t.message}", t)
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.let(::readAll).orEmpty()

            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw UploadException.Retryable(
                    if (code in 200..299) "Server did not return JSON" else "Server returned HTTP $code",
                )

            // GraphQL reports failure inside a 200. Checking the status code first would read
            // "wrong password" as success and then crash on a missing field, which is exactly
            // what the Python worker's presign step does.
            val error = firstError(json) ?: if (code in 200..299) null else "HTTP $code"
            GraphQlResponse(code = code, data = json.optJSONObject("data"), error = error)
        } catch (t: UploadException) {
            throw t
        } catch (t: Throwable) {
            throw UploadException.Retryable(t.message ?: t::class.java.simpleName, t)
        } finally {
            connection.disconnect()
        }
    }

    private fun firstError(json: JSONObject): String? {
        val errors = json.optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        val first = errors.optJSONObject(0) ?: return "Request rejected"
        return first.optString("message").takeIf { it.isNotBlank() } ?: "Request rejected"
    }

    private fun looksLikeAuth(message: String): Boolean {
        val lower = message.lowercase()
        return AUTH_HINTS.any { it in lower }
    }

    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    // --- queries ------------------------------------------------------------------------

    private const val LOGIN_MUTATION = """
        mutation authAdminUser(${'$'}username: String!, ${'$'}password: String!) {
          authAdminUser(username: ${'$'}username, password: ${'$'}password) {
            _id
            role
            token
            username
            photographer
          }
        }
    """

    /**
     * Five fields, not the forty the Python client asks for.
     *
     * The original selection set pulls bank accounts, credit balances and contract URLs to
     * fill a dropdown that shows a name — a large payload over event-day 4G, and a whole
     * query that fails if the account lacks permission on any one of those fields.
     */
    private const val EVENTS_QUERY = """
        query eventItems(
          ${'$'}myEvent: Boolean
          ${'$'}photographerEvent: Boolean
          ${'$'}titleSearch: String
          ${'$'}page: Int
        ) {
          eventItems(
            filter: {
              myEvent: ${'$'}myEvent
              photographerEvent: ${'$'}photographerEvent
              titleSearch: ${'$'}titleSearch
            }
            sort: STARTDATE_DESC
            page: ${'$'}page
          ) {
            items {
              _id
              title
              startDate
              photoCount
              approved
            }
            pageInfo {
              currentPage
              itemCount
              pageCount
            }
          }
        }
    """

    private const val TAG = "RunxAuthClient"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Ten pages of events is already far past what one device shoots in a season. */
    private const val MAX_PAGES = 10

    private val AUTH_HINTS = listOf("unauthor", "unauthen", "forbidden", "token", "permission")
}
