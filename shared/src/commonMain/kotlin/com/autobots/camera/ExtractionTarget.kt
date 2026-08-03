package com.autobots.camera

/**
 * Offline chunk extraction mode (experimental pose path for field testing).
 */
enum class ExtractionTarget(val label: String) {
    Face("Face"),
    Pose("Pose"),
    ;

    val keptNoun: String
        get() = when (this) {
            Face -> "faces"
            Pose -> "poses"
        }

    val noKeptLabel: String
        get() = when (this) {
            Face -> "No face"
            Pose -> "No pose"
        }

    val statLabel: String
        get() = when (this) {
            Face -> "Face"
            Pose -> "Pose"
        }
}
