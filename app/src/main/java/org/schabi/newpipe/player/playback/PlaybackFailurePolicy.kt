/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

// What to do when an item fails with an error that might be transient (network, timeout,
// unspecified). Retrying forever is right for a dropped connection and wrong for an item
// that cannot be had at all, such as a downloaded track whose file is gone while the
// phone is offline: the player used to reload that one item every few seconds, raising a
// notification each time. Retry a little, then move on; if several items in a row fail,
// the problem is the connection rather than the items, so stop instead of draining the
// queue.
class PlaybackFailurePolicy(
    private val retriesPerItem: Int = 2,
    private val failedItemsBeforeStop: Int = 3
) {
    enum class Action { RETRY, SKIP, STOP }

    private var lastKey: String? = null
    private var attempts = 0
    private var failedItems = 0

    // key identifies the failing item (null when unknown, treated as a new item).
    fun onFailure(key: String?): Action {
        if (key != null && key == lastKey) {
            attempts += 1
        } else {
            lastKey = key
            attempts = 1
        }
        if (attempts <= retriesPerItem) return Action.RETRY
        failedItems += 1
        lastKey = null
        attempts = 0
        if (failedItems >= failedItemsBeforeStop) {
            failedItems = 0
            return Action.STOP
        }
        return Action.SKIP
    }

    // Something played, so whatever was failing has recovered.
    fun onPlaybackStarted() {
        lastKey = null
        attempts = 0
        failedItems = 0
    }
}
