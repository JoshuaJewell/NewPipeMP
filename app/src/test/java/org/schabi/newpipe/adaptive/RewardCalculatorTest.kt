/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardCalculatorTest {

    private fun r(ratio: Double, restarts: Int = 0) = RewardCalculator.fromRatio(ratio, restarts)

    // Pins from ADAPTIVE_SHUFFLE_SPEC.md section 6.4, before any rewind bonus.
    @Test fun `early skip at 8 percent is strongly negative`() = assertEquals(-1.6576, r(0.08), 0.01)

    @Test fun `abandoning at 45 percent is clearly negative`() {
        assertEquals(-0.299, r(0.45), 0.01)
        // The v1 step reward gave a flat -0.2 here, too weak to sink a boosted track.
        assertTrue(r(0.45) < -0.25)
    }

    @Test fun `85 percent is positive`() = assertEquals(0.413, r(0.85), 0.01)

    @Test fun `full listen is the maximum base reward`() = assertEquals(0.68, r(1.0), 0.01)

    @Test fun `neutral point sits past halfway`() {
        assertTrue(r(0.55) < 0.0)
        assertTrue(r(0.65) > 0.0)
    }

    @Test fun `reward is monotonic in completion ratio`() {
        var previous = Double.NEGATIVE_INFINITY
        for (i in 0..100) {
            val now = r(i / 100.0)
            assertTrue("non-decreasing at ${i / 100.0}", now >= previous)
            previous = now
        }
    }

    @Test fun `rewinds add half a point each, capped at two`() {
        assertEquals(r(1.0) + 0.5, r(1.0, 1), 1e-12)
        assertEquals(r(1.0) + 1.0, r(1.0, 7), 1e-12)
    }

    @Test fun `progress and duration path matches the ratio path`() {
        assertEquals(r(0.45, 1), RewardCalculator.computeReward(45_000L, 100_000L, 1), 1e-12)
    }

    @Test fun `zero or negative duration yields a neutral reward`() {
        // Live streams report duration zero. We refuse to label them and return neutral.
        assertEquals(0.0, RewardCalculator.computeReward(0L, 0L, 0), 0.0)
        assertEquals(0.0, RewardCalculator.computeReward(100L, -1L, 0), 0.0)
    }

    @Test fun `legacy step reward can be inverted to recover the restart delta`() {
        for (ratio in listOf(0.05, 0.5, 0.95)) {
            for (restarts in 0..2) {
                val legacy = RewardCalculator.legacyStepReward(ratio, restarts)
                assertEquals("ratio $ratio restarts $restarts", restarts, RewardCalculator.recoverRestartDelta(legacy, ratio))
            }
        }
    }
}
