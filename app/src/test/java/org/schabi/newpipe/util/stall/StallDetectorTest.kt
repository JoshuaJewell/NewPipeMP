/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util.stall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {
    private fun detector() = StallDetector(tickMillis = 2_000L, stallAfterMillis = 15_000L, redumpAfterMillis = 60_000L)

    private fun StallDetector.run(from: Long, to: Long): List<StallDetector.Event> = (from..to step 2_000L).flatMap { tick(it) }

    @Test fun `a probe answering in time raises nothing`() {
        val d = detector()
        d.tick(0L)
        d.started("db")
        assertTrue(d.run(2_000L, 10_000L).isEmpty())
        d.finished("db")
        assertTrue(d.run(12_000L, 40_000L).isEmpty())
    }

    @Test fun `a stuck probe is reported once, again a minute later, then its recovery`() {
        val d = detector()
        d.tick(0L)
        d.started("db")
        val events = d.run(2_000L, 100_000L)
        val stalls = events.filterIsInstance<StallDetector.Event.Stalled>()
        assertEquals(2, stalls.size)
        assertEquals(false, stalls[0].repeat)
        assertEquals(16_000L, stalls[0].probes.getValue("db"))
        assertEquals(true, stalls[1].repeat)
        d.finished("db")
        val after = d.tick(102_000L)
        val recovered = after.single() as StallDetector.Event.Recovered
        assertEquals(100_000L, recovered.waitedMillis)
    }

    // A probe started just before the app was frozen for six hours, answered just after.
    @Test fun `time suspended is not counted as waiting`() {
        val d = detector()
        d.tick(0L)
        d.started("db")
        d.tick(2_000L)
        val thawed = d.tick(2_000L + 6L * 3_600_000L)
        assertEquals(StallDetector.Event.Suspended(6L * 3_600_000L), thawed.single())
        d.finished("db")
        assertTrue(d.tick(4_000L + 6L * 3_600_000L).isEmpty())
    }

    @Test fun `a probe already outstanding is not started twice`() {
        val d = detector()
        d.started("db")
        d.tick(0L)
        d.tick(2_000L)
        d.started("db")
        assertEquals(setOf("db"), d.outstanding)
        val stall = d.run(4_000L, 16_000L).filterIsInstance<StallDetector.Event.Stalled>().single()
        assertEquals(16_000L, stall.probes.getValue("db"))
    }
}
