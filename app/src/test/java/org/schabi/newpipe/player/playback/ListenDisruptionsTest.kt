/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenDisruptionsTest {
    @Test fun `leaving during or just after a system pause is an interruption`() {
        val d = ListenDisruptions()
        assertFalse(d.interruptedAt(0L))
        d.onExternalPause(1_000L)
        assertTrue(d.interruptedAt(60_000L))
        d.onResumed(60_000L)
        assertTrue(d.interruptedAt(65_000L))
        assertFalse(d.interruptedAt(71_000L))
    }

    @Test fun `only long stalls count, and only until playing settles`() {
        val d = ListenDisruptions()
        d.onStallStarted(0L)
        d.onStallEnded(1_000L)
        assertFalse(d.stalledAt(2_000L))
        d.onStallStarted(10_000L)
        assertTrue(d.stalledAt(14_000L))
        d.onStallEnded(15_000L)
        assertTrue(d.stalledAt(20_000L))
        assertFalse(d.stalledAt(26_000L))
    }

    @Test fun `a burst of volume steps is one adjustment`() {
        val d = ListenDisruptions()
        assertTrue(d.acceptVolumeStep(true, 0L))
        assertFalse(d.acceptVolumeStep(true, 400L))
        assertFalse(d.acceptVolumeStep(true, 900L))
        assertTrue(d.acceptVolumeStep(false, 1_200L))
        assertTrue(d.acceptVolumeStep(false, 5_000L))
    }
}
