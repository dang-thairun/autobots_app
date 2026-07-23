package com.autobots.camera

/**
 * App identity shared across KMP modules.
 *
 * **Version:** keep [version] in sync with `appVersionName` in root `gradle.properties`
 * (Android APK uses gradle; this constant is for UI, docs, and iOS/common).
 */
object AutobotsApp {
    const val name: String = "AutoBots"

    /** Sync with `gradle.properties` → `appVersionName`. */
    const val version: String = "0.1.2"

    val versionLabel: String get() = "v$version"

    /** Last completed implementation milestone (see docs/DOCS.md). */
    const val phase: String = "B1"

    val banner: String get() = "$name $versionLabel"
}
