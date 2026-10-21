/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt

// Linear contextual bandit with a per-track offset: reward = theta . x + u_track + noise
// (spec section 6.6). The shared weights carry what generalises across tracks; the offset
// carries "this particular track", which no feature can. Offsets have a zero-mean prior
// of precision trackLambda, so a track's own listens move it only as they accumulate, and
// a never-played track scores exactly as the shared model would.
//
// This is the hybrid model of Li et al. (2010) with a one-dimensional per-arm part,
// solved jointly rather than by alternating fits, so a skip explained by the context
// (a skipping mood, a late session) is not blamed on the track. The class keeps the raw
// weighted sums and derives the posterior from them on demand, which makes discounting a
// single scale factor and lets an observation touch only the shared part (synthetic
// priors and pairwise preferences have no track of their own).
class HybridLinUCB private constructor(
    val dimension: Int,
    private val ridgeLambda: Double,
    private val trackLambda: Double,
    private val discount: Double,
    // Data parts of the sufficient statistics, stored divided by scale.
    private val szz: DoubleArray,
    private val bz: DoubleArray,
    private val arms: HashMap<Long, Arm>,
    private var scale: Double,
    private var nUpdates: Long
) {
    constructor(
        dimension: Int,
        ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA,
        trackLambda: Double = AdaptiveShuffleConfig.TRACK_OFFSET_LAMBDA,
        discount: Double = 1.0
    ) : this(
        dimension, ridgeLambda, trackLambda, discount,
        DoubleArray(dimension * dimension), DoubleArray(dimension), HashMap(), 1.0, 0L
    )

    init {
        require(discount > 0.0 && discount <= 1.0) { "discount must be in (0, 1], was $discount" }
        require(trackLambda > 0.0 && ridgeLambda > 0.0) { "priors must be positive" }
    }

    // n: weighted listens, z: weighted sum of their feature vectors, r: weighted reward sum.
    private class Arm(var n: Double, val z: DoubleArray, var r: Double)

    private class Posterior(
        val aInverse: Matrix,
        val beta: DoubleArray,
        val factor: Matrix?
    )

    private var cached: Posterior? = null

    data class Score(val predictedReward: Double, val uncertainty: Double, val offset: Double) {
        val total: Double get() = predictedReward + uncertainty
    }

    data class TrackOffset(val trackId: Long, val offset: Double, val weightedListens: Double)

    // track null: the observation informs the shared weights only.
    fun update(x: DoubleArray, reward: Double, track: Long?, weight: Double = 1.0) {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        require(weight > 0.0) { "weight must be positive, was $weight" }
        if (discount < 1.0) {
            scale *= discount
            if (scale < RENORMALISE_BELOW) renormalise()
        }
        val w = weight / scale
        for (i in 0 until dimension) {
            val wi = w * x[i]
            val row = i * dimension
            for (j in 0 until dimension) szz[row + j] += wi * x[j]
            bz[i] += wi * reward
        }
        if (track != null) {
            val arm = arms.getOrPut(track) { Arm(0.0, DoubleArray(dimension), 0.0) }
            arm.n += w
            for (i in 0 until dimension) arm.z[i] += w * x[i]
            arm.r += w * reward
        }
        nUpdates += 1L
        cached = null
    }

    fun score(x: DoubleArray, track: Long?, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Score {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        val p = posterior(needFactor = false)
        val arm = track?.let { arms[it] }
        // Given the shared weights beta, the offset's mean is (r - z . beta) / a. The
        // prediction is then (x - z / a) . beta + r / a, whose variance is that vector's
        // quadratic form in A0^-1 plus the offset's own 1 / a.
        val a = trackLambda + (arm?.n ?: 0.0) * scale
        val w = DoubleArray(dimension) { i -> x[i] - (arm?.z?.get(i) ?: 0.0) * scale / a }
        val offset = offsetMean(arm, a, p.beta)
        val predicted = dot(x, p.beta) + offset
        val q = (p.aInverse.quadraticForm(w) + 1.0 / a).coerceAtLeast(0.0)
        return Score(predicted, alpha * sqrt(q), offset)
    }

    // One posterior draw for a decision: the shared weights once, then each candidate's
    // offset given them. Every candidate in a decision is scored against the same draw.
    inner class Draw internal constructor(private val beta: DoubleArray, private val rng: Random, private val alpha: Double) {
        fun value(x: DoubleArray, track: Long?): Double {
            val arm = track?.let { arms[it] }
            val a = trackLambda + (arm?.n ?: 0.0) * scale
            val offset = offsetMean(arm, a, beta) + alpha * rng.nextGaussian() / sqrt(a)
            return dot(x, beta) + offset
        }
    }

    fun sample(rng: Random, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Draw {
        val p = posterior(needFactor = true)
        val noise = p.factor!!.multiplyVector(DoubleArray(dimension) { rng.nextGaussian() })
        return Draw(DoubleArray(dimension) { i -> p.beta[i] + alpha * noise[i] }, rng, alpha)
    }

    fun theta(): DoubleArray = posterior(needFactor = false).beta.copyOf()

    fun numUpdates(): Long = nUpdates

    // Posterior mean offsets of the tracks with any evidence.
    fun offsets(): List<TrackOffset> {
        val beta = posterior(needFactor = false).beta
        return arms.map { (id, arm) ->
            val a = trackLambda + arm.n * scale
            TrackOffset(id, offsetMean(arm, a, beta), arm.n * scale)
        }
    }

    private fun offsetMean(arm: Arm?, a: Double, beta: DoubleArray): Double {
        if (arm == null) return 0.0
        var zb = 0.0
        for (i in 0 until dimension) zb += arm.z[i] * beta[i]
        return (arm.r - zb) * scale / a
    }

    // A0 = lambda I + Szz - sum_a z_a z_a^T / a_a, b0 = bz - sum_a z_a r_a / a_a: the
    // shared block of the joint precision with every offset integrated out.
    private fun posterior(needFactor: Boolean): Posterior {
        cached?.let { if (!needFactor || it.factor != null) return it }
        val d = dimension
        val a0 = DoubleArray(d * d)
        val b0 = DoubleArray(d)
        for (i in 0 until d * d) a0[i] = szz[i] * scale
        for (i in 0 until d) {
            a0[i * d + i] += ridgeLambda
            b0[i] = bz[i] * scale
        }
        for (arm in arms.values) {
            val a = trackLambda + arm.n * scale
            val s2 = scale * scale / a
            for (i in 0 until d) {
                val zi = arm.z[i] * s2
                if (zi == 0.0) continue
                val row = i * d
                for (j in 0 until d) a0[row + j] -= zi * arm.z[j]
                b0[i] -= zi * arm.r
            }
        }
        val inv = Matrix.of(d, d, a0).inverseSymmetricPositiveDefinite()
        val beta = inv.multiplyVector(b0)
        val factor = if (needFactor) inv.choleskyLower() else null
        return Posterior(inv, beta, factor).also { cached = it }
    }

    private fun renormalise() {
        for (i in szz.indices) szz[i] *= scale
        for (i in bz.indices) bz[i] *= scale
        for (arm in arms.values) {
            arm.n *= scale
            for (i in arm.z.indices) arm.z[i] *= scale
            arm.r *= scale
        }
        scale = 1.0
    }

    fun toByteArray(): ByteArray {
        val d = dimension
        val armBytes = Long.SIZE_BYTES + 2 * Double.SIZE_BYTES + d * Double.SIZE_BYTES
        val buf = ByteBuffer
            .allocate(
                Int.SIZE_BYTES * 3 + Long.SIZE_BYTES + Double.SIZE_BYTES +
                    (d * d + d) * Double.SIZE_BYTES + arms.size * armBytes
            )
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SERIAL_VERSION)
        buf.putInt(d)
        buf.putLong(nUpdates)
        buf.putDouble(scale)
        for (v in szz) buf.putDouble(v)
        for (v in bz) buf.putDouble(v)
        buf.putInt(arms.size)
        for ((id, arm) in arms) {
            buf.putLong(id)
            buf.putDouble(arm.n)
            buf.putDouble(arm.r)
            for (v in arm.z) buf.putDouble(v)
        }
        return buf.array()
    }

    companion object {
        private const val SERIAL_VERSION = 1
        private const val RENORMALISE_BELOW = 1e-100

        fun dot(u: DoubleArray, v: DoubleArray): Double {
            var s = 0.0
            for (i in u.indices) s += u[i] * v[i]
            return s
        }

        // Priors and discount are configuration, not state, so they are supplied on load.
        fun fromByteArray(
            bytes: ByteArray,
            ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA,
            trackLambda: Double = AdaptiveShuffleConfig.TRACK_OFFSET_LAMBDA,
            discount: Double = 1.0
        ): HybridLinUCB {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt()
            require(version == SERIAL_VERSION) { "unknown hybrid model version $version" }
            val d = buf.getInt()
            val nUpdates = buf.getLong()
            val scale = buf.getDouble()
            val szz = DoubleArray(d * d) { buf.getDouble() }
            val bz = DoubleArray(d) { buf.getDouble() }
            val count = buf.getInt()
            val arms = HashMap<Long, Arm>(count * 2)
            repeat(count) {
                val id = buf.getLong()
                val n = buf.getDouble()
                val r = buf.getDouble()
                arms[id] = Arm(n, DoubleArray(d) { buf.getDouble() }, r)
            }
            return HybridLinUCB(d, ridgeLambda, trackLambda, discount, szz, bz, arms, scale, nUpdates)
        }
    }
}
