/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// Whether a stream that failed part way through should be extracted again and resumed.
//
// YouTube stream URLs can stop being served mid-track (an HTTP 403 after the first minute
// or so, when the URL or its integrity token goes stale). Reloading reused the cached
// stream info, so the same URL failed again and the track was skipped, while a fresh
// extraction, as restarting the app does, plays on from the same point. A track that
// keeps failing at the same point is given up on rather than refreshed forever.
class StreamRefreshPolicy(
    private val maxRefreshesPerItem: Int = 3,
    private val minProgressMillis: Long = 2_000L
) {
    private var key: String? = null
    private var refreshes = 0
    private var lastFailureAt = -1L

    // key identifies the item; positionMillis is where it failed.
    fun shouldRefresh(key: String?, positionMillis: Long): Boolean {
        if (key == null) return false
        if (key != this.key) {
            this.key = key
            refreshes = 0
            lastFailureAt = -1L
        }
        val madeProgress = lastFailureAt < 0 || positionMillis - lastFailureAt >= minProgressMillis
        if (refreshes >= maxRefreshesPerItem || !madeProgress) return false
        refreshes += 1
        lastFailureAt = positionMillis
        return true
    }
}
