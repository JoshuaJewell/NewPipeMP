/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class LinUCBSyntheticRegretTest {

    private val d = 6
    private val k = 10
    private val t = 5000
    private val noiseSigma = 0.1
    private val seed = 42L

    @Test fun `cumulative regret is sub-linear and clearly beats a uniform random baseline`() {
        val rng = Random(seed)
        val thetaTrue = DoubleArray(d) { rng.nextGaussian() }

        val model = LinUCB(dimension = d)
        val regretSeries = DoubleArray(t)
        var cumulativeLinUcb = 0.0
        var cumulativeRandom = 0.0

        repeat(t) { round ->
            // Draw K candidate feature vectors uniformly from [-1, 1]^d.
            val arms = Array(k) { DoubleArray(d) { rng.nextDouble() * 2.0 - 1.0 } }
            val trueRewards = DoubleArray(k) { arm -> dot(thetaTrue, arms[arm]) }
            val bestTrue = trueRewards.max()

            // LinUCB chooses argmax of predicted + uncertainty.
            var bestIdx = 0
            var bestScore = model.score(arms[0]).total
            for (i in 1 until k) {
                val s = model.score(arms[i]).total
                if (s > bestScore) {
                    bestScore = s
                    bestIdx = i
                }
            }
            val observedReward = trueRewards[bestIdx] + rng.nextGaussian() * noiseSigma
            model.update(arms[bestIdx], observedReward)

            val stepRegretLinUcb = bestTrue - trueRewards[bestIdx]
            regretSeries[round] = stepRegretLinUcb
            cumulativeLinUcb += stepRegretLinUcb

            // Independent uniform-random baseline. Does not learn.
            val randomIdx = rng.nextInt(k)
            cumulativeRandom += bestTrue - trueRewards[randomIdx]
        }

        val firstHalfMean = regretSeries.copyOfRange(0, t / 2).average()
        val secondHalfMean = regretSeries.copyOfRange(t / 2, t).average()
        assertTrue(
            "second-half average regret ($secondHalfMean) should be well below first-half ($firstHalfMean)",
            secondHalfMean < firstHalfMean * 0.5
        )
        assertTrue(
            "LinUCB cumulative regret ($cumulativeLinUcb) should be well below random ($cumulativeRandom)",
            cumulativeLinUcb < cumulativeRandom * 0.5
        )
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
