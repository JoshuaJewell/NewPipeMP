/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploaderStatsCacheTest {

    private class Source {
        val calls = ArrayList<List<String>>()
        fun fetch(names: List<String>): Map<String, UploaderStats> {
            calls += names
            return names.filter { it != "Nobody" }.associateWith { UploaderStats.of(4.0, 3, 1) }
        }
    }

    @Test fun `a pick's uploaders are fetched in one batch, each once`() {
        val src = Source()
        val cache = UploaderStatsCache(src::fetch, clock = { 0L })
        val got = cache.get(listOf("A", "B", "A", "C"))
        assertEquals(listOf(listOf("A", "B", "C")), src.calls)
        assertEquals(setOf("A", "B", "C"), got.keys)
        assertEquals(0.75, got.getValue("A").completionRate!!, 1e-12)
    }

    @Test fun `cached uploaders are not fetched again until they expire`() {
        val src = Source()
        var now = 0L
        val cache = UploaderStatsCache(src::fetch, ttlMillis = 1_000L, clock = { now })
        cache.get(listOf("A", "B"))
        now = 500L
        cache.get(listOf("A", "B", "C"))
        assertEquals(listOf(listOf("A", "B"), listOf("C")), src.calls)
        now = 1_200L
        cache.get(listOf("A", "C"))
        // A is stale (fetched at 0); C is not (fetched at 500).
        assertEquals(listOf("A"), src.calls.last())
    }

    @Test fun `uploaders with no rows are cached as unknown and blank names are ignored`() {
        val src = Source()
        val cache = UploaderStatsCache(src::fetch, clock = { 0L })
        val got = cache.get(listOf("Nobody", "", "  "))
        assertEquals(UploaderStats.UNKNOWN, got.getValue("Nobody"))
        assertEquals(setOf("Nobody"), got.keys)
        cache.get(listOf("Nobody"))
        assertEquals(1, src.calls.size)
        assertEquals(UploaderStats.UNKNOWN, cache.get(null as String?))
    }

    @Test fun `rates are null without any finished listens`() {
        val stats = UploaderStats.of(3.5, 0, 0)
        assertNull(stats.completionRate)
        assertNull(stats.skipRate)
        assertEquals(3.5, stats.avgRating!!, 0.0)
    }
}
