/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import java.nio.ByteBuffer
import java.nio.ByteOrder

class Matrix private constructor(
    val rows: Int,
    val cols: Int,
    private val data: DoubleArray
) {
    operator fun get(i: Int, j: Int): Double = data[i * cols + j]

    // Cholesky factor L of a symmetric positive-definite matrix, with A = L L^T.
    // Returned as a full n x n matrix whose entries above the diagonal are zero.
    fun choleskyLower(): Matrix {
        require(rows == cols) { "Cholesky requires a square matrix" }
        val n = rows
        val l = DoubleArray(n * n)
        for (j in 0 until n) {
            var diag = data[j * n + j]
            for (k in 0 until j) {
                val ljk = l[j * n + k]
                diag -= ljk * ljk
            }
            require(diag > 0.0) {
                "matrix is not positive definite (non-positive pivot at $j: $diag)"
            }
            val ljj = Math.sqrt(diag)
            l[j * n + j] = ljj
            for (i in (j + 1) until n) {
                var s = data[i * n + j]
                for (k in 0 until j) s -= l[i * n + k] * l[j * n + k]
                l[i * n + j] = s / ljj
            }
        }
        return Matrix(n, n, l)
    }

    fun inverseSymmetricPositiveDefinite(): Matrix {
        require(rows == cols) { "inverse requires a square matrix" }
        val n = rows
        val l = choleskyLower().data
        // Solve A x_col = e_col column-by-column via L (forward) then L^T (back).
        val inv = DoubleArray(n * n)
        val y = DoubleArray(n)
        val x = DoubleArray(n)
        for (col in 0 until n) {
            // Forward solve L y = e_col.
            for (i in 0 until n) {
                var s = if (i == col) 1.0 else 0.0
                for (k in 0 until i) s -= l[i * n + k] * y[k]
                y[i] = s / l[i * n + i]
            }
            // Back solve L^T x = y.
            for (i in (n - 1) downTo 0) {
                var s = y[i]
                for (k in (i + 1) until n) s -= l[k * n + i] * x[k]
                x[i] = s / l[i * n + i]
            }
            for (i in 0 until n) inv[i * n + col] = x[i]
        }
        return Matrix(n, n, inv)
    }

    fun multiplyVector(x: DoubleArray): DoubleArray {
        require(x.size == cols) { "vector length must equal cols ($cols)" }
        val y = DoubleArray(rows)
        for (i in 0 until rows) {
            var acc = 0.0
            val rowOffset = i * cols
            for (j in 0 until cols) acc += data[rowOffset + j] * x[j]
            y[i] = acc
        }
        return y
    }

    fun quadraticForm(x: DoubleArray): Double {
        val ax = multiplyVector(x)
        var s = 0.0
        for (i in x.indices) s += x[i] * ax[i]
        return s
    }

    fun toByteArray(): ByteArray {
        val buf = ByteBuffer
            .allocate(SERIAL_HEADER_BYTES + data.size * Double.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(rows)
        buf.putInt(cols)
        for (v in data) buf.putDouble(v)
        return buf.array()
    }

    fun addOuterProduct(x: DoubleArray, weight: Double = 1.0) {
        require(x.size == rows && rows == cols) {
            "addOuterProduct requires a square matrix matching x.size"
        }
        for (i in 0 until rows) {
            val xi = weight * x[i]
            val rowOffset = i * cols
            for (j in 0 until cols) {
                data[rowOffset + j] += xi * x[j]
            }
        }
    }

    // A <- gamma * (A - lambda I) + lambda I. Shrinks accumulated evidence while keeping
    // the ridge prior intact, so A stays positive definite for any gamma in (0, 1].
    fun discountTowardsScaledIdentity(gamma: Double, lambda: Double) {
        require(rows == cols) { "discount requires a square matrix" }
        for (i in data.indices) data[i] *= gamma
        val restore = lambda * (1.0 - gamma)
        for (i in 0 until rows) data[i * cols + i] += restore
    }

    fun copy(): Matrix = Matrix(rows, cols, data.copyOf())

    companion object {
        private const val SERIAL_HEADER_BYTES = 2 * Int.SIZE_BYTES

        fun fromByteArray(bytes: ByteArray): Matrix {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rows = buf.getInt()
            val cols = buf.getInt()
            val data = DoubleArray(rows * cols)
            for (i in data.indices) data[i] = buf.getDouble()
            return Matrix(rows, cols, data)
        }

        fun scaledIdentity(d: Int, scale: Double): Matrix {
            val m = Matrix(d, d, DoubleArray(d * d))
            for (i in 0 until d) m.data[i * d + i] = scale
            return m
        }

        fun of(rows: Int, cols: Int, values: DoubleArray): Matrix {
            require(values.size == rows * cols) {
                "values length ${values.size} != rows*cols (${rows * cols})"
            }
            return Matrix(rows, cols, values.copyOf())
        }
    }
}
