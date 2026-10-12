/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.player.playback.PlaybackFailurePolicy.Action

class PlaybackFailurePolicyTest {

    @Test fun `an item that keeps failing is retried twice and then skipped`() {
        val p = PlaybackFailurePolicy()
        assertEquals(Action.RETRY, p.onFailure("a"))
        assertEquals(Action.RETRY, p.onFailure("a"))
        assertEquals(Action.SKIP, p.onFailure("a"))
    }

    @Test fun `several failed items in a row stop playback instead of draining the queue`() {
        val p = PlaybackFailurePolicy()
        for (key in listOf("a", "b")) {
            repeat(2) { assertEquals(Action.RETRY, p.onFailure(key)) }
            assertEquals(Action.SKIP, p.onFailure(key))
        }
        repeat(2) { assertEquals(Action.RETRY, p.onFailure("c")) }
        assertEquals(Action.STOP, p.onFailure("c"))
        // After stopping, a fresh attempt (the user pressing play) starts over.
        assertEquals(Action.RETRY, p.onFailure("c"))
    }

    @Test fun `successful playback clears the count`() {
        val p = PlaybackFailurePolicy()
        repeat(2) { p.onFailure("a") }
        assertEquals(Action.SKIP, p.onFailure("a"))
        repeat(2) { p.onFailure("b") }
        assertEquals(Action.SKIP, p.onFailure("b"))
        p.onPlaybackStarted()
        repeat(2) { assertEquals(Action.RETRY, p.onFailure("c")) }
        assertEquals(Action.SKIP, p.onFailure("c"))
    }

    @Test fun `a transient failure that recovers is never skipped`() {
        val p = PlaybackFailurePolicy()
        repeat(10) {
            assertEquals(Action.RETRY, p.onFailure("a"))
            p.onPlaybackStarted()
        }
    }
}
