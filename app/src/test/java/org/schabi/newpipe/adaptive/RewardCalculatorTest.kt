/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardCalculatorTest {

    @Test fun `early skip below 0_15 yields strongly negative reward`() {
        val r = RewardCalculator.computeReward(progressMillis = 5_000L, durationMillis = 100_000L, restartCountDelta = 0)
        assertEquals(-1.5, r, 1e-12)
    }

    @Test fun `mid skip in the middle band yields mildly negative reward`() {
        val r = RewardCalculator.computeReward(progressMillis = 50_000L, durationMillis = 100_000L, restartCountDelta = 0)
        assertEquals(-0.2, r, 1e-12)
    }

    @Test fun `completion at or above 0_85 yields positive reward`() {
        val r = RewardCalculator.computeReward(progressMillis = 90_000L, durationMillis = 100_000L, restartCountDelta = 0)
        assertEquals(1.0, r, 1e-12)
    }

    @Test fun `completion plus one rewind adds half a point`() {
        val r = RewardCalculator.computeReward(progressMillis = 95_000L, durationMillis = 100_000L, restartCountDelta = 1)
        assertEquals(1.5, r, 1e-12)
    }

    @Test fun `rewind density is capped at two`() {
        val r = RewardCalculator.computeReward(progressMillis = 95_000L, durationMillis = 100_000L, restartCountDelta = 7)
        assertEquals(2.0, r, 1e-12)
    }

    @Test fun `threshold at 0_15 is a mid skip not an early skip`() {
        val r = RewardCalculator.computeReward(progressMillis = 15_000L, durationMillis = 100_000L, restartCountDelta = 0)
        assertEquals(-0.2, r, 1e-12)
    }

    @Test fun `threshold at 0_85 counts as completion`() {
        val r = RewardCalculator.computeReward(progressMillis = 85_000L, durationMillis = 100_000L, restartCountDelta = 0)
        assertEquals(1.0, r, 1e-12)
    }

    @Test fun `zero or negative duration yields a neutral reward`() {
        // Live streams report duration zero. We refuse to label them and return neutral.
        assertEquals(0.0, RewardCalculator.computeReward(0L, 0L, 0), 0.0)
        assertEquals(0.0, RewardCalculator.computeReward(100L, -1L, 0), 0.0)
    }
}
