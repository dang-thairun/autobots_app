package com.autobots.camera.upload

import android.content.Context
import android.content.SharedPreferences
import com.autobots.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable switches the upload worker reads.
 *
 * **`SharedPreferences`, not DataStore.** The plan called for DataStore at B3d, and that was
 * reconsidered when B3d arrived: this holds one boolean and five short strings, read by a
 * worker that already reads them synchronously between files. DataStore would buy async
 * access and typed schemas that nothing here needs, at the price of a second configuration
 * store living beside this one — the pause flag was already here. One store, no dependency.
 *
 * @property paused survives process death by design — a pause that a background restart could
 *   undo is not a pause.
 */
class UploadSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /**
     * Why the queue stopped, when it stopped itself rather than being paused by hand.
     * Null when the operator paused it, or when nothing is wrong.
     */
    private val _pauseReason = MutableStateFlow(prefs.getString(KEY_PAUSE_REASON, null))
    val pauseReason: StateFlow<String?> = _pauseReason.asStateFlow()

    /** Read straight from prefs: the worker checks this between every photo. */
    val isPaused: Boolean get() = prefs.getBoolean(KEY_PAUSED, false)

    fun setPaused(value: Boolean, reason: String? = null) {
        prefs.edit()
            .putBoolean(KEY_PAUSED, value)
            .putString(KEY_PAUSE_REASON, if (value) reason else null)
            .apply()
        _paused.value = value
        _pauseReason.value = if (value) reason else null
    }

    // --- backend configuration (B3d) ---------------------------------------------------

    private val _config = MutableStateFlow(seedFromDefaults())
    val config: StateFlow<UploadConfig> = _config.asStateFlow()

    init {
        // A build that was given a token in `.env` is already provisioned; it skips the login
        // screen. Still only for this process — reopening the app re-reads it from the build,
        // which is the same rule login follows. See [UploadSession].
        UploadSession.seedIfAbsent(BuildConfig.UPLOAD_TOKEN, BUILD_DEFAULT_USER)
    }

    /**
     * Reconcile the stored configuration with what the build was given in `.env`.
     *
     * Two different rules, because the values are two different kinds of thing:
     *
     * - **The endpoints win from the build, every launch.** A URL in `.env` is a decision
     *   about which backend this APK talks to, and it should not be possible for a stale
     *   value typed on a phone weeks ago to quietly outrank it. The cost is that editing
     *   those fields on a build that sets them lasts only until the next launch.
     * - **Platform and event are seeded on first run only**, so an operator's correction is
     *   never silently reverted. `eventId` in particular is normally chosen from the event
     *   picker, not from a build.
     *
     * Either way an empty value in `.env` changes nothing — that is what "the operator fills
     * it in" means.
     *
     * **Once per process, not once per instance.** This class is constructed wherever it is
     * needed — the ViewModel, the worker, `MainActivity` — and without the guard every one of
     * those constructions re-applies the build's URLs. That turns "the build wins at launch"
     * into "the build wins at unpredictable moments", which can overwrite an edit seconds
     * after it was made, from a background thread. It cost an hour of confusing test results
     * to find; the guard is what makes the rule mean what it says.
     */
    private fun seedFromDefaults(): UploadConfig {
        val stored = readConfig()
        synchronized(Companion) {
            if (buildDefaultsApplied) return stored
            buildDefaultsApplied = true
        }
        var next = stored

        BuildConfig.UPLOAD_GRAPHQL_URL.takeIf { it.isNotBlank() }
            ?.let { next = next.copy(graphqlUrl = it) }
        BuildConfig.UPLOAD_COMPLETE_URL.takeIf { it.isNotBlank() }
            ?.let { next = next.copy(completeUrl = it) }

        if (stored == UploadConfig()) {
            next = next.copy(
                platform = BuildConfig.UPLOAD_PLATFORM,
                eventId = BuildConfig.UPLOAD_EVENT_ID,
            )
        }

        if (next == stored) return stored
        writeConfigInternal(next)
        return next
    }

    fun readConfig(): UploadConfig = UploadConfig(
        graphqlUrl = prefs.getString(KEY_GRAPHQL_URL, "").orEmpty(),
        completeUrl = prefs.getString(KEY_COMPLETE_URL, "").orEmpty(),
        platform = prefs.getString(KEY_PLATFORM, "").orEmpty(),
        eventId = prefs.getString(KEY_EVENT_ID, "").orEmpty(),
        eventTitle = prefs.getString(KEY_EVENT_TITLE, "").orEmpty(),
    )

    fun writeConfig(config: UploadConfig) {
        writeConfigInternal(config)
        _config.value = readConfig()
    }

    private fun writeConfigInternal(config: UploadConfig) {
        prefs.edit()
            .putString(KEY_GRAPHQL_URL, config.graphqlUrl.trim())
            .putString(KEY_COMPLETE_URL, config.completeUrl.trim())
            .putString(KEY_PLATFORM, config.platform.trim())
            .putString(KEY_EVENT_ID, config.eventId.trim())
            .putString(KEY_EVENT_TITLE, config.eventTitle.trim())
            .apply()
    }

    fun clearConfig() = writeConfig(UploadConfig())

    // --- remembered sign-in ------------------------------------------------------------

    /**
     * Username and password, kept only while the operator asks for it.
     *
     * The token is never stored ([UploadSession]); this is what keeps that from meaning a
     * trip to the back office every morning. It is a real trade — a password on disk is worse
     * than a 7-day token on disk if the device is lost — so it is opt-in, off by default, and
     * the whole prefs file is excluded from backup (`androidApp/src/main/res/xml/backup_rules.xml`)
     * so it cannot leave the device through `adb backup` or cloud restore.
     */
    data class Credentials(val username: String, val password: String)

    private val _remembered = MutableStateFlow(readCredentials())
    val remembered: StateFlow<Credentials?> = _remembered.asStateFlow()

    val rememberCredentials: Boolean get() = prefs.getBoolean(KEY_REMEMBER, false)

    private fun readCredentials(): Credentials? {
        if (!prefs.getBoolean(KEY_REMEMBER, false)) return null
        val username = prefs.getString(KEY_USERNAME, "").orEmpty()
        val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        if (username.isBlank() || password.isBlank()) return null
        return Credentials(username, password)
    }

    /** @param remember false erases anything already stored — unticking the box must mean it. */
    fun saveCredentials(username: String, password: String, remember: Boolean) {
        prefs.edit().apply {
            putBoolean(KEY_REMEMBER, remember)
            if (remember) {
                putString(KEY_USERNAME, username)
                putString(KEY_PASSWORD, password)
            } else {
                remove(KEY_USERNAME)
                remove(KEY_PASSWORD)
            }
        }.apply()
        _remembered.value = readCredentials()
    }

    // --- debug failure injection -------------------------------------------------------
    // Set through the debug deep link (see MainActivity). Counters, not rates, so a test
    // reads the same on every run: "fail the next N calls, then behave".

    /**
     * Send to the local sink instead of the network.
     *
     * Off by default now that a real transport exists: an upload that quietly writes to a
     * folder on the phone while the screen says it is uploading is worse than one that does
     * nothing. Kept because it is still the only way to exercise failure injection.
     */
    fun setFakeTransport(enabled: Boolean) = prefs.edit().putBoolean(KEY_FAKE, enabled).apply()

    val useFakeTransport: Boolean get() = prefs.getBoolean(KEY_FAKE, false)

    /** Slows every PUT down so the run can actually be interrupted by hand. */
    fun setFakeDelayMs(ms: Int) = prefs.edit().putInt(KEY_DELAY_MS, ms).apply()

    val fakeDelayMs: Int get() = prefs.getInt(KEY_DELAY_MS, 0)

    fun setFakeFailPut(count: Int) = prefs.edit().putInt(KEY_FAIL_PUT, count).apply()

    fun setFakeFailComplete(count: Int) = prefs.edit().putInt(KEY_FAIL_COMPLETE, count).apply()

    val fakeFailPut: Int get() = prefs.getInt(KEY_FAIL_PUT, 0)

    val fakeFailComplete: Int get() = prefs.getInt(KEY_FAIL_COMPLETE, 0)

    /** @return remaining count if this call should fail, null if it should succeed. */
    fun consumeFakeFailPut(): Int? = consume(KEY_FAIL_PUT)

    fun consumeFakeFailComplete(): Int? = consume(KEY_FAIL_COMPLETE)

    private fun consume(key: String): Int? {
        val left = prefs.getInt(key, 0)
        if (left <= 0) return null
        prefs.edit().putInt(key, left - 1).apply()
        return left
    }

    /**
     * Keeps every instance of this class agreeing with the file.
     *
     * There is one preferences file but several [UploadSettings] objects — the ViewModel
     * builds one, the worker builds one, and [UploadScheduler] builds a throwaway one every
     * time it pauses or resumes. Each used to hold its own `StateFlow`, so a pause performed
     * through the scheduler never reached the screen: the flag was written, the queue really
     * did stop, and the UI went on showing "running" until the process restarted. The same
     * gap hid the worker pausing itself over a rejected token — the one case where the reason
     * matters most.
     *
     * Registered last on purpose: it fires on writes, and the seeding in [seedFromDefaults]
     * writes while the properties below it are still being constructed.
     */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            when (key) {
                KEY_PAUSED, KEY_PAUSE_REASON -> {
                    _paused.value = store.getBoolean(KEY_PAUSED, false)
                    _pauseReason.value = store.getString(KEY_PAUSE_REASON, null)
                }
                KEY_REMEMBER, KEY_USERNAME, KEY_PASSWORD -> {
                    _remembered.value = readCredentials()
                }
                else -> _config.value = readConfig()
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private companion object {
        /** @see seedFromDefaults — process-wide, so later instances leave the config alone. */
        @Volatile
        var buildDefaultsApplied = false

        const val PREFS = "autobots_upload"
        const val KEY_PAUSED = "paused"
        const val KEY_PAUSE_REASON = "pause_reason"
        const val KEY_GRAPHQL_URL = "graphql_url"
        const val KEY_COMPLETE_URL = "complete_url"
        const val KEY_PLATFORM = "platform"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_EVENT_TITLE = "event_title"
        const val KEY_REMEMBER = "remember_credentials"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val BUILD_DEFAULT_USER = "build default"
        const val KEY_FAKE = "fake_transport"
        const val KEY_DELAY_MS = "fake_delay_ms"
        const val KEY_FAIL_PUT = "fake_fail_put"
        const val KEY_FAIL_COMPLETE = "fake_fail_complete"
    }
}
