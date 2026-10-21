/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridLinUCBTest {

    private data class Obs(val x: DoubleArray, val reward: Double, val track: Long?, val weight: Double)

    private fun observations(n: Int, d: Int, tracks: Int, rng: Random): List<Obs> = (0 until n).map { i ->
        val x = DoubleArray(d) { rng.nextGaussian() }
        val track = if (i % 7 == 0) null else (rng.nextInt(tracks)).toLong()
        val weight = if (i % 5 == 0) 0.25 else 1.0
        Obs(x, rng.nextGaussian(), track, weight)
    }

    // Ridge regression on [x, one-hot(track)] with priors lambda (shared) and trackLambda
    // (offsets); an observation of age t counts gamma^t of its weight.
    private fun explicitRidge(obs: List<Obs>, d: Int, tracks: Int, lambda: Double, trackLambda: Double, gamma: Double): Pair<Matrix, DoubleArray> {
        val m = d + tracks
        val a = DoubleArray(m * m)
        val b = DoubleArray(m)
        for (i in 0 until m) a[i * m + i] = if (i < d) lambda else trackLambda
        for ((t, o) in obs.withIndex()) {
            val w = o.weight * gamma.pow(obs.size - 1 - t)
            val v = DoubleArray(m)
            for (i in 0 until d) v[i] = o.x[i]
            if (o.track != null) v[d + o.track.toInt()] = 1.0
            for (i in 0 until m) {
                for (j in 0 until m) a[i * m + j] += w * v[i] * v[j]
                b[i] += w * o.reward * v[i]
            }
        }
        val inv = Matrix.of(m, m, a).inverseSymmetricPositiveDefinite()
        return inv to inv.multiplyVector(b)
    }

    private fun checkAgainstRidge(gamma: Double) {
        val d = 4
        val tracks = 6
        val obs = observations(60, d, tracks, Random(11))
        val model = HybridLinUCB(d, ridgeLambda = 1.0, trackLambda = 3.0, discount = gamma)
        for (o in obs) model.update(o.x, o.reward, o.track, o.weight)
        val (inv, solution) = explicitRidge(obs, d, tracks, 1.0, 3.0, gamma)

        val theta = model.theta()
        for (i in 0 until d) assertEquals(solution[i], theta[i], 1e-9)
        val offsets = model.offsets().associate { it.trackId to it.offset }
        for (t in 0 until tracks) assertEquals(solution[d + t], offsets[t.toLong()] ?: 0.0, 1e-9)

        val probe = DoubleArray(d) { 0.3 * (it + 1) }
        for (t in listOf<Long?>(null, 2L)) {
            val s = model.score(probe, t, alpha = 1.0)
            val v = DoubleArray(d + tracks)
            for (i in 0 until d) v[i] = probe[i]
            var mean = (0 until d).sumOf { probe[it] * solution[it] }
            if (t != null) {
                v[d + t.toInt()] = 1.0
                mean += solution[d + t.toInt()]
            }
            assertEquals(mean, s.predictedReward, 1e-9)
            // An unseen track adds its prior variance; a seen one its posterior variance.
            val extra = if (t == null) 1.0 / 3.0 else 0.0
            assertEquals(sqrt(inv.quadraticForm(v) + extra), s.uncertainty, 1e-9)
        }
    }

    @Test fun `matches ridge regression on one-hot track columns`() = checkAgainstRidge(1.0)

    @Test fun `discounting matches ridge with geometrically aged weights`() = checkAgainstRidge(0.97)

    @Test fun `an offset learns what the features cannot tell apart`() {
        val model = HybridLinUCB(2, trackLambda = 4.0)
        val x = doubleArrayOf(1.0, 0.5)
        repeat(30) {
            model.update(x, 0.7, 1L)
            model.update(x, -0.9, 2L)
        }
        val liked = model.score(x, 1L)
        val disliked = model.score(x, 2L)
        val unseen = model.score(x, 3L)
        assertTrue(liked.offset > 0.5 && disliked.offset < -0.5)
        assertEquals(0.0, unseen.offset, 0.0)
        // The shared part holds the average; the unseen track is predicted at it.
        assertEquals(-0.1, unseen.predictedReward, 0.05)
        assertTrue(unseen.uncertainty > liked.uncertainty)
    }

    @Test fun `shared-only observations leave offsets alone`() {
        val model = HybridLinUCB(2)
        repeat(10) { model.update(doubleArrayOf(1.0, it.toDouble()), 0.5, null) }
        assertTrue(model.offsets().isEmpty())
    }

    @Test fun `round trips through bytes`() {
        val model = HybridLinUCB(3, discount = 0.99)
        for (o in observations(40, 3, 5, Random(2))) model.update(o.x, o.reward, o.track, o.weight)
        val back = HybridLinUCB.fromByteArray(model.toByteArray(), discount = 0.99)
        val probe = doubleArrayOf(0.2, -1.0, 0.5)
        val a = model.score(probe, 1L)
        val b = back.score(probe, 1L)
        assertEquals(a.predictedReward, b.predictedReward, 1e-12)
        assertEquals(a.uncertainty, b.uncertainty, 1e-12)
        assertEquals(model.numUpdates(), back.numUpdates())
    }

    @Test fun `thompson draws centre on the posterior mean`() {
        val model = HybridLinUCB(2)
        for (o in observations(50, 2, 3, Random(5))) model.update(o.x, o.reward, o.track, o.weight)
        val probe = doubleArrayOf(0.4, -0.2)
        val rng = Random(9)
        val draws = (1..4000).map { model.sample(rng).value(probe, 1L) }
        val mean = draws.average()
        val sd = sqrt(draws.sumOf { (it - mean) * (it - mean) } / draws.size)
        val s = model.score(probe, 1L)
        assertTrue(abs(mean - s.predictedReward) < 0.05)
        assertEquals(s.uncertainty, sd, 0.05)
    }
}
