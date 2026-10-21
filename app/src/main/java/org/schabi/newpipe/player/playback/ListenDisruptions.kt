/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// Things that happen to a listen from outside the music (spec section 4.2): a pause the
// system made (a call, another app taking audio focus, headphones unplugged or a
// Bluetooth device dropping), a stall while the stream buffers, and volume presses. Each
// method takes the time explicitly so the rules can be tested; one instance per listen.
class ListenDisruptions {
    private var externalPauseAt: Long = -1L
    private var externalResumeAt: Long = -1L
    private var stallStartedAt: Long = -1L
    private var lastStallEndedAt: Long = -1L
    private var lastStallMillis: Long = 0L
    private var lastVolumeAt: Long = Long.MIN_VALUE
    private var lastVolumeUp: Boolean = false

    // The system paused playback.
    fun onExternalPause(at: Long) {
        externalPauseAt = at
        externalResumeAt = -1L
    }

    fun onResumed(at: Long) {
        if (externalPauseAt >= 0L && externalResumeAt < 0L) externalResumeAt = at
    }

    fun onStallStarted(at: Long) {
        if (stallStartedAt < 0L) stallStartedAt = at
    }

    fun onStallEnded(at: Long) {
        if (stallStartedAt < 0L) return
        lastStallMillis = at - stallStartedAt
        lastStallEndedAt = at
        stallStartedAt = -1L
    }

    // Leaving now follows a system pause: still paused by it, or resumed only moments ago.
    fun interruptedAt(at: Long): Boolean {
        if (externalPauseAt < 0L) return false
        return externalResumeAt < 0L || at - externalResumeAt < SETTLE_MILLIS
    }

    // Leaving now is leaving a stall: in one, or just out of a long one.
    fun stalledAt(at: Long): Boolean {
        if (stallStartedAt >= 0L && at - stallStartedAt >= STALL_MIN_MILLIS) return true
        return lastStallEndedAt >= 0L && lastStallMillis >= STALL_MIN_MILLIS &&
            at - lastStallEndedAt < SETTLE_MILLIS
    }

    // Whether a volume step starts a new adjustment. Holding a key or pressing it
    // repeatedly is one adjustment, not one per step, so a track is not marked as
    // needing the volume changed six times when it was changed once.
    fun acceptVolumeStep(up: Boolean, at: Long): Boolean {
        val fresh = up != lastVolumeUp || at - lastVolumeAt > VOLUME_BURST_MILLIS
        lastVolumeAt = at
        lastVolumeUp = up
        return fresh
    }

    companion object {
        // How long playing on after an interruption or stall takes to count as choosing
        // to carry on listening.
        const val SETTLE_MILLIS: Long = 10_000L
        const val STALL_MIN_MILLIS: Long = 3_000L
        const val VOLUME_BURST_MILLIS: Long = 2_000L
    }
}
