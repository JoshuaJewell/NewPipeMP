/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.min
import kotlin.math.roundToInt

object RewardCalculator {

    fun completionRatio(progressMillis: Long, durationMillis: Long): Double? {
        if (durationMillis <= 0L) return null
        return (progressMillis.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
    }

    fun computeReward(progressMillis: Long, durationMillis: Long, restartCountDelta: Int): Double {
        val cr = completionRatio(progressMillis, durationMillis) ?: return 0.0
        return fromRatio(cr, restartCountDelta)
    }

    // Continuous in completion ratio: a listen abandoned halfway is a clear negative, and
    // one abandoned in the first 15% carries an extra penalty because it is the cleanest
    // "no" the user gives. Rewinds add a bonus, as before.
    fun fromRatio(completionRatio: Double, restartCountDelta: Int): Double {
        val cr = completionRatio.coerceIn(0.0, 1.0)
        var r = AdaptiveShuffleConfig.REWARD_SLOPE * cr + AdaptiveShuffleConfig.REWARD_OFFSET
        if (cr < AdaptiveShuffleConfig.EARLY_SKIP_BELOW) r += AdaptiveShuffleConfig.REWARD_EARLY_SKIP_EXTRA
        r += AdaptiveShuffleConfig.REWARD_REWIND_DENSITY * rewindDensity(restartCountDelta)
        return r
    }

    // The v1 step reward. Used only to decode historical rewards.
    fun legacyStepReward(completionRatio: Double, restartCountDelta: Int): Double =
        legacyStepBase(completionRatio) +
            AdaptiveShuffleConfig.REWARD_REWIND_DENSITY * rewindDensity(restartCountDelta)

    // Inverts the v1 step reward: given the stored reward and the completion ratio it was
    // computed from, recover the (capped) restart delta.
    fun recoverRestartDelta(legacyReward: Double, completionRatio: Double): Int {
        val bonus = legacyReward - legacyStepBase(completionRatio)
        val delta = (bonus / AdaptiveShuffleConfig.REWARD_REWIND_DENSITY).roundToInt()
        return delta.coerceIn(0, AdaptiveShuffleConfig.REWIND_CAP.toInt())
    }

    private fun legacyStepBase(cr: Double): Double = when {
        cr >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE -> AdaptiveShuffleConfig.LEGACY_REWARD_COMPLETION
        cr < AdaptiveShuffleConfig.EARLY_SKIP_BELOW -> AdaptiveShuffleConfig.LEGACY_REWARD_EARLY_SKIP
        else -> AdaptiveShuffleConfig.LEGACY_REWARD_MID_SKIP
    }

    private fun rewindDensity(restartCountDelta: Int): Double =
        min(AdaptiveShuffleConfig.REWIND_CAP, restartCountDelta.toDouble().coerceAtLeast(0.0))
}
