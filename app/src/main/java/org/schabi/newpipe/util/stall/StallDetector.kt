/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.util.stall

// Decides when a probe has been outstanding long enough to call it a stall, from ticks
// of a clock the caller supplies. Kept free of Android so the rules can be tested.
//
// The app can be suspended for hours (Doze, the cached-app freezer). A probe started
// just before and finished just after would look like a stall of hours, so a gap
// between ticks much longer than the tick interval is taken as a suspension: it is
// recorded, and outstanding probes do not count it as waiting.
class StallDetector(
    private val tickMillis: Long,
    private val stallAfterMillis: Long,
    private val redumpAfterMillis: Long
) {
    sealed class Event {
        // Probes outstanding past stallAfterMillis, first seen now, or still stuck
        // redumpAfterMillis after the first report: the caller writes a report.
        data class Stalled(val probes: Map<String, Long>, val repeat: Boolean) : Event()

        // Every stalled probe has answered. waitedMillis: the longest wait.
        data class Recovered(val waitedMillis: Long) : Event()

        data class Suspended(val gapMillis: Long) : Event()
    }

    // name -> waiting time so far, counted in ticks while awake.
    private val waiting = LinkedHashMap<String, Long>()
    private var lastTick: Long = -1L
    private var reportedAt: Long = -1L
    private var repeated = false
    private var longestStall = 0L

    val outstanding: Set<String> get() = waiting.keys

    fun started(name: String) {
        if (name !in waiting) waiting[name] = 0L
    }

    fun finished(name: String) {
        val waited = waiting.remove(name) ?: return
        if (reportedAt >= 0L) longestStall = maxOf(longestStall, waited)
    }

    // Called every tickMillis or so with a monotonic clock.
    fun tick(now: Long): List<Event> {
        val events = ArrayList<Event>()
        val elapsed = if (lastTick < 0L) 0L else now - lastTick
        lastTick = now
        val step = if (elapsed > tickMillis * SUSPEND_FACTOR) {
            events += Event.Suspended(elapsed)
            tickMillis
        } else {
            elapsed
        }
        for (key in waiting.keys) waiting[key] = waiting.getValue(key) + step

        val stalled = waiting.filterValues { it >= stallAfterMillis }
        if (stalled.isEmpty()) {
            if (reportedAt >= 0L) {
                events += Event.Recovered(longestStall)
                reportedAt = -1L
                repeated = false
                longestStall = 0L
            }
            return events
        }
        val longest = stalled.values.max()
        longestStall = maxOf(longestStall, longest)
        if (reportedAt < 0L) {
            reportedAt = longest
            events += Event.Stalled(stalled, repeat = false)
        } else if (!repeated && longest - reportedAt >= redumpAfterMillis) {
            repeated = true
            events += Event.Stalled(stalled, repeat = true)
        }
        return events
    }

    companion object {
        // A tick this many intervals late means the process was not running.
        const val SUSPEND_FACTOR: Long = 5L
    }
}
