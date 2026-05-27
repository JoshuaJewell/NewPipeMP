/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class LinUCBReplayTest {

    private val d = FeatureSchema.DIMENSION
    private val nEvents = 600
    private val splitFraction = 0.8
    private val noiseSigma = 0.2
    private val seed = 17L

    // Spec section 11, Test 2: train on the first 80% of a fixture event log, predict
    // on the held-out 20%, and beat both a uniform-zero baseline and a constant-mean
    // baseline on Spearman correlation between predicted and actual reward.
    @Test fun `predicts held out rewards better than uniform random or mean baseline`() {
        val rng = Random(seed)
        val thetaTrue = DoubleArray(d) { rng.nextGaussian() * 0.6 }

        val events = Array(nEvents) {
            val x = DoubleArray(d) { rng.nextDouble() * 2.0 - 1.0 }
            x[d - 1] = 1.0 // bias term
            val r = dot(thetaTrue, x) + rng.nextGaussian() * noiseSigma
            FixtureEvent(x, r)
        }
        val splitAt = (nEvents * splitFraction).toInt()
        val train = events.copyOfRange(0, splitAt)
        val heldOut = events.copyOfRange(splitAt, nEvents)

        val model = LinUCB(dimension = d)
        for (e in train) model.update(e.features, e.reward)

        val theta = model.theta()
        val predictedLinUcb = DoubleArray(heldOut.size) { dot(theta, heldOut[it].features) }
        val actuals = DoubleArray(heldOut.size) { heldOut[it].reward }
        val predictedRandom = DoubleArray(heldOut.size) { 0.0 }
        val trainMean = train.map { it.reward }.average()
        val predictedMean = DoubleArray(heldOut.size) { trainMean }

        val rhoLinUcb = spearman(predictedLinUcb, actuals)
        val rhoRandom = spearman(predictedRandom, actuals)
        val rhoMean = spearman(predictedMean, actuals)

        // Both baselines produce constant predictions and so have undefined Spearman; we
        // implement them as 0. LinUCB should pick up the linear signal and clear that bar
        // by a wide margin.
        assertTrue(
            "LinUCB Spearman ($rhoLinUcb) should beat uniform-random ($rhoRandom)",
            rhoLinUcb > rhoRandom + 0.2
        )
        assertTrue(
            "LinUCB Spearman ($rhoLinUcb) should beat mean baseline ($rhoMean)",
            rhoLinUcb > rhoMean + 0.2
        )
        // Additional smoke check: positive correlation should be substantial.
        assertTrue("LinUCB Spearman should be reasonably high; got $rhoLinUcb", rhoLinUcb > 0.5)
    }

    private data class FixtureEvent(val features: DoubleArray, val reward: Double)

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun spearman(a: DoubleArray, b: DoubleArray): Double {
        require(a.size == b.size && a.isNotEmpty())
        val ra = ranks(a)
        val rb = ranks(b)
        return pearson(ra, rb)
    }

    private fun ranks(xs: DoubleArray): DoubleArray {
        val n = xs.size
        val order = (0 until n).sortedBy { xs[it] }
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && xs[order[j + 1]] == xs[order[i]]) j += 1
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = avg
            i = j + 1
        }
        return out
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val ax = a[i] - ma
            val bx = b[i] - mb
            num += ax * bx
            da += ax * ax
            db += bx * bx
        }
        val denom = sqrt(da * db)
        return if (denom == 0.0) 0.0 else num / denom
    }
}
