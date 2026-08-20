package com.autobots.camera.upload

import android.net.Uri

/**
 * What to call the place photos are going, in one line the operator can read.
 *
 * Lives outside the transports because the UI has to answer this **before** a transport
 * exists — "not configured" and "sign-in required" are the two states where there is nothing
 * to construct, and they are exactly the states worth showing.
 *
 * Kept next to the selection rule in [UploadWorker] on purpose: the screen must never name a
 * destination the worker would not actually use.
 */
fun uploadDestinationLabel(
    config: UploadConfig,
    signedIn: Boolean,
    fakeTransport: Boolean,
): String = when {
    config.isComplete && signedIn -> Uri.parse(config.completeUrl).host ?: "backend"
    fakeTransport -> "local test sink"
    !config.isComplete -> "not configured"
    else -> "sign-in required"
}
