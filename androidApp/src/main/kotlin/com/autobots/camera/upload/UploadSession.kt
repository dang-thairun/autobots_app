package com.autobots.camera.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in token — **in memory only, for the life of the process**.
 *
 * The backend issues a 7-day token, so persisting it would work. It is deliberately not
 * persisted: a token that outlives the app is an admin credential sitting on a device that
 * gets left in a bag between events, and the cost of the alternative is one login when the
 * operator opens the app anyway. See `docs/PHASES.md` B3e.
 *
 * The consequence is real and intended: after the process dies, uploads cannot resume until
 * someone signs in again. Nothing is lost — the queue is on disk and every row keeps its
 * state — but the queue stops. [UploadSettings.rememberCredentials] is what makes that a
 * few seconds instead of a trip to the back office.
 *
 * A singleton because the worker and the UI must agree on who is signed in, and the worker
 * has no access to a ViewModel.
 */
object UploadSession {

    data class SignedIn(
        val token: String,
        val username: String,
        /** From the login response. Null when the build seeded a token instead of signing in. */
        val role: String? = null,
    )

    private val _current = MutableStateFlow<SignedIn?>(null)
    val current: StateFlow<SignedIn?> = _current.asStateFlow()

    /** Read straight — the worker checks this without collecting a flow. */
    val token: String? get() = _current.value?.token

    val isSignedIn: Boolean get() = _current.value != null

    fun signIn(account: SignedIn) {
        _current.value = account
    }

    fun signOut() {
        _current.value = null
    }

    /**
     * A token handed to the build (`.env`) or a provisioning QR rather than earned by login.
     *
     * Applied only when nobody is signed in, so a real login always wins over a stale build
     * default. Still not persisted: reopening the app re-reads it from the build, which is
     * the same rule as logging in again.
     */
    fun seedIfAbsent(token: String, username: String) {
        if (token.isBlank() || _current.value != null) return
        _current.value = SignedIn(token = token, username = username)
    }
}
