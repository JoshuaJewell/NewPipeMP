/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class RepetitionMetricsTest {

    private val hour = 60L * 60L * 1000L

    @Test fun `shares are computed over the most recent window`() {
        val served = (0 until 10).map { i ->
            RepetitionMetrics.Served(chosenAt = i * 1000L, streamId = if (i % 2 == 0) 1L else i.toLong(), uploader = if (i < 6) "A" else "B")
        }
        val r = RepetitionMetrics.compute(served, window = 10)
        assertEquals(10, r.windowSize)
        assertEquals(5, r.uniqueTracks) // track 1 six times (every even i, and i = 1), then 3, 5, 7, 9
        assertEquals(1L, r.topTrackId)
        assertEquals(0.6, r.topTrackShare, 1e-12)
        assertEquals("A", r.topUploader)
        assertEquals(0.6, r.topUploaderShare, 1e-12)
    }

    @Test fun `sessions split on long gaps`() {
        // Three sessions two hours apart; track 1 appears in two of them.
        val served = listOf(
            RepetitionMetrics.Served(0, 1, "A"), RepetitionMetrics.Served(60_000, 2, "B"),
            RepetitionMetrics.Served(2 * hour, 3, "C"), RepetitionMetrics.Served(2 * hour + 60_000, 4, "D"),
            RepetitionMetrics.Served(4 * hour, 1, "A"), RepetitionMetrics.Served(4 * hour + 60_000, 5, "E")
        )
        val r = RepetitionMetrics.compute(served, sessionGapMillis = 30L * 60L * 1000L)
        assertEquals(3, r.sessionsConsidered)
        val track1 = r.recurringTracks.first { it.streamId == 1L }
        assertEquals(2.0 / 3.0, track1.sessionShare, 1e-12)
    }

    @Test fun `empty input gives an empty report`() {
        assertEquals(0, RepetitionMetrics.compute(emptyList()).windowSize)
    }
}
