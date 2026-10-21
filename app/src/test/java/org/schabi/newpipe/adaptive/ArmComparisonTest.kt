/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArmComparisonTest {
    private fun listens(n: Int, baseline: Boolean, meanReward: Double, start: Long, rng: Random) = (0 until n).map { i ->
        val reward = meanReward + rng.nextGaussian() * 0.5
        ArmComparison.Listen(start + i * 2L + if (baseline) 1L else 0L, baseline, reward, 0.7)
    }

    @Test
    fun withoutBaselinePicksThereIsNothingToCompare() {
        val r = ArmComparison.compute(listens(50, false, 0.3, 0L, Random(1)))
        assertNull(r.since)
        assertNull(r.baseline)
        assertEquals(ArmComparison.Verdict.NOT_ENOUGH_DATA, r.verdict)
    }

    @Test
    fun modelPicksBeforeTheFirstBaselinePickAreLeftOut() {
        val before = listens(100, false, 5.0, 0L, Random(1))
        val after = listens(40, false, 0.0, 1_001L, Random(2)) + listens(40, true, 0.0, 1_000L, Random(3))
        val r = ArmComparison.compute(before + after)
        assertEquals(40, r.model!!.listens)
        assertEquals(40, r.baseline!!.listens)
        assertEquals(ArmComparison.Verdict.NO_CLEAR_DIFFERENCE, r.verdict)
    }

    @Test
    fun aClearLiftIsReportedEitherWay() {
        val better = listens(200, false, 0.4, 0L, Random(4)) + listens(200, true, 0.0, 0L, Random(5))
        assertEquals(ArmComparison.Verdict.MODEL_BETTER, ArmComparison.compute(better).verdict)
        val worse = listens(200, false, -0.4, 0L, Random(6)) + listens(200, true, 0.0, 0L, Random(7))
        assertEquals(ArmComparison.Verdict.BASELINE_BETTER, ArmComparison.compute(worse).verdict)
    }

    @Test
    fun tooFewListensOnOneSideGivesNoVerdict() {
        val r = ArmComparison.compute(listens(200, false, 1.0, 0L, Random(8)) + listens(10, true, 0.0, 0L, Random(9)))
        assertNull(r.rewardLift)
        assertEquals(ArmComparison.Verdict.NOT_ENOUGH_DATA, r.verdict)
    }

    @Test
    fun sharesUseTheCompletionThresholds() {
        val ls = listOf(
            ArmComparison.Listen(0L, true, 1.0, 1.0),
            ArmComparison.Listen(1L, true, -1.0, 0.05),
            ArmComparison.Listen(2L, true, 0.0, 0.5),
            ArmComparison.Listen(3L, true, 0.0, 0.85)
        )
        val arm = ArmComparison.compute(ls).baseline!!
        assertEquals(0.5, arm.completedShare, 1e-9)
        assertEquals(0.25, arm.earlySkipShare, 1e-9)
        assertEquals(0.6, arm.meanCompletion, 1e-9)
    }
}
