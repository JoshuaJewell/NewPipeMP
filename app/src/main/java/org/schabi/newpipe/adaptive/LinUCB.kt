/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt

// Linear contextual bandit. The name predates Thompson sampling; the same posterior
// (A, b) serves both decision rules, so one class holds both.
class LinUCB private constructor(
    val dimension: Int,
    private val a: Matrix,
    private val b: DoubleArray,
    private var nUpdates: Long,
    private val ridgeLambda: Double,
    private val discount: Double
) {
    constructor(
        dimension: Int,
        ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA,
        discount: Double = 1.0
    ) : this(
        dimension = dimension,
        a = Matrix.scaledIdentity(dimension, ridgeLambda),
        b = DoubleArray(dimension),
        nUpdates = 0L,
        ridgeLambda = ridgeLambda,
        discount = discount
    )

    init {
        require(discount > 0.0 && discount <= 1.0) { "discount must be in (0, 1], was $discount" }
    }

    private var cachedAInverse: Matrix? = null
    private var cachedTheta: DoubleArray? = null
    private var cachedPosteriorFactor: Matrix? = null

    data class Score(val predictedReward: Double, val uncertainty: Double) {
        val total: Double get() = predictedReward + uncertainty
    }

    // weight scales one observation's evidence; 1.0 is a real decision, smaller values
    // are used for synthetic priors. discount (set at construction) shrinks all earlier
    // evidence before each new observation is added.
    fun update(x: DoubleArray, reward: Double, weight: Double = 1.0) {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        require(weight > 0.0) { "weight must be positive, was $weight" }
        if (discount < 1.0) {
            a.discountTowardsScaledIdentity(discount, ridgeLambda)
            for (i in 0 until dimension) b[i] *= discount
        }
        a.addOuterProduct(x, weight)
        for (i in 0 until dimension) b[i] += weight * reward * x[i]
        nUpdates += 1L
        cachedAInverse = null
        cachedTheta = null
        cachedPosteriorFactor = null
    }

    fun score(x: DoubleArray, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Score {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        val inv = ensureInverse()
        val theta = ensureTheta(inv)
        val predicted = dot(theta, x)
        val q = inv.quadraticForm(x).coerceAtLeast(0.0)
        val uncertainty = alpha * sqrt(q)
        return Score(predicted, uncertainty)
    }

    // One posterior draw, theta ~ N(theta_hat, alpha^2 A^-1). Thompson sampling scores
    // every candidate in a decision against the same draw.
    fun sampleTheta(rng: Random, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): DoubleArray {
        val inv = ensureInverse()
        val mean = ensureTheta(inv)
        val factor = ensurePosteriorFactor(inv)
        val z = DoubleArray(dimension) { rng.nextGaussian() }
        val noise = factor.multiplyVector(z)
        return DoubleArray(dimension) { i -> mean[i] + alpha * noise[i] }
    }

    fun theta(): DoubleArray = ensureTheta(ensureInverse()).copyOf()

    fun numUpdates(): Long = nUpdates

    private fun ensureInverse(): Matrix {
        val cached = cachedAInverse
        if (cached != null) return cached
        val computed = a.inverseSymmetricPositiveDefinite()
        cachedAInverse = computed
        return computed
    }

    private fun ensureTheta(inv: Matrix): DoubleArray {
        val cached = cachedTheta
        if (cached != null) return cached
        val computed = inv.multiplyVector(b)
        cachedTheta = computed
        return computed
    }

    private fun ensurePosteriorFactor(inv: Matrix): Matrix {
        val cached = cachedPosteriorFactor
        if (cached != null) return cached
        val computed = inv.choleskyLower()
        cachedPosteriorFactor = computed
        return computed
    }

    fun toByteArray(): ByteArray {
        val aBytes = a.toByteArray()
        val buf = ByteBuffer
            .allocate(Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES + aBytes.size + dimension * Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dimension)
        buf.putLong(nUpdates)
        buf.putInt(aBytes.size)
        buf.put(aBytes)
        for (v in b) buf.putDouble(v)
        return buf.array()
    }

    companion object {
        fun dot(u: DoubleArray, v: DoubleArray): Double {
            var s = 0.0
            for (i in u.indices) s += u[i] * v[i]
            return s
        }

        // Ridge lambda and discount are configuration, not state, so they are supplied
        // on load rather than persisted.
        fun fromByteArray(
            bytes: ByteArray,
            ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA,
            discount: Double = 1.0
        ): LinUCB {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val dimension = buf.getInt()
            val nUpdates = buf.getLong()
            val aLen = buf.getInt()
            val aBytes = ByteArray(aLen)
            buf.get(aBytes)
            val a = Matrix.fromByteArray(aBytes)
            val b = DoubleArray(dimension)
            for (i in 0 until dimension) b[i] = buf.getDouble()
            return LinUCB(dimension, a, b, nUpdates, ridgeLambda, discount)
        }
    }
}
