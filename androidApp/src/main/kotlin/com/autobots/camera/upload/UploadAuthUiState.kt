package com.autobots.camera.upload

/**
 * What the sign-in and event-picker part of the upload settings screen is doing.
 *
 * Separate from [UploadConfig] because none of it is durable: it is the state of two network
 * calls in flight, and it should be empty again after a restart.
 *
 * @property loaded true once an event fetch has come back, even with nothing in it. Lets the
 *   screen tell "no events for this account" apart from "not asked yet", which look identical
 *   otherwise and mean very different things.
 */
data class UploadAuthUiState(
    val busy: Boolean = false,
    val loadingEvents: Boolean = false,
    val loaded: Boolean = false,
    val events: List<RunxAuthClient.EventSummary> = emptyList(),
    val truncated: Boolean = false,
    val scope: RunxAuthClient.EventScope = RunxAuthClient.EventScope.MyEvents,
    val search: String = "",
    val error: String? = null,
)
