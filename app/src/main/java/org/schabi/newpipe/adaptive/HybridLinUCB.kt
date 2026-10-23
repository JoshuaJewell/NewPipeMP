/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt

// Linear contextual bandit with a small per-track part: reward = theta . x + phi_t . u + noise
// (spec section 6.6). The shared weights theta carry what generalises across tracks. Each
// track t has its own coefficients phi_t on a few track features u: an offset (u = 1,
// "this particular track") and how much it recovers with time since it last played (u =
// the scaled days since played), since some tracks bear hearing daily and others wear
// out. Each coefficient has a zero-mean prior of its own precision (armLambda), so a
// track's own listens move it only as they accumulate, and a never-played track scores as
// the shared model would.
//
// This is the hybrid model of Li et al. (2010), solved jointly rather than by alternating
// fits, so a skip explained by the context (a skipping mood, a late session) is not
// blamed on the track. The class keeps the raw weighted sums and derives the posterior
// from them on demand, which makes discounting a single scale factor and lets an
// observation touch only the shared part (synthetic priors and pairwise preferences have
// no track of their own).
class HybridLinUCB private constructor(
    val dimension: Int,
    private val ridgeLambda: Double,
    private val armLambda: DoubleArray,
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
        armLambda: DoubleArray = DEFAULT_ARM_LAMBDA,
        discount: Double = 1.0
    ) : this(
        dimension, ridgeLambda, armLambda.copyOf(), discount,
        DoubleArray(dimension * dimension), DoubleArray(dimension), HashMap(), 1.0, 0L
    )

    init {
        require(discount > 0.0 && discount <= 1.0) { "discount must be in (0, 1], was $discount" }
        require(ridgeLambda > 0.0 && armLambda.isNotEmpty() && armLambda.all { it > 0.0 }) { "priors must be positive" }
    }

    // Number of per-track features (u).
    val armDimension: Int get() = armLambda.size

    // suu: weighted sum of u u^T; zu: of u x^T (m by d); ru: of reward u.
    private class Arm(val suu: DoubleArray, val zu: DoubleArray, val ru: DoubleArray)

    private class Posterior(
        val aInverse: Matrix,
        val beta: DoubleArray,
        val factor: Matrix?
    )

    private var cached: Posterior? = null

    // offset: the track's own part of the prediction, phi_t . u.
    data class Score(val predictedReward: Double, val uncertainty: Double, val offset: Double) {
        val total: Double get() = predictedReward + uncertainty
    }

    // coefficients: phi_t, one per per-track feature; weightedListens: the weighted count.
    data class TrackOffset(val trackId: Long, val coefficients: DoubleArray, val weightedListens: Double) {
        val offset: Double get() = coefficients[0]
    }

    // track null: the observation informs the shared weights only, and u is ignored.
    fun update(x: DoubleArray, reward: Double, track: Long?, u: DoubleArray? = null, weight: Double = 1.0) {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        require(weight > 0.0) { "weight must be positive, was $weight" }
        if (discount < 1.0) {
            scale *= discount
            if (scale < RENORMALISE_BELOW) renormalise()
        }
        val w = weight / scale
        val d = dimension
        for (i in 0 until d) {
            val wi = w * x[i]
            val row = i * d
            for (j in 0 until d) szz[row + j] += wi * x[j]
            bz[i] += wi * reward
        }
        if (track != null) {
            val uu = requireArmFeatures(u)
            val m = armDimension
            val arm = arms.getOrPut(track) { Arm(DoubleArray(m * m), DoubleArray(m * d), DoubleArray(m)) }
            for (p in 0 until m) {
                val wp = w * uu[p]
                for (q in 0 until m) arm.suu[p * m + q] += wp * uu[q]
                for (j in 0 until d) arm.zu[p * d + j] += wp * x[j]
                arm.ru[p] += wp * reward
            }
        }
        nUpdates += 1L
        cached = null
    }

    fun score(x: DoubleArray, track: Long?, u: DoubleArray? = null, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Score {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        val p = posterior(needFactor = false)
        val uu = requireArmFeatures(u)
        val part = armPart(track?.let { arms[it] })
        // Given the shared weights beta, the track's coefficients have mean
        // A^-1 (ru - zu beta). The prediction is then (x - zu^T A^-1 u) . beta + u . A^-1 ru,
        // whose variance is that vector's quadratic form in A0^-1 plus u^T A^-1 u.
        val au = part.aInverse.multiplyVector(uu)
        val w = DoubleArray(dimension) { j -> x[j] - part.zuT(j, au) }
        val phi = part.mean(p.beta)
        val offset = dot(phi, uu)
        val predicted = dot(x, p.beta) + offset
        val q = (p.aInverse.quadraticForm(w) + dot(uu, au)).coerceAtLeast(0.0)
        return Score(predicted, alpha * sqrt(q), offset)
    }

    // The posterior mean of a track's own coefficients; zeros for a track never heard.
    fun coefficients(track: Long?): DoubleArray = armPart(track?.let { arms[it] }).mean(posterior(needFactor = false).beta)

    // One posterior draw for a decision: the shared weights once, then each candidate's
    // coefficients given them. Every candidate in a decision is scored against the same draw.
    inner class Draw internal constructor(private val beta: DoubleArray, private val rng: Random, private val alpha: Double) {
        fun value(x: DoubleArray, track: Long?, u: DoubleArray? = null): Double {
            val uu = requireArmFeatures(u)
            val part = armPart(track?.let { arms[it] })
            val mean = part.mean(beta)
            val factor = part.aInverse.choleskyLower()
            val noise = factor.multiplyVector(DoubleArray(armDimension) { rng.nextGaussian() })
            val phi = DoubleArray(armDimension) { i -> mean[i] + alpha * noise[i] }
            return dot(x, beta) + dot(phi, uu)
        }
    }

    fun sample(rng: Random, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Draw {
        val p = posterior(needFactor = true)
        val noise = p.factor!!.multiplyVector(DoubleArray(dimension) { rng.nextGaussian() })
        return Draw(DoubleArray(dimension) { i -> p.beta[i] + alpha * noise[i] }, rng, alpha)
    }

    fun theta(): DoubleArray = posterior(needFactor = false).beta.copyOf()

    fun numUpdates(): Long = nUpdates

    // Posterior mean coefficients of the tracks with any evidence.
    fun offsets(): List<TrackOffset> {
        val beta = posterior(needFactor = false).beta
        return arms.map { (id, arm) -> TrackOffset(id, armPart(arm).mean(beta), arm.suu[0] * scale) }
    }

    // A track's part of the joint precision, A = diag(armLambda) + Suu, with the sums it
    // needs, all at their true (unscaled) size.
    private inner class ArmPart(val aInverse: Matrix, private val zu: DoubleArray?, private val ru: DoubleArray?) {
        // (zu^T v)_j
        fun zuT(j: Int, v: DoubleArray): Double {
            val z = zu ?: return 0.0
            var s = 0.0
            for (p in v.indices) s += z[p * dimension + j] * v[p]
            return s
        }

        // A^-1 (ru - zu beta)
        fun mean(beta: DoubleArray): DoubleArray {
            val r = ru ?: return DoubleArray(armDimension)
            val z = zu!!
            val m = armDimension
            val rhs = DoubleArray(m) { p ->
                var zb = 0.0
                for (j in 0 until dimension) zb += z[p * dimension + j] * beta[j]
                r[p] - zb
            }
            return aInverse.multiplyVector(rhs)
        }
    }

    private fun armPart(arm: Arm?): ArmPart {
        val m = armDimension
        val a = DoubleArray(m * m)
        for (p in 0 until m) a[p * m + p] = armLambda[p]
        if (arm == null) return ArmPart(Matrix.of(m, m, a).inverseSymmetricPositiveDefinite(), null, null)
        for (i in a.indices) a[i] += arm.suu[i] * scale
        val zu = DoubleArray(arm.zu.size) { arm.zu[it] * scale }
        val ru = DoubleArray(m) { arm.ru[it] * scale }
        return ArmPart(Matrix.of(m, m, a).inverseSymmetricPositiveDefinite(), zu, ru)
    }

    private fun requireArmFeatures(u: DoubleArray?): DoubleArray {
        val uu = u ?: DoubleArray(armDimension).also { it[0] = 1.0 }
        require(uu.size == armDimension) { "per-track feature length ${uu.size} != $armDimension" }
        return uu
    }

    // A0 = lambda I + Szz - sum_t zu_t^T A_t^-1 zu_t, b0 = bz - sum_t zu_t^T A_t^-1 ru_t: the
    // shared block of the joint precision with every track's coefficients integrated out.
    private fun posterior(needFactor: Boolean): Posterior {
        cached?.let { if (!needFactor || it.factor != null) return it }
        val d = dimension
        val m = armDimension
        val a0 = DoubleArray(d * d)
        val b0 = DoubleArray(d)
        for (i in 0 until d * d) a0[i] = szz[i] * scale
        for (i in 0 until d) {
            a0[i * d + i] += ridgeLambda
            b0[i] = bz[i] * scale
        }
        for (arm in arms.values) {
            val a = DoubleArray(m * m)
            for (i in a.indices) a[i] = arm.suu[i] * scale
            for (p in 0 until m) a[p * m + p] += armLambda[p]
            val inv = Matrix.of(m, m, a).inverseSymmetricPositiveDefinite()
            // g = A^-1 zu (m by d), then A0 -= zu^T g and b0 -= zu^T A^-1 ru.
            val g = DoubleArray(m * d)
            for (p in 0 until m) {
                for (j in 0 until d) {
                    var s = 0.0
                    for (q in 0 until m) s += inv[p, q] * arm.zu[q * d + j]
                    g[p * d + j] = s * scale
                }
            }
            val ar = DoubleArray(m) { p -> (0 until m).sumOf { q -> inv[p, q] * arm.ru[q] } * scale }
            for (i in 0 until d) {
                for (p in 0 until m) {
                    val zi = arm.zu[p * d + i] * scale
                    if (zi == 0.0) continue
                    val row = i * d
                    for (j in 0 until d) a0[row + j] -= zi * g[p * d + j]
                    b0[i] -= zi * ar[p]
                }
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
            for (i in arm.suu.indices) arm.suu[i] *= scale
            for (i in arm.zu.indices) arm.zu[i] *= scale
            for (i in arm.ru.indices) arm.ru[i] *= scale
        }
        scale = 1.0
    }

    fun toByteArray(): ByteArray {
        val d = dimension
        val m = armDimension
        val armBytes = Long.SIZE_BYTES + (m * m + m * d + m) * Double.SIZE_BYTES
        val buf = ByteBuffer
            .allocate(
                Int.SIZE_BYTES * 4 + Long.SIZE_BYTES + Double.SIZE_BYTES +
                    (d * d + d) * Double.SIZE_BYTES + arms.size * armBytes
            )
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SERIAL_VERSION)
        buf.putInt(d)
        buf.putInt(m)
        buf.putLong(nUpdates)
        buf.putDouble(scale)
        for (v in szz) buf.putDouble(v)
        for (v in bz) buf.putDouble(v)
        buf.putInt(arms.size)
        for ((id, arm) in arms) {
            buf.putLong(id)
            for (v in arm.suu) buf.putDouble(v)
            for (v in arm.zu) buf.putDouble(v)
            for (v in arm.ru) buf.putDouble(v)
        }
        return buf.array()
    }

    companion object {
        private const val SERIAL_VERSION = 2
        private const val RENORMALISE_BELOW = 1e-100

        // Offset, then recovery slope (spec section 6.6).
        val DEFAULT_ARM_LAMBDA: DoubleArray
            get() = doubleArrayOf(AdaptiveShuffleConfig.TRACK_OFFSET_LAMBDA, AdaptiveShuffleConfig.TRACK_RECOVERY_LAMBDA)

        fun dot(u: DoubleArray, v: DoubleArray): Double {
            var s = 0.0
            for (i in u.indices) s += u[i] * v[i]
            return s
        }

        // Priors and discount are configuration, not state, so they are supplied on load.
        fun fromByteArray(
            bytes: ByteArray,
            ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA,
            armLambda: DoubleArray = DEFAULT_ARM_LAMBDA,
            discount: Double = 1.0
        ): HybridLinUCB {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt()
            require(version == SERIAL_VERSION) { "unknown hybrid model version $version" }
            val d = buf.getInt()
            val m = buf.getInt()
            require(m == armLambda.size) { "stored model has $m per-track features, expected ${armLambda.size}" }
            val nUpdates = buf.getLong()
            val scale = buf.getDouble()
            val szz = DoubleArray(d * d) { buf.getDouble() }
            val bz = DoubleArray(d) { buf.getDouble() }
            val count = buf.getInt()
            val arms = HashMap<Long, Arm>(count * 2)
            repeat(count) {
                val id = buf.getLong()
                arms[id] = Arm(DoubleArray(m * m) { buf.getDouble() }, DoubleArray(m * d) { buf.getDouble() }, DoubleArray(m) { buf.getDouble() })
            }
            return HybridLinUCB(d, ridgeLambda, armLambda.copyOf(), discount, szz, bz, arms, scale, nUpdates)
        }
    }
}
