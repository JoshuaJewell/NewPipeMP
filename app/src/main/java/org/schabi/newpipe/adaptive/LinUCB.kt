/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class LinUCB private constructor(
    val dimension: Int,
    private val a: Matrix,
    private val b: DoubleArray,
    private var nUpdates: Long
) {
    constructor(dimension: Int, ridgeLambda: Double = AdaptiveShuffleConfig.RIDGE_LAMBDA) : this(
        dimension = dimension,
        a = Matrix.scaledIdentity(dimension, ridgeLambda),
        b = DoubleArray(dimension),
        nUpdates = 0L
    )

    private var cachedAInverse: Matrix? = null
    private var cachedTheta: DoubleArray? = null

    data class Score(val predictedReward: Double, val uncertainty: Double) {
        val total: Double get() = predictedReward + uncertainty
    }

    fun update(x: DoubleArray, reward: Double) {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        a.addOuterProduct(x)
        for (i in 0 until dimension) b[i] += reward * x[i]
        nUpdates += 1L
        cachedAInverse = null
        cachedTheta = null
    }

    fun score(x: DoubleArray, alpha: Double = AdaptiveShuffleConfig.EXPLORATION_ALPHA): Score {
        require(x.size == dimension) { "feature length ${x.size} != dimension $dimension" }
        val inv = ensureInverse()
        val theta = ensureTheta(inv)
        var predicted = 0.0
        for (i in 0 until dimension) predicted += theta[i] * x[i]
        val q = inv.quadraticForm(x).coerceAtLeast(0.0)
        val uncertainty = alpha * sqrt(q)
        return Score(predicted, uncertainty)
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
        fun fromByteArray(bytes: ByteArray): LinUCB {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val dimension = buf.getInt()
            val nUpdates = buf.getLong()
            val aLen = buf.getInt()
            val aBytes = ByteArray(aLen)
            buf.get(aBytes)
            val a = Matrix.fromByteArray(aBytes)
            val b = DoubleArray(dimension)
            for (i in 0 until dimension) b[i] = buf.getDouble()
            return LinUCB(dimension, a, b, nUpdates)
        }
    }
}
