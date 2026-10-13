/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeavePointStatsTest {

    @Test fun `running mean and spread match the batch values`() {
        val points = listOf(0.12, 1.0, 0.95, 0.4, 1.0, 0.33, 0.87)
        val stats = points.fold(LeavePointStats(0, 0.0, 0.0)) { s, x -> LeavePointStats.add(s, x) }
        val mean = points.average()
        val sd = sqrt(points.sumOf { (it - mean) * (it - mean) } / (points.size - 1))
        assertEquals(points.size, stats.count)
        assertEquals(mean, stats.mean, 1e-12)
        assertEquals(sd, stats.spread()!!, 1e-12)
    }

    @Test fun `spread needs two points`() {
        assertNull(LeavePointStats.add(LeavePointStats(0, 0.0, 0.0), 0.5).spread())
    }

    @Test fun `points outside the track are clamped`() {
        val s = LeavePointStats.add(LeavePointStats.add(LeavePointStats(0, 0.0, 0.0), 1.7), -0.2)
        assertEquals(0.5, s.mean, 1e-12)
    }

    @Test fun `continues from values already stored`() {
        // A row the v1.1 build left: three leaves with mean 0.5 and M2 0.08.
        val s = LeavePointStats.add(LeavePointStats(3, 0.5, 0.08), 0.9)
        assertEquals(4, s.count)
        assertEquals(0.6, s.mean, 1e-12)
        assertEquals(0.08 + 0.4 * (0.9 - 0.6), s.m2, 1e-12)
    }
}
