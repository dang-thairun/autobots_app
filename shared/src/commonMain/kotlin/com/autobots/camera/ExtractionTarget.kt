package com.autobots.camera

/**
 * Offline chunk extraction mode (experimental pose path for field testing).
 */
enum class ExtractionTarget(val label: String) {
    Face("Face"),
    Pose("Pose"),

    /**
     * Both detectors must agree before a frame is kept: a face large and sharp enough to
     * identify the runner by, *and* a torso that is fully in frame.
     *
     * Face alone answers "is someone there" but never "is this a usable photograph" — every
     * gate it applies is measured inside the face box, so a frame whose legs are cut off at
     * the edge passes untouched. This mode trades yield for that check.
     */
    FaceAndPose("Face + Pose"),
    ;

    /** True when this mode runs the face detector at all. */
    val usesFace: Boolean
        get() = this == Face || this == FaceAndPose

    /** True when this mode runs the pose detector at all. */
    val usesPose: Boolean
        get() = this == Pose || this == FaceAndPose

    val keptNoun: String
        get() = when (this) {
            Face -> "faces"
            Pose -> "poses"
            FaceAndPose -> "shots"
        }

    val noKeptLabel: String
        get() = when (this) {
            Face -> "No face"
            Pose -> "No pose"
            FaceAndPose -> "No subject"
        }

    val statLabel: String
        get() = when (this) {
            Face -> "Face"
            Pose -> "Pose"
            FaceAndPose -> "F+P"
        }

    /** Prefix for the JPEGs this mode writes. */
    val filePrefix: String
        get() = when (this) {
            Face -> "face"
            Pose -> "pose"
            FaceAndPose -> "person"
        }
}
