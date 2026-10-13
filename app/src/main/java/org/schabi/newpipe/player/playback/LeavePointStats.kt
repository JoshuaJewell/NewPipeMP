/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.player.playback

import kotlin.math.sqrt

// Running mean and spread of the point (0 to 1 of the duration) at which listens of a
// track end, kept with Welford's method so no history is stored. Stored in the dropout_*
// columns of playback_statistics, in the same form the on-device v1.1 build used.
data class LeavePointStats(val count: Int, val mean: Double, val m2: Double) {

    // Sample standard deviation of the leave points, or null with fewer than two.
    fun spread(): Double? = spread(count, m2)

    companion object {
        @JvmStatic
        fun add(stats: LeavePointStats, fraction: Double): LeavePointStats {
            val x = fraction.coerceIn(0.0, 1.0)
            val count = stats.count + 1
            val delta = x - stats.mean
            val mean = stats.mean + delta / count
            val m2 = stats.m2 + delta * (x - mean)
            return LeavePointStats(count, mean, m2)
        }

        @JvmStatic
        fun spread(count: Int, m2: Double): Double? = if (count < 2) null else sqrt((m2 / (count - 1)).coerceAtLeast(0.0))
    }
}
