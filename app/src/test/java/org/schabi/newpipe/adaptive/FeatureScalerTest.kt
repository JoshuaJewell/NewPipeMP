/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureScalerTest {

    @Test fun `with no observations transform returns input unchanged`() {
        val s = FeatureScaler(intArrayOf(0, 2))
        val v = doubleArrayOf(3.0, 1.0, 7.0)
        val out = s.transform(v)
        assertEquals(3.0, out[0], 0.0)
        assertEquals(1.0, out[1], 0.0)
        assertEquals(7.0, out[2], 0.0)
    }

    @Test fun `running mean and variance match the batch computation`() {
        val s = FeatureScaler(intArrayOf(0))
        val xs = doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        for (x in xs) s.observe(doubleArrayOf(x))
        // Batch reference: mean = 5.0, sample variance = 32 / 7 = 4.571428...
        val expectedMean = 5.0
        val expectedVariance = 32.0 / 7.0
        // We probe by transforming the mean value: it should map to ~0.
        val transformedMean = s.transform(doubleArrayOf(expectedMean))[0]
        assertEquals(0.0, transformedMean, 1e-12)
        // Transforming a value one std above the mean should map to ~+1.
        val std = sqrt(expectedVariance)
        val transformedOneAbove = s.transform(doubleArrayOf(expectedMean + std))[0]
        assertEquals(1.0, transformedOneAbove, 1e-6)
    }

    @Test fun `unscaled indices are passed through untouched`() {
        val s = FeatureScaler(intArrayOf(1))
        for (x in doubleArrayOf(10.0, 20.0, 30.0)) {
            s.observe(doubleArrayOf(99.0, x, 99.0))
        }
        val out = s.transform(doubleArrayOf(42.0, 20.0, 7.0))
        assertEquals(42.0, out[0], 0.0)
        assertEquals(7.0, out[2], 0.0)
        // Index 1 should be centred to roughly zero (mean is 20).
        assertEquals(0.0, out[1], 1e-12)
    }

    @Test fun `serialisation round trip preserves running statistics`() {
        val original = FeatureScaler(intArrayOf(0))
        for (x in doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)) {
            original.observe(doubleArrayOf(x))
        }
        val bytes = original.toByteArray()
        val restored = FeatureScaler.fromByteArray(bytes)
        // Both transform a fixed input to the same scaled value.
        val probe = doubleArrayOf(3.5)
        assertEquals(original.transform(probe)[0], restored.transform(probe)[0], 0.0)
    }
}
