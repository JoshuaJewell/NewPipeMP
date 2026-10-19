/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRefreshPolicyTest {

    @Test fun `refreshes a track that failed part way through`() {
        assertTrue(StreamRefreshPolicy().shouldRefresh("a", 59_000L))
    }

    @Test fun `gives up when a refresh made no progress`() {
        val p = StreamRefreshPolicy()
        assertTrue(p.shouldRefresh("a", 59_000L))
        assertFalse(p.shouldRefresh("a", 59_500L))
    }

    @Test fun `keeps refreshing while the track moves on, up to the limit`() {
        val p = StreamRefreshPolicy(maxRefreshesPerItem = 3)
        assertTrue(p.shouldRefresh("a", 59_000L))
        assertTrue(p.shouldRefresh("a", 120_000L))
        assertTrue(p.shouldRefresh("a", 180_000L))
        assertFalse(p.shouldRefresh("a", 240_000L))
    }

    @Test fun `a new track starts afresh`() {
        val p = StreamRefreshPolicy(maxRefreshesPerItem = 1)
        assertTrue(p.shouldRefresh("a", 59_000L))
        assertFalse(p.shouldRefresh("a", 120_000L))
        assertTrue(p.shouldRefresh("b", 59_000L))
    }

    @Test fun `an unknown item is not refreshed`() {
        assertFalse(StreamRefreshPolicy().shouldRefresh(null, 59_000L))
    }
}
