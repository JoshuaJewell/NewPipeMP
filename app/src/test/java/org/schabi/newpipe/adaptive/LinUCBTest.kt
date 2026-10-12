/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinUCBTest {

    @Test fun `before any update predicted reward is zero and uncertainty positive`() {
        val model = LinUCB(dimension = 3)
        val s = model.score(doubleArrayOf(1.0, 1.0, 1.0))
        assertEquals(0.0, s.predictedReward, 1e-12)
        assertTrue("uncertainty positive on a fresh model", s.uncertainty > 0.0)
    }

    @Test fun `after one update theta moves towards the observed direction`() {
        val model = LinUCB(dimension = 3)
        // Observe a strong positive reward for the direction (1, 0, 0).
        model.update(doubleArrayOf(1.0, 0.0, 0.0), reward = 2.0)
        val theta = model.theta()
        // The update is A += xx^T (boosts the (0,0) diagonal), b += r*x. With ridge lambda=1 the (0,0)
        // diagonal becomes 2, so theta[0] = (r*x[0]) / 2 = 1.0; other components stay at zero.
        assertEquals(1.0, theta[0], 1e-12)
        assertEquals(0.0, theta[1], 1e-12)
        assertEquals(0.0, theta[2], 1e-12)
    }

    @Test fun `repeated identical observations shrink uncertainty in that direction`() {
        val model = LinUCB(dimension = 3)
        val x = doubleArrayOf(1.0, 0.0, 0.0)
        val before = model.score(x).uncertainty
        repeat(20) { model.update(x, reward = 1.0) }
        val after = model.score(x).uncertainty
        assertTrue("uncertainty should fall: before=$before after=$after", after < before * 0.5)
    }

    @Test fun `serialisation round trip preserves predictions`() {
        val model = LinUCB(dimension = 4)
        model.update(doubleArrayOf(0.5, 1.0, -0.25, 0.0), 1.0)
        model.update(doubleArrayOf(0.0, -0.5, 1.0, 0.5), -0.5)
        val probe = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
        val originalScore = model.score(probe)
        val bytes = model.toByteArray()
        val restored = LinUCB.fromByteArray(bytes)
        val restoredScore = restored.score(probe)
        assertEquals(originalScore.predictedReward, restoredScore.predictedReward, 1e-12)
        assertEquals(originalScore.uncertainty, restoredScore.uncertainty, 1e-12)
        assertEquals(model.numUpdates(), restored.numUpdates())
    }

    @Test fun `updates with different rewards produce different thetas`() {
        val a = LinUCB(dimension = 2)
        val b = LinUCB(dimension = 2)
        val x = doubleArrayOf(1.0, 0.0)
        a.update(x, 1.0)
        b.update(x, -1.0)
        assertNotEquals(a.theta()[0], b.theta()[0])
    }

    @Test fun `a weight of two equals two identical updates`() {
        val x = doubleArrayOf(1.0, 0.5, -0.25)
        val weighted = LinUCB(dimension = 3)
        weighted.update(x, 0.8, weight = 2.0)
        val repeated = LinUCB(dimension = 3)
        repeated.update(x, 0.8)
        repeated.update(x, 0.8)
        val tw = weighted.theta()
        val tr = repeated.theta()
        for (i in 0 until 3) assertEquals(tr[i], tw[i], 1e-12)
    }

    @Test fun `discount lets the model follow a change in taste`() {
        val x = doubleArrayOf(1.0, 1.0)
        val forgetful = LinUCB(dimension = 2, discount = 0.95)
        val stubborn = LinUCB(dimension = 2, discount = 1.0)
        repeat(300) { forgetful.update(x, 1.0); stubborn.update(x, 1.0) }
        repeat(60) { forgetful.update(x, -1.0); stubborn.update(x, -1.0) }
        val pf = forgetful.score(x).predictedReward
        val ps = stubborn.score(x).predictedReward
        assertTrue("discounted model should have moved to the new taste ($pf)", pf < -0.5)
        assertTrue("undiscounted model should still lean on old evidence ($ps)", ps > 0.5)
    }

    @Test fun `discount keeps uncertainty from collapsing to zero`() {
        val x = doubleArrayOf(1.0, 0.0)
        val model = LinUCB(dimension = 2, discount = 0.99)
        repeat(5000) { model.update(x, 0.0) }
        assertTrue(model.score(x).uncertainty > 0.05)
    }

    @Test fun `posterior draws centre on theta`() {
        val model = LinUCB(dimension = 2)
        repeat(50) { model.update(doubleArrayOf(1.0, 0.0), 1.0) }
        val rng = java.util.Random(11)
        var mean = 0.0
        repeat(2000) { mean += model.sampleTheta(rng)[0] }
        mean /= 2000
        assertEquals(model.theta()[0], mean, 0.02)
    }
}
