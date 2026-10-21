/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// Holds a finished listen back for a moment before it counts (spec section 4.2). Skipping
// a track and coming straight back to it, or pressing previous just after a track ended,
// says the opposite of what the skip or the move on said: a fumbled headset button, a
// change of mind, or wanting the track again. While the listen is held, coming back to
// its track resumes it instead, and the track played on the way back is a detour that
// says nothing. Anything else (the next track playing on for a while, leaving for a
// different track, playback stopping) releases it to be recorded as it was.
//
// Keys are compared by identity: a queue can hold the same track twice.
class ReturnWindow<K : Any, T : Any>(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    sealed class Outcome<T> {
        // Record released (if any) now; the listen just ended is held.
        class Held<T>(val released: T?) : Outcome<T>()

        // Playback went back to the held listen's track: resume it. The listen just
        // ended was the detour.
        class Returned<T>(val resumed: T, val detour: T) : Outcome<T>()
    }

    private var heldKey: K? = null
    private var held: T? = null

    val isHolding: Boolean get() = held != null

    // Whether key's listen is the one held: going to it now would be a return.
    fun holds(key: K): Boolean = held != null && heldKey === key

    // A listen of key ended and playback moved to destination (null if unknown).
    fun leave(key: K, listen: T, destination: K?): Outcome<T> {
        val k = heldKey
        val h = held
        if (h != null && destination != null && destination === k && key !== k) {
            heldKey = null
            held = null
            return Outcome.Returned(h, listen)
        }
        heldKey = key
        held = listen
        return Outcome.Held(h)
    }

    // The track now playing has been heard for heardMillis. Returns the held listen once
    // it can no longer be returned to.
    fun onProgress(heardMillis: Long): T? = if (held != null && heardMillis >= windowMillis) release() else null

    // Playback stopped, or the queue changed under it.
    fun release(): T? {
        val h = held
        heldKey = null
        held = null
        return h
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS: Long = 20_000L
    }
}
