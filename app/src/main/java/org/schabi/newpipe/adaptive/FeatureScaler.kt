/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class FeatureScaler private constructor(
    private val scaledIndices: IntArray,
    private val counts: LongArray,
    private val means: DoubleArray,
    private val m2s: DoubleArray
) {
    constructor(scaledIndices: IntArray) : this(
        scaledIndices = scaledIndices.copyOf(),
        counts = LongArray(scaledIndices.size),
        means = DoubleArray(scaledIndices.size),
        m2s = DoubleArray(scaledIndices.size)
    )

    fun observe(featureVector: DoubleArray) {
        for (k in scaledIndices.indices) {
            val idx = scaledIndices[k]
            val x = featureVector[idx]
            counts[k] += 1L
            val delta = x - means[k]
            means[k] += delta / counts[k]
            m2s[k] += delta * (x - means[k])
        }
    }

    fun transform(featureVector: DoubleArray): DoubleArray {
        val out = featureVector.copyOf()
        for (k in scaledIndices.indices) {
            val idx = scaledIndices[k]
            if (counts[k] < 2L) continue
            val variance = m2s[k] / (counts[k] - 1L)
            val std = sqrt(variance) + EPSILON
            out[idx] = (out[idx] - means[k]) / std
        }
        return out
    }

    fun toByteArray(): ByteArray {
        val n = scaledIndices.size
        val buf = ByteBuffer
            .allocate(Int.SIZE_BYTES + n * (Int.SIZE_BYTES + Long.SIZE_BYTES + 2 * Double.SIZE_BYTES))
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(n)
        for (k in 0 until n) {
            buf.putInt(scaledIndices[k])
            buf.putLong(counts[k])
            buf.putDouble(means[k])
            buf.putDouble(m2s[k])
        }
        return buf.array()
    }

    companion object {
        const val EPSILON: Double = 1e-8

        fun fromByteArray(bytes: ByteArray): FeatureScaler {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.getInt()
            val idx = IntArray(n)
            val counts = LongArray(n)
            val means = DoubleArray(n)
            val m2s = DoubleArray(n)
            for (k in 0 until n) {
                idx[k] = buf.getInt()
                counts[k] = buf.getLong()
                means[k] = buf.getDouble()
                m2s[k] = buf.getDouble()
            }
            return FeatureScaler(idx, counts, means, m2s)
        }
    }
}
