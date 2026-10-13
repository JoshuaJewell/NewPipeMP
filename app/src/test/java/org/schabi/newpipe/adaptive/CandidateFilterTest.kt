/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateFilterTest {

    private fun tail(n: Int, uploaderOf: (Int) -> String? = { "U$it" }) = (0 until n).map { CandidateFilter.TailEntry(it, "k$it", uploaderOf(it)) }

    @Test fun `recently played tracks are excluded`() {
        val r = CandidateFilter.filter(tail(50), listOf("k1", "k2", "k3"), emptyList(), recencyWindow = 40, minEligible = 20)
        assertEquals(47, r.eligible.size)
        assertTrue(r.eligible.none { it.key in setOf("k1", "k2", "k3") })
    }

    @Test fun `recent uploaders are spaced out`() {
        val t = tail(60) { if (it < 10) "Same" else "U$it" }
        val r = CandidateFilter.filter(t, emptyList(), listOf("Same", "Other"), minEligible = 20)
        assertEquals(50, r.eligible.size)
        assertTrue(r.eligible.none { it.uploader == "Same" })
    }

    @Test fun `spacing relaxes before the recency window shrinks`() {
        // 25 items, all by one uploader who just played; 3 recently played.
        val t = tail(25) { "Same" }
        val r = CandidateFilter.filter(t, listOf("k0", "k1", "k2"), listOf("Same"), minEligible = 20)
        assertEquals(0, r.uploaderSpacingUsed)
        assertEquals(3, r.recencyWindowUsed)
        assertEquals(22, r.eligible.size)
    }

    @Test fun `exclusion never empties a non-empty tail`() {
        val t = tail(5) { "Same" }
        val r = CandidateFilter.filter(t, (0 until 5).map { "k$it" }, listOf("Same"), minEligible = 20)
        assertEquals(5, r.eligible.size)
    }

    @Test fun `a short tail keeps what exclusions allow when that meets the tail size`() {
        // Tail of 8 with nothing recent: everything eligible, nothing relaxed.
        val r = CandidateFilter.filter(tail(8), listOf("other"), emptyList(), minEligible = 20)
        assertEquals(8, r.eligible.size)
    }

    @Test fun `sample is without replacement and capped`() {
        val items = (0 until 100).toList()
        val s = CandidateFilter.sample(items, 30, Random(1))
        assertEquals(30, s.size)
        assertEquals(30, s.toSet().size)
        assertEquals(10, CandidateFilter.sample(items.take(10), 30, Random(1)).size)
    }

    @Test fun `recency window scales with the tail and stays within bounds`() {
        assertEquals(AdaptiveShuffleConfig.RECENCY_EXCLUSION_N, CandidateFilter.recencyWindowFor(30))
        assertEquals(375, CandidateFilter.recencyWindowFor(1500))
        assertEquals(AdaptiveShuffleConfig.RECENCY_EXCLUSION_MAX, CandidateFilter.recencyWindowFor(5000))
    }
}
