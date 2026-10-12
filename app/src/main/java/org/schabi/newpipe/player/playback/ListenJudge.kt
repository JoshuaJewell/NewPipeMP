/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// Decides what leaving a track says about it, for the lifetime skip and completion counts
// that ratings statistics and adaptive shuffle both read. Kept free of player state so the
// rules can be tested off-device.
object ListenJudge {

    enum class Verdict { COMPLETION, SKIP, NONE }

    // Matches the threshold the player has always used for a completion.
    const val COMPLETION_FRACTION: Double = 0.9

    // Turning shuffle on this early in the first track of a queue means the track was
    // only ever a starting point: "Play all" followed by shuffle. Leaving it is not a
    // verdict on it.
    const val SHUFFLE_START_GRACE_MILLIS: Long = 30_000L

    // naturalEnd: the track played out and the player moved on by itself.
    // unjudged: the listen carries no signal (see the callers for when that holds).
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
