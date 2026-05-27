/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

object AdaptiveShuffleConfig {
    const val RIDGE_LAMBDA: Double = 1.0
    const val EXPLORATION_ALPHA: Double = 1.0

    // Reward weights, mirroring section 3 of the design spec.
    const val REWARD_COMPLETION: Double = 1.0
    const val REWARD_EARLY_SKIP: Double = -1.5
    const val REWARD_MID_SKIP: Double = -0.2
    const val REWARD_REWIND_DENSITY: Double = 0.5
    const val REWIND_CAP: Double = 2.0

    // Completion-ratio thresholds.
    const val EARLY_SKIP_BELOW: Double = 0.15
    const val COMPLETION_AT_OR_ABOVE: Double = 0.85

    // Defaults for cold-start items (lifetime plays < this many).
    const val COLD_START_MIN_PLAYS: Int = 3
    const val COLD_START_SKIP_RATIO: Double = 0.2
    const val COLD_START_COMPLETION_RATIO: Double = 0.5
    const val NEVER_PLAYED_DAYS_DEFAULT: Double = 30.0

    // Telemetry rotation cap (bytes).
    const val TELEMETRY_MAX_BYTES: Long = 10L * 1024L * 1024L

    // Recent-decision window for confidence-badge normalisation.
    const val CONFIDENCE_NORMALISATION_WINDOW: Int = 100
}
