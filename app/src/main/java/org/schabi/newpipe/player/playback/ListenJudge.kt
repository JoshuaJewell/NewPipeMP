/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// Decides what leaving a track says about it, for the lifetime skip and completion counts
// that ratings statistics and adaptive shuffle both read, and for adaptive shuffle's
// reward. Kept free of player state so the rules can be tested off-device.
//
// Most of the rules say when a listen says nothing (spec section 4.2): the track was only
// where a queue started, playback was interrupted by something outside the music, the
// stream stalled, or it was left too soon to have been heard. A listen that says nothing
// leaves no trace on the track at all, rather than a skip.
object ListenJudge {

    enum class Verdict { COMPLETION, SKIP, NONE }

    // Why a listen carries no verdict. The player supplies the first four; the rest
    // follow from the facts.
    enum class Unjudged {
        // Removed from the queue while playing, or the player closed.
        REMOVED,

        // Its source failed part way.
        FAILED,

        // The first track of a queue, left early once shuffle was on: "Play all", then
        // shuffle, then skip to get to the shuffled tracks.
        QUEUE_START,

        // Played only on the way back to the previous track (see ReturnWindow).
        DETOUR,

        UNKNOWN_LENGTH,

        // Resumed so near the end that there was nothing left to judge.
        RESUMED_NEAR_END,

        // Paused by a call, another app or unplugged headphones, and left before playing
        // on for long.
        INTERRUPTED,

        // Left while stalled, or straight after a long stall.
        STALLED,

        // Left within a second: a double press, not a decision.
        TOO_SHORT,

        // Left within a few seconds with the screen off: with nothing showing what was
        // playing, a quick skip is a fumble with the headset more often than a verdict.
        UNSEEN
    }

    data class Facts(
        val positionMillis: Long,
        val durationMillis: Long,
        val naturalEnd: Boolean,
        // From ListenTrace; null coverage means it could not be measured.
        val heardMillis: Long,
        val coverage: Double?,
        val screenOn: Boolean = true,
        val interrupted: Boolean = false,
        val stalled: Boolean = false,
        val reason: Unjudged? = null
    )

    data class Judgement(
        val verdict: Verdict,
        // Share of the track heard, the basis of the reward; null when unjudged.
        val completionRatio: Double?,
        val unjudged: Unjudged?
    )

    // Matches the threshold the player has always used for a completion.
    const val COMPLETION_FRACTION: Double = 0.9

    // A track that played to its own end counts as finished when at least this much of
    // it was heard: skipping a long intro or outro is not rejecting the track.
    const val NATURAL_END_COMPLETION_FRACTION: Double = 0.6

    const val TOO_SHORT_MILLIS: Long = 1_000L
    const val UNSEEN_MILLIS: Long = 3_000L

    // Turning shuffle on this early in the first track of a queue means the track was
    // only ever a starting point: "Play all" followed by shuffle. Leaving it is not a
    // verdict on it.
    const val SHUFFLE_START_GRACE_MILLIS: Long = 30_000L

    @JvmStatic
    fun judge(f: Facts): Judgement {
        fun none(reason: Unjudged) = Judgement(Verdict.NONE, null, reason)
        f.reason?.let { return none(it) }
        if (f.durationMillis <= 0L) return none(Unjudged.UNKNOWN_LENGTH)
        if (f.interrupted) return none(Unjudged.INTERRUPTED)
        if (f.stalled) return none(Unjudged.STALLED)
        if (!f.naturalEnd && f.heardMillis < TOO_SHORT_MILLIS) return none(Unjudged.TOO_SHORT)
        if (!f.naturalEnd && !f.screenOn && f.heardMillis < UNSEEN_MILLIS) return none(Unjudged.UNSEEN)
        val ratio = f.coverage ?: return none(Unjudged.RESUMED_NEAR_END)
        val completed = ratio > COMPLETION_FRACTION || (f.naturalEnd && ratio >= NATURAL_END_COMPLETION_FRACTION)
        return Judgement(if (completed) Verdict.COMPLETION else Verdict.SKIP, ratio, null)
    }

    // The original position-only rule, kept for callers without a trace.
    @JvmStatic
    fun judge(positionMillis: Long, durationMillis: Long, naturalEnd: Boolean, unjudged: Boolean): Verdict {
        if (unjudged || durationMillis <= 0L) return Verdict.NONE
        if (naturalEnd) return Verdict.COMPLETION
        val fraction = positionMillis.coerceAtLeast(0L).toDouble() / durationMillis
        return if (fraction > COMPLETION_FRACTION) Verdict.COMPLETION else Verdict.SKIP
    }

    @JvmStatic
    fun isShuffleStartGrace(isFirstTrackOfQueue: Boolean, positionMillis: Long): Boolean = isFirstTrackOfQueue && positionMillis < SHUFFLE_START_GRACE_MILLIS
}
