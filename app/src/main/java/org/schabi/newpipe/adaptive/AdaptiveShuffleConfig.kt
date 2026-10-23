/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

object AdaptiveShuffleConfig {
    const val RIDGE_LAMBDA: Double = 1.0
    const val EXPLORATION_ALPHA: Double = 1.0

    // Prior precision of a track's own offset in the hybrid model (spec section 6.6), in
    // units of listens: a track's offset carries half its own evidence after this many.
    // With reward noise about 0.8 it makes the prior sd of an offset about 0.28, and the
    // extra exploration bonus of a never-played track 1 / sqrt(8), about 0.35.
    const val TRACK_OFFSET_LAMBDA: Double = 8.0

    // Prior precision of a track's own recovery slope: how much more (or less) it is
    // wanted per standard deviation of time since it last played, beyond the shared
    // days_since_played weight. Tighter than the offset's, as a slope needs listens at
    // varied gaps to show; until then a track recovers like the library as a whole.
    const val TRACK_RECOVERY_LAMBDA: Double = 16.0

    // Per-update discount on past evidence (spec section 6, "ways not yet discussed").
    // Each update shrinks earlier evidence by this factor, so the model's memory is
    // roughly 1 / (1 - gamma) events: about 3,300 here, so an event 1,000 listens ago
    // still counts 74%. Tastes drift, and early decisions made by a worse policy should
    // not anchor the model forever, but per-track evidence builds slowly (a track heard
    // twice a month), and random picks now guard against the policy's own bias. Counted
    // in listens, not time: a month away forgets nothing. 1.0 disables it.
    const val DISCOUNT_GAMMA: Double = 0.9997

    // Continuous reward (spec section 6.4): base = SLOPE * cr + OFFSET, neutral near 0.62.
    const val REWARD_SLOPE: Double = 1.78
    const val REWARD_OFFSET: Double = -1.10
    const val REWARD_EARLY_SKIP_EXTRA: Double = -0.70
    const val REWARD_REWIND_DENSITY: Double = 0.5
    const val REWIND_CAP: Double = 2.0

    // A rewind of part of a track counts as this much of a restart.
    const val PARTIAL_REWIND_WEIGHT: Double = 0.5

    // Evidence weights (spec section 6.7). A track the listener chose themselves is
    // nearly always finished, so its outcome overstates how it would do as a shuffle
    // pick. A pairwise preference (the chosen track over the pick it displaced) touches
    // only the shared weights, with this target margin in reward units.
    const val USER_PICK_WEIGHT: Double = 0.5
    const val PAIRWISE_WEIGHT: Double = 0.25
    const val PAIRWISE_MARGIN: Double = 0.5
    const val PAIRWISE_CLIP: Double = 3.0

    // A track heard again within this long counts for less each time: the k-th listen in
    // the window weighs 1 / k. An album on repeat for an afternoon is one choice heard
    // many times, not many independent verdicts.
    const val REPEAT_WINDOW_MILLIS: Long = 24L * 60L * 60L * 1000L

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

    // Completion rate given to a track with too few finished listens: near the usual one,
    // so it reads as "an ordinary track", not as a has-history indicator (spec D11).
    const val COLD_START_COMPLETION_RATIO: Double = 0.8

    // Mean leave point given to a track without enough recorded listens. Near the played
    // tracks' usual value, so that has_listen_history, not this value, says "no history".
    const val COLD_START_LEAVE_POINT: Double = 0.8
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
