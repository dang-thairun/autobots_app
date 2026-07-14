package com.autobots.camera

/**
 * Phase 0 smoke API — proves shared ↔ androidApp linkage.
 * Real domain types arrive in later phases.
 */
object AutobotsApp {
    const val name: String = "AutoBots"
    const val phase: String = "P8"
    val banner: String get() = "$name · $phase · shared OK"
}
