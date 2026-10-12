/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
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

    // Spec section 7.3. Two features that always sum to one, plus the bias, make the
    // individual weights unidentifiable: the data only pins down w1 - w2 and w2 + bias,
    // and ridge settles on the minimum-norm split. The model's job is to predict, and
    // its predictions must stay right; what the weights must not be is read one by one.
    @Test fun `collinear features keep predictions correct though weights are unidentifiable`() {
        val rng = Random(seed)
        // Layout: [f, 1 - f, z1, z2, bias]. The truth is deliberately far from the
        // minimum-norm split, so reading the fitted weights individually would mislead.
        val dim = 5
        val thetaTrue = doubleArrayOf(1.5, 0.5, 0.8, -0.6, -0.5)
        fun arm(): DoubleArray {
            val f = rng.nextDouble()
            return doubleArrayOf(f, 1.0 - f, rng.nextDouble() * 2.0 - 1.0, rng.nextDouble() * 2.0 - 1.0, 1.0)
        }

        val model = LinUCB(dimension = dim)
        val regretSeries = DoubleArray(t)
        repeat(t) { round ->
            val arms = Array(k) { arm() }
            val trueRewards = DoubleArray(k) { dot(thetaTrue, arms[it]) }
            val chosen = (0 until k).maxByOrNull { model.score(arms[it]).total }!!
            model.update(arms[chosen], trueRewards[chosen] + rng.nextGaussian() * noiseSigma)
            regretSeries[round] = trueRewards.max() - trueRewards[chosen]
        }

        // Learning is unaffected by the dependency.
        val firstHalfMean = regretSeries.copyOfRange(0, t / 2).average()
        val secondHalfMean = regretSeries.copyOfRange(t / 2, t).average()
        assertTrue(
            "second-half average regret ($secondHalfMean) should be well below first-half ($firstHalfMean)",
            secondHalfMean < firstHalfMean * 0.5
        )

        // Predictions on fresh arms match the truth.
        val heldOut = Array(500) { arm() }
        var squaredError = 0.0
        for (x in heldOut) {
            val e = model.score(x).predictedReward - dot(thetaTrue, x)
            squaredError += e * e
        }
        val rmse = sqrt(squaredError / heldOut.size)
        assertTrue("held-out prediction RMSE ($rmse) should be small", rmse < 0.05)

        // The identifiable combinations are recovered...
        val theta = model.theta()
        val slope = theta[0] - theta[1]
        val offset = theta[1] + theta[4]
        assertTrue("w1 - w2 = $slope, expected ${thetaTrue[0] - thetaTrue[1]}", abs(slope - 1.0) < 0.05)
        assertTrue("w2 + bias = $offset, expected ${thetaTrue[1] + thetaTrue[4]}", abs(offset - 0.0) < 0.05)
        // ...but the individual weights are not, which is why the debug view must not
        // present the members of a collinear group as separate findings.
        assertTrue(
            "w1 = ${theta[0]} should differ from the true ${thetaTrue[0]}: only combinations are identifiable",
            abs(theta[0] - thetaTrue[0]) > 0.5
        )
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
