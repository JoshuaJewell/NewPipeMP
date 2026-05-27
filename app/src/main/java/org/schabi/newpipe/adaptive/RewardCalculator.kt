/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.min

object RewardCalculator {
    fun computeReward(progressMillis: Long, durationMillis: Long, restartCountDelta: Int): Double {
        if (durationMillis <= 0L) return 0.0
        val completionRatio = progressMillis.toDouble() / durationMillis.toDouble()
        val earlySkip = completionRatio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW
        val completion = completionRatio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE
        val midSkip = !earlySkip && !completion
        val rewindDensity = min(
            AdaptiveShuffleConfig.REWIND_CAP,
            restartCountDelta.toDouble().coerceAtLeast(0.0)
        )
        var r = 0.0
        if (completion) r += AdaptiveShuffleConfig.REWARD_COMPLETION
        if (earlySkip) r += AdaptiveShuffleConfig.REWARD_EARLY_SKIP
        if (midSkip) r += AdaptiveShuffleConfig.REWARD_MID_SKIP
        r += AdaptiveShuffleConfig.REWARD_REWIND_DENSITY * rewindDensity
        return r
    }
}
