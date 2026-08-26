package com.autobots.camera

/**
 * Which detectors a session runs, and therefore what a frame has to satisfy to be kept.
 *
 * **This was an enum until 0.1.7**, with `Face`, `Pose` and `FaceAndPose` as three separate
 * entries. That shape was already strained: the import page has never had three radio buttons,
 * it has independent switches, and its own comment admitted the mismatch — *"`FaceAndPose` is
 * both switches on, not a row"*. A third detector settles it. Three flags are eight
 * combinations, and enumerating eight named constants to model three booleans is a listing of
 * the truth table rather than a type.
 *
 * So this is the truth table itself. Every gate reads its own flag, every label is derived,
 * and adding a fourth detector costs one property instead of eight constants.
 *
 * **All three gates are AND, not OR.** A frame with every enabled detector satisfied is kept;
 * anything less is rejected. That is deliberate and it costs yield — the point of running two
 * detectors is that each answers a question the other cannot, and a frame that fails either
 * one is a frame that fails.
 */
data class ExtractionTarget(
    /** ML Kit Face or `face_det_lite`, depending on the backend. Answers *who*. */
    val usesFace: Boolean = false,
    /** ML Kit Pose. Answers *is the body usably in shot* — and sees one person per frame. */
    val usesPose: Boolean = false,
    /** `foot_track_net`. Answers *where is everybody*, and unlike the other two, for everyone. */
    val usesPerson: Boolean = false,
) {
    /** Nothing to detect — the Extract button is disabled rather than running an empty gate. */
    val isEmpty: Boolean
        get() = !usesFace && !usesPose && !usesPerson

    val enabledCount: Int
        get() = (if (usesFace) 1 else 0) + (if (usesPose) 1 else 0) + (if (usesPerson) 1 else 0)

    /** Full name for headings and the session log: `Face + Person`. */
    val label: String
        get() = names.joinToString(" + ").ifEmpty { "None" }

    /** Compact form for status chips, where the column is a few characters wide. */
    val statLabel: String
        get() = when {
            isEmpty -> "—"
            enabledCount == 1 -> names.first()
            else -> tags.joinToString("+")
        }

    /**
     * Prefix for the JPEGs this mode writes.
     *
     * A single-detector session says which detector took it; any combination says `person`,
     * because the file is then a whole runner rather than one detector's idea of them.
     */
    val filePrefix: String
        get() = when {
            enabledCount != 1 -> "person"
            usesFace -> "face"
            usesPose -> "pose"
            else -> "person"
        }

    val keptNoun: String
        get() = when {
            enabledCount != 1 -> "shots"
            usesFace -> "faces"
            usesPose -> "poses"
            else -> "people"
        }

    val noKeptLabel: String
        get() = when {
            enabledCount != 1 -> "No subject"
            usesFace -> "No face"
            usesPose -> "No pose"
            else -> "No person"
        }

    /** Stable machine form for `perf_report.json` and `session_log.txt`. */
    val slug: String
        get() = if (isEmpty) "none" else tags.joinToString("_") { it.lowercase() }

    private val names: List<String>
        get() = buildList {
            if (usesFace) add("Face")
            if (usesPose) add("Pose")
            if (usesPerson) add("Person")
        }

    private val tags: List<String>
        get() = buildList {
            // Pose and Person both start with P, so neither gets the bare letter.
            if (usesFace) add("F")
            if (usesPose) add("Ps")
            if (usesPerson) add("Pr")
        }

    companion object {
        val Face = ExtractionTarget(usesFace = true)
        val Pose = ExtractionTarget(usesPose = true)
        val Person = ExtractionTarget(usesPerson = true)

        /**
         * Both detectors must agree: a face large and sharp enough to identify the runner by,
         * *and* a torso fully in frame.
         *
         * Face alone answers "is someone there" but never "is this a usable photograph" —
         * every gate it applies is measured inside the face box, so a frame whose legs are cut
         * off at the edge passes untouched. This mode trades yield for that check.
         */
        val FaceAndPose = ExtractionTarget(usesFace = true, usesPose = true)

        /**
         * Person finds, face confirms — the combination `foot_track_net` was added for.
         *
         * A face detector loses runners who look down, wear a cap, or run at 5 a.m.; a person
         * box survives all three. But a person box cannot say whether the face is visible, and
         * a photo of the back of someone's head does not sell. Neither detector is sufficient
         * and together they are.
         */
        val FaceAndPerson = ExtractionTarget(usesFace = true, usesPerson = true)

        val DEFAULT = Face

        /** What the live-capture chip row offers. The import page has the full truth table. */
        val PRESETS = listOf(Face, Pose, Person, FaceAndPerson)
    }
}
