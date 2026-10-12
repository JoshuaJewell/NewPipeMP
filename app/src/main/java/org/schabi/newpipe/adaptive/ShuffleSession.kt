/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

// Per-queue bookkeeping for adaptive shuffle, free of player types so it can be tested.
//
// A shuffled queue is one pass over its tracks: every track not yet played in this pass
// is a candidate, wherever it sits in the queue. Taking candidates only from after the
// current position, as before, meant starting a playlist at track 50 never shuffled in
// tracks 1 to 49, and repeat-all wrapped back into playlist order.
class ShuffleSession {

    private var queueIdentity: Any? = null
    private val playedThisPass = HashSet<String>()

    // Tracks that failed to load. Kept for the life of the process, across queues: a
    // removed or blocked video stays unplayable, and to the model an item that never
    // resolves looks like an unexplored one worth trying.
    private val unplayable = HashSet<String>()

    // Returns true when the queue is a different one from last time, which starts a new
    // pass (and, for the caller, a new listening session).
    fun begin(queue: Any): Boolean {
        if (queue === queueIdentity) return false
        queueIdentity = queue
        playedThisPass.clear()
        return true
    }

    fun notePlayed(key: String) {
        playedThisPass += key
    }

    fun markUnplayable(key: String) {
        unplayable += key
    }

    fun isUnplayable(key: String): Boolean = key in unplayable

    // Indices into keys that may be picked next. Excludes the current item, every other
    // copy of it, anything played this pass and anything unplayable. When the pass is
    // used up and the queue repeats, a new pass starts.
    fun candidateIndices(keys: List<String>, current: Int, repeatAll: Boolean): List<Int> {
        val currentKey = keys.getOrNull(current)
        fun eligible() = keys.indices.filter { i ->
            val k = keys[i]
            i != current && k != currentKey && k !in playedThisPass && k !in unplayable
        }
        val first = eligible()
        if (first.isNotEmpty() || !repeatAll) return first
        playedThisPass.clear()
        if (currentKey != null) playedThisPass += currentKey
        return eligible()
    }

    companion object {
        // Where to move the chosen item so it plays right after the current one, given
        // PlayQueue.move semantics. Returns -1 when it is already there.
        //  - After the current item: move it to current + 1.
        //  - Before it: move it to current. Removing it shifts the current item down one,
        //    so it lands directly after the current item, which is now at current - 1.
        @JvmStatic
        fun placementTarget(chosen: Int, current: Int): Int = when {
            chosen == current + 1 || chosen == current || chosen < 0 -> -1
            chosen > current -> current + 1
            else -> current
        }
    }
}
