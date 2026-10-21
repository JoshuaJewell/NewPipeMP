/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive

import kotlin.math.sqrt

// Whether the model beats random order (spec section 7.5). A share of picks is drawn
// uniformly from the same eligible candidates the model chooses from, so the two arms
// differ only in who chose. Only decisions from the first baseline pick on are compared:
// earlier ones were all the model's and come from a different period of listening.
object ArmComparison {

    data class Listen(
        val chosenAt: Long,
        val baseline: Boolean,
        val reward: Double,
        val completionRatio: Double
    )

    data class Arm(
        val listens: Int,
        val meanReward: Double,
        val meanCompletion: Double,
        // Share of listens that reached the completion threshold, or ended early.
        val completedShare: Double,
        val earlySkipShare: Double,
        val rewardVariance: Double
    )

    data class Report(
        val model: Arm?,
        val baseline: Arm?,
        // Model mean reward minus baseline mean reward, and the half-width of its 95%
        // interval. Null until both arms have MIN_LISTENS_PER_ARM listens.
        val rewardLift: Double?,
        val rewardLiftHalfWidth: Double?,
        val since: Long?
    ) {
        val verdict: Verdict
            get() {
                val lift = rewardLift ?: return Verdict.NOT_ENOUGH_DATA
                val half = rewardLiftHalfWidth ?: return Verdict.NOT_ENOUGH_DATA
                return when {
                    lift - half > 0.0 -> Verdict.MODEL_BETTER
                    lift + half < 0.0 -> Verdict.BASELINE_BETTER
                    else -> Verdict.NO_CLEAR_DIFFERENCE
                }
            }
    }

    enum class Verdict { NOT_ENOUGH_DATA, MODEL_BETTER, BASELINE_BETTER, NO_CLEAR_DIFFERENCE }

    const val MIN_LISTENS_PER_ARM: Int = 30
    private const val Z_95 = 1.96

    fun compute(listens: List<Listen>): Report {
        val since = listens.filter { it.baseline }.minOfOrNull { it.chosenAt }
            ?: return Report(arm(listens), null, null, null, null)
        val window = listens.filter { it.chosenAt >= since }
        val model = arm(window.filter { !it.baseline })
        val baseline = arm(window.filter { it.baseline })
        val enough = model != null && baseline != null &&
            model.listens >= MIN_LISTENS_PER_ARM && baseline.listens >= MIN_LISTENS_PER_ARM
        if (!enough) return Report(model, baseline, null, null, since)
        val lift = model!!.meanReward - baseline!!.meanReward
        val se = sqrt(model.rewardVariance / model.listens + baseline.rewardVariance / baseline.listens)
        return Report(model, baseline, lift, Z_95 * se, since)
    }

    private fun arm(listens: List<Listen>): Arm? {
        if (listens.isEmpty()) return null
        val n = listens.size
        val mean = listens.sumOf { it.reward } / n
        val variance = if (n > 1) listens.sumOf { (it.reward - mean) * (it.reward - mean) } / (n - 1) else 0.0
        return Arm(
            listens = n,
            meanReward = mean,
            meanCompletion = listens.sumOf { it.completionRatio.coerceIn(0.0, 1.0) } / n,
            completedShare = listens.count { it.completionRatio >= AdaptiveShuffleConfig.COMPLETION_AT_OR_ABOVE }.toDouble() / n,
            earlySkipShare = listens.count { it.completionRatio < AdaptiveShuffleConfig.EARLY_SKIP_BELOW }.toDouble() / n,
            rewardVariance = variance
        )
    }
}
