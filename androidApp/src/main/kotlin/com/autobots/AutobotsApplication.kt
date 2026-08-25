package com.autobots

import android.app.Application
import android.util.Log
import com.autobots.camera.diag.CrashDiagnostics
import com.autobots.camera.diag.SessionRecovery
import java.io.File
import kotlin.concurrent.thread

/**
 * Startup, in the order the ordering matters.
 *
 * 1. Install the crash handler **first**, so a failure in step 2 or 3 is itself recorded.
 * 2. Ask the platform why the previous process died. This is the only moment that record is
 *    available and the only source that can see a native crash or a low-memory kill.
 * 3. Rebuild reports for any session that died before it could write one.
 *
 * Steps 2 and 3 touch the filesystem, so they run off the main thread. They are diagnostics
 * about a run that is already over; nothing waits on them and a failure in either must not
 * delay the UI.
 */
class AutobotsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val diagDir = File(cacheDir, DIAG_DIR)
        CrashDiagnostics.installCrashHandler(diagDir)

        thread(name = "autobots-diag", isDaemon = true) {
            runCatching { CrashDiagnostics.reportLastExit(this, diagDir) }
                .onFailure { Log.w(TAG, "exit-info read failed: ${it.message}") }
            runCatching { SessionRecovery.sweep(this, File(cacheDir, SESSIONS_DIR)) }
                .onFailure { Log.w(TAG, "session recovery failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "AutobotsApplication"

        /** Not per session — at startup the session that died is not yet identified. */
        const val DIAG_DIR = "autobots/diag"

        /** Mirrors `CapturePipelineCoordinator`'s `cache/autobots/{sessionId}` layout. */
        private const val SESSIONS_DIR = "autobots"
    }
}
