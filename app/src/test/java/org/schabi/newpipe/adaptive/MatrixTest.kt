/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixTest {

    @Test fun `scaled identity has lambda on the diagonal and zero off-diagonal`() {
        val m = Matrix.scaledIdentity(3, 2.5)
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val expected = if (i == j) 2.5 else 0.0
                assertEquals("entry ($i,$j)", expected, m[i, j], 0.0)
            }
        }
    }

    @Test fun `add outer product accumulates x x transpose into the matrix`() {
        // Starting from a scaled identity so we can check accumulation, not just assignment.
        val m = Matrix.scaledIdentity(3, 1.0)
        val x = doubleArrayOf(2.0, -1.0, 0.5)
        m.addOuterProduct(x)
        // Expected: I + x x^T where x x^T is:
        //   [ 4.0  -2.0   1.0 ]
        //   [-2.0   1.0  -0.5 ]
        //   [ 1.0  -0.5   0.25]
        val expected = arrayOf(
            doubleArrayOf(5.0, -2.0, 1.0),
            doubleArrayOf(-2.0, 2.0, -0.5),
            doubleArrayOf(1.0, -0.5, 1.25)
        )
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                assertEquals("entry ($i,$j)", expected[i][j], m[i, j], 1e-12)
            }
        }
    }

    @Test fun `quadratic form x A x matches hand calculation`() {
        // A = [[2, 1], [1, 3]] is SPD. x = (1, -2). x^T A x = 1*2*1 + 2*1*1*-2 + -2*3*-2 = 2 - 4 + 12 = 10.
        val a = Matrix.of(2, 2, doubleArrayOf(2.0, 1.0, 1.0, 3.0))
        val x = doubleArrayOf(1.0, -2.0)
        assertEquals(10.0, a.quadraticForm(x), 1e-12)
    }

    @Test fun `toByteArray and fromByteArray round trip preserves entries`() {
        val original = Matrix.scaledIdentity(4, 1.0)
        original.addOuterProduct(doubleArrayOf(1.5, -0.5, 2.0, 0.25))
        val bytes = original.toByteArray()
        val restored = Matrix.fromByteArray(bytes)
        assertEquals(original.rows, restored.rows)
        assertEquals(original.cols, restored.cols)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                assertEquals("entry ($i,$j)", original[i, j], restored[i, j], 0.0)
            }
        }
    }

    @Test fun `inverse round trips against a known symmetric positive definite matrix`() {
        // A = lambda*I + x x^T with lambda=1, x=(2, -1, 0.5).
        // This is SPD by construction (sum of an SPD matrix and a positive-semidefinite rank-1 update).
        val a = Matrix.scaledIdentity(3, 1.0)
        a.addOuterProduct(doubleArrayOf(2.0, -1.0, 0.5))
        val inv = a.inverseSymmetricPositiveDefinite()
        // Verify A * inv = I by computing A*inv column-by-column.
        for (j in 0 until 3) {
            val ej = DoubleArray(3).also { it[j] = 1.0 }
            val invEj = inv.multiplyVector(ej)
            val aInvEj = a.multiplyVector(invEj)
            for (i in 0 until 3) {
                val expected = if (i == j) 1.0 else 0.0
                assertEquals("(A * inv)($i,$j)", expected, aInvEj[i], 1e-10)
            }
        }
    }

    @Test fun `multiply vector returns A times x`() {
        // Build A = [[1, 2, 3], [4, 5, 6]] via outer products is awkward; use a builder.
        val a = Matrix.of(
            rows = 2,
            cols = 3,
            values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        )
        val x = doubleArrayOf(1.0, 0.5, -1.0)
        // Expected: [1*1 + 2*0.5 + 3*-1, 4*1 + 5*0.5 + 6*-1] = [-1.0, 0.5]
        val y = a.multiplyVector(x)
        assertArrayEquals(doubleArrayOf(-1.0, 0.5), y, 1e-12)
    }
}
