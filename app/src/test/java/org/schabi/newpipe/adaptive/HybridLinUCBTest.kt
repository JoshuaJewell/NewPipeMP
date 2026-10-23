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

    private data class Obs(val x: DoubleArray, val reward: Double, val track: Long?, val weight: Double, val u: DoubleArray)

    private fun observations(n: Int, d: Int, tracks: Int, rng: Random): List<Obs> = (0 until n).map { i ->
        val x = DoubleArray(d) { rng.nextGaussian() }
        val track = if (i % 7 == 0) null else (rng.nextInt(tracks)).toLong()
        val weight = if (i % 5 == 0) 0.25 else 1.0
        Obs(x, rng.nextGaussian(), track, weight, doubleArrayOf(1.0, rng.nextGaussian()))
    }

    private val lambdas = doubleArrayOf(3.0, 5.0)

    // Ridge regression on [x, one-hot(track) * u0, one-hot(track) * u1] with priors lambda
    // (shared) and lambdas (each track's offset and slope); an observation of age t counts
    // gamma^t of its weight.
    private fun explicitRidge(obs: List<Obs>, d: Int, tracks: Int, lambda: Double, gamma: Double): Pair<Matrix, DoubleArray> {
        val m = d + 2 * tracks
        val a = DoubleArray(m * m)
        val b = DoubleArray(m)
        for (i in 0 until m) a[i * m + i] = if (i < d) lambda else lambdas[(i - d) % 2]
        for ((t, o) in obs.withIndex()) {
            val w = o.weight * gamma.pow(obs.size - 1 - t)
            val v = DoubleArray(m)
            for (i in 0 until d) v[i] = o.x[i]
            if (o.track != null) {
                v[d + 2 * o.track.toInt()] = o.u[0]
                v[d + 2 * o.track.toInt() + 1] = o.u[1]
            }
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
        val model = HybridLinUCB(d, ridgeLambda = 1.0, armLambda = lambdas, discount = gamma)
        for (o in obs) model.update(o.x, o.reward, o.track, o.u, o.weight)
        val (inv, solution) = explicitRidge(obs, d, tracks, 1.0, gamma)

        val theta = model.theta()
        for (i in 0 until d) assertEquals(solution[i], theta[i], 1e-9)
        val coefficients = model.offsets().associate { it.trackId to it.coefficients }
        for (t in 0 until tracks) {
            val c = coefficients[t.toLong()] ?: doubleArrayOf(0.0, 0.0)
            assertEquals(solution[d + 2 * t], c[0], 1e-9)
            assertEquals(solution[d + 2 * t + 1], c[1], 1e-9)
        }

        val probe = DoubleArray(d) { 0.3 * (it + 1) }
        val u = doubleArrayOf(1.0, 0.7)
        for (t in listOf<Long?>(null, 2L)) {
            val s = model.score(probe, t, u, alpha = 1.0)
            val v = DoubleArray(d + 2 * tracks)
            for (i in 0 until d) v[i] = probe[i]
            var mean = (0 until d).sumOf { probe[it] * solution[it] }
            if (t != null) {
                v[d + 2 * t.toInt()] = u[0]
                v[d + 2 * t.toInt() + 1] = u[1]
                mean += solution[d + 2 * t.toInt()] * u[0] + solution[d + 2 * t.toInt() + 1] * u[1]
            }
            assertEquals(mean, s.predictedReward, 1e-9)
            // An unseen track adds its prior variance; a seen one its posterior variance.
            val extra = if (t == null) u[0] * u[0] / lambdas[0] + u[1] * u[1] / lambdas[1] else 0.0
            assertEquals(sqrt(inv.quadraticForm(v) + extra), s.uncertainty, 1e-9)
        }
    }

    @Test fun `matches ridge regression on one-hot track columns`() = checkAgainstRidge(1.0)

    @Test fun `discounting matches ridge with geometrically aged weights`() = checkAgainstRidge(0.97)

    @Test fun `an offset learns what the features cannot tell apart`() {
        val model = HybridLinUCB(2, armLambda = doubleArrayOf(4.0, 4.0))
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
        for (o in observations(40, 3, 5, Random(2))) model.update(o.x, o.reward, o.track, o.u, o.weight)
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
        for (o in observations(50, 2, 3, Random(5))) model.update(o.x, o.reward, o.track, o.u, o.weight)
        val probe = doubleArrayOf(0.4, -0.2)
        val rng = Random(9)
        val draws = (1..4000).map { model.sample(rng).value(probe, 1L) }
        val mean = draws.average()
        val sd = sqrt(draws.sumOf { (it - mean) * (it - mean) } / draws.size)
        val s = model.score(probe, 1L)
        assertTrue(abs(mean - s.predictedReward) < 0.05)
        assertEquals(s.uncertainty, sd, 0.05)
    }

    // A track that is liked after a long rest and skipped when heard again soon, against one
    // liked either way: the first learns a positive recovery slope, the second none.
    @Test fun `a track that needs rest learns its own recovery slope`() {
        val model = HybridLinUCB(2, armLambda = doubleArrayOf(4.0, 4.0))
        val x = doubleArrayOf(1.0, 0.0)
        val rng = Random(4)
        repeat(60) {
            val rest = rng.nextGaussian()
            model.update(x, if (rest > 0) 0.7 else -0.9, 1L, doubleArrayOf(1.0, rest))
            model.update(x, 0.6, 2L, doubleArrayOf(1.0, rng.nextGaussian()))
        }
        val needsRest = model.coefficients(1L)
        val anyTime = model.coefficients(2L)
        assertTrue("slope ${needsRest[1]}", needsRest[1] > 0.4)
        assertTrue("slope ${anyTime[1]}", abs(anyTime[1]) < 0.15)
        assertTrue(model.score(x, 1L, doubleArrayOf(1.0, 1.5)).predictedReward > model.score(x, 1L, doubleArrayOf(1.0, -1.5)).predictedReward)
    }
}
