/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

object AdaptiveShuffleConfig {
    const val RIDGE_LAMBDA: Double = 1.0
    const val EXPLORATION_ALPHA: Double = 1.0

    // Per-update discount on past evidence (spec section 6, "ways not yet discussed").
    // Each update shrinks earlier evidence by this factor, so the model's memory is
    // roughly 1 / (1 - gamma) events (about 1000 here). Tastes drift, and early decisions
    // made by a worse policy should not anchor the model forever. 1.0 disables it.
    const val DISCOUNT_GAMMA: Double = 0.999

    // Continuous reward (spec section 6.4): base = SLOPE * cr + OFFSET, neutral near 0.62.
    const val REWARD_SLOPE: Double = 1.78
    const val REWARD_OFFSET: Double = -1.10
    const val REWARD_EARLY_SKIP_EXTRA: Double = -0.70
    const val REWARD_REWIND_DENSITY: Double = 0.5
    const val REWIND_CAP: Double = 2.0

    // Completion-ratio thresholds. Still used for the early-skip penalty and for the
    // prev_was_completed / recent-skip context features.
    const val EARLY_SKIP_BELOW: Double = 0.15
    const val COMPLETION_AT_OR_ABOVE: Double = 0.85

    // The v1 step reward, kept only so historical rewards can be decoded back into a
    // restart delta when recomputing them under the continuous reward.
    const val LEGACY_REWARD_COMPLETION: Double = 1.0
    const val LEGACY_REWARD_EARLY_SKIP: Double = -1.5
    const val LEGACY_REWARD_MID_SKIP: Double = -0.2

    // Defaults for cold-start items (lifetime plays < this many).
    const val COLD_START_MIN_PLAYS: Int = 3
    const val COLD_START_SKIP_RATIO: Double = 0.2
    const val COLD_START_COMPLETION_RATIO: Double = 0.5
    const val NEVER_PLAYED_DAYS_DEFAULT: Double = 30.0

    // Candidate selection (spec sections 6.2 and 9.2).
    const val CANDIDATE_SAMPLE_CAP: Int = 30

    // Recency window: a played track is ineligible for this many subsequent picks. It
    // scales with the queue so a large library rotates widely: a quarter of the tail,
    // never fewer than MIN nor more than MAX. With ~1500 tracks that is 375 picks, about
    // 19 hours at three-minute tracks. Without scaling, a bandit happily replays its top
    // few dozen every two hours (CollapseSimulationTest shows this).
    const val RECENCY_EXCLUSION_N: Int = 40
    const val RECENCY_EXCLUSION_MAX: Int = 400
    const val RECENCY_EXCLUSION_FRACTION: Double = 0.25
    const val UPLOADER_SPACING: Int = 2
    const val MIN_ELIGIBLE_CANDIDATES: Int = 20

    // Synthetic examples from Bootstrap are a weak prior, not evidence. Each counts as
    // this fraction of a real decision.
    const val BOOTSTRAP_WEIGHT: Double = 0.25

    // Telemetry rotation cap (bytes).
    const val TELEMETRY_MAX_BYTES: Long = 10L * 1024L * 1024L

    // Recent-decision window for confidence-badge normalisation.
    const val CONFIDENCE_NORMALISATION_WINDOW: Int = 100

    // Repetition metrics (spec section 7.1).
    const val METRICS_WINDOW: Int = 100
    const val METRICS_SESSIONS: Int = 10
    const val SESSION_GAP_MILLIS: Long = 30L * 60L * 1000L

    // Telemetry lines are matched to pending events within this window when recovering
    // historical completion ratios.
    const val TELEMETRY_MATCH_TOLERANCE_MILLIS: Long = 10_000L
}
