/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleSessionTest {

    private val keys = (0 until 6).map { "k$it" }

    @Test fun `tracks before the starting point are candidates`() {
        val s = ShuffleSession()
        s.begin(Any())
        // Started at index 3: everything but the current item is eligible.
        assertEquals(listOf(0, 1, 2, 4, 5), s.candidateIndices(keys, current = 3, repeatAll = false))
    }

    @Test fun `played tracks wait for the next pass, which only repeat starts`() {
        val s = ShuffleSession()
        s.begin(Any())
        listOf("k0", "k1", "k2", "k4", "k5").forEach(s::notePlayed)
        assertEquals(emptyList<Int>(), s.candidateIndices(keys, current = 3, repeatAll = false))
        // With repeat-all a new pass begins, still never the current track.
        assertEquals(listOf(0, 1, 2, 4, 5), s.candidateIndices(keys, current = 3, repeatAll = true))
    }

    @Test fun `duplicates of the current track and unplayable tracks are never candidates`() {
        val s = ShuffleSession()
        s.begin(Any())
        s.markUnplayable("k5")
        val withDuplicate = keys + "k3"
        assertEquals(listOf(0, 1, 2, 4), s.candidateIndices(withDuplicate, current = 3, repeatAll = true))
    }

    @Test fun `a new queue starts a new pass but unplayable tracks stay excluded`() {
        val s = ShuffleSession()
        val q1 = Any()
        assertTrue(s.begin(q1))
        assertFalse(s.begin(q1))
        s.notePlayed("k0")
        s.markUnplayable("k1")
        assertTrue(s.begin(Any()))
        // k0 is eligible again in the new queue; k1 is still unplayable.
        assertEquals(listOf(0, 3, 4, 5), s.candidateIndices(keys, current = 2, repeatAll = false))
    }

    @Test fun `placement puts the chosen item straight after the current one`() {
        // Simulates PlayQueue.move on a list to check the targets.
        fun play(items: MutableList<String>, current: Int, chosen: Int): Pair<MutableList<String>, Int> {
            val target = ShuffleSession.placementTarget(chosen, current)
            if (target < 0) return items to current
            var idx = current
            if (chosen < current && target >= current) {
                idx -= 1
            } else if (chosen > current && target <= current) {
                idx += 1
            }
            val item = items.removeAt(chosen)
            items.add(target, item)
            return items to idx
        }
        for (current in 0 until 6) {
            for (chosen in 0 until 6) {
                if (chosen == current) continue
                val list = keys.toMutableList()
                val (after, idx) = play(list, current, chosen)
                assertEquals("current keeps playing", keys[current], after[idx])
                assertEquals("chosen plays next (current=$current chosen=$chosen)", keys[chosen], after[idx + 1])
            }
        }
    }
}
